/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.smis.srdf.collectors;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.volumecontroller.impl.smis.srdf.SRDFUtils;

import javax.cim.CIMObjectPath;
import java.util.Collection;

/**
 * Created by bibbyi1 on 3/25/2015.
 */
public class StorageSynchronizedCollector extends AbstractCollector {

    public StorageSynchronizedCollector(DbClient dbClient, SRDFUtils utils) {
        super(dbClient, utils);
    }

    @Override
    public Collection<CIMObjectPath> collect(StorageSystem activeProviderSystem, Volume targetVolume) {
        Volume sourceVolume = dbClient.queryObject(Volume.class, targetVolume.getSrdfParent().getURI());

        Collection<CIMObjectPath> syncPaths = null;
        try {
            syncPaths = utils.getSynchronizations(activeProviderSystem, sourceVolume, targetVolume, false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to collect synchronization instances", e);
        }

        return syncPaths;
    }
}
