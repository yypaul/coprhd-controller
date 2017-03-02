/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.block.taskcompleter;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.FCZoneReference;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Operation.Status;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.networkcontroller.NetworkFCZoneInfo;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.volumecontroller.TaskCompleter;

public class ZoneReferencesRemoveCompleter extends TaskCompleter {
    private static final Logger _log = LoggerFactory.getLogger(ZoneReferencesRemoveCompleter.class);

    private List<NetworkFCZoneInfo> zoneInfoList;
    private boolean removeZoneReferences;

    /**
     * Constructor for ZoneReferencesRemoveCompleter.
     * 
     * @param zoneList List of FCZoneInfo instance
     * @param removeZoneRefs True if Zone needs to be removed or Zone was added and needs to be removed in rollback call. Else False.
     * @param opId Operation ID.
     */
    public ZoneReferencesRemoveCompleter(List<NetworkFCZoneInfo> zoneList, boolean removeZoneRefs, String opId) {
        super();
        zoneInfoList = new ArrayList<NetworkFCZoneInfo>();
        zoneInfoList.addAll(zoneList);
        removeZoneReferences = removeZoneRefs;
    }

    @Override
    protected void complete(DbClient dbClient, Operation.Status status, ServiceCoded coded)
            throws DeviceControllerException {

        if (removeZoneReferences && status == Status.error) {
            String refKey = null;
            try {
                for (NetworkFCZoneInfo fabricInfo : zoneInfoList) {
                    FCZoneReference ref = dbClient.queryObject(FCZoneReference.class, fabricInfo.getFcZoneReferenceId());
                    if (ref != null) {
                        refKey = ref.getPwwnKey();
                        _log.info(String.format("Remove FCZoneReference key: %s volume %s id %s", ref.getPwwnKey(), ref.getVolumeUri(),
                                ref.getId().toString()));
                        dbClient.removeObject(ref);
                    }
                }
            } catch (DatabaseException ex) {
                _log.error("Could not persist FCZoneReference: " + refKey);
            }
        }
    }
}
