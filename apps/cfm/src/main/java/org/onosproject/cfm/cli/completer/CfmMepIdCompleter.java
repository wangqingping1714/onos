/*
 * Copyright 2018-present Open Networking Foundation
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
package org.onosproject.cfm.cli.completer;

import org.onosproject.cli.AbstractChoicesCompleter;
import org.onosproject.incubator.net.l2monitoring.cfm.service.CfmConfigException;
import org.onosproject.incubator.net.l2monitoring.cfm.service.CfmMdService;
import org.onosproject.incubator.net.l2monitoring.cfm.service.CfmMepService;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

import static org.onosproject.cli.AbstractShellCommand.get;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * CLI completer for Mep Id creation.
 */
public class CfmMepIdCompleter extends AbstractChoicesCompleter {
    private final Logger log = getLogger(getClass());

    @Override
    public List<String> choices() {
        List<String> choices = new ArrayList<>();

        CfmMdService mdService = get(CfmMdService.class);
        CfmMepService mepService = get(CfmMepService.class);

        mdService.getAllMaintenanceDomain().forEach(md -> {
            choices.add(new StringBuilder(md.mdId().mdName())
                            .append("(")
                            .append(md.mdId().nameType())
                            .append(")").toString());

            md.maintenanceAssociationList().forEach(ma -> {
                    choices.add(new StringBuilder(md.mdId().mdName())
                            .append("(")
                            .append(md.mdId().nameType())
                            .append(") ")
                            .append(ma.maId().maName())
                            .append("(")
                            .append(ma.maId().nameType())
                            .append(")").toString());

                    try {
                        mepService.getAllMeps(md.mdId(), ma.maId()).forEach(mep ->
                                choices.add(new StringBuilder(md.mdId().mdName())
                                        .append("(")
                                        .append(md.mdId().nameType())
                                        .append(") ")
                                        .append(ma.maId().maName())
                                        .append("(")
                                        .append(ma.maId().nameType())
                                        .append(") ")
                                        .append(mep.mepId()).toString())
                        );
                    } catch (CfmConfigException e) {
                        log.warn("Unable to retrieve mep details", e);
                    }
                }
            );
        });

        return choices;
    }

}
