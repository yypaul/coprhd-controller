/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.block.consistency.tasks;

import java.net.URI;

import com.emc.sa.service.vipr.tasks.WaitForTask;
import com.emc.storageos.model.block.BlockConsistencyGroupRestRep;
import com.emc.vipr.client.Task;

public class RestoreConsistencyGroupSnapshot extends
        WaitForTask<BlockConsistencyGroupRestRep> {

    private URI consistencyGroup;
    private URI snapshot;

    public RestoreConsistencyGroupSnapshot(URI consistencyGroup, URI snapshot) {
        this.consistencyGroup = consistencyGroup;
        this.snapshot = snapshot;
    }

    @Override
    protected Task<BlockConsistencyGroupRestRep> doExecute() throws Exception {
        return getClient().blockConsistencyGroups().restoreSnapshot(consistencyGroup, snapshot);
    }
}
