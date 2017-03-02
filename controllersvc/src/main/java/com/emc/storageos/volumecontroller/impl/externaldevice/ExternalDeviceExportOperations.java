/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.externaldevice;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.mutable.MutableBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.AbstractChangeTrackingSet;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.ExportPathParams;
import com.emc.storageos.db.client.model.Host;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.StringSetMap;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.client.util.StringSetUtil;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.storagedriver.BlockStorageDriver;
import com.emc.storageos.storagedriver.DriverTask;
import com.emc.storageos.storagedriver.model.Initiator;
import com.emc.storageos.storagedriver.model.StoragePort;
import com.emc.storageos.storagedriver.model.StorageVolume;
import com.emc.storageos.storagedriver.storagecapabilities.CommonStorageCapabilities;
import com.emc.storageos.storagedriver.storagecapabilities.ExportPathsServiceOption;
import com.emc.storageos.storagedriver.storagecapabilities.StorageCapabilities;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.util.ConnectivityUtil;
import com.emc.storageos.util.ExportUtils;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.VolumeURIHLU;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.ExportMaskAddInitiatorCompleter;
import com.emc.storageos.volumecontroller.impl.smis.ExportMaskOperations;
import com.emc.storageos.volumecontroller.impl.utils.ExportMaskUtils;
import com.emc.storageos.volumecontroller.placement.BlockStorageScheduler;
import com.google.common.base.Joiner;

/**
 * Export operations for storage systems managed by drivers
 */
public class ExternalDeviceExportOperations implements ExportMaskOperations {

    private static Logger log = LoggerFactory.getLogger(ExternalDeviceExportOperations.class);

    private DbClient dbClient;

    // Need this reference to get driver for device type.
    private ExternalBlockStorageDevice externalDevice;
    private BlockStorageScheduler blockScheduler;

    public void setDbClient(DbClient dbClient) {
        this.dbClient = dbClient;
    }

    public void setExternalDevice(ExternalBlockStorageDevice externalDevice) {
        this.externalDevice = externalDevice;
    }

    public void setBlockScheduler(BlockStorageScheduler blockScheduler) {
        this.blockScheduler = blockScheduler;
    }

    @Override
    public void createExportMask(StorageSystem storage, URI exportMaskUri, VolumeURIHLU[] volumeURIHLUs, List<URI> targetURIList,
            List<com.emc.storageos.db.client.model.Initiator> initiatorList, TaskCompleter taskCompleter) throws DeviceControllerException {
        log.info("{} createExportMask START...", storage.getSerialNumber());

        try {
            log.info("createExportMask: Export mask id: {}", exportMaskUri);
            log.info("createExportMask: volume-HLU pairs: {}", Joiner.on(',').join(volumeURIHLUs));
            log.info("createExportMask: initiators: {}", Joiner.on(',').join(initiatorList));
            log.info("createExportMask: assignments: {}", Joiner.on(',').join(targetURIList));

            BlockStorageDriver driver = externalDevice.getDriver(storage.getSystemType());
            ExportMask exportMask = (ExportMask) dbClient.queryObject(exportMaskUri);

            // Get export group uri from task completer
            URI exportGroupUri = taskCompleter.getId();
            ExportGroup exportGroup = (ExportGroup) dbClient.queryObject(exportGroupUri);
            Set<URI> volumeUris = new HashSet<>();
            for (VolumeURIHLU volumeURIHLU : volumeURIHLUs) {
                URI volumeURI = volumeURIHLU.getVolumeURI();
                volumeUris.add(volumeURI);
            }
            // Prepare volumes
            List<StorageVolume> driverVolumes = new ArrayList<>();
            Map<String, String> driverVolumeToHLUMap = new HashMap<>();
            Map<String, URI> volumeNativeIdToUriMap = new HashMap<>();
            prepareVolumes(storage, volumeURIHLUs, driverVolumes, driverVolumeToHLUMap, volumeNativeIdToUriMap);

            // Prepare initiators
            List<Initiator> driverInitiators = new ArrayList<>();
            prepareInitiators(initiatorList, exportGroup.forCluster(), driverInitiators);

            // Prepare target storage ports
            List<StoragePort> recommendedPorts = new ArrayList<>();
            List<StoragePort> availablePorts = new ArrayList<>();
            List<StoragePort> selectedPorts = new ArrayList<>();
            // Prepare ports for driver call. Populate lists of recommended and available ports.
            Map<String, com.emc.storageos.db.client.model.StoragePort> nativeIdToAvailablePortMap = new HashMap<>();
            preparePorts(storage, exportMaskUri, targetURIList, recommendedPorts, availablePorts, nativeIdToAvailablePortMap);
            ExportPathParams pathParams = blockScheduler.calculateExportPathParamForVolumes(volumeUris, exportGroup.getNumPaths(),
                    storage.getId(), exportGroupUri);
            StorageCapabilities capabilities = new StorageCapabilities();
            // Prepare num paths to send to driver
            prepareCapabilities(pathParams, capabilities);
            MutableBoolean usedRecommendedPorts = new MutableBoolean(true);

            // Ready to call driver
            DriverTask task = driver.exportVolumesToInitiators(driverInitiators, driverVolumes, driverVolumeToHLUMap, recommendedPorts,
                    availablePorts, capabilities, usedRecommendedPorts, selectedPorts);
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                // If driver used recommended ports, we are done.
                // Otherwise, if driver did not use recommended ports, we have to get ports selected by driver
                // and use them in export mask and zones.
                // We will verify driver selected ports against available ports list.
                String msg = String.format("createExportMask -- Created export: %s . Used recommended ports: %s .", task.getMessage(),
                        usedRecommendedPorts);
                log.info(msg);
                log.info("Recommended ports: {}", recommendedPorts);
                log.info("Available ports: {}", availablePorts);
                log.info("Selected ports: {}", selectedPorts);

                if (usedRecommendedPorts.isFalse()) {
                    // process driver selected ports
                    if (validateSelectedPorts(availablePorts, selectedPorts, pathParams.getMinPaths())) {
                        List<com.emc.storageos.db.client.model.StoragePort> selectedPortsForMask = new ArrayList<>();

                        for (StoragePort driverPort : selectedPorts) {
                            com.emc.storageos.db.client.model.StoragePort port = nativeIdToAvailablePortMap.get(driverPort.getNativeId());
                            selectedPortsForMask.add(port);
                        }
                        updateStoragePortsInExportMask(exportMask, exportGroup, selectedPortsForMask);
                        // Update volumes Lun Ids in export mask based on driver selection
                        for (String volumeNativeId : driverVolumeToHLUMap.keySet()) {
                            String targetLunId = driverVolumeToHLUMap.get(volumeNativeId);
                            URI volumeUri = volumeNativeIdToUriMap.get(volumeNativeId);
                            exportMask.getVolumes().put(volumeUri.toString(), targetLunId);
                        }

                        dbClient.updateObject(exportMask);
                        taskCompleter.ready(dbClient);
                    } else {
                        // selected ports are not valid. failure
                        String errorMsg = "createExportMask -- Ports selected by driver failed validation.";
                        log.error("createExportMask -- Ports selected by driver failed validation.");
                        ServiceError serviceError = ExternalDeviceException.errors.createExportMaskFailed("createExportMask", errorMsg);
                        taskCompleter.error(dbClient, serviceError);
                    }
                } else {
                    // Used recommended ports.
                    // Update volumes Lun Ids in export mask based on driver selection
                    for (String volumeNativeId : driverVolumeToHLUMap.keySet()) {
                        String targetLunId = driverVolumeToHLUMap.get(volumeNativeId);
                        URI volumeUri = volumeNativeIdToUriMap.get(volumeNativeId);
                        exportMask.getVolumes().put(volumeUri.toString(), targetLunId);
                    }
                    dbClient.updateObject(exportMask);
                    taskCompleter.ready(dbClient);
                }
            } else {
                String errorMsg = String.format("createExportMask -- Failed to create export: %s .", task.getMessage());
                log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.createExportMaskFailed("createExportMask", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception ex) {
            log.error("Problem in createExportMask: ", ex);
            log.error("createExportMask -- Failed to create export mask. ", ex);
            ServiceError serviceError = ExternalDeviceException.errors.createExportMaskFailed("createExportMask", ex.getMessage());
            taskCompleter.error(dbClient, serviceError);
        }

        log.info("{} createExportMask END...", storage.getSerialNumber());
    }

    @Override
    public void deleteExportMask(StorageSystem storage, URI exportMaskUri, List<URI> volumeUrisList, List<URI> targetUris,
            List<com.emc.storageos.db.client.model.Initiator> initiators, TaskCompleter taskCompleter) throws DeviceControllerException {

        // Unexport export mask volumes from export mask initiators.
        log.info("{} deleteExportMask START...", storage.getSerialNumber());
        try {
            log.info("deleteExportMask: Export mask id: {}", exportMaskUri);
            if (volumeUrisList != null) {
                log.info("deleteExportMask: volumes:  {}", Joiner.on(',').join(volumeUrisList));
            }
            if (targetUris != null) {
                log.info("deleteExportMask: assignments: {}", Joiner.on(',').join(targetUris));
            }
            if (initiators != null) {
                log.info("deleteExportMask: initiators: {}", Joiner.on(',').join(initiators));
            }

            BlockStorageDriver driver = externalDevice.getDriver(storage.getSystemType());
            ExportMask exportMask = (ExportMask) dbClient.queryObject(exportMaskUri);

            List<URI> volumeUris = new ArrayList<>();
            StringMap maskVolumes = exportMask.getVolumes();
            if (maskVolumes != null) {
                for (String vol : maskVolumes.keySet()) {
                    URI volumeURI = URI.create(vol);
                    volumeUris.add(volumeURI);
                }
            }

            StringSet maskInitiatorUris = exportMask.getInitiators();
            List<String> initiatorUris = new ArrayList<>();
            for (String initiatorUri : maskInitiatorUris) {
                initiatorUris.add(initiatorUri);
            }
            log.info("Export mask existing initiators: {} ", Joiner.on(',').join(initiatorUris));

            StringMap volumes = exportMask.getVolumes();
            log.info("Export mask existing volumes: {} ", volumes != null ? Joiner.on(',').join(volumes.keySet()) : null);

            // Prepare volumes.
            List<StorageVolume> driverVolumes = new ArrayList<>();
            prepareVolumes(storage, volumeUris, driverVolumes);

            // Prepare initiators
            List<Initiator> driverInitiators = new ArrayList<>();
            Set<com.emc.storageos.db.client.model.Initiator> maskInitiators = ExportMaskUtils.getInitiatorsForExportMask(dbClient,
                    exportMask, null);
            // Get export group uri from task completer
            URI exportGroupUri = taskCompleter.getId();
            ExportGroup exportGroup = (ExportGroup) dbClient.queryObject(exportGroupUri);
            prepareInitiators(maskInitiators, exportGroup.forCluster(), driverInitiators);

            // Ready to call driver
            log.info("Initiators to call driver: {} ", maskInitiators);
            log.info("Volumes to call driver: {} ", volumeUris);
            DriverTask task = driver.unexportVolumesFromInitiators(driverInitiators, driverVolumes);
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                String msg = String.format("Deleted export mask: %s.", task.getMessage());
                log.info(msg);
                taskCompleter.ready(dbClient);
            } else {
                String errorMsg = String.format("Failed to delete export mask: %s .", task.getMessage());
                log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.deleteExportMaskFailed("deleteExportMask", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception ex) {
            log.error("Problem in deleteExportMask: ", ex);
            String errorMsg = String.format("Failed to delete export mask: %s .", ex.getMessage());
            log.error(errorMsg);
            ServiceError serviceError = ExternalDeviceException.errors.deleteExportMaskFailed("deleteExportMask", errorMsg);
            taskCompleter.error(dbClient, serviceError);
        }
        log.info("{} deleteExportMask END...", storage.getSerialNumber());
    }

    @Override
    public void addInitiators(StorageSystem storage, URI exportMaskUri, List<URI> volumeURIs,
            List<com.emc.storageos.db.client.model.Initiator> initiatorList, List<URI> targetURIList, TaskCompleter taskCompleter)
                    throws DeviceControllerException {

        log.info("{} addInitiators START...", storage.getSerialNumber());

        try {
            log.info("addInitiators: Export mask id: {}", exportMaskUri);
            if (volumeURIs != null) {
                log.info("addInitiators: volumes : {}", Joiner.on(',').join(volumeURIs));
            }
            log.info("addInitiators: initiators : {}", Joiner.on(',').join(initiatorList));
            log.info("addInitiators: targets : {}", Joiner.on(",").join(targetURIList));

            BlockStorageDriver driver = externalDevice.getDriver(storage.getSystemType());
            ExportMask exportMask = (ExportMask) dbClient.queryObject(exportMaskUri);

            // Get export group uri from task completer
            URI exportGroupUri = taskCompleter.getId();
            ExportGroup exportGroup = (ExportGroup) dbClient.queryObject(exportGroupUri);
            List<URI> volumeUris = ExportMaskUtils.getVolumeURIs(exportMask);

            // Prepare volumes
            List<StorageVolume> driverVolumes = new ArrayList<>();
            Map<String, String> driverVolumeToHLUMap = new HashMap<>();
            Map<String, String> volumeNativeIdToUriMap = new HashMap<>();
            prepareVolumes(storage, exportMask.getVolumes(), driverVolumes, driverVolumeToHLUMap, volumeNativeIdToUriMap);

            // Prepare initiators
            List<Initiator> driverInitiators = new ArrayList<>();

            prepareInitiators(initiatorList, exportGroup.forCluster(), driverInitiators);

            // Prepare target storage ports
            List<StoragePort> recommendedPorts = new ArrayList<>();
            List<StoragePort> availablePorts = new ArrayList<>();
            List<StoragePort> selectedPorts = new ArrayList<>();
            // Prepare ports for driver call. Populate lists of recommended and available ports.
            Map<String, com.emc.storageos.db.client.model.StoragePort> nativeIdToAvailablePortMap = new HashMap<>();
            preparePorts(storage, exportMaskUri, targetURIList, recommendedPorts, availablePorts, nativeIdToAvailablePortMap);

            ExportPathParams pathParams = blockScheduler.calculateExportPathParamForVolumes(volumeUris, exportGroup.getNumPaths(),
                    storage.getId(), exportGroupUri);
            StorageCapabilities capabilities = new StorageCapabilities();
            // Prepare num paths to send to driver
            prepareCapabilitiesForAddInitiators(pathParams, exportMask.getZoningMap(), exportGroup.getVirtualArray(), initiatorList,
                    capabilities);
            MutableBoolean usedRecommendedPorts = new MutableBoolean(true);

            // Ready to call driver
            DriverTask task = driver.exportVolumesToInitiators(driverInitiators, driverVolumes, driverVolumeToHLUMap, recommendedPorts,
                    availablePorts, capabilities, usedRecommendedPorts, selectedPorts);

            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                // If driver used recommended ports, we are done.
                // Otherwise, if driver did not use recommended ports, we have to get ports selected by driver
                // and use them in export mask and zones.
                // We will verify driver selected ports against available ports list.
                String msg = String.format("addInitiators -- Added initiators: %s . Used recommended ports: %s .", task.getMessage(),
                        usedRecommendedPorts);
                log.info(msg);
                if (usedRecommendedPorts.isFalse()) {
                    // process driver selected ports
                    log.info("Ports selected by driver: {}", selectedPorts);
                    if (validateSelectedPorts(availablePorts, selectedPorts, pathParams.getPathsPerInitiator())) {
                        List<com.emc.storageos.db.client.model.StoragePort> selectedPortsForMask = new ArrayList<>();
                        URI varrayUri = exportGroup.getVirtualArray();
                        for (StoragePort driverPort : selectedPorts) {
                            com.emc.storageos.db.client.model.StoragePort port = nativeIdToAvailablePortMap.get(driverPort.getNativeId());
                            selectedPortsForMask.add(port);
                        }
                        updateStoragePortsForAddInitiators((ExportMaskAddInitiatorCompleter) taskCompleter, storage, exportMask,
                                initiatorList, selectedPortsForMask, varrayUri, pathParams);
                        taskCompleter.ready(dbClient);
                    } else {
                        // selected ports are not valid. failure
                        String errorMsg = "addInitiators -- Ports selected by driver failed validation.";
                        log.error("addInitiators -- Ports selected by driver failed validation.");
                        ServiceError serviceError = ExternalDeviceException.errors.addInitiatorsToExportMaskFailed("addInitiators",
                                errorMsg);
                        taskCompleter.error(dbClient, serviceError);
                    }
                } else {
                    // Used recommended ports.
                    taskCompleter.ready(dbClient);
                }
            } else {
                String errorMsg = String.format("addInitiators -- Failed to add initiators to export mask: %s .", task.getMessage());
                log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.addInitiatorsToExportMaskFailed("addInitiators", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception ex) {
            log.error("addInitiators -- Failed to add initiators to export mask. ", ex);
            ServiceError serviceError = ExternalDeviceException.errors.addInitiatorsToExportMaskFailed("addInitiators", ex.getMessage());
            taskCompleter.error(dbClient, serviceError);
        }

        log.info("{} addInitiators END...", storage.getSerialNumber());
    }

    @Override
    public void removeInitiators(StorageSystem storage, URI exportMaskUri, List<URI> volumeURIList,
            List<com.emc.storageos.db.client.model.Initiator> initiatorList, List<URI> targetURIList, TaskCompleter taskCompleter)
                    throws DeviceControllerException {

        log.info("{} removeInitiators START...", storage.getSerialNumber());

        try {
            log.info("removeInitiators: Export mask id: {}", exportMaskUri);
            if (volumeURIList != null) {
                log.info("removeInitiators: volumes : {}", Joiner.on(',').join(volumeURIList));
            }
            log.info("removeInitiators: initiators : {}", Joiner.on(',').join(initiatorList));
            log.info("removeInitiators: targets : {}", Joiner.on(',').join(targetURIList));

            BlockStorageDriver driver = externalDevice.getDriver(storage.getSystemType());
            ExportMask exportMask = (ExportMask) dbClient.queryObject(exportMaskUri);

            List<URI> volumeUris = ExportMaskUtils.getVolumeURIs(exportMask);
            log.info("Export mask existing volumes: {} ", volumeUris);

            // Prepare volumes
            List<StorageVolume> driverVolumes = new ArrayList<>();
            prepareVolumes(storage, volumeUris, driverVolumes);

            // Prepare initiators
            List<Initiator> driverInitiators = new ArrayList<>();
            // Get export group uri from task completer
            URI exportGroupUri = taskCompleter.getId();
            ExportGroup exportGroup = (ExportGroup) dbClient.queryObject(exportGroupUri);
            prepareInitiators(initiatorList, exportGroup.forCluster(), driverInitiators);

            // Ready to call driver
            DriverTask task = driver.unexportVolumesFromInitiators(driverInitiators, driverVolumes);
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                String msg = String.format("Removed initiators from export mask: %s.", task.getMessage());
                log.info(msg);
                taskCompleter.ready(dbClient);
            } else {
                String errorMsg = String.format("Failed to remove initiators from export mask: %s .", task.getMessage());
                log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.removeInitiatorsFromExportMaskFailed("removeInitiators",
                        errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception ex) {
            log.error("Problem in removeInitiators: ", ex);
            String errorMsg = String.format("Failed to remove initiators from export mask: %s .", ex.getMessage());
            log.error(errorMsg);
            ServiceError serviceError = ExternalDeviceException.errors.removeInitiatorsFromExportMaskFailed("removeInitiators", errorMsg);
            taskCompleter.error(dbClient, serviceError);
        }
        log.info("{} removeInitiators END...", storage.getSerialNumber());
    }

    @Override
    public void addVolumes(StorageSystem storage, URI exportMaskUri, VolumeURIHLU[] volumeURIHLUs,
            List<com.emc.storageos.db.client.model.Initiator> initiatorList, TaskCompleter taskCompleter) throws DeviceControllerException {
        log.info("{} addVolumes START...", storage.getSerialNumber());

        try {
            log.info("addVolumes: Export mask id: {}", exportMaskUri);
            log.info("addVolumes: New volumes to add: volume-HLU pairs: {}", Joiner.on(',').join(volumeURIHLUs));
            if (initiatorList != null) {
                log.info("addVolumes: initiators: {}", Joiner.on(',').join(initiatorList));
            }

            BlockStorageDriver driver = externalDevice.getDriver(storage.getSystemType());
            ExportMask exportMask = (ExportMask) dbClient.queryObject(exportMaskUri);

            StringSet maskInitiators = exportMask.getInitiators();
            List<String> maskInitiatorList = new ArrayList<>();
            for (String initiatorUri : maskInitiators) {
                maskInitiatorList.add(initiatorUri);
            }
            log.info("Export mask existing initiators: {} ", Joiner.on(',').join(maskInitiatorList));

            StringSet storagePorts = exportMask.getStoragePorts();
            List<URI> portList = new ArrayList<>();
            for (String portUri : storagePorts) {
                portList.add(URI.create(portUri));
            }
            log.info("Export mask existing storage ports: {} ", Joiner.on(',').join(portList));

            // Get export group uri from task completer
            URI exportGroupUri = taskCompleter.getId();
            ExportGroup exportGroup = (ExportGroup) dbClient.queryObject(exportGroupUri);
            Set<URI> volumeUris = new HashSet<>();
            for (VolumeURIHLU volumeURIHLU : volumeURIHLUs) {
                URI volumeURI = volumeURIHLU.getVolumeURI();
                volumeUris.add(volumeURI);
            }
            // Prepare volumes. We send to driver only new volumes for the export mask.
            List<StorageVolume> driverVolumes = new ArrayList<>();
            Map<String, String> driverVolumeToHLUMap = new HashMap<>();
            Map<String, URI> volumeNativeIdToUriMap = new HashMap<>();
            prepareVolumes(storage, volumeURIHLUs, driverVolumes, driverVolumeToHLUMap, volumeNativeIdToUriMap);

            // Prepare initiators
            Set<com.emc.storageos.db.client.model.Initiator> initiators = ExportMaskUtils.getInitiatorsForExportMask(dbClient, exportMask,
                    null);
            List<Initiator> driverInitiators = new ArrayList<>();
            prepareInitiators(initiators, exportGroup.forCluster(), driverInitiators);

            // Prepare target storage ports
            List<StoragePort> recommendedPorts = new ArrayList<>();
            List<StoragePort> availablePorts = new ArrayList<>();
            List<StoragePort> selectedPorts = new ArrayList<>();
            // Prepare ports for driver call. Populate lists of recommended and available ports.
            Map<String, com.emc.storageos.db.client.model.StoragePort> nativeIdToAvailablePortMap = new HashMap<>();

            // We use existing ports in the mask as recommended ports.
            preparePorts(storage, exportMaskUri, portList, recommendedPorts, availablePorts, nativeIdToAvailablePortMap);
            log.info("varray ports: {}", nativeIdToAvailablePortMap);
            // For add volumes to existing export mask, we do not allow storage port change in the mask.
            // Only ports in the mask are available for driver call.
            availablePorts = recommendedPorts;

            ExportPathParams pathParams = blockScheduler.calculateExportPathParamForVolumes(volumeUris, exportGroup.getNumPaths(),
                    storage.getId(), exportGroupUri);
            StorageCapabilities capabilities = new StorageCapabilities();
            // Prepare num paths to send to driver
            prepareCapabilities(pathParams, capabilities);
            MutableBoolean usedRecommendedPorts = new MutableBoolean(true);
            // Ready to call driver
            DriverTask task = driver.exportVolumesToInitiators(driverInitiators, driverVolumes, driverVolumeToHLUMap, recommendedPorts,
                    availablePorts, capabilities, usedRecommendedPorts, selectedPorts);

            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                String msg = String.format("Created export for volumes: %s . Used recommended ports: %s .", task.getMessage(), usedRecommendedPorts);
                log.info(msg);
                log.info("Driver selected storage ports: {} ", Joiner.on(',').join(selectedPorts));
                // If driver used recommended ports (the same ports as already in the mask), we are done.
                // If driver did not use recommended ports, we will allow this to proceed only when auto san zoning is disabled, otherwise, if
                // auto san zoning is enabled, we will fail the request.
                if (usedRecommendedPorts.isFalse() && !selectedPorts.containsAll(recommendedPorts)) {
                    // for auto san zoning enabled we can not support case when selected ports do not include ports which are already in the mask
                    VirtualArray varray = dbClient.queryObject(VirtualArray.class, exportGroup.getVirtualArray());
                    log.info("AutoSanZoning for varray {} is {} ", varray.getLabel(), varray.getAutoSanZoning());
                    if (varray.getAutoSanZoning()) {
                        String errorMsg = String.format("AutoSanZoning is enabled and driver selected ports do not contain ports from the export mask: %s .",
                                task.getMessage());
                        log.error(errorMsg);
                        ServiceError serviceError = ExternalDeviceException.errors.addVolumesToExportMaskFailed("addVolumes", errorMsg);
                        taskCompleter.error(dbClient, serviceError);
                    } else {
                        // auto san zoning is disabled --- add new selected ports to the mask
                        // we do not care about zoning map in this case
                        List<com.emc.storageos.db.client.model.StoragePort> selectedPortsForMask = new ArrayList<>();
                        for (StoragePort driverPort : selectedPorts) {
                            log.info("Driver selected port: {}", driverPort);
                            com.emc.storageos.db.client.model.StoragePort port = nativeIdToAvailablePortMap.get(driverPort.getNativeId());
                            if (port != null) {
                                // add all ports, StringSet in the mask will ignore duplicates
                                log.info("System port: {}", port);
                                selectedPortsForMask.add(port);
                            }
                        }
                        for (com.emc.storageos.db.client.model.StoragePort port : selectedPortsForMask) {
                            exportMask.addTarget(port.getId());
                        }
                        dbClient.updateObject(exportMask);
                        taskCompleter.ready(dbClient);
                    }
                } else {
                    // Driver used recommended ports for a new volumes or all export mask ports are contained
                    // in the list of driver selected ports
                    // No port change in export mask is needed.
                    // Update volumes Lun Ids in export mask based on driver selection
                    for (String volumeNativeId : driverVolumeToHLUMap.keySet()) {
                        String targetLunId = driverVolumeToHLUMap.get(volumeNativeId);
                        URI volumeUri = volumeNativeIdToUriMap.get(volumeNativeId);
                        exportMask.getVolumes().put(volumeUri.toString(), targetLunId);
                    }
                    dbClient.updateObject(exportMask);
                    taskCompleter.ready(dbClient);
                }
            } else {
                String errorMsg = String.format("Failed to add volumes to export mask: %s .", task.getMessage());
                log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.addVolumesToExportMaskFailed("addVolumes", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception ex) {
            log.error("Problem in addVolumes: ", ex);
            String errorMsg = String.format("Failed to add volumes to export mask: %s .", ex.getMessage());
            log.error(errorMsg);
            ServiceError serviceError = ExternalDeviceException.errors.addVolumesToExportMaskFailed("addVolumes", errorMsg);
            taskCompleter.error(dbClient, serviceError);
        }
        log.info("{} addVolumes END...", storage.getSerialNumber());
    }

    @Override
    public void removeVolumes(StorageSystem storage, URI exportMaskUri, List<URI> volumeUris,
            List<com.emc.storageos.db.client.model.Initiator> initiatorList, TaskCompleter taskCompleter) throws DeviceControllerException {
        log.info("{} removeVolumes START...", storage.getSerialNumber());

        try {
            log.info("removeVolumes: Export mask id: {}", exportMaskUri);
            log.info("removeVolumes: volumes: {}", Joiner.on(',').join(volumeUris));
            if (initiatorList != null) {
                log.info("removeVolumes: impacted initiators: {}", Joiner.on(",").join(initiatorList));
            }

            BlockStorageDriver driver = externalDevice.getDriver(storage.getSystemType());
            ExportMask exportMask = (ExportMask) dbClient.queryObject(exportMaskUri);

            StringSet maskInitiators = exportMask.getInitiators();

            List<String> maskInitiatorList = new ArrayList<>();
            for (String initiatorUri : maskInitiators) {
                maskInitiatorList.add(initiatorUri);
            }
            log.info("Export mask existing initiators: {} ", Joiner.on(",").join(maskInitiatorList));

            // Prepare volumes. We send to driver only new volumes for the export mask.
            List<StorageVolume> driverVolumes = new ArrayList<>();
            prepareVolumes(storage, volumeUris, driverVolumes);

            // Prepare initiators
            Set<com.emc.storageos.db.client.model.Initiator> initiators = ExportMaskUtils.getInitiatorsForExportMask(dbClient, exportMask,
                    null);
            List<Initiator> driverInitiators = new ArrayList<>();
            // Get export group uri from task completer
            URI exportGroupUri = taskCompleter.getId();
            ExportGroup exportGroup = (ExportGroup) dbClient.queryObject(exportGroupUri);
            prepareInitiators(initiators, exportGroup.forCluster(), driverInitiators);

            // Ready to call driver
            DriverTask task = driver.unexportVolumesFromInitiators(driverInitiators, driverVolumes);
            // todo: need to implement support for async case.
            if (task.getStatus() == DriverTask.TaskStatus.READY) {
                String msg = String.format("Removed volumes from export: %s.", task.getMessage());
                log.info(msg);
                taskCompleter.ready(dbClient);
            } else {
                String errorMsg = String.format("Failed to remove volumes from export mask: %s .", task.getMessage());
                log.error(errorMsg);
                ServiceError serviceError = ExternalDeviceException.errors.deleteVolumesFromExportMaskFailed("removeVolumes", errorMsg);
                taskCompleter.error(dbClient, serviceError);
            }
        } catch (Exception ex) {
            log.error("Problem in removeVolumes: ", ex);
            String errorMsg = String.format("Failed to remove volumes from export mask: %s .", ex.getMessage());
            log.error(errorMsg);
            ServiceError serviceError = ExternalDeviceException.errors.deleteVolumesFromExportMaskFailed("removeVolumes", errorMsg);
            taskCompleter.error(dbClient, serviceError);
        }
        log.info("{} removeVolumes END...", storage.getSerialNumber());
    }

    @Override
    public Map<String, Set<URI>> findExportMasks(StorageSystem storage,
                                                 List<String> initiatorNames, boolean mustHaveAllPorts) throws DeviceControllerException {
        // not supported. There are no common masking concepts. So, return null.
        return null;
    }

    @Override
    public Set<Integer> findHLUsForInitiators(StorageSystem storage, List<String> initiatorNames, boolean mustHaveAllPorts) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ExportMask refreshExportMask(StorageSystem storage, ExportMask mask) throws DeviceControllerException {
        // No common masking concept for driver managed systems. Return export mask as is.
        return mask;
    }

    @Override
    public void updateStorageGroupPolicyAndLimits(StorageSystem storage, ExportMask exportMask, List<URI> volumeURIs,
            VirtualPool newVirtualPool, boolean rollback, TaskCompleter taskCompleter) throws Exception {
        throw DeviceControllerException.exceptions.blockDeviceOperationNotSupported();
    }

    @Override
    public Map<URI, Integer> getExportMaskHLUs(StorageSystem storage, ExportMask exportMask) {
        return null;
    }

    /**
     * Prepare new driver volumes for driver export request (Ex. in context of create a new export mask,
     * or add new volumes to the existing mask).
     *
     * @param storage
     *            storage sytem
     * @param volumeURIHLUs
     *            mapping of volume uri to volume hlu
     * @param driverVolumes
     *            driver volumes (output)
     * @param driverVolumeToHLUMap
     *            map of driver volumes to hlu values
     * @param volumeNativeIdToUriMap
     *            map of volume native id to uri
     */
    private void prepareVolumes(StorageSystem storage, VolumeURIHLU[] volumeURIHLUs, List<StorageVolume> driverVolumes,
            Map<String, String> driverVolumeToHLUMap, Map<String, URI> volumeNativeIdToUriMap) {

        for (VolumeURIHLU volumeURIHLU : volumeURIHLUs) {
            URI volumeURI = volumeURIHLU.getVolumeURI();
            BlockObject volume = (BlockObject) dbClient.queryObject(volumeURI);
            StorageVolume driverVolume = createDriverVolume(storage, volume);
            driverVolumes.add(driverVolume);
            // We send volume lun number to driver in decimal format.
            Integer decimalHLU;
            if (volumeURIHLU.getHLU().equals(ExportGroup.LUN_UNASSIGNED_STR)) { // cannot parse "ffffffff" with
                                                                                // Integer.parseInt(volumeURIHLU.getHLU(),
                                                                                // 16),
                                                                                // got "NumberFormatException". Looks as
                                                                                // Java error ???
                decimalHLU = ExportGroup.LUN_UNASSIGNED;
            } else {
                decimalHLU = Integer.parseInt(volumeURIHLU.getHLU(), 16);
            }
            driverVolumeToHLUMap.put(driverVolume.getNativeId(), decimalHLU.toString());
            volumeNativeIdToUriMap.put(driverVolume.getNativeId(), volumeURI);
        }

        log.info("prepareVolumes: volume-HLU pairs for driver: {}", driverVolumeToHLUMap);
    }

    /**
     * Prepare existing export mask volumes to driver export request (Ex. in context of add/remove initiators for export
     * masks.)
     *
     * @param storage
     *            storage system
     * @param driverVolumes
     *            driver volumes (output)
     * @param driverVolumeToHLUMap
     *            map of driver volumes to hlu values
     * @param volumeNativeIdToUriMap
     *            map of volume native id to uri
     */
    private void prepareVolumes(StorageSystem storage, Map<String, String> volumeUriToHluMap, List<StorageVolume> driverVolumes,
            Map<String, String> driverVolumeToHLUMap, Map<String, String> volumeNativeIdToUriMap) {

        for (Map.Entry<String, String> volumeUriToHlu : volumeUriToHluMap.entrySet()) {
            String volumeURI = volumeUriToHlu.getKey();
            BlockObject volume = (BlockObject) dbClient.queryObject(URIUtil.uri(volumeURI));
            StorageVolume driverVolume = createDriverVolume(storage, volume);
            driverVolumes.add(driverVolume);
            driverVolumeToHLUMap.put(driverVolume.getNativeId(), volumeUriToHlu.getValue());
            volumeNativeIdToUriMap.put(driverVolume.getNativeId(), volumeURI);
        }

        log.info("prepareVolumes: volume-HLU pairs for driver: {}", driverVolumeToHLUMap);
    }

    private void prepareVolumes(StorageSystem storage, List<URI> volumeUris, List<StorageVolume> driverVolumes) {

        for (URI volumeUri : volumeUris) {
            BlockObject volume = (BlockObject) dbClient.queryObject(volumeUri);
            StorageVolume driverVolume = createDriverVolume(storage, volume);
            driverVolumes.add(driverVolume);
        }
    }

    private void prepareInitiators(Collection<com.emc.storageos.db.client.model.Initiator> initiatorList, boolean isClusterExport,
            List<Initiator> driverInitiators) {
        for (com.emc.storageos.db.client.model.Initiator initiator : initiatorList) {
            Initiator driverInitiator = createDriverInitiator(initiator);
            if (isClusterExport) {
                driverInitiator.setInitiatorType(Initiator.Type.Cluster);
            } else {
                driverInitiator.setInitiatorType(Initiator.Type.Host);
            }
            driverInitiators.add(driverInitiator);
        }
    }

    private void preparePorts(StorageSystem storage, URI exportMaskUri, List<URI> targetURIList, List<StoragePort> recommendedPorts,
            List<StoragePort> availablePorts, Map<String, com.emc.storageos.db.client.model.StoragePort> nativeIdToAvailablePortMap) {

        Iterator<com.emc.storageos.db.client.model.StoragePort> ports = dbClient
                .queryIterativeObjects(com.emc.storageos.db.client.model.StoragePort.class, targetURIList);

        while (ports.hasNext()) {
            StoragePort driverPort = createDriverPort(storage, ports.next());
            recommendedPorts.add(driverPort);
        }

        List<ExportGroup> exportGroups = ExportUtils.getExportGroupsForMask(exportMaskUri, dbClient);
        URI varrayUri = exportGroups.get(0).getVirtualArray();
        List<com.emc.storageos.db.client.model.StoragePort> varrayPorts = getStorageSystemVarrayStoragePorts(storage, varrayUri);

        for (com.emc.storageos.db.client.model.StoragePort port : varrayPorts) {
            if (port.getNativeId() != null) {
                nativeIdToAvailablePortMap.put(port.getNativeId(), port);
            } else {
                // This is error condition. Each port for driver managed systems should have nativeId set at
                // discovery time.
                String errorMsg = String.format("No nativeId defined for port: storage system: %s, storagePort: %s .", storage.getId(),
                        port.getId());
                log.error(errorMsg);
                throw ExternalDeviceException.exceptions.noNativeIdDefinedForPort(storage.getNativeId(), port.getId().toString());
            }
            StoragePort driverPort = createDriverPort(storage, port);
            availablePorts.add(driverPort);
        }
    }

    private void prepareCapabilities(ExportPathParams pathParams, StorageCapabilities capabilities) {
        ExportPathsServiceOption numPath = new ExportPathsServiceOption(pathParams.getMinPaths(), pathParams.getMaxPaths());
        List<ExportPathsServiceOption> exportPathParams = new ArrayList<>();
        exportPathParams.add(numPath);

        CommonStorageCapabilities commonCapabilities = new CommonStorageCapabilities();
        commonCapabilities.setExportPathParams(exportPathParams);
        capabilities.setCommonCapabilitis(commonCapabilities);
    }

    private void prepareCapabilitiesForAddInitiators(ExportPathParams pathParams, StringSetMap existingZoningMap, URI varrayURI,
            List<com.emc.storageos.db.client.model.Initiator> initiators, StorageCapabilities capabilities) {
        int driverMaxPath;
        StringSetMap zoningMap = new StringSetMap();
        // Calculate existing paths (without new initiators).
        int existingPaths = 0;
        List<URI> initiatorUris = URIUtil.toUris(initiators);
        for (Map.Entry<String, AbstractChangeTrackingSet<String>> entry : existingZoningMap.entrySet()) {
            if (!initiatorUris.contains(URIUtil.uri(entry.getKey()))) {
                zoningMap.put(entry.getKey(), entry.getValue());
            }
        }
        Map<com.emc.storageos.db.client.model.Initiator, List<com.emc.storageos.db.client.model.StoragePort>> assignments = blockScheduler
                .generateInitiatorsToStoragePortsMap(zoningMap, varrayURI);
        existingPaths = assignments.values().size();
        log.info("Existing path number in the export mask is {}", existingPaths);

        // Calculate maxPath for the driver request.
        // We try to balance two requirements: not violate maxPaths and to allocate at least enough paths
        // for one new initiator. If we are over max path already or will be over max paths, when we allocate
        // ppi number of new paths, we allocate only ppi new paths (a minimum).
        // Otherwise we allocate the difference between max paths and existing paths
        if (existingPaths + pathParams.getPathsPerInitiator() > pathParams.getMaxPaths()) {
            driverMaxPath = pathParams.getPathsPerInitiator(); // we always need at least path-per-initiator ports
        } else {
            driverMaxPath = pathParams.getMaxPaths() - existingPaths;
        }

        // We assume that masking view meets min path before new initiators are added. We pass ppi as a minPath
        // to driver, so we can zone at least one new initiator.
        ExportPathsServiceOption numPath = new ExportPathsServiceOption(pathParams.getPathsPerInitiator(), driverMaxPath);
        List<ExportPathsServiceOption> exportPathParams = new ArrayList<>();
        exportPathParams.add(numPath);

        CommonStorageCapabilities commonCapabilities = new CommonStorageCapabilities();
        commonCapabilities.setExportPathParams(exportPathParams);
        capabilities.setCommonCapabilitis(commonCapabilities);
    }

    /**
     * Gets the list of storage ports of a storage system which belong to a virtual array.
     *
     * @param storage
     *            StorageSystem from which ports needs to be queried.
     * @param varrayURI
     *            URI of the virtual array
     * @return list of storage ports of storage system which belong to virtual array
     */
    private List<com.emc.storageos.db.client.model.StoragePort> getStorageSystemVarrayStoragePorts(StorageSystem storage, URI varrayURI) {
        log.debug("START - getStorageSystemVarrayStoragePorts");
        // Get the list of storage ports of a storage system which are in a given varray
        Map<URI, List<com.emc.storageos.db.client.model.StoragePort>> networkUriVsStoragePorts = ConnectivityUtil
                .getStoragePortsOfTypeAndVArray(dbClient, storage.getId(), com.emc.storageos.db.client.model.StoragePort.PortType.frontend,
                        varrayURI);

        List<com.emc.storageos.db.client.model.StoragePort> varrayTaggedStoragePorts = new ArrayList<>();

        Set<URI> networkUriSet = networkUriVsStoragePorts.keySet();
        for (URI nwUri : networkUriSet) {
            List<com.emc.storageos.db.client.model.StoragePort> ports = networkUriVsStoragePorts.get(nwUri);
            varrayTaggedStoragePorts.addAll(ports);
            log.info("Varray Tagged Ports for network {} are {}", nwUri, ports);
        }

        log.debug("END - getStorageSystemVarrayStoragePorts");

        return varrayTaggedStoragePorts;
    }

    private Boolean validateSelectedPorts(List<StoragePort> availablePorts, List<StoragePort> selectedPorts, int minPorts) {
        boolean containmentCheck = availablePorts.containsAll(selectedPorts);
        boolean numPathCheck = (selectedPorts.size() >= minPorts);
        log.info(
                String.format("Validation check for selected ports: containmentCheck: %s, minPorts: %s .", containmentCheck, numPathCheck));
        return containmentCheck && numPathCheck;
    }

    private void updateStoragePortsInExportMask(ExportMask exportMask, ExportGroup exportGroup,
            List<com.emc.storageos.db.client.model.StoragePort> storagePorts) {

        // This method will update storage ports and zoning map in the export mask based on the storagePorts list.
        // Clean all existing targets in the export mask and add new targets
        List<URI> storagePortListFromMask = StringSetUtil.stringSetToUriList(exportMask.getStoragePorts());
        for (URI removeUri : storagePortListFromMask) {
            exportMask.removeTarget(removeUri);
        }

        // Add new target ports
        for (com.emc.storageos.db.client.model.StoragePort port : storagePorts) {
            exportMask.addTarget(port.getId());
        }
        log.info("Ports in mask after add new ports: {}", exportMask.getStoragePorts());
        // Update zoning map based on provided storage ports
        blockScheduler.updateZoningMap(exportMask, exportGroup.getVirtualArray(), exportGroup.getId());

    }

    /**
     * This method updates storage ports in export masks for new initiators, based on array selected ports.
     *
     * @param taskCompleter
     * @param system
     * @param exportMask
     * @param initiatorList
     * @param selectedPortsForMask
     * @param virtualArray
     * @param pathParams
     */
    private void updateStoragePortsForAddInitiators(ExportMaskAddInitiatorCompleter taskCompleter, StorageSystem system,
            ExportMask exportMask, List<com.emc.storageos.db.client.model.Initiator> initiatorList,
            List<com.emc.storageos.db.client.model.StoragePort> selectedPortsForMask, URI virtualArray, ExportPathParams pathParams) {
        // update storage ports in completer
        List<URI> portUris = new ArrayList<>();
        for (com.emc.storageos.db.client.model.StoragePort port : selectedPortsForMask) {
            portUris.add(port.getId());
        }
        taskCompleter.setTargetURIs(portUris);

        // update zoning map entries for new initiators
        // remove old entries for the initiators from the zoning map
        List<URI> initiatorUris = URIUtil.toUris(initiatorList);
        for (URI initiatorUri : initiatorUris) {
            exportMask.removeZoningMapEntry(initiatorUri.toString());
        }
        // add new entries for the initiators to the zoning map
        // Build assignments for new added initiators.
        Map<URI, List<URI>> assignments = blockScheduler.assignSelectedStoragePorts(system, selectedPortsForMask, virtualArray,
                initiatorList, pathParams, exportMask.getZoningMap());

        exportMask.addZoningMap(BlockStorageScheduler.getZoneMapFromAssignments(assignments));
        dbClient.updateObject(exportMask);
    }

    /**
     * Create driver block object
     * 
     * @param storage
     * @param volume
     * @return
     */
    private StorageVolume createDriverVolume(StorageSystem storage, BlockObject volume) {
        StorageVolume driverVolume = new StorageVolume();
        driverVolume.setStorageSystemId(storage.getNativeId());
        driverVolume.setNativeId(volume.getNativeId());
        driverVolume.setDeviceLabel(volume.getDeviceLabel());
        if (!NullColumnValueGetter.isNullURI(volume.getConsistencyGroup())) {
            BlockConsistencyGroup cg = dbClient.queryObject(BlockConsistencyGroup.class, volume.getConsistencyGroup());
            driverVolume.setConsistencyGroup(cg.getLabel());
        }
        return driverVolume;
    }

    private Initiator createDriverInitiator(com.emc.storageos.db.client.model.Initiator initiator) {
        Initiator driverInitiator = new Initiator();
        driverInitiator.setPort(initiator.getInitiatorPort());
        driverInitiator.setHostName(initiator.getHostName());
        driverInitiator.setClusterName(initiator.getClusterName());
        driverInitiator.setNode(initiator.getInitiatorNode());
        driverInitiator.setProtocol(Initiator.Protocol.valueOf(initiator.getProtocol()));
        driverInitiator.setDisplayName(initiator.getLabel());

        // set host OS type
        driverInitiator.setHostOsType(Initiator.HostOsType.Other);
        Host host = dbClient.queryObject(Host.class, initiator.getHost());
        String hostType = host.getType();
        for (Initiator.HostOsType driverInitiatorHostType : Initiator.HostOsType.values()) {
            if (hostType.equals(driverInitiatorHostType.toString())) {
                driverInitiator.setHostOsType(driverInitiatorHostType);
            }
        }
        log.info("Initiator host OS type {}", driverInitiator.getHostOsType());

        return driverInitiator;
    }

    private StoragePort createDriverPort(StorageSystem storage, com.emc.storageos.db.client.model.StoragePort port) {
        StoragePort driverPort = new StoragePort();
        driverPort.setNativeId(port.getNativeId());
        driverPort.setStorageSystemId(storage.getNativeId());
        driverPort.setPortName(port.getPortName());
        driverPort.setDeviceLabel(port.getLabel());
        driverPort.setPortGroup(port.getPortGroup());

        return driverPort;
    }
    
    @Override
    public void addPaths(StorageSystem storage, URI exportMask, Map<URI, List<URI>> newPaths, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        throw DeviceControllerException.exceptions.blockDeviceOperationNotSupported();
    }

    @Override
    public void removePaths(StorageSystem storage, URI exportMask, Map<URI, List<URI>> adjustedPaths, Map<URI, List<URI>> removePaths, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        throw DeviceControllerException.exceptions.blockDeviceOperationNotSupported();
    }

}
