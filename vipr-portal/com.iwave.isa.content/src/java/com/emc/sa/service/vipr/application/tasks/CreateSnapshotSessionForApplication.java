/*
 * Copyright (c) 2015 EMC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.application.tasks;

import java.net.URI;

import com.emc.sa.service.vipr.tasks.WaitForTasks;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.TaskResourceRep;
import com.emc.storageos.model.application.VolumeGroupSnapshotSessionCreateParam;
import com.emc.vipr.client.Tasks;
import com.google.common.collect.Lists;

public class CreateSnapshotSessionForApplication extends WaitForTasks<TaskResourceRep> {
    private final URI applicationId;
    private final String name;
    private final URI volume;
    private final Boolean copyOnHighAvailabilitySide;

    public CreateSnapshotSessionForApplication(URI applicationId, URI volume, String name, Boolean copyOnHighAvailabilitySide) {
        this.applicationId = applicationId;
        this.name = name;
        this.volume = volume;
        this.copyOnHighAvailabilitySide = copyOnHighAvailabilitySide;
        provideDetailArgs(applicationId, name);
    }

    @Override
    protected Tasks<TaskResourceRep> doExecute() throws Exception {
        VolumeGroupSnapshotSessionCreateParam input = new VolumeGroupSnapshotSessionCreateParam();
        input.setName(name);
        input.setVolumes(Lists.newArrayList(volume));
        input.setPartial(true);
        input.setCopyOnHighAvailabilitySide(copyOnHighAvailabilitySide);

        TaskList taskList = getClient().application().createSnapshotSessionOfApplication(applicationId, input);

        return new Tasks<TaskResourceRep>(getClient().auth().getClient(), taskList.getTaskList(),
                TaskResourceRep.class);
    }
}
