/*
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.sdnip;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.lang3.tuple.Pair;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Host;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.host.HostEvent;
import org.onosproject.net.host.HostListener;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.sdnip.config.BgpPeer;
import org.onosproject.sdnip.config.Interface;
import org.onosproject.sdnip.config.SdnIpConfigurationService;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IpAddress;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.MacAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.googlecode.concurrenttrees.common.KeyValuePair;
import com.googlecode.concurrenttrees.radix.node.concrete.DefaultByteArrayNodeFactory;
import com.googlecode.concurrenttrees.radixinverted.ConcurrentInvertedRadixTree;
import com.googlecode.concurrenttrees.radixinverted.InvertedRadixTree;

/**
 * This class processes BGP route update, translates each update into a intent
 * and submits the intent.
 */
public class Router implements RouteListener {

    private static final Logger log = LoggerFactory.getLogger(Router.class);
    // For routes announced by local BGP daemon in SDN network,
    // the next hop will be 0.0.0.0.
    private static final Ip4Address LOCAL_NEXT_HOP =
        Ip4Address.valueOf("0.0.0.0");

    // Store all route updates in a radix tree.
    // The key in this tree is the binary string of prefix of the route.
    private InvertedRadixTree<RouteEntry> bgpRoutes;

    // Stores all incoming route updates in a queue.
    private final BlockingQueue<Collection<RouteUpdate>> routeUpdatesQueue;

    // The Ip4Address is the next hop address of each route update.
    private final SetMultimap<Ip4Address, RouteEntry> routesWaitingOnArp;

    // The IPv4 address to MAC address mapping
    private final Map<Ip4Address, MacAddress> ip2Mac;

    private final ApplicationId appId;
    private final IntentSynchronizer intentSynchronizer;
    private final HostService hostService;
    private final SdnIpConfigurationService configService;
    private final InterfaceService interfaceService;
    private final ExecutorService bgpUpdatesExecutor;
    private final HostListener hostListener;

    /**
     * Class constructor.
     *
     * @param appId             the application ID
     * @param intentSynchronizer the intent synchronizer
     * @param configService     the configuration service
     * @param interfaceService  the interface service
     * @param hostService       the host service
     */
    public Router(ApplicationId appId, IntentSynchronizer intentSynchronizer,
                  SdnIpConfigurationService configService,
                  InterfaceService interfaceService,
                  HostService hostService) {
        this.appId = appId;
        this.intentSynchronizer = intentSynchronizer;
        this.configService = configService;
        this.interfaceService = interfaceService;
        this.hostService = hostService;

        this.hostListener = new InternalHostListener();

        bgpRoutes = new ConcurrentInvertedRadixTree<>(
                new DefaultByteArrayNodeFactory());
        routeUpdatesQueue = new LinkedBlockingQueue<>();
        routesWaitingOnArp = Multimaps.synchronizedSetMultimap(
                HashMultimap.<Ip4Address, RouteEntry>create());
        ip2Mac = new ConcurrentHashMap<>();

        bgpUpdatesExecutor = Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder()
                .setNameFormat("sdnip-bgp-updates-%d").build());
    }

    /**
     * Starts the router.
     */
    public void start() {
        this.hostService.addListener(hostListener);

        bgpUpdatesExecutor.execute(new Runnable() {
            @Override
            public void run() {
                doUpdatesThread();
            }
        });
    }

    /**
     * Stops the router.
     */
    public void stop() {
        this.hostService.removeListener(hostListener);

        // Stop the thread(s)
        bgpUpdatesExecutor.shutdownNow();

        synchronized (this) {
            // Cleanup all local state
            bgpRoutes = new ConcurrentInvertedRadixTree<>(
                new DefaultByteArrayNodeFactory());
            routeUpdatesQueue.clear();
            routesWaitingOnArp.clear();
            ip2Mac.clear();
        }
    }

    @Override
    public void update(Collection<RouteUpdate> routeUpdates) {
        try {
            routeUpdatesQueue.put(routeUpdates);
        } catch (InterruptedException e) {
            log.debug("Interrupted while putting on routeUpdatesQueue", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Thread for handling route updates.
     */
    private void doUpdatesThread() {
        boolean interrupted = false;
        try {
            while (!interrupted) {
                try {
                    Collection<RouteUpdate> routeUpdates =
                        routeUpdatesQueue.take();
                    processRouteUpdates(routeUpdates);
                } catch (InterruptedException e) {
                    log.debug("Interrupted while taking from updates queue", e);
                    interrupted = true;
                } catch (Exception e) {
                    log.debug("exception", e);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Processes route updates.
     *
     * @param routeUpdates the route updates to process
     */
    void processRouteUpdates(Collection<RouteUpdate> routeUpdates) {
        synchronized (this) {
            Collection<Pair<Ip4Prefix, MultiPointToSinglePointIntent>>
                submitIntents = new LinkedList<>();
            Collection<Ip4Prefix> withdrawPrefixes = new LinkedList<>();
            MultiPointToSinglePointIntent intent;

            for (RouteUpdate update : routeUpdates) {
                switch (update.type()) {
                case UPDATE:
                    intent = processRouteAdd(update.routeEntry(),
                                             withdrawPrefixes);
                    if (intent != null) {
                        submitIntents.add(Pair.of(update.routeEntry().prefix(),
                                                  intent));
                    }
                    break;
                case DELETE:
                    processRouteDelete(update.routeEntry(), withdrawPrefixes);
                    break;
                default:
                    log.error("Unknown update Type: {}", update.type());
                    break;
                }
            }

            intentSynchronizer.updateRouteIntents(submitIntents,
                                                  withdrawPrefixes);
        }
    }

    /**
     * Processes adding a route entry.
     * <p>
     * The route entry is added to the radix tree. If there was an existing
     * next hop for this prefix, but the next hop was different, then the
     * old route entry is deleted.
     * </p>
     * <p>
     * NOTE: Currently, we don't handle routes if the next hop is within the
     * SDN domain.
     * </p>
     *
     * @param routeEntry the route entry to add
     * @param withdrawPrefixes the collection of accumulated prefixes whose
     * intents will be withdrawn
     * @return the corresponding intent that should be submitted, or null
     */
    private MultiPointToSinglePointIntent processRouteAdd(
                RouteEntry routeEntry,
                Collection<Ip4Prefix> withdrawPrefixes) {
        log.debug("Processing route add: {}", routeEntry);

        Ip4Prefix prefix = routeEntry.prefix();
        Ip4Address nextHop = null;
        RouteEntry foundRouteEntry =
            bgpRoutes.put(RouteEntry.createBinaryString(prefix),
                          routeEntry);
        if (foundRouteEntry != null) {
            nextHop = foundRouteEntry.nextHop();
        }

        if (nextHop != null && !nextHop.equals(routeEntry.nextHop())) {
            // There was an existing nexthop for this prefix. This update
            // supersedes that, so we need to remove the old flows for this
            // prefix from the switches
            withdrawPrefixes.add(routeEntry.prefix());
        }
        if (nextHop != null && nextHop.equals(routeEntry.nextHop())) {
            return null;
        }

        if (routeEntry.nextHop().equals(LOCAL_NEXT_HOP)) {
            // Route originated by SDN domain
            // We don't handle these at the moment
            log.debug("Own route {} to {}",
                      routeEntry.prefix(), routeEntry.nextHop());
            return null;
        }

        //
        // Find the MAC address of next hop router for this route entry.
        // If the MAC address can not be found in ARP cache, then this prefix
        // will be put in routesWaitingOnArp queue. Otherwise, generate
        // a new route intent.
        //

        // Monitor the IP address for updates of the MAC address
        hostService.startMonitoringIp(routeEntry.nextHop());

        // Check if we know the MAC address of the next hop
        MacAddress nextHopMacAddress = ip2Mac.get(routeEntry.nextHop());
        if (nextHopMacAddress == null) {
            Set<Host> hosts = hostService.getHostsByIp(routeEntry.nextHop());
            if (!hosts.isEmpty()) {
                // TODO how to handle if multiple hosts are returned?
                nextHopMacAddress = hosts.iterator().next().mac();
            }
            if (nextHopMacAddress != null) {
                ip2Mac.put(routeEntry.nextHop(), nextHopMacAddress);
            }
        }
        if (nextHopMacAddress == null) {
            routesWaitingOnArp.put(routeEntry.nextHop(), routeEntry);
            return null;
        }
        return generateRouteIntent(routeEntry.prefix(), routeEntry.nextHop(),
                                   nextHopMacAddress);
    }

    /**
     * Generates a route intent for a prefix, the next hop IP address, and
     * the next hop MAC address.
     * <p/>
     * This method will find the egress interface for the intent.
     * Intent will match dst IP prefix and rewrite dst MAC address at all other
     * border switches, then forward packets according to dst MAC address.
     *
     * @param prefix            IP prefix of the route to add
     * @param nextHopIpAddress  IP address of the next hop
     * @param nextHopMacAddress MAC address of the next hop
     * @return the generated intent, or null if no intent should be submitted
     */
    private MultiPointToSinglePointIntent generateRouteIntent(
                Ip4Prefix prefix,
                Ip4Address nextHopIpAddress,
                MacAddress nextHopMacAddress) {

        // Find the attachment point (egress interface) of the next hop
        Interface egressInterface;
        if (configService.getBgpPeers().containsKey(nextHopIpAddress)) {
            // Route to a peer
            log.debug("Route to peer {}", nextHopIpAddress);
            BgpPeer peer =
                    configService.getBgpPeers().get(nextHopIpAddress);
            egressInterface =
                    interfaceService.getInterface(peer.connectPoint());
        } else {
            // Route to non-peer
            log.debug("Route to non-peer {}", nextHopIpAddress);
            egressInterface =
                    interfaceService.getMatchingInterface(nextHopIpAddress);
            if (egressInterface == null) {
                log.warn("No outgoing interface found for {}",
                         nextHopIpAddress);
                return null;
            }
        }

        //
        // Generate the intent itself
        //
        Set<ConnectPoint> ingressPorts = new HashSet<>();
        ConnectPoint egressPort = egressInterface.connectPoint();
        log.debug("Generating intent for prefix {}, next hop mac {}",
                  prefix, nextHopMacAddress);

        for (Interface intf : interfaceService.getInterfaces()) {
            if (!intf.connectPoint().equals(egressInterface.connectPoint())) {
                ConnectPoint srcPort = intf.connectPoint();
                ingressPorts.add(srcPort);
            }
        }

        // Match the destination IP prefix at the first hop
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(prefix)
                .build();

        // Rewrite the destination MAC address
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setEthDst(nextHopMacAddress)
                .build();

        return new MultiPointToSinglePointIntent(appId, selector, treatment,
                                                 ingressPorts, egressPort);
    }

    /**
     * Processes the deletion of a route entry.
     * <p>
     * The prefix for the routing entry is removed from radix tree.
     * If the operation is successful, the prefix is added to the collection
     * of prefixes whose intents that will be withdrawn.
     * </p>
     *
     * @param routeEntry the route entry to delete
     * @param withdrawPrefixes the collection of accumulated prefixes whose
     * intents will be withdrawn
     */
    private void processRouteDelete(RouteEntry routeEntry,
                                    Collection<Ip4Prefix> withdrawPrefixes) {
        log.debug("Processing route delete: {}", routeEntry);
        Ip4Prefix prefix = routeEntry.prefix();

        if (bgpRoutes.remove(RouteEntry.createBinaryString(prefix))) {
            //
            // Only withdraw intents if an entry was actually removed from the
            // tree. If no entry was removed, the <prefix, nexthop> wasn't
            // there so it's probably already been removed and we don't
            // need to do anything.
            //
            withdrawPrefixes.add(routeEntry.prefix());
        }

        routesWaitingOnArp.remove(routeEntry.nextHop(), routeEntry);
        // TODO cancel the request in the ARP manager as well
    }

    /**
     * Signals the Router that the MAC to IP mapping has potentially been
     * updated. This has the effect of updating the MAC address for any
     * installed prefixes if it has changed, as well as installing any pending
     * prefixes that were waiting for MAC resolution.
     *
     * @param ipAddress the IP address that an event was received for
     * @param macAddress the most recently known MAC address for the IP address
     */
    private void updateMac(Ip4Address ipAddress, MacAddress macAddress) {
        log.debug("Received updated MAC info: {} => {}", ipAddress, macAddress);

        // TODO here we should check whether the next hop for any of our
        // installed prefixes has changed, not just prefixes pending
        // installation.

        // We synchronize on this to prevent changes to the radix tree
        // while we're pushing intents. If the tree changes, the
        // tree and intents could get out of sync.
        synchronized (this) {
            Collection<Pair<Ip4Prefix, MultiPointToSinglePointIntent>>
                submitIntents = new LinkedList<>();
            MultiPointToSinglePointIntent intent;

            Set<RouteEntry> routesToPush =
                    routesWaitingOnArp.removeAll(ipAddress);

            for (RouteEntry routeEntry : routesToPush) {
                // These will always be adds
                Ip4Prefix prefix = routeEntry.prefix();
                String binaryString = RouteEntry.createBinaryString(prefix);
                RouteEntry foundRouteEntry =
                        bgpRoutes.getValueForExactKey(binaryString);
                if (foundRouteEntry != null &&
                    foundRouteEntry.nextHop().equals(routeEntry.nextHop())) {
                    // We only push prefix flows if the prefix is still in the
                    // radix tree and the next hop is the same as our
                    // update.
                    // The prefix could have been removed while we were waiting
                    // for the ARP, or the next hop could have changed.
                    intent = generateRouteIntent(prefix, ipAddress,
                                                 macAddress);
                    if (intent != null) {
                        submitIntents.add(Pair.of(prefix, intent));
                    }
                } else {
                    log.debug("{} has been revoked before the MAC was resolved",
                              routeEntry);
                }
            }

            if (!submitIntents.isEmpty()) {
                Collection<Ip4Prefix> withdrawPrefixes = new LinkedList<>();
                intentSynchronizer.updateRouteIntents(submitIntents,
                                                      withdrawPrefixes);
            }

            ip2Mac.put(ipAddress, macAddress);
        }
    }

    /**
     * Gets the SDN-IP routes.
     *
     * @return the SDN-IP routes
     */
    public Collection<RouteEntry> getRoutes() {
        Iterator<KeyValuePair<RouteEntry>> it =
                bgpRoutes.getKeyValuePairsForKeysStartingWith("").iterator();

        List<RouteEntry> routes = new LinkedList<>();

        while (it.hasNext()) {
            KeyValuePair<RouteEntry> entry = it.next();
            routes.add(entry.getValue());
        }

        return routes;
    }

    /**
     * Listener for host events.
     */
    class InternalHostListener implements HostListener {
        @Override
        public void event(HostEvent event) {
            log.debug("Received HostEvent {}", event);

            Host host = event.subject();
            switch (event.type()) {
            case HOST_ADDED:
                // FALLTHROUGH
            case HOST_UPDATED:
                for (IpAddress ip : host.ipAddresses()) {
                    Ip4Address ip4Address = ip.getIp4Address();
                    if (ip4Address == null) {
                        // TODO: For now we support only IPv4
                        continue;
                    }
                    updateMac(ip4Address, host.mac());
                }
                break;
            case HOST_REMOVED:
                for (IpAddress ip : host.ipAddresses()) {
                    Ip4Address ip4Address = ip.getIp4Address();
                    if (ip4Address == null) {
                        // TODO: For now we support only IPv4
                        continue;
                    }
                    ip2Mac.remove(ip4Address);
                }
                break;
            default:
                break;
            }
        }
    }
}
