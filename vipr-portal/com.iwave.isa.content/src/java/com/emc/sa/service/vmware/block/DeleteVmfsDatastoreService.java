/*
 * Copyright (c) 2012-2015 iWave Software LLC
 * All Rights Reserved
 */
package com.emc.sa.service.vmware.block;

import static com.emc.sa.service.ServiceParams.DATASTORE_NAME;

import java.util.List;
import java.util.Map;

import com.emc.sa.engine.ExecutionUtils;
import com.emc.sa.engine.bind.Param;
import com.emc.sa.engine.service.Service;
import com.emc.sa.service.vmware.VMwareHostService;
import com.emc.storageos.model.block.VolumeRestRep;
import com.google.common.collect.Maps;
import com.vmware.vim25.mo.Datastore;

@Service("VMware-DeleteVmfsDatastore")
public class DeleteVmfsDatastoreService extends VMwareHostService {
    @Param(DATASTORE_NAME)
    protected List<String> datastoreNames;

    private Map<Datastore, List<VolumeRestRep>> datastores;

    @Override
    public void precheck() throws Exception {
        StringBuilder preCheckErrors = new StringBuilder();

        super.precheck();
        datastores = Maps.newHashMap();
        acquireHostLock();
        for (String datastoreName : datastoreNames) {
            Datastore datastore = vmware.getDatastore(datacenter.getLabel(), datastoreName);

            vmware.verifyDatastoreForRemoval(datastore);

            List<VolumeRestRep> volumes = vmware.findVolumesBackingDatastore(host, hostId, datastore);

            // If no volumes were found (or not all the volumes were found in our DB), indicate an error
            if (volumes == null) {
                preCheckErrors.append(
                        ExecutionUtils.getMessage("delete.vmfs.datastore.notsamewwn", datastoreName) + " ");
            }

            datastores.put(datastore, volumes);
        }

        if (preCheckErrors.length() > 0) {
            throw new IllegalStateException(preCheckErrors.toString());
        }
    }

    @Override
    public void execute() throws Exception {

        for (Map.Entry<Datastore, List<VolumeRestRep>> entry : datastores.entrySet()) {
            vmware.deleteVmfsDatastore(entry.getValue(), hostId, entry.getKey(), false);
        }
        vmware.refreshStorage(host, cluster);
        if (hostId != null) {
            ExecutionUtils.addAffectedResource(hostId.toString());
        }
    }
}
