/*
 * Copyright (c) 2008-2011 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.block.taskcompleter;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Stat;
import com.emc.storageos.db.client.model.StatTimeSeries;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.plugins.common.Constants;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;

public class VolumeDeleteCompleter extends VolumeTaskCompleter {
    private static final Logger _log = LoggerFactory
            .getLogger(VolumeDeleteCompleter.class);

    public VolumeDeleteCompleter(URI volUri, String task) {
        super(Volume.class, volUri, task);
    }

    public VolumeDeleteCompleter(List<URI> volUris, String task) {
        super(Volume.class, volUris, task);
    }

    /**
     * Remove reference of deleted volume from associated source volume
     * 
     * @param dbClient
     * @param deletedVolume
     */
    private void removeDeletedVolumeReference(DbClient dbClient, Volume deletedVolume) {
        if (deletedVolume != null && !NullColumnValueGetter.isNullURI(deletedVolume.getAssociatedSourceVolume())) {
            Volume srcVolume = dbClient.queryObject(Volume.class, deletedVolume.getAssociatedSourceVolume());

            // remove reference of deleted volume from fullCopies
            if (srcVolume != null) {
                srcVolume.getFullCopies().remove(deletedVolume.getId().toString());
                dbClient.persistObject(srcVolume);
            }
        }
    }

    @Override
    protected void complete(DbClient dbClient, Operation.Status status, ServiceCoded coded) throws DeviceControllerException {
        try {
            List<Volume> volumes = dbClient.queryObject(Volume.class, getIds());
            for (Volume volume : volumes) {
                switch (status) {
                    case error:
                        // Pattern for rollback quality:
                        // If the step is a rollback step and we did receive an error, we want to notify the
                        // user of the potential resource(s) that were not cleaned-up as a by-product of this
                        // failure, so we will add such information into the incoming service code message.
                        if (isRollingBack() && (coded instanceof ServiceError)) {
                            ServiceError error = (ServiceError) coded;
                            String originalMessage = error.getMessage();
                            String additionMessage = "Rollback encountered problems cleaning up " +
                                    volume.getNativeGuid() + " and may require manual clean up";
                            String updatedMessage = String.format("%s\n%s", originalMessage, additionMessage);
                            error.setMessage(updatedMessage);
                        }
                        dbClient.error(Volume.class, volume.getId(), getOpId(), coded);
                        break;
                    default:
                        dbClient.ready(Volume.class, volume.getId(), getOpId());
                }

                _log.info(String.format("Done VolumeDelete - Id: %s, OpId: %s, status: %s",
                        getId().toString(), getOpId(), status.name()));
                // Generate Zero Metering Record only after successful deletion
                if (Operation.Status.ready == status) {
                    if (null != volume) {
                        generateZeroStatisticsRecord(dbClient, volume);

                        removeDeletedVolumeReference(dbClient, volume);
                    }
                }

                recordBlockVolumeOperation(dbClient, OperationTypeEnum.DELETE_BLOCK_VOLUME, status, volume.getId().toString());
            }

            if (status.equals(Operation.Status.ready) || (status.equals(Operation.Status.error) && isRollingBack())) {
                for (Volume volume : volumes) {
                    volume.setConsistencyGroup(NullColumnValueGetter.getNullURI());
                }
                dbClient.markForDeletion(volumes);
            }

        } catch (Exception e) {
            _log.error(String.format(
                    "Failed updating status for VolumeDelete - Id: %s, OpId: %s", getIds()
                            .toString(), getOpId()), e);
        } finally {
            super.complete(dbClient, status, coded);
        }
    }

    /**
     * Generate Zero Statistics Record for successfully deleted Volumes
     * 
     * @param dbClient
     * @param volume
     * @throws IOException
     */
    private void generateZeroStatisticsRecord(DbClient dbClient, Volume volume) {
        try {
            Stat zeroStatRecord = new Stat();
            zeroStatRecord.setTimeInMillis(System.currentTimeMillis());
            zeroStatRecord.setTimeCollected(System.currentTimeMillis());
            zeroStatRecord.setServiceType(Constants._Block);
            zeroStatRecord.setAllocatedCapacity(0);
            zeroStatRecord.setProvisionedCapacity(0);
            zeroStatRecord.setBandwidthIn(0);
            zeroStatRecord.setBandwidthOut(0);
            zeroStatRecord.setTotalIOs(0);
            zeroStatRecord.setIdleTimeCounter(0);
            zeroStatRecord.setQueueLength(0);
            zeroStatRecord.setWriteIOs(0);
            zeroStatRecord.setReadIOs(0);
            zeroStatRecord.setKbytesTransferred(0);
            zeroStatRecord.setIoTimeCounter(0);
            zeroStatRecord.setNativeGuid(volume.getNativeGuid());
            zeroStatRecord.setSnapshotCapacity(0);
            zeroStatRecord.setSnapshotCount(0);
            zeroStatRecord.setResourceId(volume.getId());
            zeroStatRecord.setVirtualPool(volume.getVirtualPool());
            zeroStatRecord.setProject(volume.getProject().getURI());
            zeroStatRecord.setTenant(volume.getTenant().getURI());
            dbClient.insertTimeSeries(StatTimeSeries.class, zeroStatRecord);
        } catch (Exception e) {
            _log.error("Zero Stat Record Creation failed for Volume : {}", volume.getId());
        }
    }
}
