/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.placement;

import java.util.List;
import java.util.Map;

import com.emc.storageos.api.service.impl.resource.utils.FilePolicyServiceUtils;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;

/**
 * 
 * this call specific implementation of Scheduler based on Vpool capability(default, local, remote)
 * 
 */
public class FilePlacementManager {
    private DbClient dbClient;
    // Storage schedulers
    private Map<String, Scheduler> storageSchedulers;

    public void setDbClient(DbClient dbClient) {
        this.dbClient = dbClient;
    }

    public void setStorageSchedulers(Map<String, Scheduler> storageSchedulers) {
        this.storageSchedulers = storageSchedulers;
    }

    public Scheduler getStorageScheduler(String type) {
        return storageSchedulers.get(type);
    }

    public List<FileRecommendation> getRecommendationsForFileCreateRequest(VirtualArray virtualArray,
            Project project, VirtualPool vPool, VirtualPoolCapabilityValuesWrapper capabilities) {

        // Get the file placement based on passed parameters.
        Scheduler scheduler = getFileServiceImpl(capabilities);
        return scheduler.getRecommendationsForResources(virtualArray, project, vPool, capabilities);
    }

    /**
     * Returns the scheduler responsible for scheduling resources
     * 
     * @param vpool Virtual Pool
     * @return storage scheduler
     */
    private Scheduler getFileServiceImpl(VirtualPoolCapabilityValuesWrapper caps) {

        // Select an implementation of the right scheduler
        Scheduler scheduler = storageSchedulers.get("file");
        if (FilePolicyServiceUtils.vPoolSpecifiesFileReplication(caps)) {
            scheduler = storageSchedulers.get("mirrorfile");
        }
        return scheduler;
    }

}
