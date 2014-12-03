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

import static org.slf4j.LoggerFactory.getLogger;

import java.util.Collection;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.ControllerNode;
import org.onosproject.cluster.LeadershipEvent;
import org.onosproject.cluster.LeadershipEventListener;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.IntentService;
import org.onosproject.sdnip.bgp.BgpRouteEntry;
import org.onosproject.sdnip.bgp.BgpSession;
import org.onosproject.sdnip.bgp.BgpSessionManager;
import org.onosproject.sdnip.config.SdnIpConfigurationReader;
import org.slf4j.Logger;

/**
 * Component for the SDN-IP peering application.
 */
@Component(immediate = true)
@Service
public class SdnIp implements SdnIpService {

    private static final String SDN_IP_APP = "org.onosproject.sdnip";
    // NOTE: Must be 5s for now
    private static final int LEASE_DURATION_MS = 5 * 1000;
    private static final int LEASE_EXTEND_RETRY_MAX = 3;

    private final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    private IntentSynchronizer intentSynchronizer;
    private SdnIpConfigurationReader config;
    private PeerConnectivityManager peerConnectivity;
    private Router router;
    private BgpSessionManager bgpSessionManager;
    private LeadershipEventListener leadershipEventListener =
        new InnerLeadershipEventListener();
    private ApplicationId appId;
    private ControllerNode localControllerNode;

    @Activate
    protected void activate() {
        log.info("SDN-IP started");

        appId = coreService.registerApplication(SDN_IP_APP);
        config = new SdnIpConfigurationReader();
        config.readConfiguration();

        localControllerNode = clusterService.getLocalNode();

        InterfaceService interfaceService =
            new HostToInterfaceAdaptor(hostService);

        intentSynchronizer = new IntentSynchronizer(appId, intentService);
        intentSynchronizer.start();

        peerConnectivity = new PeerConnectivityManager(appId,
                                                       intentSynchronizer,
                                                       config,
                                                       interfaceService);
        peerConnectivity.start();

        router = new Router(appId, intentSynchronizer, config,
                            interfaceService, hostService);
        router.start();

        leadershipService.addListener(leadershipEventListener);
        leadershipService.runForLeadership(appId.name());

        bgpSessionManager = new BgpSessionManager(router);
        // TODO: the local BGP listen port number should be configurable
        bgpSessionManager.start(2000);

        // TODO need to disable link discovery on external ports
    }

    @Deactivate
    protected void deactivate() {

        bgpSessionManager.stop();
        router.stop();
        peerConnectivity.stop();
        intentSynchronizer.stop();

        leadershipService.withdraw(appId.name());
        leadershipService.removeListener(leadershipEventListener);

        log.info("SDN-IP Stopped");
    }

    @Override
    public Collection<BgpSession> getBgpSessions() {
        return bgpSessionManager.getBgpSessions();
    }

    @Override
    public Collection<BgpRouteEntry> getBgpRoutes() {
        return bgpSessionManager.getBgpRoutes();
    }

    @Override
    public Collection<RouteEntry> getRoutes() {
        return router.getRoutes();
    }

    @Override
    public void modifyPrimary(boolean isPrimary) {
        intentSynchronizer.leaderChanged(isPrimary);
    }

    static String dpidToUri(String dpid) {
        return "of:" + dpid.replace(":", "");
    }

    /**
     * A listener for Leadership Events.
     */
    private class InnerLeadershipEventListener
        implements LeadershipEventListener {

        @Override
        public void event(LeadershipEvent event) {
            log.debug("Leadership Event: time = {} type = {} event = {}",
                      event.time(), event.type(), event);

            if (!event.subject().topic().equals(appId.name())) {
                return;         // Not our topic: ignore
            }
            if (!event.subject().leader().equals(
                        localControllerNode.id())) {
                return;         // The event is not about this instance: ignore
            }

            switch (event.type()) {
            case LEADER_ELECTED:
                log.info("SDN-IP Leader Elected");
                intentSynchronizer.leaderChanged(true);
                break;
            case LEADER_BOOTED:
                log.info("SDN-IP Leader Lost Election");
                intentSynchronizer.leaderChanged(false);
                break;
            case LEADER_REELECTED:
                break;
            default:
                break;
            }
        }
    }
}
