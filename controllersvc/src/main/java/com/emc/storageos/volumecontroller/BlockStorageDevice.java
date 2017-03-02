/*
 * Copyright (c) 2008-2012 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.volumecontroller.impl.block.ExportMaskPolicy;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CleanupMetaVolumeMembersCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeCreateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeExpandCompleter;
import com.emc.storageos.volumecontroller.impl.smis.MetaVolumeRecommendation;
import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;

/**
 * Main interface for block storage device specific implementations.
 */
public interface BlockStorageDevice {
    /**
     * Creates one or more volumes.
     * 
     * @param storage
     *            Storage system on which the operation is performed.
     * @param storagePool
     *            Storage pool in which the volumes are created.
     * @param opId
     *            The unique operation id.
     * @param volumes
     *            The volumes to be created.
     * @param capabilities
     *            wrapper for vpool capability values
     * @param taskCompleter
     *            The completer invoked when the operation completes.
     * @throws DeviceControllerException
     */
    public void doCreateVolumes(StorageSystem storage, StoragePool storagePool, String opId,
            List<Volume> volumes, VirtualPoolCapabilityValuesWrapper capabilities,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Modifies one or more volumes.
     * 
     * @param storage
     *            Storage system on which the operation is performed.
     * @param storagePool
     *            Storage pool in which the volumes are created.
     * @param opId
     *            The unique operation id.
     * @param volumes
     *            The volumes to be created.
     * @param taskCompleter
     *            The completer invoked when the operation completes.
     * @throws DeviceControllerException
     */
    public void doModifyVolumes(StorageSystem storage, StoragePool storagePool, String opId,
            List<Volume> volumes, TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Creates meta volumes.
     * 
     * @param storage
     *            Storage system on which the operation is performed.
     * @param storagePool
     *            Storage pool in which the volumes are created.
     * @param volumes
     *            The volumes to be created.
     * @param capabilities
     *            wrapper for vpool capability values
     * @param recommendation
     *            recommendation for meta volume components
     * @param completer
     *            The completer invoked when the operation completes.
     * @throws DeviceControllerException
     */
    public void doCreateMetaVolumes(StorageSystem storage, StoragePool storagePool, List<Volume> volumes,
            VirtualPoolCapabilityValuesWrapper capabilities,
            MetaVolumeRecommendation recommendation, TaskCompleter completer)
            throws DeviceControllerException;

    /**
     * Creates meta volume.
     * 
     * @param storage
     *            Storage system on which the operation is performed.
     * @param storagePool
     *            Storage pool in which the volumes are created.
     * @param volume
     *            The volume to be created.
     * @param capabilities
     *            wrapper for vpool capability values
     * @param recommendation
     *            recommendation for meta volume components
     * @param completer
     *            The completer invoked when the operation completes.
     * @throws DeviceControllerException
     */
    public void doCreateMetaVolume(StorageSystem storage, StoragePool storagePool, Volume volume,
            VirtualPoolCapabilityValuesWrapper capabilities,
            MetaVolumeRecommendation recommendation, VolumeCreateCompleter completer)
            throws DeviceControllerException;

    /**
     * @param storage
     *            storage object operation is being performed on
     * @return
     * @throws DeviceControllerException
     */
    public void doExpandVolume(StorageSystem storage, StoragePool pool, Volume volume, Long size,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * @param storageSystem
     * @param storagePool
     * @param volume
     * @param size
     * @param recommendation
     * @param volumeCompleter
     * @throws DeviceControllerException
     */
    public void doExpandAsMetaVolume(StorageSystem storageSystem, StoragePool storagePool,
            Volume volume, long size, MetaVolumeRecommendation recommendation,
            VolumeExpandCompleter volumeCompleter) throws DeviceControllerException;

    /**
     * Deletes one or more volumes on the same storage system.
     * 
     * @param storageSystem
     *            Storage system on which the operation is performed.
     * @param opId
     *            The unique operation id.
     * @param volumes
     *            The volumes to be deleted.
     * @param completer
     *            The completer invoked when the operation completes.
     * @throws DeviceControllerException
     */
    public void doDeleteVolumes(StorageSystem storageSystem, String opId, List<Volume> volumes,
            TaskCompleter completer) throws DeviceControllerException;

    /**
     * Export one or more volumes for the ExportMask. The volumeToExports parameter has all the
     * information required to do the add volumes operation.
     * 
     * @param storage
     *            URI of storage controller.
     * @param exportMask
     *            URI of ExportMask
     * @param volumeMap
     *            List of volumes to be part of the export group
     * @param initiators
     *            List of initiators to be added to the export group
     * @param targets
     *            List of targets to be added to the export group
     * @param taskCompleter
     *            Operation ID
     * @throws DeviceControllerException
     */
    public void doExportCreate(StorageSystem storage, ExportMask exportMask,
            Map<URI, Integer> volumeMap, List<Initiator> initiators, List<URI> targets,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Delete an export mask and all associated mappings that exists at the storage systems
     * 
     * @param storage
     * @param exportMask
     * @param volumeURIs volumes that are impacted by this operation
     * @param initiatorURIs initiators that are impacted by this operation
     * @param taskCompleter
     * @return
     * @throws DeviceControllerException
     */
    public void doExportDelete(StorageSystem storage, ExportMask exportMask,
            List<URI> volumeURIs, List<URI> initiatorURIs, TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Add one or more volumes to the ExportMask.
     * 
     * @param storage
     * @param exportMask
     * @param volume
     * @param lun
     * @param initiators 
     * @param taskCompleter
     * @return
     * @throws DeviceControllerException
     */
    public void doExportAddVolume(StorageSystem storage, ExportMask exportMask, URI volume,
            Integer lun, List<Initiator> initiators, TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Add one or more volumes to the ExportMask.
     * 
     * @param storage
     * @param exportMask
     * @param initiators 
     * @param volumes
     * @param taskCompleter
     * @return
     * @throws DeviceControllerException
     */
    public void doExportAddVolumes(StorageSystem storage, ExportMask exportMask,
            List<Initiator> initiators, Map<URI, Integer> volumes, TaskCompleter taskCompleter)
            throws DeviceControllerException;

    /**
     * Remove one or more volumes for the ExportMask. The volumeToExports parameter has all the
     * information required to do the remove volumes operation.
     * 
     * @param storage
     *            URI of storage controller
     * @param exportMask
     *            URI of ExportMask at the storage device
     * @param volume
     *            Volume to remove from export mask
     * @param initiators
     *            Initiators that will lose access to this volume
     * @param taskCompleter
     *            The task completer
     * @throws DeviceControllerException
     */
    public void doExportRemoveVolume(StorageSystem storage, ExportMask exportMask, URI volume,
            List<Initiator> initiators, TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Remove one or more volumes for the ExportMask. The volumeToExports parameter has all the
     * information required to do the remove volumes operation.
     * 
     * @param storage
     *            URI of storage controller
     * @param exportMask
     *            URI of ExportMask at the storage device
     * @param volumes
     *            Volumes to remove from the export mask
     * @param initiators
     *            Initiators that will lose access to these volumes
     * @param taskCompleter
     *            The task completer
     * @throws DeviceControllerException
     */
    public void doExportRemoveVolumes(StorageSystem storage, ExportMask exportMask,
            List<URI> volumes, List<Initiator> initiators, TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Add one or more initiators to the export group.
     * 
     * @param storage
     *            URI of storage controller.
     * @param exportMask
     *            URI of ExportMask at the storage device
     * @param volumeURIs
     *            URIs of the volumes that are impacted by the operation
     * @param initiator
     *            Initiator to be added.
     * @param targets
     *            Targets to be added for the initiators
     * @param taskCompleter
     *            The task completer
     * @throws DeviceControllerException
     */
    public void doExportAddInitiator(StorageSystem storage, ExportMask exportMask,
            List<URI> volumeURIs, Initiator initiator, List<URI> targets, TaskCompleter taskCompleter)
            throws DeviceControllerException;

    /**
     * Add one or more initiators to the export group.
     * 
     * @param storage
     *            URI of storage controller
     * @param exportMask
     *            URI of ExportMask at the storage device
     * @param volumeURIs
     *            URIs of the volumes that are impacted by the operation
     * @param initiators
     *            Initiators to be added
     * @param targets
     *            Targets to be added for the initiators
     * @param taskCompleter
     *            The task completer
     * @throws DeviceControllerException
     */
    public void doExportAddInitiators(StorageSystem storage, ExportMask exportMask,
            List<URI> volumeURIs, List<Initiator> initiators, List<URI> targets, TaskCompleter taskCompleter)
            throws DeviceControllerException;

    /**
     * Remove one or more initiators from the export group.
     * 
     * @param storage
     *            URI of storage controller.
     * @param exportMask
     *            URI of ExportMask at the storage device
     * @param volumes
     *            Volumes that will no longer be visible to the removed initiators
     * @param initiator
     *            Initiator to be removed.
     * @param targets
     *            Targets to be removed
     * @param taskCompleter
     *            The task completer
     * @throws DeviceControllerException
     */
    public void doExportRemoveInitiator(StorageSystem storage, ExportMask exportMask,
            List<URI> volumes, Initiator initiator, List<URI> targets, TaskCompleter taskCompleter)
            throws DeviceControllerException;

    /**
     * Remove one or more initiators from the export group.
     * 
     * @param storage
     *            URI of storage controller
     * @param exportMask
     *            URI of ExportMask at the storage device
     * @param volumes
     *            Volumes that will no longer be visible to the removed initiators
     * @param targets
     *            Targets to be removed
     * @param taskCompleter
     *            The task completer
     * @param initiator
     *            Initiators to be removed
     * @throws DeviceControllerException
     */
    public void doExportRemoveInitiators(StorageSystem storage, ExportMask exportMask,
            List<URI> volumes, List<Initiator> initiators, List<URI> targets, TaskCompleter taskCompleter)
            throws DeviceControllerException;

    /**
     * Create a single snapshot, using CreateElementReplica.
     * 
     * @param storage
     * @param snapshotList
     * @param createInactive
     * @param readOnly
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doCreateSingleSnapshot(StorageSystem storage, List<URI> snapshotList,
            Boolean createInactive, Boolean readOnly, TaskCompleter taskCompleter)
            throws DeviceControllerException;

    /**
     * @param storage
     * @param snapshotList
     * @param createInactive
     * @param readOnly
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doCreateSnapshot(StorageSystem storage, List<URI> snapshotList,
            Boolean createInactive, Boolean readOnly, TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Activate a snapshot. Activation means that the source and target synchronization will be
     * established.
     * 
     * @param storage
     * @param snapshotList
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doActivateSnapshot(StorageSystem storage, List<URI> snapshotList,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * @param storage
     * @param snapshot
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doDeleteSnapshot(StorageSystem storage, URI snapshot, TaskCompleter taskCompleter)
            throws DeviceControllerException;

    /**
     * Delete a single snapshot.
     * 
     * @param storage
     * @param snapshot
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    void doDeleteSelectedSnapshot(StorageSystem storage, URI snapshot,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    public void doRestoreFromSnapshot(StorageSystem storage, URI volume, URI snapshot,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Resynchronize snapsht
     * 
     * @param storage Storage system the snapshot created on
     * @param volume The URI of the snapshot's parent volume
     * @param snapshot The URI of the snapshot to be resynchronized
     * @param taskCompleter The task completer
     * @throws DeviceControllerException
     */
    public void doResyncSnapshot(StorageSystem storage, URI volume, URI snapshot,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Create a mirror for a volume
     * 
     * @param storage
     * @param mirror
     * @param createInactive
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doCreateMirror(StorageSystem storage, URI mirror, Boolean createInactive,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Fracture a mirror or mirrors for a volume or volumes.
     * Create group mirrors for volumes in a CG.
     * 
     * @param storage
     * @param mirrorList
     * @param createInactive
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doCreateGroupMirrors(StorageSystem storage, List<URI> mirrorList, Boolean createInactive,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Fracture a mirror or mirrors for a volume or volumes.
     * 
     * @param storage
     * @param mirror
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doFractureMirror(StorageSystem storage, URI mirror, Boolean sync,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Fracture group mirrors for volumes in a CG.
     * 
     * @param storage
     * @param mirrorList
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doFractureGroupMirrors(StorageSystem storage, List<URI> mirrorList, Boolean sync,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Detach a mirror or mirrors for a volume or volumes.
     * 
     * @param storage
     * @param mirror
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doDetachMirror(StorageSystem storage, URI mirror, TaskCompleter taskCompleter)
            throws DeviceControllerException;

    /**
     * Detach group mirrors for volumes in a CG.
     * 
     * @param storage
     * @param mirrorList
     * @param deleteGroup
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doDetachGroupMirrors(StorageSystem storage, List<URI> mirrorList, Boolean deleteGroup, TaskCompleter taskCompleter)
            throws DeviceControllerException;

    /**
     * Resumes one or more mirrors.
     * 
     * @param storage
     * @param mirror
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doResumeNativeContinuousCopy(StorageSystem storage, URI mirror,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Resumes group mirrors for volumes in a CG.
     * 
     * @param storage
     * @param mirrorList
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doResumeGroupNativeContinuousCopies(StorageSystem storage, List<URI> mirrorList,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Delete a mirror or mirrors for a volume or volumes.
     * 
     * @param storage
     * @param mirror
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doDeleteMirror(StorageSystem storage, URI mirror, TaskCompleter taskCompleter)
            throws DeviceControllerException;

    /**
     * Delete group mirrors for volumes in a CG.
     * 
     * @param storage
     * @param mirrorList
     * @param taskCompleter
     * @throws DeviceControllerException
     */
    public void doDeleteGroupMirrors(StorageSystem storage, List<URI> mirrorList, TaskCompleter taskCompleter)
            throws DeviceControllerException;

    /**
     * Creates group relation between volume group and full copy group.
     * 
     * @param storage the storage
     * @param sourceVolume the source volume
     * @param fullCopy the full copy
     * @param taskCompleter the task completer
     * @throws DeviceControllerException the device controller exception
     */
    public void doEstablishVolumeFullCopyGroupRelation(StorageSystem storage, URI sourceVolume,
            URI fullCopy, TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Create a clone of an existing source volume.
     * 
     * @param storageSystem
     * @param sourceVolume
     * @param cloneVolume
     * @param createInactive
     * @param taskCompleter
     */
    public void doCreateClone(StorageSystem storageSystem, URI sourceVolume, URI cloneVolume,
            Boolean createInactive, TaskCompleter taskCompleter);

    /**
     * Create a clone for a consistency group.
     * 
     * @param storageSystem
     * @param cloneVolumes
     * @param createInactive
     * @param taskCompleter
     */
    public void doCreateGroupClone(StorageSystem storageSystem, List<URI> cloneVolumes,
            Boolean createInactive, TaskCompleter taskCompleter);

    /**
     * Detach a cloned volume from its source volume.
     * 
     * @param storage
     * @param cloneVolume
     * @param taskCompleter
     */
    public void doDetachClone(StorageSystem storage, URI cloneVolume, TaskCompleter taskCompleter);

    /**
     * Detach a cloned volume in a consistency group.
     * 
     * @param storage
     * @param cloneVolume
     * @param taskCompleter
     */
    public void doDetachGroupClone(StorageSystem storage, List<URI> cloneVolume, TaskCompleter taskCompleter);

    /**
     * Restore from a clone.
     * 
     * @param storageSystem
     * @param cloneVolume
     * @param taskCompleter
     */
    public void doRestoreFromClone(StorageSystem storageSystem, URI cloneVolume, TaskCompleter taskCompleter);

    /**
     * Restore from a clone in a consistency group.
     * 
     * @param storageSystem
     * @param cloneVolume
     * @param taskCompleter
     */
    public void doRestoreFromGroupClone(StorageSystem storageSystem, List<URI> cloneVolume,
            TaskCompleter taskCompleter);

    /**
     * Create a consistency group in the given StorageSystem
     * 
     * @param storage
     * @param consistencyGroup
     * @param replicationGroupName
     * @param taskCompleter
     */
    public void doCreateConsistencyGroup(StorageSystem storage, URI consistencyGroup, String replicationGroupName,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Delete a consistency group in the given StorageSystem
     * 
     * @param storage
     * @param consistencyGroup
     * @param replicationGroupName name of the replication group to be deleted
     * @param keepRGName Boolean if true, ViPR will keep group name for CG
     * @param markInactive
     * @param taskCompleter
     */
    public void doDeleteConsistencyGroup(StorageSystem storage, URI consistencyGroup,
            String replicationGroupName, Boolean keepRGName, Boolean markInactive, TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Connect the device - called when a new device is added
     * 
     * @param storage
     *            storage device object
     * @return command result object
     * @throws ControllerException
     */
    public void doConnect(StorageSystem storage);

    /**
     * Disconnect the device - called when a device is being removed
     * 
     * @param storage
     *            storage device object
     * @return command result object
     * @throws ControllerException
     */
    public void doDisconnect(StorageSystem storage);

    /**
     * Add a new Storage System to an SMIS Provider.
     * 
     * @param storage
     *            storage device object
     * @throws DeviceControllerException
     */
    public String doAddStorageSystem(StorageSystem storage) throws DeviceControllerException;

    /**
     * Remove a Storage System from an SMIS Provider.
     * 
     * @param storage
     *            storage device object
     * @throws DeviceControllerException
     */
    public void doRemoveStorageSystem(StorageSystem storage) throws DeviceControllerException;

    /**
     * Implementation should attach an inactive CG blocksnapshot set to target devices.
     * 
     * @param storage
     *            [required] - StorageSystem object representing the array
     * @param snapshotList
     * @param taskCompleter
     *            - TaskCompleter object used for the updating operation status. @throws
     *            DeviceControllerException
     */
    public void doCopySnapshotsToTarget(StorageSystem storage, List<URI> snapshotList,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * This call can be used to look up the passed in initiator/port names and find (if any) to
     * which export masks they belong on the 'storage' array.
     * 
     * @param storage
     *            [in] - StorageSystem object representing the array
     * @param initiatorNames
     *            [in] - Port identifiers (WWPN or iSCSI name)
     * @param mustHaveAllPorts
     *            [in] Indicates if true, *all* the passed in initiators have to be in the existing
     *            matching mask. If false, a mask with *any* of the specified initiators will be
     *            considered a hit.
     * @return Map of port name to Set of ExportMask URIs
     * @throws DeviceControllerException TODO
     */
    public Map<String, Set<URI>> findExportMasks(StorageSystem storage,
            List<String> initiatorNames, boolean mustHaveAllPorts) throws DeviceControllerException;

    /**
     * For the given list of initiators, go to the Storage Array and get the list of HLUs that the volumes are assigned with.
     *
     * @param storage the storage system
     * @param initiatorNames the initiator names
     * @param mustHaveAllPorts
     *            If true, *all* the passed in initiators have to be in the existing matching mask.
     *            If false, a mask with *any* of the specified initiators will be considered a hit.
     * @return the HLUs for the given initiators
     */
    public Set<Integer> findHLUsForInitiators(StorageSystem storage, List<String> initiatorNames, boolean mustHaveAllPorts);

    /**
     * This call will be used to update the ExportMask with the latest data from the array.
     * 
     * @param storage
     *            [in] - StorageSystem object representing the array
     * @param mask
     *            [in] - ExportMask object to be refreshed
     * @return instance of ExportMask object that has been refreshed with data from the array.
     * @throws DeviceControllerException TODO
     */
    public ExportMask refreshExportMask(StorageSystem storage, ExportMask mask) throws DeviceControllerException;

    /**
     * Activates a full copy volume.
     * 
     * @param storageSystem
     * @param fullCopy
     * @param completer
     */
    public void doActivateFullCopy(StorageSystem storageSystem, URI fullCopy,
            TaskCompleter completer);

    /**
     * Activates a full copy volume in a consistency group.
     * 
     * @param storageSystem
     * @param fullCopy
     * @param completer
     */
    public void doActivateGroupFullCopy(StorageSystem storageSystem, List<URI> fullCopy,
            TaskCompleter completer);

    /**
     * Cleanups meta member volumes of meta volume in array.
     * 
     * @param storageSystem
     * @param volume
     * @param cleanupCompleter
     * @throws DeviceControllerException
     */
    public void doCleanupMetaMembers(StorageSystem storageSystem, Volume volume,
            CleanupMetaVolumeMembersCompleter cleanupCompleter) throws DeviceControllerException;

    /**
     * Gets synchronization details between source and target block objects.
     * 
     * @param storage
     * @param source
     * @param target
     * @return percent of synchronization
     */
    public Integer checkSyncProgress(URI storage, URI source, URI target);

    /**
     * Poll the synchronization state of a replication relationship. Mark the task completer as
     * ready when the synchronized state has been reached.
     * 
     * @param storageObj
     * @param target
     * @param completer
     */
    public void doWaitForSynchronized(Class<? extends BlockObject> clazz, StorageSystem storageObj,
            URI target, TaskCompleter completer);

    /**
     * Poll the synchronization state of a group replication relationship. Mark the task completer as
     * ready when the synchronized state has been reached.
     * 
     * @param storageObj
     * @param target
     * @param completer
     */
    public void doWaitForGroupSynchronized(StorageSystem storageObj,
            List<URI> target, TaskCompleter completer);

    public void doAddToConsistencyGroup(StorageSystem storage, URI consistencyGroupId, String replicationGroupName,
            List<URI> blockObjects, TaskCompleter taskCompleter) throws DeviceControllerException;

    public void doRemoveFromConsistencyGroup(StorageSystem storage, URI consistencyGroupId,
            List<URI> blockObjects, TaskCompleter taskCompleter) throws DeviceControllerException;

    public void doAddToReplicationGroup(StorageSystem storage, URI consistencyGroupId, String replicationGroupName,
            List<URI> blockObjects, TaskCompleter taskCompleter) throws DeviceControllerException;

    public void doRemoveFromReplicationGroup(StorageSystem storage, URI consistencyGroupId, String replicationGroupName,
            List<URI> blockObjects, TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Validate storage provider connection.
     * 
     * @param ipAddress the ip address
     * @param portNumber the port number
     * @return true, if successful
     */
    public boolean validateStorageProviderConnection(String ipAddress, Integer portNumber);

    /**
     * Updates Auto-tiering policy and/or host io limits (only apply for VMAX. Other will be ignore)
     * 
     * @param storage the storage system
     * @param exportMask the export mask
     * @param volumeURIs the volume uris
     * @param newVpool the new vPool where policy name and limits settings can be obtained
     * @param rollback boolean to know if it is called as a roll back step from workflow.
     * @param taskCompleter the task completer
     * @throws Exception the exception
     */
    public void updatePolicyAndLimits(StorageSystem storage, ExportMask exportMask,
            List<URI> volumeURIs, VirtualPool newVpool, boolean rollback,
            TaskCompleter taskCompleter) throws Exception;

    /**
     * Retrieves the export mask policies, in the case where this is one.
     * 
     * @param storage storage system
     * @param mask the export mask
     * @return export mask policy
     */
    public ExportMaskPolicy getExportMaskPolicy(StorageSystem storage, ExportMask mask);

    /**
     * Terminate any restore sessions for the given source volume.
     * 
     * @param storageDevice the storage system
     * @param source the source volume
     * @param snapshot the restored snapshot
     * @param completer the task completer
     */
    public void doTerminateAnyRestoreSessions(StorageSystem storageDevice, URI source, BlockObject snapshot,
            TaskCompleter completer) throws Exception;

    /**
     * Creates group relation between volume group and mirror group.
     * 
     * @param storage the storage
     * @param sourceVolume the source volume
     * @param mirror the mirror
     * @param taskCompleter the task completer
     * @throws DeviceControllerException the device controller exception
     */
    public void doEstablishVolumeNativeContinuousCopyGroupRelation(StorageSystem storage, URI sourceVolume,
            URI mirror, TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Creates group relation between volume group and snapshot group.
     * 
     * @param storage the storage
     * @param sourceVolume the source volume
     * @param snapshot the snapshot
     * @param taskCompleter the task completer
     * @throws DeviceControllerException the device controller exception
     */
    public void doEstablishVolumeSnapshotGroupRelation(StorageSystem storage, URI sourceVolume,
            URI snapshot, TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * For mirrors associated with SRDF volumes, remove the mirrors
     * from device masking group equivalent to its Replication group.
     * 
     * @param system the system
     * @param mirrors the mirror list
     * @param completer the completer
     */
    public void doRemoveMirrorFromDeviceMaskingGroup(StorageSystem system, List<URI> mirrors, TaskCompleter completer);

    /**
     * Fracture clone.
     * 
     * @param storageDevice the storage system
     * @param source the source volume
     * @param clone the restored clone
     * @param completer the task completer
     */
    public void doFractureClone(StorageSystem storageDevice, URI source, URI clone,
            TaskCompleter completer) throws Exception;

    /**
     * Fracture group clone.
     * 
     * @param storageDevice the storage system
     * @param source the source volume
     * @param clone the restored clone
     * @param completer the task completer
     */
    public void doFractureGroupClone(StorageSystem storageDevice, List<URI> clone,
            TaskCompleter completer) throws Exception;

    /**
     * Resync clone.
     * 
     * @param storageDevice the storage system
     * @param source the source volume
     * @param clone the restored clone
     * @param completer the task completer
     */
    public void doResyncClone(StorageSystem storageDevice, URI clone,
            TaskCompleter completer) throws Exception;

    /**
     * Resync clone.
     * 
     * @param storageDevice the storage system
     * @param source the source volume
     * @param clone the restored clone
     * @param completer the task completer
     */
    public void doResyncGroupClone(StorageSystem storageDevice, List<URI> clone,
            TaskCompleter completer) throws Exception;

    /**
     * Create list replica.
     * 
     * @param storage the storage system
     * @param replicaList the replicas
     * @param createInactive
     * @param taskCompleter the task completer
     * @throws Exception
     */
    public void doCreateListReplica(StorageSystem storage, List<URI> replicaList, Boolean createInactive, TaskCompleter taskCompleter)
            throws Exception;

    /**
     * Detach list replica.
     * 
     * @param storage the storage system
     * @param replicaList the replicas
     * @param taskCompleter the task completer
     * @throws Exception
     */
    public void doDetachListReplica(StorageSystem storage, List<URI> replicaList, TaskCompleter taskCompleter) throws Exception;

    /**
     * Fracture list replica.
     *
     * @param storage the storage system
     * @param replicaList the replicas
     * @param sync
     * @param taskCompleter the task completer
     * @throws Exception
     */
    public void doFractureListReplica(StorageSystem storage, List<URI> replicaList, Boolean sync, TaskCompleter taskCompleter) throws Exception;

    /**
     * Delete list replica.
     *
     * @param storage the storage system
     * @param replicaList the replicas
     * @param taskCompleter the task completer
     * @throws Exception
     */
    public void doDeleteListReplica(StorageSystem storage, List<URI> replicaList, TaskCompleter taskCompleter) throws Exception;

    /*
     * For the given ExportMask, go to the StorageArray and get a mapping of volumes to their HLUs
     * 
     * @param storage the storage system
     * 
     * @param exportMask the ExportMask that represents the masking component of the array
     * 
     * @return The BlockObject URI to HLU mapping for the ExportMask
     */
    public Map<URI, Integer> getExportMaskHLUs(StorageSystem storage, ExportMask exportMask);

    /**
     * Untags one or more volumes on the same storage system.
     * 
     * @param storageSystem
     *            Storage system on which the operation is performed.
     * @param opId
     *            The unique operation id.
     * @param volumes
     *            The volumes to be untagged.
     * @param taskCompleter
     *            The completer invoked when the operation completes.
     * @throws DeviceControllerException
     */
    public void doUntagVolumes(StorageSystem storageSystem, String opId, List<Volume> volumes,
            TaskCompleter taskCompleter) throws DeviceControllerException;

    /**
     * Creates new array snapshots on the passed storage system.
     * 
     * @param system A reference to the storage system.
     * @param snapSessionURI The URIs of the BlockSnapshotSession instances in ViPR
     *            that will represent these array snapshots.
     * @param groupName The group name when creating a group session.
     * @param completer A reference to the task completer.
     * 
     * @throws DeviceControllerException
     */
    public void doCreateSnapshotSession(StorageSystem system, URI snapSessionURI, String groupName, TaskCompleter completer)
            throws DeviceControllerException;

    /**
     * Creates a new target volume and links it to an array snapshot on the passed storage system.
     * 
     * @param system A reference to the storage system.
     * @param snapSessionURI The URI of the BlockSnapshotSession instance in ViPR
     *            that represents the array snapshot.
     * @param snapshotURI The URI of the BlockSnapshot instance in ViPR that will represent
     *            the new target volume.
     * @param copyMode The copy mode in which the target is linked to the snapshot.
     * @param targetExists true if the target exists, false if a new one needs to be created.
     * @param completer A reference to the task completer.
     * 
     * @throws DeviceControllerException
     */
    public void doLinkBlockSnapshotSessionTarget(StorageSystem system, URI snapSessionURI, URI snapshotURI,
            String copyMode, Boolean targetExists, TaskCompleter completer) throws DeviceControllerException;

    /**
     * Creates a new target volume group and links it to an array snapshot on the passed storage system.
     * 
     * @param system A reference to the storage system.
     * @param snapshotSessionURI
     * @param snapSessionSnapshotURIs Map of BlockSnapshotSession URI's to their BlockSnapshot instance URI,
     *            representing the linked target.
     * @param copyMode The copy mode in which the target is linked to the snapshot.
     * @param targetsExist true if the target exists, false if a new one needs to be created.
     * @param completer A reference to the task completer.
     * @throws DeviceControllerException
     */
    public void doLinkBlockSnapshotSessionTargetGroup(StorageSystem system, URI snapshotSessionURI, List<URI> snapSessionSnapshotURIs,
            String copyMode, Boolean targetsExist, TaskCompleter completer)
            throws DeviceControllerException;

    /**
     * Re-links a target volume to an array snapshot on the passed storage system.
     * 
     * @param system A reference to the storage system.
     * @param tgtSnapSessionURI The URI of the BlockSnapshotSession instance in ViPR
     *            that represents the target array snapshot.
     * @param snapshotURI The URI of the BlockSnapshot instance in ViPR that represents
     *            the target volume to be re-linked.
     * @param completer A reference to the task completer.
     * 
     * @throws DeviceControllerException
     */
    public void doRelinkBlockSnapshotSessionTarget(StorageSystem system, URI tgtSnapSessionURI, URI snapshotURI,
            TaskCompleter completer) throws DeviceControllerException;

    /**
     * Creates a new target volume and links it to an array snapshot on the passed storage system.
     * 
     * @param system A reference to the storage system.
     * @param snapSessionURI The URI of the BlockSnapshotSession instance in ViPR
     *            that represents the array snapshot.
     * @param snapshotURI The URI of the BlockSnapshot instance in ViPR that represents
     *            the target volume.
     * @param deleteTarget True if the target should also be deleted.
     * @param completer A reference to the task completer.
     * 
     * @throws DeviceControllerException
     */
    public void doUnlinkBlockSnapshotSessionTarget(StorageSystem system, URI snapSessionURI, URI snapshotURI,
            Boolean deleteTarget, TaskCompleter completer) throws DeviceControllerException;

    /**
     * Restores the data on a snapshot session to its source.
     * 
     * @param system A reference to the storage system.
     * @param snapSessionURI The URI of the BlockSnapshotSession instance in ViPR
     *            that represents the array snapshot.
     * @param completer A reference to the task completer.
     * 
     * @throws DeviceControllerException
     */
    public void doRestoreBlockSnapshotSession(StorageSystem system, URI snapSessionURI, TaskCompleter completer)
            throws DeviceControllerException;

    /**
     * Deletes the snapshot session.
     * 
     * @param system A reference to the storage system.
     * @param snapSessionURI The URI of the BlockSnapshotSession instance in ViPR
     *            that represents the array snapshot.
     * @param groupName The name of the group when deleting a group snapshot session.
     * @param completer A reference to the task completer.
     * 
     * @throws DeviceControllerException
     */
    public void doDeleteBlockSnapshotSession(StorageSystem system, URI snapSessionURI, String groupName, TaskCompleter completer)
            throws DeviceControllerException;

    void doAddSnapshotSessionsToConsistencyGroup(StorageSystem storageSystem, URI consistencyGroup, List<URI> addVolumesList, TaskCompleter taskCompleter);

    /**
     * Delete a replica replication group in the given StorageSystem
     * 
     * @param storage
     * @param consistencyGroup
     * @param replicationGroupName name of the replication group to be deleted
     * @param keepRGName Boolean if true, ViPR will keep group name for CG
     * @param markInactive
     * @param sourceReplicationGroup name of the source replication group
     * @param taskCompleter
     */
    public void doDeleteConsistencyGroup(StorageSystem storage, URI consistencyGroup,
            String replicationGroupName, Boolean keepRGName, Boolean markInactive, 
            String sourceReplicationGroup, TaskCompleter taskCompleter) throws DeviceControllerException;
    
    /**
     * Finds the HLUs for a Host or Hosts within Cluster.
     * 
     * @param storage StorageSystem on which HLU needs to be determined
     * @param initiatorURIs List of Initiator URIs. Cluster export will have more than one Initiators from different hosts
     * @return Map of Initiators to its Volume HLUs
     * @throws DeviceControllerException If error occurs during the processing
     */
    public Map<URI, List<Integer>> doFindHostHLUs(StorageSystem storage, Collection<URI> initiatorURIs) throws DeviceControllerException;
    /**
     * Set an Alias for the supplied initiator on a given Storage System
     * 
     * @param storage
     * @param initiator
     * @param initiatorAlias - User friendly name for the Initiator on the Storage System
     */
    public void doInitiatorAliasSet(StorageSystem storage, Initiator initiator, String initiatorAlias) throws Exception;

    /**
     * Get the Alias for an initiator on the supplied Storage System if Set
     * 
     * @param storage
     * @param initiator
     */
    public String doInitiatorAliasGet(StorageSystem storage, Initiator initiator) throws Exception;
    
    /**
     * Add paths to export mask
     * 
     * @param storage Storage system
     * @param exportMask Export mask 
     * @param addedPaths - paths to be added
     * @param taskCompleter - task completer
     * @throws DeviceControllerException
     */
    public void doExportAddPaths(StorageSystem storage, URI exportMask, Map<URI, List<URI>>addedPaths, 
            TaskCompleter taskCompleter) throws DeviceControllerException;
    
    /**
     * Remove paths from export mask
     * 
     * @param storage - Storage system
     * @param exportMask - Export mask 
     * @param adjustedPaths - new and/or retained paths in the export mask
     * @param removedPaths - paths to be removed
     * @param taskCompleter - task completer
     * @throws DeviceControllerException
     */
    public void doExportRemovePaths(StorageSystem storage, URI exportMask, Map<URI, List<URI>> adjustedPaths, 
            Map<URI, List<URI>>removedPaths, TaskCompleter taskCompleter) throws DeviceControllerException;

}
