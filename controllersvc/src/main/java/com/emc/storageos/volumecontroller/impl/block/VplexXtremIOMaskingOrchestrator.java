/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.block;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.Controller;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.ExportGroup.ExportGroupType;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.StringSetMap;
import com.emc.storageos.db.client.util.StringSetUtil;
import com.emc.storageos.exceptions.DeviceControllerExceptions;
import com.emc.storageos.locking.LockTimeoutValue;
import com.emc.storageos.locking.LockType;
import com.emc.storageos.plugins.common.Constants;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.util.NetworkLite;
import com.emc.storageos.volumecontroller.BlockStorageDevice;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.ControllerLockingUtil;
import com.emc.storageos.volumecontroller.placement.StoragePortsAllocator;
import com.emc.storageos.volumecontroller.placement.StoragePortsAssigner;
import com.emc.storageos.vplex.api.VPlexApiException;
import com.emc.storageos.workflow.Workflow;
import com.emc.storageos.workflow.Workflow.Method;
import com.emc.storageos.workflow.WorkflowService;
import com.emc.storageos.workflow.WorkflowStepCompleter;

public class VplexXtremIOMaskingOrchestrator extends XtremIOMaskingOrchestrator implements
        VplexBackEndMaskingOrchestrator, Controller {
    private static final Logger _log = LoggerFactory.getLogger(VplexXtremIOMaskingOrchestrator.class);
    private boolean simulation = false;
    private static final int XTREMIO_NUM_PORT_GROUP = 1;
    private int vplexDirectorCount;
    private int xtremIOXbricksCount;
    BlockDeviceController _blockController = null;
    WorkflowService _workflowService = null;

    public VplexXtremIOMaskingOrchestrator() {
    }

    public void setBlockDeviceController(BlockDeviceController blockController) {
        this._blockController = blockController;
    }

    public VplexXtremIOMaskingOrchestrator(DbClient dbClient, BlockDeviceController controller) {
        this._dbClient = dbClient;
        this._blockController = controller;
    }

    public void setVplexDirectorCount(int vplexDirectorCount) {
        this.vplexDirectorCount = vplexDirectorCount;
    }

    @Override
    public Map<URI, ExportMask> readExistingExportMasks(StorageSystem storage,
            BlockStorageDevice device, List<Initiator> initiators) {
        // This will cause the VPlexBackendManager to generate an ExportMask
        // for the first volume and then reuse it by finding it from the
        // database for subsequent volumes.
        // Use this , if you really don't care about the # masks created on
        // theArray
        return new HashMap<URI, ExportMask>();
    }

    @Override
    public ExportMask refreshExportMask(StorageSystem storage, BlockStorageDevice device,
            ExportMask mask) {
        // Use this ,if you really don't care about the details of existing
        // masks on Array.
        return mask;
    }

    @Override
    public void suggestExportMasksForPlacement(
            StorageSystem storage, BlockStorageDevice device, List<Initiator> initiators,
            ExportMaskPlacementDescriptor placementDescriptor) {
        super.suggestExportMasksForPlacement(storage, device, initiators, placementDescriptor);
    }

    @Override
    public Set<Map<URI, List<List<StoragePort>>>> getPortGroups(
            Map<URI, List<StoragePort>> allocatablePorts, Map<URI, NetworkLite> networkMap,
            URI varrayURI, int nInitiatorGroups) {
        /**
         * TODO add detailed info
         * Number of Port Group for XtremIO is always one.
         * - If multiple port groups, each VPLEX Director's initiators will be mapped to multiple ports
         * 
         * Single Port Group contains different set of storage ports for each network,
         * so that each VPLEX director's initiators will map to different port set.
         * 
         * why allocatePorts() not required:
         * 
         */
        Set<Map<URI, List<List<StoragePort>>>> portGroups = new HashSet<Map<URI, List<List<StoragePort>>>>();

        StringSet netNames = new StringSet();
        // Order the networks from those with fewest ports to those with the most ports.
        List<URI> orderedNetworks = orderNetworksByNumberOfPorts(allocatablePorts);
        for (URI networkURI : orderedNetworks) {
            netNames.add(networkMap.get(networkURI).getLabel());
        }
        _log.info("Calculating PortGroups for Networks: {}", netNames.toString());

        Map<URI, List<List<StoragePort>>> useablePorts = new HashMap<URI, List<List<StoragePort>>>();
        // Map<URI, List<List<StoragePort>>> allocatablePortsNew = new HashMap<URI, List<List<StoragePort>>>();
        Set<String> usedPorts = new HashSet<String>();

        boolean allPortsLooped = false;
        // map of selected X-brick to Storage Controllers across all networks
        Map<String, List<String>> xBricksToSelectedSCs = new HashMap<String, List<String>>();
        // map of network to selected X-bricks
        Map<URI, List<String>> networkToSelectedXbricks = new HashMap<URI, List<String>>();
        do {
            Map<URI, List<StoragePort>> useablePortsSet = getUsablePortsSet(allocatablePorts, orderedNetworks, usedPorts,
                    xBricksToSelectedSCs, networkToSelectedXbricks);
            if (useablePortsSet == null) {
                // if requirement not satisfied
                break;
            }
            for (URI networkURI : useablePortsSet.keySet()) {
                if (!useablePorts.containsKey(networkURI)) {
                    useablePorts.put(networkURI, new ArrayList<List<StoragePort>>());
                }
                useablePorts.get(networkURI).add(useablePortsSet.get(networkURI));
            }

            allPortsLooped = isAllPortsLooped(orderedNetworks, allocatablePorts, usedPorts);
        } while (!allPortsLooped);


        // TODO required?
        // If all networks have some ports remaining, use the filtered ports.
        // If not, emit a warning and do not use the filtered port configuration.

        // int numPG = minPorts / portsPerNetPerPG;
        int numPG = XTREMIO_NUM_PORT_GROUP;
        _log.info(String.format("Number of Port Groups: %d", numPG));
        portGroups.add(useablePorts);
        _log.info("Selected network to ports set: {}", useablePorts.entrySet());

        // get number of X-bricks from selected ports
        xtremIOXbricksCount = getXbricksCount(useablePorts);

        // TODO required?
        // Don't allow this network to have more than twice the number of
        // ports from the network with the fewest ports, i.e. do not exceed 2x portsPerPG.

        return portGroups;
    }

    /**
     * Gets the usable ports set.
     *
     * @param allocatablePorts the allocatable ports
     * @param orderedNetworks the ordered networks
     * @param usedPorts the used ports
     * @param networkToSelectedXbricks
     * @param xBricksToSelectedSCs
     * @return the usable ports set
     */
    private Map<URI, List<StoragePort>> getUsablePortsSet(Map<URI, List<StoragePort>> allocatablePorts, List<URI> orderedNetworks,
            Set<String> usedPorts, Map<String, List<String>> xBricksToSelectedSCs, Map<URI, List<String>> networkToSelectedXbricks) {

        Map<URI, List<StoragePort>> useablePorts = new HashMap<URI, List<StoragePort>>();
        Set<String> usedPortsSet = new HashSet<String>();
        Set<String> portsSelected = new HashSet<String>(usedPorts);

        do {
            int PreviousSize = usedPortsSet.size();
            for (URI networkURI : orderedNetworks) {
                // TODO move log level to DEBUG mode after testing
                _log.info("network: {}", networkURI);
                _log.info("xBricksToSelectedSCs: {}, networkToSelectedXbricks: {}",
                        xBricksToSelectedSCs.entrySet(), networkToSelectedXbricks.get(networkURI));
                StoragePort port = getNetworkPortUniqueXbrick(networkURI, allocatablePorts.get(networkURI),
                        portsSelected, networkToSelectedXbricks, xBricksToSelectedSCs);
                _log.info("Port selected {} for network {}", port != null ? port.getPortName() : null, networkURI);
                if (port != null) {
                    usedPortsSet.add(port.getPortName());
                    portsSelected.add(port.getPortName());
                    if (!useablePorts.containsKey(networkURI)) {
                        useablePorts.put(networkURI, new ArrayList<StoragePort>());
                    }
                    useablePorts.get(networkURI).add(port);
                }
                // Stop processing more networks if 4 ports have been selected
                if (usedPortsSet.size() == 4) {
                    break;
                }
            }
            boolean allPortsLooped = isAllPortsLooped(orderedNetworks, allocatablePorts, portsSelected);
            if (allPortsLooped) {
                // no more ports to process
                if (usedPortsSet.size() < 2) {
                    return null;  // requirement not met
                } else if (usedPortsSet.size() >= 2) {
                    break;  // satisfies minimum requirement
                }
            } else {
                // still ports available AND no ports selected in this round,
                // clear the X-bricks map
                if (PreviousSize == usedPortsSet.size()) {
                    xBricksToSelectedSCs.clear();
                    networkToSelectedXbricks.clear();
                }
            }
            _log.info("Ports selected in this set : {}", usedPortsSet);
        } while (usedPortsSet.size() < 4);
        _log.info("Set Done: Ports selected in this set: {}", usedPortsSet);
        // add to all usedPorts list
        usedPorts.addAll(usedPortsSet);
        return useablePorts;
    }

    /**
     * Gets a storage port for the given network from Unique X-brick/SC.
     *
     * @param networkURI the network uri
     * @param storagePorts the storage ports
     * @param usedPorts the used ports
     * @param networkToSelectedXbricks the network to selected xbricks
     * @param xBricksToSelectedSCs the x bricks to selected s cs
     * @return the network port unique x brick
     */
    private StoragePort getNetworkPortUniqueXbrick(URI networkURI, List<StoragePort> storagePorts, Set<String> usedPorts,
            Map<URI, List<String>> networkToSelectedXbricks, Map<String, List<String>> xBricksToSelectedSCs) {
        /**
         * Input:
         * -List of network's storage ports;
         * -X-bricks already chosen for this network;
         * -X-bricks already chosen for all networks with StorageControllers (SC) chosen:
         * 
         * Choose a storage port based on below logic:
         * -See if there is a port from X-brick other than allNetworkXbricks (select different SC for the selected X-brick)
         * -If not, see if there is a port from X-brick other than networkXbricks (select different SC for the selected X-brick)
         */
        StoragePort port = null;
        if (networkToSelectedXbricks.get(networkURI) == null) {
            networkToSelectedXbricks.put(networkURI, new ArrayList<String>());
        }
        for (StoragePort sPort : storagePorts) {
            // Do not choose a port that has already been chosen
            if (!usedPorts.contains(sPort.getPortName())) {
                String[] splitArray = sPort.getPortGroup().split(Constants.HYPEN);
                String xBrick = splitArray[0];
                String sc = splitArray[1];
                // select port from unique X-brick/SC
                if (!xBricksToSelectedSCs.keySet().contains(xBrick)) {
                    port = sPort;
                    addSCToXbrick(xBricksToSelectedSCs, xBrick, sc);
                    networkToSelectedXbricks.get(networkURI).add(xBrick);
                    break;
                }
            }
        }
        if (port == null) {
            for (StoragePort sPort : storagePorts) {
                // Do not choose a port that has already been chosen
                if (!usedPorts.contains(sPort.getPortName())) {
                    String[] splitArray = sPort.getPortGroup().split(Constants.HYPEN);
                    String xBrick = splitArray[0];
                    String sc = splitArray[1];
                    // select port from unique X-brick/SC for this network
                    if (!networkToSelectedXbricks.get(networkURI).contains(xBrick)
                            && (xBricksToSelectedSCs.get(xBrick) == null || !xBricksToSelectedSCs.get(xBrick).contains(sc))) {
                        port = sPort;
                        addSCToXbrick(xBricksToSelectedSCs, xBrick, sc);
                        networkToSelectedXbricks.get(networkURI).add(xBrick);
                        break;
                    }
                }
            }
        }
        return port;
    }

    /**
     * Adds the selected Storage controller to X-brick in the xBricksToSelectedSCs map.
     */
    private void addSCToXbrick(Map<String, List<String>> xBricksToSelectedSCs, String xBrick, String storageController) {
        if (xBricksToSelectedSCs.get(xBrick) == null) {
            xBricksToSelectedSCs.put(xBrick, new ArrayList<String>());
        }
        if (!xBricksToSelectedSCs.get(xBrick).contains(storageController)) {
            xBricksToSelectedSCs.get(xBrick).add(storageController);
        }
    }

    /**
     * Checks if is all given ports have been processed or selected.
     */
    private boolean isAllPortsLooped(List<URI> orderedNetworks, Map<URI, List<StoragePort>> allocatablePorts, Set<String> usedPorts) {
        for (URI networkURI : orderedNetworks) {
            for (StoragePort port : allocatablePorts.get(networkURI)) {
                if (!usedPorts.contains(port.getPortName())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Order the networks from those with least ports to those with most ports.
     *
     * @param allocatablePorts -- Map of Network URI to list of ports
     * @return ordered list of Network URIs
     */
    private List<URI> orderNetworksByNumberOfPorts(Map<URI, List<StoragePort>> allocatablePorts) {
        List<URI> orderedNetworks = new ArrayList<URI>();

        Map<Integer, Set<URI>> numPortsToNetworkSet = new HashMap<Integer, Set<URI>>();
        int maxNumPorts = 0;
        for (URI networkURI : allocatablePorts.keySet()) {
            int numPorts = allocatablePorts.get(networkURI).size();
            if (numPortsToNetworkSet.get(numPorts) == null) {
                numPortsToNetworkSet.put(numPorts, new HashSet<URI>());
            }
            numPortsToNetworkSet.get(numPorts).add(networkURI);
            if (maxNumPorts < numPorts) {
                maxNumPorts = numPorts;
            }
        }

        for (int numPorts = 1; numPorts <= maxNumPorts; numPorts++) {
            Set<URI> networkURIs = numPortsToNetworkSet.get(numPorts);
            if (networkURIs == null) {
                continue;
            }
            for (URI networkURI : networkURIs) {
                orderedNetworks.add(networkURI);
            }
        }
        return orderedNetworks;
    }

    /**
     * Gets the number of X-bricks from the selected ports.
     *
     * @param useablePorts the port groups
     * @return the xbricks count
     */
    private int getXbricksCount(Map<URI, List<List<StoragePort>>> useablePorts) {
        Set<String> xBricks = new HashSet<String>();
        for (List<List<StoragePort>> portSet : useablePorts.values()) {
            for (List<StoragePort> ports : portSet) {
                for (StoragePort port : ports) {
                    xBricks.add(port.getPortGroup().split(Constants.HYPEN)[0]);
                }
            }
        }
        return xBricks.size();
    }

    private List<StoragePort> allocatePorts(StoragePortsAllocator allocator,
            List<StoragePort> candidatePorts, int portsRequested, NetworkLite net, URI varrayURI) {
        return VPlexBackEndOrchestratorUtil.allocatePorts(allocator, candidatePorts, portsRequested, net, varrayURI,
                simulation, _blockScheduler, _dbClient);
    }

    @Override
    public StringSetMap configureZoning(Map<URI, List<List<StoragePort>>> portGroup,
            Map<String, Map<URI, Set<Initiator>>> initiatorGroup, Map<URI, NetworkLite> networkMap, StoragePortsAssigner assigner) {

        StringSetMap zoningMap = new StringSetMap();
        // Set up a map to track port usage so that we can use all ports more or less equally.
        Map<StoragePort, Integer> portUsage = new HashMap<StoragePort, Integer>();
        // Iterate through each of the directors, matching each of its initiators
        // with one port. This will ensure not to violate four paths per director.

        // select number of paths per VPLEX director
        // if director count is less than X-bricks counts, choose only 2 initiators from each director
        // leaving other initiators for future scale of X-bricks
        int pathsPerDirector = 4;
        // TODO determine which of the Director-Xbrick combinations require lesser paths per director
        if (vplexDirectorCount < xtremIOXbricksCount) {
            pathsPerDirector = 2;
        }
        _log.info(String.format("VPLEX Directors: %s, X-bricks: %s, Number of paths per VPLEX Director: %s", vplexDirectorCount,
                xtremIOXbricksCount, pathsPerDirector));

        int directorNumber = 1;
        for (String director : initiatorGroup.keySet()) {
            int numberOfPaths = 0;
            for (URI networkURI : initiatorGroup.get(director).keySet()) {
                NetworkLite net = networkMap.get(networkURI);
                for (Initiator initiator : initiatorGroup.get(director).get(networkURI)) {
                    // If there are no ports on the initiators network, too bad...
                    if (portGroup.get(networkURI) == null) {
                        _log.info(String.format("%s -> no ports in network",
                                initiator.getInitiatorPort()));
                        continue;
                    }

                    List<StoragePort> portList = getStoragePortSetForDirector(portGroup.get(networkURI), directorNumber);
                    // find a port for the initiator
                    StoragePort storagePort = VPlexBackEndOrchestratorUtil.assignPortToInitiator(assigner,
                            portList, net, initiator, portUsage, null);
                    if (storagePort != null) {
                        _log.info(String.format("%s %s   %s -> %s  %s", director, net.getLabel(),
                                initiator.getInitiatorPort(), storagePort.getPortNetworkId(),
                                storagePort.getPortName()));
                        StringSet ports = new StringSet();
                        ports.add(storagePort.getId().toString());
                        zoningMap.put(initiator.getId().toString(), ports);
                        numberOfPaths++;
                    } else {
                        _log.info(String.format("A port could not be assigned for %s %s   %s", director, net.getLabel(),
                                initiator.getInitiatorPort()));
                    }
                    if (numberOfPaths >= pathsPerDirector) {
                        // TODO split initiators paths across networks depending on number of paths
                        break;
                    }
                }
                if (numberOfPaths >= pathsPerDirector) {
                    _log.info("Maximum paths per director ({}) reached for Director {}", numberOfPaths, director);
                    break;
                }
            }
            directorNumber++;
        }
        return zoningMap;
    }

    /**
     * Gets the storage port set for director.
     *
     * @param list the list
     * @param directorNumber the director number
     * @return the storage port set for director
     */
    private List<StoragePort> getStoragePortSetForDirector(List<List<StoragePort>> list, int directorNumber) {
        List<StoragePort> portsSetForDirector = null;
        Iterator<List<StoragePort>> itr = list.iterator();
        for (int i = 1; i <= directorNumber; i++) {
            if (!itr.hasNext()) {
                itr = list.iterator();
            }
            portsSetForDirector = itr.next();
        }
        return portsSetForDirector;
    }

    @Override
    public Method createOrAddVolumesToExportMaskMethod(URI arrayURI, URI exportGroupURI, URI exportMaskURI,
            Map<URI, Integer> volumeMap, TaskCompleter completer) {
        return new Workflow.Method("createOrAddVolumesToExportMask", arrayURI,
                exportGroupURI, exportMaskURI, volumeMap, completer);
    }

    @Override
    public void createOrAddVolumesToExportMask(URI arrayURI, URI exportGroupURI, URI exportMaskURI,
            Map<URI, Integer> volumeMap, TaskCompleter completer, String stepId) {
        try {
            WorkflowStepCompleter.stepExecuting(stepId);
            StorageSystem array = _dbClient.queryObject(StorageSystem.class, arrayURI);
            ExportMask exportMask = _dbClient.queryObject(ExportMask.class, exportMaskURI);

            // If the exportMask isn't found, or has been deleted, fail, ask user to retry.
            if (exportMask == null || exportMask.getInactive()) {
                _log.info(String.format("ExportMask %s deleted or inactive, failing", exportMaskURI));
                ServiceError svcerr = VPlexApiException.errors
                        .createBackendExportMaskDeleted(exportMaskURI.toString(), arrayURI.toString());
                WorkflowStepCompleter.stepFailed(stepId, svcerr);
                return;
            }

            // Protect concurrent operations by locking {host, array} dupple.
            // Lock will be released when workflow step completes.
            List<String> lockKeys = ControllerLockingUtil.getHostStorageLockKeys(
                    _dbClient, ExportGroupType.Host,
                    StringSetUtil.stringSetToUriList(exportMask.getInitiators()), arrayURI);
            getWorkflowService().acquireWorkflowStepLocks(stepId, lockKeys, LockTimeoutValue.get(LockType.VPLEX_BACKEND_EXPORT));

            // Refresh the ExportMask
            BlockStorageDevice device = _blockController.getDevice(array.getSystemType());

            if (!exportMask.hasAnyVolumes()) {
                // We are creating this ExportMask on the hardware! (Maybe not
                // the first time though...)
                // Fetch the Initiators
                List<Initiator> initiators = new ArrayList<Initiator>();
                for (String initiatorId : exportMask.getInitiators()) {
                    Initiator initiator = _dbClient.queryObject(Initiator.class,
                            URI.create(initiatorId));
                    if (initiator != null) {
                        initiators.add(initiator);
                    }
                }
                // Fetch the targets
                List<URI> targets = new ArrayList<URI>();
                for (String targetId : exportMask.getStoragePorts()) {
                    targets.add(URI.create(targetId));
                }
                if (volumeMap != null) {
                    for (URI volume : volumeMap.keySet()) {
                        exportMask.addVolume(volume, volumeMap.get(volume));
                    }
                }
                _dbClient.persistObject(exportMask);
                device.doExportGroupCreate(array, exportMask, volumeMap, initiators, targets,
                        completer);
            } else {
                device.doExportAddVolumes(array, exportMask, volumeMap, completer);
            }

        } catch (Exception ex) {
            _log.error("Failed to create or add volumes to export mask for vmax: ", ex);
            VPlexApiException vplexex = DeviceControllerExceptions.vplex
                    .addStepsForCreateVolumesFailed(ex);
            WorkflowStepCompleter.stepFailed(stepId, vplexex);
        }

    }

    @Override
    public Method deleteOrRemoveVolumesFromExportMaskMethod(URI arrayURI,
            URI exportGroupURI, URI exportMaskURI,
            List<URI> volumes, TaskCompleter completer) {
        return new Workflow.Method("deleteOrRemoveVolumesFromExportMask", arrayURI,
                exportGroupURI, exportMaskURI, volumes, completer);
    }

    @Override
    public void deleteOrRemoveVolumesFromExportMask(URI arrayURI, URI exportGroupURI, URI exportMaskURI,
            List<URI> volumes, TaskCompleter completer, String stepId) {
        try {
            WorkflowStepCompleter.stepExecuting(stepId);
            StorageSystem array = _dbClient.queryObject(StorageSystem.class, arrayURI);
            BlockStorageDevice device = _blockController.getDevice(array.getSystemType());
            ExportMask exportMask = _dbClient.queryObject(ExportMask.class, exportMaskURI);

            // If the exportMask isn't found, or has been deleted, nothing to do.
            if (exportMask == null || exportMask.getInactive()) {
                _log.info(String.format("ExportMask %s inactive, returning success", exportMaskURI));
                WorkflowStepCompleter.stepSucceded(stepId);
                return;
            }

            // Protect concurrent operations by locking {host, array} dupple.
            // Lock will be released when workflow step completes.
            List<String> lockKeys = ControllerLockingUtil.getHostStorageLockKeys(
                    _dbClient, ExportGroupType.Host,
                    StringSetUtil.stringSetToUriList(exportMask.getInitiators()), arrayURI);
            getWorkflowService().acquireWorkflowStepLocks(stepId, lockKeys, LockTimeoutValue.get(LockType.VPLEX_BACKEND_EXPORT));

            // Make sure the completer will complete the workflow. This happens
            // on rollback case.
            if (!completer.getOpId().equals(stepId)) {
                completer.setOpId(stepId);
            }

            Set<String> remainingVolumes = new HashSet<String>();
            if (exportMask.getVolumes() != null) {
                remainingVolumes.addAll(exportMask.getVolumes().keySet());
            }
            for (URI volume : volumes) {
                remainingVolumes.remove(volume.toString());
            }
            // If so, delete the ExportMask.
            if (remainingVolumes.isEmpty()
                    && (exportMask.getExistingVolumes() == null || exportMask.getExistingVolumes()
                            .isEmpty())) {
                device.doExportGroupDelete(array, exportMask, completer);
            } else {
                device.doExportRemoveVolumes(array, exportMask, volumes, completer);
            }
        } catch (Exception ex) {
            _log.error("Failed to delete or remove volumes to export mask for vmax: ", ex);
            VPlexApiException vplexex = DeviceControllerExceptions.vplex
                    .addStepsForCreateVolumesFailed(ex);
            WorkflowStepCompleter.stepFailed(stepId, vplexex);
        }
    }

    public void setSimulation(boolean simulation) {
        this.simulation = simulation;
    }

    public WorkflowService getWorkflowService() {
        return _workflowService;
    }

    @Override
    public void setWorkflowService(WorkflowService _workflowService) {
        this._workflowService = _workflowService;
    }
}
