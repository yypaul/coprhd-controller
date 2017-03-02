/*
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.volumecontroller.impl.block.taskcompleter;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.util.ExportUtils;

@SuppressWarnings("serial")
public class ExportMaskDeleteCompleter extends ExportTaskCompleter {
    private static final Logger _log = LoggerFactory.getLogger(ExportMaskDeleteCompleter.class);

    public ExportMaskDeleteCompleter(URI egUri, URI emUri, String task) {
        super(ExportGroup.class, egUri, emUri, task);
    }

    @Override
    protected void complete(DbClient dbClient, Operation.Status status, ServiceCoded coded) throws DeviceControllerException {
        try {            
            ExportMask exportMask = (getMask() != null) ?
                    dbClient.queryObject(ExportMask.class, getMask()) : null;
            if ((status == Operation.Status.error) && (isRollingBack()) && (coded instanceof ServiceError)) {
                ServiceError error = (ServiceError) coded;
                String originalMessage = error.getMessage();
                StorageSystem storageSystem = exportMask != null ? dbClient.queryObject(StorageSystem.class, exportMask.getStorageDevice())
                        : null;
                String additionMessage = String.format(
                        "Rollback encountered problems cleaning up export mask %s on storage system %s and may require manual clean up",
                        exportMask.getMaskName(), storageSystem != null ? storageSystem.forDisplay() : "Unknown");
                String updatedMessage = String.format("%s\n%s", originalMessage, additionMessage);
                error.setMessage(updatedMessage);
            }

            if (exportMask != null && (status == Operation.Status.ready || (Operation.isTerminalState(status) && isRollingBack()))) {
                ExportUtils.cleanupAssociatedMaskResources(dbClient, exportMask);
                dbClient.markForDeletion(exportMask);
            }

            _log.info(String.format("Done ExportMaskDelete - EG: %s, OpId: %s, status: %s",
                    getId().toString(), getOpId(), status.name()));
        } catch (Exception e) {
            _log.error(String.format("Failed updating status for ExportMaskDelete - EG: %s, OpId: %s",
                    getId().toString(), getOpId()), e);
        } finally {
            super.complete(dbClient, status, coded);
        }
    }
}
