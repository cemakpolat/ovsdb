/*
 * Copyright (c) 2015 China Telecom Beijing Research Institute and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.ovsdb.hwvtepsouthbound.transact;

import static org.opendaylight.ovsdb.lib.operations.Operations.op;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.opendaylight.controller.md.sal.binding.api.DataObjectModification;
import org.opendaylight.controller.md.sal.binding.api.DataTreeModification;
import org.opendaylight.ovsdb.lib.notation.Mutator;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.TransactionBuilder;
import org.opendaylight.ovsdb.lib.schema.typed.TyperUtils;
import org.opendaylight.ovsdb.schema.hardwarevtep.PhysicalPort;
import org.opendaylight.ovsdb.schema.hardwarevtep.PhysicalSwitch;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.hwvtep.rev150901.HwvtepPhysicalPortAugmentation;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.node.TerminationPoint;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.collect.Sets;

public class PhysicalPortRemoveCommand extends AbstractTransactCommand {
    private static final Logger LOG = LoggerFactory.getLogger(PhysicalPortRemoveCommand.class);

    public PhysicalPortRemoveCommand(HwvtepOperationalState state,
            Collection<DataTreeModification<Node>> changes) {
        super(state, changes);
    }

    @Override
    public void execute(TransactionBuilder transaction) {
        //TODO reuse from base class instead of extractRemovedPorts
        Map<InstanceIdentifier<Node>, List<HwvtepPhysicalPortAugmentation>> removeds =
                extractRemovedPorts(getChanges(), HwvtepPhysicalPortAugmentation.class);
        if (!removeds.isEmpty()) {
            for (Entry<InstanceIdentifier<Node>, List<HwvtepPhysicalPortAugmentation>> removed:
                removeds.entrySet()) {
                removePhysicalPort(transaction,  removed.getKey(), removed.getValue());
            }
        }
    }

    private void removePhysicalPort(TransactionBuilder transaction,
            InstanceIdentifier<Node> psNodeiid,
            List<HwvtepPhysicalPortAugmentation> listPort) {
        for (HwvtepPhysicalPortAugmentation port : listPort) {
            LOG.debug("Removing a physical port named: {}", port.getHwvtepNodeName().getValue());
            Optional<HwvtepPhysicalPortAugmentation> operationalPhysicalPortOptional =
                    getOperationalState().getPhysicalPortAugmentation(psNodeiid, port.getHwvtepNodeName());
            PhysicalPort physicalPort = TyperUtils.getTypedRowWrapper(transaction.getDatabaseSchema(), PhysicalPort.class, null);
            //get managing global node of physicalSwitchBelong
            //InstanceIdentifier<?> globalNodeIid = physicalSwitchBelong.getManagedBy().getValue();
            if (operationalPhysicalPortOptional.isPresent()) {
                UUID physicalPortUuid = new UUID(operationalPhysicalPortOptional.get().getPhysicalPortUuid().getValue());
                PhysicalSwitch physicalSwitch = TyperUtils.getTypedRowWrapper(transaction.getDatabaseSchema(),
                        PhysicalSwitch.class, null);
                transaction.add(op.delete(physicalPort.getSchema())
                        .where(physicalPort.getUuidColumn().getSchema().opEqual(physicalPortUuid)).build());
                transaction.add(op.comment("Physical Port: Deleting " + port.getHwvtepNodeName().getValue()));
                transaction.add(op.mutate(physicalSwitch.getSchema())
                        .addMutation(physicalSwitch.getPortsColumn().getSchema(), Mutator.DELETE,
                                Sets.newHashSet(physicalPortUuid)));
                transaction.add(op.comment("Physical Switch: Mutating " + port.getHwvtepNodeName().getValue() + " " + physicalPortUuid));
            } else {
                LOG.warn("Unable to delete logical switch {} because it was not found in the operational store, "
                        + "and thus we cannot retrieve its UUID", port.getHwvtepNodeName().getValue());
            }
        }
    }

    protected Map<InstanceIdentifier<Node>, List<HwvtepPhysicalPortAugmentation>> extractRemovedPorts(
            Collection<DataTreeModification<Node>> changes, Class<HwvtepPhysicalPortAugmentation> class1) {
        Map<InstanceIdentifier<Node>, List<HwvtepPhysicalPortAugmentation>> result
            = new HashMap<InstanceIdentifier<Node>, List<HwvtepPhysicalPortAugmentation>>();
        if (changes != null && !changes.isEmpty()) {
            for (DataTreeModification<Node> change : changes) {
                final InstanceIdentifier<Node> key = change.getRootPath().getRootIdentifier();
                final DataObjectModification<Node> mod = change.getRootNode();
                //If the node which physical ports belong to is removed, all physical ports
                //should be removed too.
                Node removed = TransactUtils.getRemoved(mod);
                if (removed != null) {
                    List<HwvtepPhysicalPortAugmentation> lswitchListRemoved = new ArrayList<HwvtepPhysicalPortAugmentation>();
                    if (removed.getTerminationPoint() != null) {
                        for (TerminationPoint tp : removed.getTerminationPoint()) {
                            HwvtepPhysicalPortAugmentation hppAugmentation = tp.getAugmentation(HwvtepPhysicalPortAugmentation.class);
                            if (hppAugmentation != null) {
                                lswitchListRemoved.add(hppAugmentation);
                            }
                        }
                    }
                    if (!lswitchListRemoved.isEmpty()) {
                        result.put(key, lswitchListRemoved);
                    }
                }
                //If the node which physical ports belong to is updated, and physical ports may
                //be created or updated or deleted, we need to get deleted ones.
                Node updated = TransactUtils.getUpdated(mod);
                Node before = mod.getDataBefore();
                if (updated != null && before != null) {
                    List<HwvtepPhysicalPortAugmentation> portListUpdated = new ArrayList<HwvtepPhysicalPortAugmentation>();
                    List<HwvtepPhysicalPortAugmentation> portListBefore = new ArrayList<HwvtepPhysicalPortAugmentation>();
                    List<HwvtepPhysicalPortAugmentation> portListRemoved = new ArrayList<HwvtepPhysicalPortAugmentation>();
                    if (updated.getTerminationPoint() != null) {
                        for (TerminationPoint tp : updated.getTerminationPoint()) {
                            HwvtepPhysicalPortAugmentation hppAugmentation = tp.getAugmentation(HwvtepPhysicalPortAugmentation.class);
                            if (hppAugmentation != null) {
                                portListUpdated.add(hppAugmentation);
                            }
                        }
                    }
                    if (before.getTerminationPoint() != null) {
                        for (TerminationPoint tp : before.getTerminationPoint()) {
                            HwvtepPhysicalPortAugmentation hppAugmentation = tp.getAugmentation(HwvtepPhysicalPortAugmentation.class);
                            if (hppAugmentation != null) {
                                portListBefore.add(hppAugmentation);
                            }
                        }
                    }
                    portListBefore.removeAll(portListUpdated);
                    //then exclude updated physical ports
                    for (HwvtepPhysicalPortAugmentation portBefore: portListBefore) {
                        int i = 0;
                        for(; i < portListUpdated.size(); i++) {
                            if (portBefore.getHwvtepNodeName().equals(portListUpdated.get(i).getHwvtepNodeName())) {
                                break;
                            }
                        }
                        if (i == portListUpdated.size()) {
                            portListRemoved.add(portBefore);
                        }
                    }
                    if (!portListRemoved.isEmpty()) {
                        result.put(key, portListRemoved);
                    }
                }
            }
        }
        return result;
    }
}
