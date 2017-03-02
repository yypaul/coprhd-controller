/*
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.vplex.api;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.vplex.api.clientdata.VolumeInfo;
import com.sun.jersey.api.client.ClientResponse;

/**
 * VPlexApiMigrationManager provides methods creating and managing data
 * migrations.
 */
public class VPlexApiMigrationManager {

    // Logger reference.
    private static Logger s_logger = LoggerFactory
            .getLogger(VPlexApiMigrationManager.class);

    // A reference to the API client.
    private VPlexApiClient _vplexApiClient;

    /**
     * Package protected constructor.
     * 
     * @param client A reference to the API client.
     */
    VPlexApiMigrationManager(VPlexApiClient client) {
        _vplexApiClient = client;
    }

    /**
     * For the virtual volume with the passed name, migrates the data on the
     * backend volume(s) to the backend volumes identified by the passed native
     * volume information.
     * 
     * @param migrationName The name for this migration.
     * @param virtualVolumeName The name of the virtual volume whose data is to
     *            be migrated.
     * @param nativeVolumeInfoList The native information for the volume(s) to
     *            which the data should be migrated.
     * @param isRemote true if the the migration is across clusters, else false.
     * @param useDeviceMigration true if device migration is required.
     * @param discoveryRequired true if the passed native volumes are newly
     *            exported and need to be discovered by the VPlex.
     * @param startNow true to start the migration now, else migration is
     *            created in a paused state.
     * @param transferSize The migration transfer size
     * @return A reference to the migration(s) started to migrate the virtual
     *         volume.
     * 
     * @throws VPlexApiException When an error occurs creating and/or
     *             initializing the migration.
     */
    List<VPlexMigrationInfo> migrateVirtualVolume(String migrationName,
            String virtualVolumeName, List<VolumeInfo> nativeVolumeInfoList,
            boolean isRemote, boolean useDeviceMigration, boolean discoveryRequired,
            boolean startNow, String transferSize) throws VPlexApiException {

        s_logger.info("Migrating virtual volume {}", virtualVolumeName);

        // Find the storage volumes corresponding to the passed native
        // volume information, discovering them if required. If a requested
        // volume cannot be found an exception is thrown. Do this first
        // as it ensures we have the latest cluster info for finding the
        // virtual volume.
        VPlexApiVirtualVolumeManager virtualVolumeMgr = _vplexApiClient
                .getVirtualVolumeManager();
        List<VPlexClusterInfo> clusterInfoList = new ArrayList<VPlexClusterInfo>();
        Map<VolumeInfo, VPlexStorageVolumeInfo> storageVolumeInfoMap = virtualVolumeMgr
                .findStorageVolumes(nativeVolumeInfoList, discoveryRequired, clusterInfoList, null);
        s_logger.info("Found storage volumes");

        // Now find the virtual volume.
        VPlexVirtualVolumeInfo virtualVolumeInfo = null;
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        for (VPlexClusterInfo clusterInfo : clusterInfoList) {
            virtualVolumeInfo = discoveryMgr.findVirtualVolume(clusterInfo.getName(),
                    virtualVolumeName, false);
            if (virtualVolumeInfo != null) {
                // Update the virtual volume info with the attribute information.
                discoveryMgr.updateVirtualVolumeInfo(clusterInfo.getName(), virtualVolumeInfo);
                break;
            }
        }
        if (virtualVolumeInfo == null) {
            throw VPlexApiException.exceptions.cantFindRequestedVolume(virtualVolumeName);
        }
        s_logger.info("Found virtual volume");

        // For a distributed virtual volume we migrate one or both extents
        // used by the virtual volume. For a local virtual volume we have
        // the ability to either migrate the local device or extent. We
        // choose to migrate the the local extent used by the virtual volume.
        // Originally we used device migration, but the VPlex migration name
        // length restriction for a device migration is only 22 characters
        // and we could not give it the descriptive name we desired, so
        // we switch to extent migration which supports a 63 character name.
        // We only use device migration if specifically requested.
        if (VPlexVirtualVolumeInfo.Locality.distributed.name().equals(virtualVolumeInfo.getLocality())) {
            s_logger.info("Virtual volume is on distributed device {}", virtualVolumeInfo.getSupportingDevice());
            return migrateDistributedVirtualVolume(migrationName, virtualVolumeInfo,
                    storageVolumeInfoMap, startNow, transferSize);
        } else {
            // The virtual volume was built on a local device.
            s_logger.info("Virtual volume is on local device {}", virtualVolumeInfo.getSupportingDevice());
            return Arrays.asList(migrateLocalVirtualVolume(migrationName, virtualVolumeInfo,
                    storageVolumeInfoMap, startNow, isRemote, useDeviceMigration, transferSize));
        }
    }

    /**
     * Pauses the executing migrations with the passed names.
     * 
     * @param migrationNamse The names of the migrations.
     * 
     * @throws VPlexApiException When an error occurs pausing the migrations.
     */
    void pauseMigrations(List<String> migrationNames) throws VPlexApiException {
        s_logger.info("Pausing migrations {}", migrationNames);

        // Find the requested migrations. An exception will be thrown if
        // they are not all found.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        List<VPlexMigrationInfo> migrationInfoList = discoveryMgr
                .findMigrations(migrationNames);

        // Verify that the migrations are in a state in which they can be paused.
        StringBuilder migrationArgBuilder = new StringBuilder();
        for (VPlexMigrationInfo migrationInfo : migrationInfoList) {
            String migrationStatus = migrationInfo.getStatus();
            if (VPlexApiConstants.MIGRATION_PAUSED.equals(migrationStatus)) {
                // Skip those already paused.
                continue;
            } else if (!VPlexApiConstants.MIGRATION_INPROGRESS.equals(migrationInfo.getStatus())) {
                // TBD maybe queued as well? Not sure if you can pause a queued migration?
                throw VPlexApiException.exceptions
                        .cantPauseMigrationNotInProgress(migrationInfo.getName());
            }
            if (migrationArgBuilder.length() != 0) {
                migrationArgBuilder.append(",");
            }
            migrationArgBuilder.append(migrationInfo.getPath());
        }

        // If the migration paths argument is empty, then all the requested
        // migrations must already be paused, so just return.
        String migrationPaths = migrationArgBuilder.toString();
        if (migrationPaths.length() == 0) {
            s_logger.info("All requested migrations are already paused");
            return;
        }

        // Pause the migrations.
        URI requestURI = _vplexApiClient.getBaseURI().resolve(
                VPlexApiConstants.URI_PAUSE_MIGRATIONS);
        s_logger.info("Pause migrations URI is {}", requestURI.toString());
        ClientResponse response = null;
        try {
            s_logger.info("Pausing migrations");
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_M, migrationArgBuilder.toString());
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, false);
            s_logger.info("Pause migrations POST data is {}",
                    postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Pause migrations response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Pause migrations is completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.pauseMigrationsFailureStatus(
                            migrationNames, String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully paused migrations {}", migrationNames);
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedPauseMigrations(migrationNames, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Resume the paused migrations with with the passed names.
     * 
     * @param migrationNames The names of the migrations.
     * 
     * @throws VPlexApiException When an error occurs resuming the migrations.
     */
    void resumeMigrations(List<String> migrationNames) throws VPlexApiException {
        s_logger.info("Resuming migrations {}", migrationNames);

        // Find the requested migrations. An exception will be thrown if
        // they are not all found.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        List<VPlexMigrationInfo> migrationInfoList = discoveryMgr
                .findMigrations(migrationNames);

        // Verify that the migrations are in a state in which they can be resumed.
        StringBuilder migrationArgBuilder = new StringBuilder();
        for (VPlexMigrationInfo migrationInfo : migrationInfoList) {
            String migrationStatus = migrationInfo.getStatus();
            if (VPlexApiConstants.MIGRATION_INPROGRESS.equals(migrationStatus)) {
                // Skip those in progress.
                continue;
            } else if (!VPlexApiConstants.MIGRATION_PAUSED.equals(migrationStatus)) {
                throw VPlexApiException.exceptions
                        .cantResumeMigrationNotPaused(migrationInfo.getName());
            }
            if (migrationArgBuilder.length() != 0) {
                migrationArgBuilder.append(",");
            }
            migrationArgBuilder.append(migrationInfo.getPath());
        }

        // If the migration paths argument is empty, then all the requested
        // migrations must already be in progress, so just return.
        String migrationPaths = migrationArgBuilder.toString();
        if (migrationPaths.length() == 0) {
            s_logger.info("All requested migrations are already in progress");
            return;
        }

        // Resume the migrations.
        URI requestURI = _vplexApiClient.getBaseURI().resolve(
                VPlexApiConstants.URI_RESUME_MIGRATIONS);
        s_logger.info("Resume migrations URI is {}", requestURI.toString());
        ClientResponse response = null;
        try {
            s_logger.info("Resuming migrations");
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_M, migrationArgBuilder.toString());
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, false);
            s_logger.info("Resume migrations POST data is {}",
                    postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Resume migrations response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Resume migrations is completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.resumeMigrationsFailureStatus(
                            migrationNames, String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully resume migrations {}", migrationNames);
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedResumeMigrations(migrationNames, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Commits the completed migrations with the passed names and tears down the
     * old devices and unclaims the storage volumes.
     * 
     * @param virtualVolumeName The name of the virtual volume prior to the commit.
     * @param migrationNames The names of the migrations.
     * @param cleanup true to automatically cleanup after commit.
     * @param remove true to automatically remove the migration record.
     * @param rename true to rename the volumes after committing the migration.
     * 
     * @return A list of VPlexMigrationInfo instances for the committed
     *         migrations each of which contains a reference to the
     *         VPlexVirtualVolumeInfo associated with that migration which can
     *         be used to update the virtual volume native id, which can change
     *         as a result of the migration.
     * 
     * @throws VPlexApiException When an error occurs committing the migrations.
     */
    List<VPlexMigrationInfo> commitMigrations(String virtualVolumeName, List<String> migrationNames,
            boolean cleanup, boolean remove, boolean rename) throws VPlexApiException {

        s_logger.info("Committing migrations {}", migrationNames);

        // Find the requested migrations. An exception will be thrown if
        // they are not all found.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        List<VPlexMigrationInfo> migrationInfoList = discoveryMgr
                .findMigrations(migrationNames);

        // Verify that the migrations have completed successfully and can be
        // committed.
        StringBuilder migrationArgBuilder = new StringBuilder();
        for (VPlexMigrationInfo migrationInfo : migrationInfoList) {
            if (!VPlexApiConstants.MIGRATION_COMPLETE.equals(migrationInfo.getStatus())) {
                throw VPlexApiException.exceptions
                        .cantCommitedMigrationNotCompletedSuccessfully(migrationInfo.getName());
            }
            if (migrationArgBuilder.length() != 0) {
                migrationArgBuilder.append(",");
            }
            migrationArgBuilder.append(migrationInfo.getPath());
        }

        // Commit the migrations.
        URI requestURI = _vplexApiClient.getBaseURI().resolve(
                VPlexApiConstants.URI_COMMIT_MIGRATIONS);
        s_logger.info("Commit migrations URI is {}", requestURI.toString());
        ClientResponse response = null;
        try {
            s_logger.info("Committing migrations");
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_M, migrationArgBuilder.toString());
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Commit migrations POST data is {}",
                    postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Commit migrations response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Commit migrations is completing asynchronously");
                    _vplexApiClient.waitForCompletion(response, VPlexApiClient.getMaxMigrationAsyncPollingRetries());
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.commitMigrationsFailureStatus(
                            migrationNames, String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully committed migrations {}", migrationNames);

            // If specified, first try and cleanup the old resources.
            if (cleanup) {
                try {
                    cleanCommittedMigrations(migrationArgBuilder.toString());
                } catch (Exception e) {
                    s_logger.error("Error cleaning migrations after successful commit: {}", e.getMessage(), e);
                }
            }

            // If specified, now try and remove the migration records.
            if (remove) {
                try {
                    removeCommittedOrCanceledMigrations(migrationArgBuilder.toString());
                } catch (Exception e) {
                    s_logger.error("Error removing migration records after successful commit: {}", e.getMessage(), e);
                }
            }

            // Update virtual volume info for virtual volume associated with
            // each committed migration.
            try {
                updateVirtualVolumeInfoAfterCommit(virtualVolumeName, migrationInfoList, rename);
            } catch (Exception e) {
                s_logger.error("Error updating virtual volume after successful commit: {}", e.getMessage(), e);
            }

            return migrationInfoList;
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedCommitMigrations(migrationNames, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Cleans the committed migrations with the passed names tearing down the
     * old devices and unclaiming the storage volumes.
     * 
     * @param migrationNames The names of the migrations.
     * 
     * @throws VPlexApiException When an error occurs cleaning the migrations.
     */
    void cleanMigrations(List<String> migrationNames) throws VPlexApiException {
        s_logger.info("Cleaning migrations {}", migrationNames);

        // Find the requested migrations. An exception will be thrown if
        // they are not all found.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        List<VPlexMigrationInfo> migrationInfoList = discoveryMgr
                .findMigrations(migrationNames);

        // Verify that all migrations are committed.
        StringBuilder migrationArgBuilder = new StringBuilder();
        for (VPlexMigrationInfo migrationInfo : migrationInfoList) {
            if (!VPlexApiConstants.MIGRATION_COMMITTED.equals(migrationInfo.getStatus())) {
                throw VPlexApiException.exceptions
                        .cantCleanMigrationNotCommitted(migrationInfo.getName());
            }
            if (migrationArgBuilder.length() != 0) {
                migrationArgBuilder.append(",");
            }
            migrationArgBuilder.append(migrationInfo.getPath());
        }

        // If the migrations are committed, do the cleanup.
        cleanCommittedMigrations(migrationArgBuilder.toString());
    }

    /**
     * Cancels the uncommitted, executing, paused, or queued migrations with the
     * passed names and tears down the new devices that were created as targets
     * for the migration and unclaims the storage volumes.
     * 
     * @param migrationNames The names of the migrations.
     * @param cleanup true to automatically cleanup after the cancellation.
     * @param remove true to automatically remove the migration record.
     * 
     * @throws VPlexApiException When an error occurs canceling the migrations.
     */
    void cancelMigrations(List<String> migrationNames, boolean cleanup, boolean remove)
            throws VPlexApiException {
        s_logger.info("Canceling migrations {}", migrationNames);

        // Find the requested migrations. An exception will be thrown if
        // they are not all found.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        List<VPlexMigrationInfo> migrationInfoList = discoveryMgr
                .findMigrations(migrationNames);

        // Verify that the migrations are in a state in which they can be
        // canceled.
        StringBuilder migrationArgBuilder = new StringBuilder();
        for (VPlexMigrationInfo migrationInfo : migrationInfoList) {
            String migrationStatus = migrationInfo.getStatus();
            if ((VPlexApiConstants.MIGRATION_CANCELED.equals(migrationStatus))
                    || (VPlexApiConstants.MIGRATION_PART_CANCELED.equals(migrationStatus))) {
                // Skip those already canceled or in the process of being
                // canceled.
                continue;
            } else if ((!VPlexApiConstants.MIGRATION_PAUSED.equals(migrationStatus))
                    && (!VPlexApiConstants.MIGRATION_INPROGRESS.equals(migrationStatus))
                    && (!VPlexApiConstants.MIGRATION_COMPLETE.equals(migrationStatus))
                    && (!VPlexApiConstants.MIGRATION_ERROR.equals(migrationStatus))
                    && (!VPlexApiConstants.MIGRATION_QUEUED.equals(migrationStatus))) {
                throw VPlexApiException.exceptions
                        .cantCancelMigrationInvalidState(migrationInfo.getName());
            }
            if (migrationArgBuilder.length() != 0) {
                migrationArgBuilder.append(",");
            }
            migrationArgBuilder.append(migrationInfo.getPath());
        }

        // If the migration paths argument is empty, then all the requested
        // migrations must already be in progress, so just return.
        String migrationPaths = migrationArgBuilder.toString();
        if (migrationPaths.length() == 0) {
            s_logger.info("All requested migrations are already canceled or " +
                    "in the process of being canceled.");
            return;
        }

        // Cancel the migrations.
        URI requestURI = _vplexApiClient.getBaseURI().resolve(
                VPlexApiConstants.URI_CANCEL_MIGRATIONS);
        s_logger.info("Cancel migrations URI is {}", requestURI.toString());
        ClientResponse response = null;
        try {
            s_logger.info("Canceling migrations");
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_M, migrationArgBuilder.toString());
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Cancel migrations POST data is {}",
                    postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Cancel migrations response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Cancel migrations is completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.cancelMigrationsFailureStatus(
                            migrationNames, String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully canceled migrations {}", migrationNames);

            // If specified, cleanup the target device/extents depending on
            // whether this was a device or extent migration.
            if (cleanup) {
                VPlexApiVirtualVolumeManager virtualVolumeMgr = _vplexApiClient
                        .getVirtualVolumeManager();
                for (VPlexMigrationInfo migrationInfo : migrationInfoList) {
                    try {
                        String targetName = migrationInfo.getTarget();
                        if (migrationInfo.getIsDeviceMigration()) {
                            virtualVolumeMgr.deleteLocalDevice(targetName);
                        } else {
                            virtualVolumeMgr.deleteExtent(targetName);
                        }
                    } catch (VPlexApiException vae) {
                        s_logger.error(
                                "Error cleaning target for canceled migration {}:{}",
                                migrationInfo.getName(), vae.getMessage());
                    }
                }
            }

            // If specified, try and remove the migration records.
            if (remove) {
                try {
                    removeCommittedOrCanceledMigrations(migrationArgBuilder.toString());
                } catch (VPlexApiException vae) {
                    s_logger.error(
                            "Error removing migration records after successful cancel: {}",
                            vae.getMessage(), vae);
                }
            }
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedCancelMigrations(migrationNames, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Removes the records for the committed or canceled migrations with the passed names.
     * 
     * @param migrationNames The names of the migrations.
     * 
     * @throws VPlexApiException When an error occurs removing the migration
     *             records.
     */
    void removeMigrations(List<String> migrationNames) throws VPlexApiException {
        s_logger.info("Removing records for migrations {}", migrationNames);

        // Find the requested migrations. An exception will be thrown if
        // they are not all found.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        List<VPlexMigrationInfo> migrationInfoList = null;
        try {
            migrationInfoList = discoveryMgr.findMigrations(migrationNames);
        } catch (VPlexApiException vae) {
            // migrations might have deleted from VPLEX, then we just need to delete them from ViPR DB.
            s_logger.info("No migration found in the VPLEX", vae);
            return;
        }

        // Verify that the migrations are in a state in which they can be removed.
        StringBuilder migrationArgBuilder = new StringBuilder();
        for (VPlexMigrationInfo migrationInfo : migrationInfoList) {
            String migrationStatus = migrationInfo.getStatus();
            if ((!VPlexApiConstants.MIGRATION_COMMITTED.equals(migrationStatus)) &&
                    (!VPlexApiConstants.MIGRATION_CANCELED.equals(migrationStatus)) &&
                    (!VPlexApiConstants.MIGRATION_ERROR.equals(migrationStatus))) {
                throw VPlexApiException.exceptions
                        .cantRemoveMigrationInvalidState(migrationInfo.getName());
            }
            if (migrationArgBuilder.length() != 0) {
                migrationArgBuilder.append(",");
            }
            migrationArgBuilder.append(migrationInfo.getPath());
        }

        // If the migrations are committed or canceled, do the removal.
        removeCommittedOrCanceledMigrations(migrationArgBuilder.toString());
    }

    /**
     * Migrate one or both extents used by the passed virtual volume.
     * 
     * @param migrationName The name for the migration.
     * @param virtualVolumeInfo A reference to the virtual volume info.
     * @param storageVolumeInfoMap A map specifying the volume(s) to which the
     *            data is to be migrated.
     * @param startNow true to start the migration now, else the migration is
     *            created in a paused state.
     * @param transferSize migration transfer size
     * 
     * @return A list of migration infos.
     * 
     * @throws VPlexApiException When an error occurs migrating the virtual
     *             volume.
     */
    private List<VPlexMigrationInfo> migrateDistributedVirtualVolume(
            String migrationName, VPlexVirtualVolumeInfo virtualVolumeInfo,
            Map<VolumeInfo, VPlexStorageVolumeInfo> storageVolumeInfoMap, boolean startNow,
            String transferSize) throws VPlexApiException {

        // Get the discovery manager.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();

        // The virtual volume is built on a distributed device. We
        // can find the component structure of the distributed
        // device to find the extents.
        List<VPlexExtentInfo> srcExtentInfoList = new ArrayList<VPlexExtentInfo>();
        String distributedDeviceName = virtualVolumeInfo.getSupportingDevice();
        VPlexDistributedDeviceInfo ddInfo = discoveryMgr.findDistributedDevice(distributedDeviceName);
        discoveryMgr.setSupportingComponentsForDistributedDevice(ddInfo);
        List<VPlexDeviceInfo> localDeviceInfoList = ddInfo.getLocalDeviceInfo();
        for (VPlexDeviceInfo localDeviceInfo : localDeviceInfoList) {
            String localDeviceName = localDeviceInfo.getName();
            s_logger.info("Local device: {}", localDeviceName);
            List<VPlexExtentInfo> localDeviceExtentInfoList = localDeviceInfo.getExtentInfo();
            int extentCount = localDeviceExtentInfoList.size();
            if (extentCount != 1) {
                // If the extent count is not 1, then it is likely that a
                // distributed volume is being migrated. When a distributed
                // volume is migrated two migration instances are created and
                // started in parallel by calling this function two separate
                // times. When the VPLEX starts a migration, the component
                // structure of the local device whose extent is being migrated
                // is temporarily modified by the VPLEX such that the local device
                // is actually composed of another local device (rather than a
                // single extent) which itself is composed of two extents
                // representing the src and target extents for the migration.
                // We can just ignore this local device as the migration has
                // already been created and started for extent associated with
                // that local device.
                s_logger.info("Extent count for local device {} is {}", localDeviceName, extentCount);
                continue;
            }
            srcExtentInfoList.add(discoveryMgr.findExtent(localDeviceExtentInfoList.get(0).getName()));
        }

        // Now we need to build extent(s) for the storage volume(s) to
        // which the data is to be migrated. This is done in the same way as
        // creating a virtual volume. First claim the storage volume(s).
        // Note there could only be one volume if only one side of the
        // distributed virtual volume is being migrated.
        VPlexApiVirtualVolumeManager virtualVolumeMgr = _vplexApiClient
                .getVirtualVolumeManager();
        virtualVolumeMgr.claimStorageVolumes(storageVolumeInfoMap, false);
        s_logger.info("Claimed storage volumes");

        // Try and build up the VPLEX extents from the claimed storage
        // volumes to which the data will be migrated. If we get an error,
        // clean up the VPLEX artifacts and unclaim the storage volumes.
        List<VPlexMigrationInfo> migrationInfoList = new ArrayList<VPlexMigrationInfo>();
        try {
            // Create extent(s).
            List<VPlexStorageVolumeInfo> storageVolumeInfoList = new ArrayList<VPlexStorageVolumeInfo>(
                    storageVolumeInfoMap.values());
            virtualVolumeMgr.createExtents(storageVolumeInfoList);
            s_logger.info("Created extents on storage volumes");

            // Find the extent(s) just created.
            List<VPlexExtentInfo> tgtExtentInfoList = discoveryMgr
                    .findExtents(storageVolumeInfoList);
            s_logger.info("Found the target extents");

            // Map the source extent(s) to the target extent(s)
            // in the same cluster.
            Map<VPlexExtentInfo, VPlexExtentInfo> extentMigrationMap = new HashMap<VPlexExtentInfo, VPlexExtentInfo>();
            for (VPlexExtentInfo tgtExtentInfo : tgtExtentInfoList) {
                String clusterId = tgtExtentInfo.getClusterId();
                for (VPlexExtentInfo srcExtentInfo : srcExtentInfoList) {
                    if (clusterId.equals(srcExtentInfo.getClusterId())) {
                        extentMigrationMap.put(tgtExtentInfo, srcExtentInfo);
                        break;
                    }
                }
            }

            // Migrate each extent for the virtual volume from the
            // source to the target.
            int migrationCount = 1;
            Iterator<Entry<VPlexExtentInfo, VPlexExtentInfo>> tgtExtentIter = extentMigrationMap.entrySet().iterator();
            while (tgtExtentIter.hasNext()) {
                Entry<VPlexExtentInfo, VPlexExtentInfo> entry = tgtExtentIter.next();
                VPlexExtentInfo tgtExtentInfo = entry.getKey();
                VPlexExtentInfo srcExtentInfo = entry.getValue();
                StringBuilder migrationNameBuilder = new StringBuilder(migrationName);
                if (extentMigrationMap.size() > 1) {
                    migrationNameBuilder.append("_");
                    migrationNameBuilder.append(String.valueOf(migrationCount++));
                }
                VPlexMigrationInfo migrationInfo = migrateResource(
                        migrationNameBuilder.toString(), srcExtentInfo, tgtExtentInfo, false,
                        startNow, transferSize);
                migrationInfo.setVirtualVolumeInfo(virtualVolumeInfo);
                migrationInfoList.add(migrationInfo);
            }

            return migrationInfoList;
        } catch (Exception e) {
            // An error occurred. Clean up the VPLEX artifacts created for
            // the migration and unclaim the storage volumes.
            s_logger.info("Exception occurred migrating distributed volume, attempting to cleanup VPLEX artifacts");
            try {
                // Cancel any migrations that may have been successfully created.
                if (!migrationInfoList.isEmpty()) {
                    for (VPlexMigrationInfo migrationInfo : migrationInfoList) {
                        cancelMigrations(Collections.singletonList(migrationInfo.getName()), true, true);
                    }
                }

                // This will look for any artifacts, starting with a virtual
                // volume, that use the passed native volume info and destroy
                // them and then unclaim the volume.
                List<VolumeInfo> nativeVolumeInfoList = new ArrayList<VolumeInfo>();
                nativeVolumeInfoList.addAll(storageVolumeInfoMap.keySet());
                virtualVolumeMgr.deleteVirtualVolume(nativeVolumeInfoList);
            } catch (Exception ex) {
                s_logger.error("Failed attempting to cleanup VPLEX after failed attempt " +
                        "to migrate distributed virtual volume {}", virtualVolumeInfo.getPath(), ex);
            }
            throw e;
        }
    }

    /**
     * Migrate the local device used by the passed virtual volume.
     * 
     * @param migrationName The name for the migration.
     * @param virtualVolumeInfo A reference to the virtual volume info.
     * @param storageVolumeInfoMap A map specifying the volume(s) to which the
     *            data is to be migrated.
     * @param startNow true to start the migration now, else the migration is
     *            created in a paused state.
     * @param isRemote true if the migration is across clusters, else false.
     * @param useDeviceMigration true if device migration is required.
     * @param transferSize migration transfer size
     * @return A list of migration infos.
     * 
     * @throws VPlexApiException When an error occurs migrating the virtual
     *             volume.
     */
    private VPlexMigrationInfo migrateLocalVirtualVolume(String migrationName,
            VPlexVirtualVolumeInfo virtualVolumeInfo,
            Map<VolumeInfo, VPlexStorageVolumeInfo> storageVolumeInfoMap, boolean startNow,
            boolean isRemote, boolean useDeviceMigration, String transferSize) throws VPlexApiException {
        // For remote migrations of local virtual volumes we must use
        // device migration. Otherwise, we can use either. We choose to
        // use extent migration when device migration is not specifically
        // requested simply because the length of the migration name is
        // much less restrictive and allows Bourne to uniquely identify
        // the source and target in the migration name.
        if (isRemote || useDeviceMigration) {
            return migrateLocalVirtualVolumeDevice(migrationName, virtualVolumeInfo,
                    storageVolumeInfoMap, startNow, transferSize);
        } else {
            return migrateLocalVirtualVolumeExtent(migrationName, virtualVolumeInfo,
                    storageVolumeInfoMap, startNow, transferSize);
        }
    }

    /**
     * Migrate the local device used by the passed virtual volume.
     * 
     * @param migrationName The name for the migration.
     * @param virtualVolumeInfo A reference to the virtual volume info.
     * @param storageVolumeInfoMap A map specifying the volume(s) to which the
     *            data is to be migrated.
     * @param startNow true to start the migration now, else the migration is
     *            created in a paused state.
     * @param transferSize migration transfer size
     * 
     * @return A list of migration infos.
     * 
     * @throws VPlexApiException When an error occurs migrating the virtual
     *             volume.
     */
    private VPlexMigrationInfo migrateLocalVirtualVolumeDevice(String migrationName,
            VPlexVirtualVolumeInfo virtualVolumeInfo, Map<VolumeInfo, VPlexStorageVolumeInfo> storageVolumeInfoMap, boolean startNow,
            String transferSize)
                    throws VPlexApiException {

        // Find the local device.
        String localDeviceName = virtualVolumeInfo.getSupportingDevice();
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        VPlexDeviceInfo srcDeviceInfo = discoveryMgr.findLocalDevice(localDeviceName);

        // Now we need to build a local device for the storage volume to
        // which the data is to be migrated. This is done in the same way as
        // creating a virtual volume. First claim the storage volume.
        VPlexApiVirtualVolumeManager virtualVolumeMgr = _vplexApiClient
                .getVirtualVolumeManager();
        virtualVolumeMgr.claimStorageVolumes(storageVolumeInfoMap, false);
        s_logger.info("Claimed storage volume");

        // Try and build up the VPLEX local device from the claimed storage
        // volume and start a migration from the local device of the passed
        // virtual volume to this new local device. If we get an error,
        // clean up the VPLEX artifacts and unclaim the storage volume.
        try {
            // Create the extent.
            List<VPlexStorageVolumeInfo> storageVolumeInfoList = new ArrayList<VPlexStorageVolumeInfo>(
                    storageVolumeInfoMap.values());
            virtualVolumeMgr.createExtents(storageVolumeInfoList);
            s_logger.info("Created extent on storage volume");

            // Find the extent just created and create local device on
            // the extent.
            List<VPlexExtentInfo> extentInfoList = discoveryMgr
                    .findExtents(storageVolumeInfoList);
            virtualVolumeMgr.createLocalDevices(extentInfoList);
            s_logger.info("Created local device on extent");

            // Find the local device just created.
            VPlexDeviceInfo tgtDeviceInfo = discoveryMgr.findLocalDevices(extentInfoList).get(0);

            // Migrate the source local device to the target local device.
            VPlexMigrationInfo migrationInfo = migrateResource(
                    migrationName, srcDeviceInfo, tgtDeviceInfo, true, startNow, transferSize);
            migrationInfo.setVirtualVolumeInfo(virtualVolumeInfo);

            return migrationInfo;
        } catch (Exception e) {
            // An error occurred. Clean up the VPLEX artifacts created for
            // the migration and unclaim the storage volumes.
            s_logger.info("Exception occurred migrating local volume device, attempting to cleanup VPLEX artifacts");
            try {
                // This will look for any artifacts, starting with a virtual
                // volume, that use the passed native volume info and destroy
                // them and then unclaim the volume.
                List<VolumeInfo> nativeVolumeInfoList = new ArrayList<VolumeInfo>();
                nativeVolumeInfoList.addAll(storageVolumeInfoMap.keySet());
                virtualVolumeMgr.deleteVirtualVolume(nativeVolumeInfoList);
            } catch (Exception ex) {
                s_logger.error("Failed attempting to cleanup VPLEX after failed attempt " +
                        "to migrate local virtual volume {}", virtualVolumeInfo.getPath(), ex);
            }
            throw e;
        }
    }

    /**
     * Migrate the local device used by the passed virtual volume.
     * 
     * @param migrationName The name for the migration.
     * @param virtualVolumeInfo A reference to the virtual volume info.
     * @param storageVolumeInfoMap A map specifying the volume(s) to which the
     *            data is to be migrated.
     * @param startNow true to start the migration now, else the migration is
     *            created in a paused state.
     * @param transferSize migration transfer size
     * 
     * @return A list of migration infos.
     * 
     * @throws VPlexApiException When an error occurs migrating the virtual
     *             volume.
     */
    private VPlexMigrationInfo migrateLocalVirtualVolumeExtent(String migrationName,
            VPlexVirtualVolumeInfo virtualVolumeInfo,
            Map<VolumeInfo, VPlexStorageVolumeInfo> storageVolumeInfoMap, boolean startNow,
            String transferSize) throws VPlexApiException {

        // Get the extent name from the local device name and find the extent.
        // This is the source extent for the migration.
        String localDeviceName = virtualVolumeInfo.getSupportingDevice();
        s_logger.info("Finding local device with name {}", localDeviceName);
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        VPlexDeviceInfo deviceInfo = discoveryMgr.findLocalDevice(localDeviceName);
        if (null == deviceInfo) {
            throw VPlexApiException.exceptions.cantFindLocalDevice(localDeviceName);
        }
        discoveryMgr.setSupportingComponentsForLocalDevice(deviceInfo);
        List<VPlexExtentInfo> extentInfoList = deviceInfo.getExtentInfo();
        if (null == extentInfoList || extentInfoList.isEmpty()) {
            throw VPlexApiException.exceptions.cantFindExtentForLocalDevice(localDeviceName);
        }
        String extentName = extentInfoList.get(0).getName();
        s_logger.info("Finding extent with name {}", extentName);
        VPlexExtentInfo srcExtentInfo = discoveryMgr.findExtent(extentName);
        s_logger.info("Found source extent");

        // Now we need to build the target extent for the storage volume to
        // which the data is to be migrated. This is done in the same way as
        // creating a virtual volume. First claim the storage volume.
        VPlexApiVirtualVolumeManager virtualVolumeMgr = _vplexApiClient
                .getVirtualVolumeManager();
        virtualVolumeMgr.claimStorageVolumes(storageVolumeInfoMap, false);
        s_logger.info("Claimed storage volume");

        // Try and build up the VPLEX extent from the claimed storage
        // volume and start a migration from the extent of the passed
        // virtual volume to this new extent. If we get an error,
        // clean up the VPLEX artifacts and unclaim the storage volume.
        try {
            // Now create the extent.
            List<VPlexStorageVolumeInfo> storageVolumeInfoList = new ArrayList<VPlexStorageVolumeInfo>(
                    storageVolumeInfoMap.values());
            virtualVolumeMgr.createExtents(storageVolumeInfoList);
            s_logger.info("Created extent on storage volume");

            // Find the extent just created.
            VPlexExtentInfo tgtExtentInfo = discoveryMgr.findExtents(storageVolumeInfoList)
                    .get(0);
            s_logger.info("Found target extent");

            // Migrate the source local device to the target local device.
            VPlexMigrationInfo migrationInfo = migrateResource(
                    migrationName, srcExtentInfo, tgtExtentInfo, false, startNow, transferSize);
            migrationInfo.setVirtualVolumeInfo(virtualVolumeInfo);

            return migrationInfo;
        } catch (Exception e) {
            // An error occurred. Clean up the VPLEX artifacts created for
            // the migration and unclaim the storage volumes.
            s_logger.info("Exception occurred migrating local volume extent, attempting to cleanup VPLEX artifacts");
            try {
                // This will look for any artifacts, starting with a virtual
                // volume, that use the passed native volume info and destroy
                // them and then unclaim the volume.
                List<VolumeInfo> nativeVolumeInfoList = new ArrayList<VolumeInfo>();
                nativeVolumeInfoList.addAll(storageVolumeInfoMap.keySet());
                virtualVolumeMgr.deleteVirtualVolume(nativeVolumeInfoList);
            } catch (Exception ex) {
                s_logger.error("Failed attempting to cleanup VPLEX after failed attempt " +
                        "to migrate local virtual volume by extent {}", virtualVolumeInfo.getPath(), ex);
            }
            throw e;
        }
    }

    /**
     * Creates a migration to migrate the data on the passed source
     * device/extent to the passed target device/extent. The migration is
     * created in the paused state if the startNow flag is false.
     * 
     * @param migrationName The name for the migration.
     * @param sourceInfo A reference to the source device/extent info.
     * @param targetInfo A reference to the target device/extent info.
     * @param isDeviceMigration true when migrating a local device, false when
     *            migrating an extent.
     * @param startNow true to start the migration, false to create the
     *            migration in the paused state.
     * @param transferSize migration transfer size
     * 
     * @return A reference to the VPlex migration info.
     * 
     * @throws VPlexApiException If an error occurs creating or starting the
     *             migration.
     */
    private VPlexMigrationInfo migrateResource(String migrationName,
            VPlexResourceInfo sourceInfo, VPlexResourceInfo targetInfo,
            boolean isDeviceMigration, boolean startNow, String transferSize) throws VPlexApiException {
        URI requestURI = _vplexApiClient.getBaseURI().resolve(
                VPlexApiConstants.URI_START_MIGRATION);
        s_logger.info("Start migration URI is {}", requestURI.toString());
        ClientResponse response = null;
        try {
            s_logger.info("Start migration {} from {} to {}", new Object[] {
                    migrationName, sourceInfo.getName(), targetInfo.getName() });
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_N, migrationName);
            argsMap.put(VPlexApiConstants.ARG_DASH_F, sourceInfo.getPath());
            argsMap.put(VPlexApiConstants.ARG_DASH_T, targetInfo.getPath());
            if (transferSize != null && !transferSize.isEmpty()) {
                argsMap.put(VPlexApiConstants.ARG_TRANSFER_SIZE, transferSize);
            }
            if (!startNow) {
                argsMap.put(VPlexApiConstants.ARG_PAUSED, "");
            }
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Start migration POST data is {}", postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Start migration response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Start migration is completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.migrationFailureStatus(
                            sourceInfo.getName(), targetInfo.getName(),
                            String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully started migration {}", migrationName);

            // Find and return the migration.
            URI migrationPath = (isDeviceMigration ? VPlexApiConstants.URI_DEVICE_MIGRATIONS
                    : VPlexApiConstants.URI_EXTENT_MIGRATIONS);
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
            VPlexMigrationInfo migrationInfo = discoveryMgr.findMigration(migrationName,
                    migrationPath, true);
            migrationInfo.setIsDeviceMigration(isDeviceMigration);
            return migrationInfo;
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedStartMigration(migrationName, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Cleans the migrations identified by the passed concatenated string of
     * migration context paths after the migrations have been successfully
     * committed.
     * 
     * @param migrationPaths Comma separated migration context paths.
     * 
     * @throws VPlexApiException When an error occurs cleaning the migrations.
     */
    private void cleanCommittedMigrations(String migrationPaths) throws VPlexApiException {
        // Clean the migrations.
        URI requestURI = _vplexApiClient.getBaseURI().resolve(
                VPlexApiConstants.URI_CLEAN_MIGRATIONS);
        s_logger.info("Clean migrations URI is {}", requestURI.toString());
        ClientResponse response = null;
        try {
            s_logger.info("Cleaning committed migrations");
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_M, migrationPaths);
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Clean migrations POST data is {}",
                    postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Clean migrations response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Clean migrations is completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.cleanMigrationsFailureStatus(
                            migrationPaths, String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully cleaned migrations {}", migrationPaths);
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedCleanMigrations(migrationPaths, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Removes the records for the canceled and/or committed migrations
     * identified by the passed concatenated string of migration context paths
     * 
     * @param migrationPaths Comma separated migration context paths.
     * 
     * @throws VPlexApiException When an error occurs removing the migration
     *             records.
     */
    private void removeCommittedOrCanceledMigrations(String migrationPaths)
            throws VPlexApiException {
        // Remove the migrations.
        URI requestURI = _vplexApiClient.getBaseURI().resolve(
                VPlexApiConstants.URI_REMOVE_MIGRATIONS);
        s_logger.info("Remove migrations URI is {}", requestURI.toString());
        ClientResponse response = null;
        try {
            s_logger.info("Removing migrations");
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_M, migrationPaths);
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Remove migrations POST data is {}",
                    postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Remove migrations response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Remove migrations is completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.removeMigrationsFailureStatus(
                            migrationPaths, String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully removed migrations {}", migrationPaths);
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedRemoveMigrations(migrationPaths, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Ensures that the name of the virtual volume associated with the committed
     * migration is updated to reflect the target of the migration. When
     * created, the name of the virtual volume will include the migration
     * source, and we want to be sure the name continues to reflect the backend
     * storage volumes used by the virtual volume.
     * 
     * @param virtualVolumeName The name of the virtual volume prior to migration.
     * @param migrationInfoList The list of committed migrations.
     * @param rename true to rename the volumes.
     * 
     * @throws VPlexApiException When an error occurs making the updates.
     */
    private void updateVirtualVolumeInfoAfterCommit(String virtualVolumeName,
            List<VPlexMigrationInfo> migrationInfoList, boolean rename)
                    throws VPlexApiException {

        // Get the cluster information.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        List<VPlexClusterInfo> clusterInfoList = discoveryMgr.getClusterInfoLite();

        // Process each migration. Note that there will only be 1.
        for (VPlexMigrationInfo migrationInfo : migrationInfoList) {
            if (migrationInfo.getIsDeviceMigration()) {
                updateVolumeInfoAfterCommitDeviceMigration(virtualVolumeName, migrationInfo,
                        clusterInfoList, rename);
            } else {
                updateVolumeInfoAfterCommitExtentMigration(virtualVolumeName, migrationInfo,
                        clusterInfoList, rename);
            }
        }
        
        s_logger.info("Done updating volume info after commit");
    }
    
    /**
     * Updates the virtual volume information after a device migration is successfully
     * committed. Note that the volume will be a local virtual volume as device migration
     * is not supported for distributed volumes.
     * 
     * @param originalVolumeName The name of the volume prior to the commit.
     * @param migrationInfo The migration information.
     * @param clusterInfoList The cluster info
     * @param rename Whether or not the volume should be renamed after commit.
     */
    private void updateVolumeInfoAfterCommitDeviceMigration(String originalVolumeName, 
            VPlexMigrationInfo migrationInfo, List<VPlexClusterInfo> clusterInfoList,
            boolean rename) {
        // Find the volume after the migration.
        VPlexVirtualVolumeInfo virtualVolumeInfo = findVirtualVolumeAfterDeviceMigration(originalVolumeName, 
                migrationInfo, clusterInfoList);
        if (virtualVolumeInfo == null) {
            s_logger.warn("Could not find virtual volume {} after device migration", originalVolumeName);
            return;
        }

        // Check to see if the volume is to be renamed after migration to maintain
        // the "<local-device-name>_vol" naming convention. Since it is now using a 
        // new local device, its current name will not match this convention.
        if ((rename) && (volumeHasDefaultNamingConvention(originalVolumeName, false))) {
            // In some cases for a device migration, the virtual volume name is
            // automatically updated by VPLEX after the commit and there is nothing 
            // to do. In other cases it remains unchanged, and in others it simply ends
            // up as the target volume name without the "_vol" suffix. We have seen
            // all these behaviors on the VPLEX. So, we check if the volume name needs
            // to be updated and if so, we update the name.
            String migrationTgtName = migrationInfo.getTarget();
            if ((originalVolumeName.equals(virtualVolumeInfo.getName())) || 
                    (migrationTgtName.equals(virtualVolumeInfo.getName()))) {
                // If we are here then VPLEX didn't rename the volume, so make a call to 
                // rename the volume. Build the name for volume so as to rename the vplex 
                // volume that is created with the same name as the device name to follow 
                // the name pattern _vol as the suffix for the vplex volumes.
                String volumeNameAfterMigration = virtualVolumeInfo.getName();
                String volumePathAfterMigration = virtualVolumeInfo.getPath();
                StringBuilder volumeNameBuilder = new StringBuilder();
                volumeNameBuilder.append(migrationTgtName);
                // Only append the suffix if the volume name doesn't already end with it.
                // This will prevent issues where we could be appending the suffix ("_vol")
                // multiple times on the volume name.
                if (!volumeNameBuilder.toString().endsWith(VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX)) {
                    volumeNameBuilder.append(VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX);
                }
    
                // Rename the VPLEX volume name and update the migration information.
                virtualVolumeInfo = _vplexApiClient.renameResource(virtualVolumeInfo, volumeNameBuilder.toString());
                s_logger.info(String.format("Renamed virtual volume after migration from name: %s path: %s to %s",
                        volumeNameAfterMigration, volumePathAfterMigration, volumeNameBuilder.toString()));
            }
        } else if (!originalVolumeName.equals(virtualVolumeInfo.getName())) {
            // We are not to rename the volume, but it could be that VPLEX renamed it 
            // automatically. So we will rename it back to its original name if necessary
            // and update the migration info.
            String newName = virtualVolumeInfo.getName();
            virtualVolumeInfo = _vplexApiClient.renameResource(virtualVolumeInfo, originalVolumeName);
            s_logger.info("Renamed virtual volume {} back to its orginal name {}", newName, originalVolumeName);
        }

        // virtual volume info should be set so that the caller can tell
        // if the thin-capable flag changed and needs further 
        migrationInfo.setVirtualVolumeInfo(virtualVolumeInfo);
    }

    /**
     * Finds the virtual volume after a device migration.
     * 
     * @param originalVolumeName The name of the virtual volume prior migration.
     * @param migrationInfo The migration information.
     * @param clusterInfoList The VPLEX cluster information.
     * 
     * @return A VPlexVirtualVolumeInfo representing the volume, or null when not found.
     */
    private VPlexVirtualVolumeInfo findVirtualVolumeAfterDeviceMigration(String originalVolumeName, VPlexMigrationInfo migrationInfo,
            List<VPlexClusterInfo> clusterInfoList) {
        VPlexVirtualVolumeInfo virtualVolumeInfo = null;
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        // Initially we found that during a device migration, VPLEX automatically updated the
        // volume name to reflect the new local device name, based on the new backend storage
        // volume. As such, there was no need to update the name after migration. However, it
        // then seemed that some versions of VPLEX did not do this and the volume simply had
        // the same name as the new local device without the "_vol" suffix. Then we found cases
        // where the name is not changed at all and maintain the original volume name. So to
        // find the volume after a device migration, we need to handle these possible conditions.
        for (VPlexClusterInfo clusterInfo : clusterInfoList) {
            String clusterName = clusterInfo.getName();
            virtualVolumeInfo = discoveryMgr.findVirtualVolume(clusterName, originalVolumeName, false);
            // First look for it by it original name.
            if (virtualVolumeInfo != null) {
                s_logger.info("Found virtual volume after device migration with name {} on cluster {}",
                        originalVolumeName, clusterName);
                break;
            } else {
                // Now try to find the volume based on the fact that the name was not
                // updated to append the "_vol" suffix. This means the volume would have
                // the same name as the new local device on which the volume is now built
                // which is captured by the migration target. The name would have the
                // format "device_<sysid>-<devid>".
                String virtualVolumeName = migrationInfo.getTarget();
                virtualVolumeInfo = discoveryMgr.findVirtualVolume(clusterName, virtualVolumeName, false);
                if (virtualVolumeInfo != null) {
                    s_logger.info("Found virtual volume after device migration with name {} on cluster {}",
                            virtualVolumeName, clusterName);
                    break;
                } else {
                    // If not found, then VPLEX must have renamed the volume automatically to
                    // append the "_vol" suffix to the volume, which it also does when you create
                    // a new virtual volume.
                    virtualVolumeName += VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX;
                    virtualVolumeInfo = discoveryMgr.findVirtualVolume(clusterInfo.getName(), virtualVolumeName, false);
                    if (virtualVolumeInfo != null) {
                        s_logger.info("Found virtual volume after device migration with name {} on cluster {}",
                                virtualVolumeName, clusterName);
                        break;
                    }
                }
            }
        }
        return virtualVolumeInfo;
    }
    
    /**
     * Updates the virtual volume information after an extent migration is successfully
     * committed. Note that the volume can be local or distributed for an extent migration.
     * 
     * @param originalVolumeName The name of the volume prior to the commit.
     * @param migrationInfo The migration information.
     * @param clusterInfoList The cluster info
     * @param rename Whether or not the volume should be renamed after commit.
     */
    private void updateVolumeInfoAfterCommitExtentMigration(String originalVolumeName, 
            VPlexMigrationInfo migrationInfo, List<VPlexClusterInfo> clusterInfoList,
            boolean rename) {
        s_logger.info("Updating volume information after committing extent migration for volume {}", originalVolumeName);
        
        // Find the virtual volume.
        VPlexVirtualVolumeInfo virtualVolumeInfo = null;
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        for (VPlexClusterInfo clusterInfo : clusterInfoList) {
            virtualVolumeInfo = discoveryMgr.findVirtualVolume(clusterInfo.getName(), originalVolumeName, false);
            if (virtualVolumeInfo != null) {
                break;
            }
        }
        if (virtualVolumeInfo == null) {
            s_logger.warn("Could not find virtual volume {}", originalVolumeName);
            return;
        }

        // Check to see if we should rename the volume and/or supporting devices to
        // maintain the the ViPR default naming convention. We don't want to rename if
        // the passed rename flag is false or the volume does not conform to the 
        // default ViPR naming convention for example, ingested volumes.
        if (rename) {
            boolean isDistributed = VPlexVirtualVolumeInfo.Locality.distributed.name().equals(virtualVolumeInfo.getLocality());
            if (volumeHasDefaultNamingConvention(originalVolumeName, isDistributed)) {
                renameVolumeAfterExtentMigration(virtualVolumeInfo, migrationInfo);
                renameSupportingDevicesAfterExtentMigration(virtualVolumeInfo, migrationInfo);
            } else if (supportingDeviceHasDefaultNamingConvention(virtualVolumeInfo.getSupportingDevice(), isDistributed)) {
                renameSupportingDevicesAfterExtentMigration(virtualVolumeInfo, migrationInfo);
            }
        }

        // virtual volume info should be set so that the caller can tell
        // if the thin-capable flag changed and needs further 
        migrationInfo.setVirtualVolumeInfo(virtualVolumeInfo);
    }

    /**
     * Determines if the volume has default naming conventions. A volume with default naming
     * convention has a format like below:
     * 
     * local volume: device_<claimed_storage_volume_name>_vol.
     * distribute volume: dd_<claimed_storage_volume_name1>_<claimed_storage_volume_name2>_vol.
     * 
     * We should be able to parse the passed volume name to get the claimed storage
     * volume names and then call the utility method to verify the name. If there is
     * any kind of parsing error, then we know the volume does not have the default 
     * naming convention. This could be a volume which was created with custom naming enabled
     * or it could be a volume that was ingested.
     * 
     * @param volumeName The volume name.
     * @param isDistributed true if the volume is distributed, false for local.
     *  
     * @return true if the volume has the default naming convention, else false.
     */
    private boolean volumeHasDefaultNamingConvention(String volumeName, boolean isDistributed) {
        try {
            int endIndex = volumeName.length() - VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX.length();
            String supportingDeviceName = volumeName.substring(0, endIndex);
            List<String> claimedVolumeNames = new ArrayList<String>();
            if (!isDistributed) {
                int startIndex = VPlexApiConstants.DEVICE_PREFIX.length();
                claimedVolumeNames.add(volumeName.substring(startIndex, endIndex));
            } else {
                String distVolPrefix = VPlexApiConstants.DIST_DEVICE_PREFIX + VPlexApiConstants.DIST_DEVICE_NAME_DELIM;
                int startIndex = distVolPrefix.length();
                String supportingDeviceNameNoPrefix = supportingDeviceName.substring(startIndex);
                claimedVolumeNames.addAll(Arrays.asList(supportingDeviceNameNoPrefix.split(VPlexApiConstants.DIST_DEVICE_NAME_DELIM)));
            }
            
            return VPlexApiUtils.volumeHasDefaultNamingConvention(volumeName, supportingDeviceName, isDistributed, claimedVolumeNames);
        } catch (Exception e) {
            // Exceptions will occur while indexing the passed volume name if the volume
            // does not conform to the default naming convention.
            s_logger.info("Volume {} does not conform to default naming convention: {}", volumeName, e.getMessage());
            return false;
        }
    }
    
    /**
     * Determines if the passed device has default naming conventions. A device with 
     * default naming convention has a format like below:
     * 
     * local device: device_<claimed_storage_volume_name>.
     * distribute device: dd_<claimed_storage_volume_name1>_<claimed_storage_volume_name2>.
     * 
     * We should be able to parse the passed device name to get the claimed storage
     * volume names and then call the utility method to verify the name. If there is
     * any kind of parsing error, then we know the device does not have the default 
     * naming convention.
     * 
     * We need to check the device in cases of custom naming. For custom naming the
     * the virtual volume may not have the default naming convention, but since we
     * we always build the underlying artifacts using the default conventions, the
     * supporting device for a volume may have the default convention. If the supporting
     * device does have the default convention, we want to update it after successful
     * commit of a migration.
     * 
     * @param deviceName The deviceName
     * @param isDistributed true if the device is distributed, false for local.
     *  
     * @return true if the device has the default naming convention, else false.
     */
    private boolean supportingDeviceHasDefaultNamingConvention(String deviceName, boolean isDistributed) {
        try {
            List<String> claimedVolumeNames = new ArrayList<String>();
            if (!isDistributed) {
                int startIndex = VPlexApiConstants.DEVICE_PREFIX.length();
                claimedVolumeNames.add(deviceName.substring(startIndex));
            } else {
                String distVolPrefix = VPlexApiConstants.DIST_DEVICE_PREFIX + VPlexApiConstants.DIST_DEVICE_NAME_DELIM;
                int startIndex = distVolPrefix.length();
                String supportingDeviceNameNoPrefix = deviceName.substring(startIndex);
                claimedVolumeNames.addAll(Arrays.asList(supportingDeviceNameNoPrefix.split(VPlexApiConstants.DIST_DEVICE_NAME_DELIM)));
            }
            
            return VPlexApiUtils.deviceHasDefaultNamingConvention(deviceName, isDistributed, claimedVolumeNames);
        } catch (Exception e) {
            // Exceptions will occur while indexing the passed volume name if the volume
            // does not conform to the default naming convention.
            s_logger.info("Device {} does not conform to default naming convention: {}", deviceName, e.getMessage());
            return false;
        }
    }

    /**
     * Renames the virtual volume after an extent migration to maintain the default ViPR
     * naming conventions.
     * 
     * @param virtualVolumeInfo The virtual volume information
     * @param migrationInfo The migration information.
     */
    private void renameVolumeAfterExtentMigration(VPlexVirtualVolumeInfo virtualVolumeInfo, VPlexMigrationInfo migrationInfo) {
        // Update the virtual volume name and associated distributed
        // device name to reflect the target rather than the source.
        String migrationSrcName = migrationInfo.getSource();
        String srcVolumeName = migrationSrcName.substring(VPlexApiConstants.EXTENT_PREFIX.length(),
                migrationSrcName.indexOf(VPlexApiConstants.EXTENT_SUFFIX));
        String migrationTgtName = migrationInfo.getTarget();
        String tgtVolumeName = migrationTgtName.substring(
                VPlexApiConstants.EXTENT_PREFIX.length(),
                migrationTgtName.indexOf(VPlexApiConstants.EXTENT_SUFFIX));
        String virtualVolumeName = virtualVolumeInfo.getName();
        String updatedVirtualVolumeName = virtualVolumeName.replace(
                srcVolumeName, tgtVolumeName);
        virtualVolumeInfo = _vplexApiClient.getVirtualVolumeManager()
                .renameVPlexResource(virtualVolumeInfo, updatedVirtualVolumeName);

        // Update the virtual volume information and set it
        // into the migration info.
        virtualVolumeInfo.updateNameOnMigrationCommit(updatedVirtualVolumeName);
    }
    
    /**
     * Renames the supporting devices of a virtual volume after an extent migration to
     * maintain the default ViPR naming conventions. This would rename the distributed
     * device and the underlying local devices.
     * 
     * @param vvInfo The virtual volume info.
     * @param migrationInfo The migration information.
     */
    private void renameSupportingDevicesAfterExtentMigration(VPlexVirtualVolumeInfo vvInfo, VPlexMigrationInfo migrationInfo) {
        // Get the discovery and virtual volume managers.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        VPlexApiVirtualVolumeManager vvolMgr = _vplexApiClient.getVirtualVolumeManager();
        
        // Get the supporting device name.
        String supportingDeviceName = vvInfo.getSupportingDevice();        
        
        // Get the source and target volume names from the migration info.
        String migrationSrcName = migrationInfo.getSource();
        String srcVolumeName = migrationSrcName.substring(VPlexApiConstants.EXTENT_PREFIX.length(),
                migrationSrcName.indexOf(VPlexApiConstants.EXTENT_SUFFIX));
        String migrationTgtName = migrationInfo.getTarget();
        String tgtVolumeName = migrationTgtName.substring(
                VPlexApiConstants.EXTENT_PREFIX.length(),
                migrationTgtName.indexOf(VPlexApiConstants.EXTENT_SUFFIX));

        // Update the distributed device if the virtual volume is a distributed
        // virtual volume.
        if (supportingDeviceName.startsWith(VPlexApiConstants.DIST_DEVICE_PREFIX)) {
            VPlexDistributedDeviceInfo supportingDeviceInfo = discoveryMgr.findDistributedDevice(supportingDeviceName);
            if (supportingDeviceInfo == null) {
                s_logger.info("Could not find distributed device {} for the virtual volume {}, hence distributed "
                        + "device name will not be updated. ", supportingDeviceName, vvInfo.getName());
                return;
            }
            String updatedDistDeviceName = supportingDeviceName.replace(srcVolumeName, tgtVolumeName);
            supportingDeviceInfo = vvolMgr.renameVPlexResource(supportingDeviceInfo, updatedDistDeviceName);

            // Lastly, update the names of the distributed device components, i.e., 
            // the local device names.
            String srcDeviceName = VPlexApiConstants.DEVICE_PREFIX + srcVolumeName;
            String tgtDeviceName = VPlexApiConstants.DEVICE_PREFIX + tgtVolumeName;
            List<VPlexDistributedDeviceComponentInfo> componentList = vvolMgr
                    .getDistributedDeviceComponents(supportingDeviceInfo.getName());
            for (VPlexResourceInfo component : componentList) {
                if (component.getName().equals(srcDeviceName)) {
                    vvolMgr.renameVPlexResource(component, tgtDeviceName);
                }
            }
        } else {
            // Update the local device name for local volumes.
            s_logger.info("Updating device {} to reflect new volume {}", supportingDeviceName, tgtVolumeName);
            VPlexDeviceInfo supportingDeviceInfo = discoveryMgr.findLocalDevice(supportingDeviceName);
            if (supportingDeviceInfo == null) {
                s_logger.info("Could not find local device {} for the virtual volume {}, hence "
                        + "device name will not be updated. ", supportingDeviceName, vvInfo.getName());
                return;
            }
            String updatedDeviceName = supportingDeviceName.replace(srcVolumeName, tgtVolumeName);
            vvolMgr.renameVPlexResource(supportingDeviceInfo, updatedDeviceName);
        }
    }    
}