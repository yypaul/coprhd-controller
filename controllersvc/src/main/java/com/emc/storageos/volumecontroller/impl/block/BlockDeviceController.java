/*
 * Copyright (c) 2008-2011 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.volumecontroller.impl.block;

import static com.emc.storageos.db.client.util.CommonTransformerFunctions.FCTN_MIRROR_TO_URI;
import static com.emc.storageos.db.client.util.CommonTransformerFunctions.fctnBlockObjectToNativeID;
import static com.emc.storageos.db.client.util.CommonTransformerFunctions.fctnDataObjectToID;
import static com.emc.storageos.volumecontroller.impl.ControllerUtils.checkCloneConsistencyGroup;
import static com.emc.storageos.volumecontroller.impl.ControllerUtils.checkSnapshotSessionConsistencyGroup;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Collections2.transform;
import static java.lang.String.format;
import static java.util.Arrays.asList;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.xml.bind.DataBindingException;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationInterface;
import com.emc.storageos.blockorchestrationcontroller.VolumeDescriptor;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.PrefixConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockConsistencyGroup.Types;
import com.emc.storageos.db.client.model.BlockMirror;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.BlockSnapshot.TechnologyType;
import com.emc.storageos.db.client.model.BlockSnapshotSession;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.DataObject.Flag;
import com.emc.storageos.db.client.model.DecommissionedResource;
import com.emc.storageos.db.client.model.DiscoveredDataObject.Type;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.Migration;
import com.emc.storageos.db.client.model.NamedURI;
import com.emc.storageos.db.client.model.OpStatusMap;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StorageProvider;
import com.emc.storageos.db.client.model.StorageProvider.InterfaceType;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.SynchronizationState;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.Volume.ReplicationState;
import com.emc.storageos.db.client.model.VolumeGroup;
import com.emc.storageos.db.client.model.factories.VolumeFactory;
import com.emc.storageos.db.client.model.util.BlockConsistencyGroupUtils;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.emc.storageos.exceptions.DeviceControllerErrors;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.locking.LockTimeoutValue;
import com.emc.storageos.locking.LockType;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.plugins.BaseCollectionException;
import com.emc.storageos.plugins.StorageSystemViewObject;
import com.emc.storageos.plugins.common.Constants;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.services.util.StorageDriverManager;
import com.emc.storageos.srdfcontroller.SRDFDeviceController;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.util.ExportUtils;
import com.emc.storageos.util.InvokeTestFailure;
import com.emc.storageos.volumecontroller.ApplicationAddVolumeList;
import com.emc.storageos.volumecontroller.AsyncTask;
import com.emc.storageos.volumecontroller.BlockController;
import com.emc.storageos.volumecontroller.BlockStorageDevice;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.ControllerLockingUtil;
import com.emc.storageos.volumecontroller.impl.ControllerServiceImpl;
import com.emc.storageos.volumecontroller.impl.ControllerServiceImpl.Lock;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.block.rollback.ReplicaCleanupContext;
import com.emc.storageos.volumecontroller.impl.block.rollback.ReplicaCleanupFactory;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.ApplicationTaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockConsistencyGroupAddVolumeCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockConsistencyGroupCreateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockConsistencyGroupDeleteCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockConsistencyGroupRemoveVolumeCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockConsistencyGroupUpdateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockMirrorCreateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockMirrorDeactivateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockMirrorDeleteCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockMirrorDetachCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockMirrorFractureCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockMirrorResumeCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockMirrorTaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotActivateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotCreateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotDeleteCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotEstablishGroupTaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotRestoreCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotResyncCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionCreateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionCreateWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionDeleteCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionDeleteWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionLinkTargetCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionLinkTargetsWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionRelinkTargetCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionRelinkTargetsWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionRestoreCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionRestoreWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionUnlinkTargetCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionUnlinkTargetsWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockWaitForSynchronizedCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CleanupMetaVolumeMembersCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CloneActivateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CloneCreateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CloneCreateWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CloneFractureCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CloneRestoreCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CloneResyncCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CloneTaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CloneWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.MultiVolumeTaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.SimpleTaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeCreateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeDeleteCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeDetachCloneCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeExpandCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeTaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeUpdateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.hds.prov.utils.HDSUtils;
import com.emc.storageos.volumecontroller.impl.monitoring.RecordableBourneEvent;
import com.emc.storageos.volumecontroller.impl.monitoring.RecordableEventManager;
import com.emc.storageos.volumecontroller.impl.monitoring.cim.enums.RecordType;
import com.emc.storageos.volumecontroller.impl.plugins.discovery.smis.DiscoverTaskCompleter;
import com.emc.storageos.volumecontroller.impl.plugins.discovery.smis.ScanTaskCompleter;
import com.emc.storageos.volumecontroller.impl.smis.MetaVolumeRecommendation;
import com.emc.storageos.volumecontroller.impl.smis.SRDFOperations.Mode;
import com.emc.storageos.volumecontroller.impl.smis.srdf.SRDFUtils;
import com.emc.storageos.volumecontroller.impl.utils.ConsistencyGroupUtils;
import com.emc.storageos.volumecontroller.impl.utils.ExportMaskUtils;
import com.emc.storageos.volumecontroller.impl.utils.MetaVolumeUtils;
import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;
import com.emc.storageos.volumecontroller.placement.BlockStorageScheduler;
import com.emc.storageos.workflow.Workflow;
import com.emc.storageos.workflow.WorkflowException;
import com.emc.storageos.workflow.WorkflowService;
import com.emc.storageos.workflow.WorkflowStepCompleter;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;

/**
 * Generic Block Controller Implementation that does all of the database
 * operations and calls methods on the array specific implementations
 *
 *
 *
 */
public class BlockDeviceController implements BlockController, BlockOrchestrationInterface {
    // Constants for Event Types
    private static final String EVENT_SERVICE_TYPE = "block";
    private static final String EVENT_SERVICE_SOURCE = "BlockController";
    private DbClient _dbClient;
    private static final Logger _log = LoggerFactory.getLogger(BlockDeviceController.class);
    private static final int SCAN_LOCK_TIMEOUT = 60; // wait at most 60 seconds for scan lock
    private Map<String, BlockStorageDevice> _devices;

    private RecordableEventManager _eventManager;
    private BlockStorageScheduler _blockScheduler;
    private WorkflowService _workflowService;
    private SRDFDeviceController srdfDeviceController;
    private ReplicaDeviceController _replicaDeviceController;
    private StorageDriverManager driverManager = null;

    private static final String ATTACH_MIRRORS_WF_NAME = "ATTACH_MIRRORS_WORKFLOW";
    private static final String DETACH_MIRRORS_WF_NAME = "DETACH_MIRRORS_WORKFLOW";
    private static final String RESUME_MIRRORS_WF_NAME = "RESUME_MIRRORS_WORKFLOW";
    private static final String ESTABLISH_VOLUME_MIRROR_GROUP_WF_NAME = "ESTABLISH_VOLUME_MIRROR_GROUP_WORKFLOW";
    private static final String ESTABLISH_VOLUME_SNAPSHOT_GROUP_WF_NAME = "ESTABLISH_VOLUME_SNAPSHOT_GROUP_WORKFLOW";
    private static final String ESTABLISH_VOLUME_FULL_COPY_GROUP_WF_NAME = "ESTABLISH_VOLUME_FULL_COPY_GROUP_WORKFLOW";
    private static final String PAUSE_MIRRORS_WF_NAME = "PAUSE_MIRRORS_WORKFLOW";
    private static final String RESTORE_VOLUME_WF_NAME = "RESTORE_VOLUME_WORKFLOW";
    private static final String EXPAND_VOLUME_WF_NAME = "expandVolume";
    private static final String ROLLBACK_METHOD_NULL = "rollbackMethodNull";
    private static final String TERMINATE_RESTORE_SESSIONS_METHOD = "terminateRestoreSessions";
    private static final String FRACTURE_CLONE_METHOD = "fractureClone";
    private static final String UPDATE_CONSISTENCY_GROUP_WF_NAME = "UPDATE_CONSISTENCY_GROUP_WORKFLOW";
    private static final String UNTAG_VOLUME_STEP_GROUP = "UNTAG_VOLUME_WORKFLOW";
    static final String CREATE_LIST_SNAPSHOT_METHOD = "createListSnapshot";
    private static final String CREATE_SAPSHOT_SESSION_WF_NAME = "createSnapshotSessionWf";
    private static final String LINK_SNAPSHOT_SESSION_TARGETS_WF_NAME = "linkSnapshotSessionTargetsWF";
    private static final String RELINK_SNAPSHOT_SESSION_TARGETS_WF_NAME = "relinkSnapshotSessionTargetsWF";
    private static final String UNLINK_SNAPSHOT_SESSION_TARGETS_WF_NAME = "unlinkSnapshotSessionTargetsWF";
    private static final String RESTORE_SNAPSHOT_SESSION_WF_NAME = "restoreSnapshotSessionWF";
    private static final String DELETE_SNAPSHOT_SESSION_WF_NAME = "deleteSnapshotSessionWF";
    public static final String CREATE_SNAPSHOT_SESSION_STEP_GROUP = "createSnapshotSession";
    private static final String CREATE_SNAPSHOT_SESSION_METHOD = "createBlockSnapshotSession";
    public static final String LINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP = "LinkSnapshotSessionTarget";
    private static final String LINK_SNAPSHOT_SESSION_TARGET_METHOD = "linkBlockSnapshotSessionTarget";
    private static final String LINK_SNAPSHOT_SESSION_TARGET_GROUP_METHOD = "linkBlockSnapshotSessionTargetGroup";
    private static final String RB_LINK_SNAPSHOT_SESSION_TARGET_METHOD = "rollbackLinkBlockSnapshotSessionTarget";
    private static final String RELINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP = "RelinkSnapshotSessionTarget";
    private static final String RELINK_SNAPSHOT_SESSION_TARGET_METHOD = "relinkBlockSnapshotSessionTarget";
    private static final String UNLINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP = "UnlinkSnapshotSessionTarget";
    private static final String UNLINK_SNAPSHOT_SESSION_TARGET_METHOD = "unlinkBlockSnapshotSessionTarget";
    private static final String RESTORE_SNAPSHOT_SESSION_STEP_GROUP = "RestoreSnapshotSession";
    private static final String RESTORE_SNAPSHOT_SESSION_METHOD = "restoreBlockSnapshotSession";
    private static final String DELETE_SNAPSHOT_SESSION_STEP_GROUP = "DeleteSnapshotSession";
    private static final String DELETE_SNAPSHOT_SESSION_METHOD = "deleteBlockSnapshotSession";
    private static final String RESTORE_FROM_FULLCOPY_METHOD_NAME = "restoreFromFullCopy";
    private static final String ROLLBACK_CLEANUP_REPLICAS_STEP_GROUP = "RollbackReplicaCleanUp";
    private static final String ROLLBACK_CLEANUP_REPLICAS_METHOD_NAME = "rollbackCleanupReplicas";
    private static final String ROLLBACK_CLEANUP_REPLICAS_STEP_DESC = "Null provisioning step; clean up replicas on rollback";
    
    private static final String METHOD_CREATE_FULLCOPY_ORCHESTRATE_ROLLBACK_STEP = "createFullCopyOrchestrationRollbackSteps";

    public static final String BLOCK_VOLUME_EXPAND_GROUP = "BlockDeviceExpandVolume";

    public static final String RESTORE_VOLUME_STEP = "restoreVolume";
    private static final String RESTORE_VOLUME_METHOD_NAME = "restoreVolume";

    private static final String ADD_VOLUMES_TO_CG_KEY = "ADD";
    private static final String REMOVE_VOLUMES_FROM_CG_KEY = "REMOVE";

    public static final String UPDATE_VOLUMES_FOR_APPLICATION_WS_NAME = "UPDATE_VOLUMES_FOR_APPLICATION_WS";
    private static final String REMOVE_VOLUMES_FROM_CG_STEP_GROUP = "REMOVE_VOLUMES_FROM_CG";
    private static final String UPDATE_VOLUMES_STEP_GROUP = "UPDATE_VOLUMES";
    public static final String DELETE_GROUP_STEP_GROUP = "DELETE_GROUP";
    private static final String RESTORE_FROM_FULLCOPY_STEP = "restoreFromFullCopy";

    private static final String METHOD_CREATE_FULL_COPY_STEP = "createFullCopy";
    private static final String METHOD_CREATE_SNAPSHOT_SESSION_STEP = "createSnapshotSession";

    public void setDbClient(DbClient dbc) {
        _dbClient = dbc;
    }

    public void setBlockScheduler(BlockStorageScheduler blockScheduler) {
        _blockScheduler = blockScheduler;
    }

    public void setDevices(Map<String, BlockStorageDevice> deviceInterfaces) {
        _devices = deviceInterfaces;
    }

    public void setEventManager(RecordableEventManager eventManager) {
        _eventManager = eventManager;
    }

    public void setWorkflowService(WorkflowService workflowService) {
        _workflowService = workflowService;
    }

    public WorkflowService getWorkflowService() {
        return _workflowService;
    }

    public void setReplicaDeviceController(ReplicaDeviceController replicaDeviceController) {
        _replicaDeviceController = replicaDeviceController;
    }

    public synchronized StorageDriverManager getDriverManager() {
        if (driverManager == null) {
            driverManager = (StorageDriverManager) ControllerServiceImpl.getBean(StorageDriverManager.STORAGE_DRIVER_MANAGER);
        }
        return driverManager;
    }

    public BlockStorageDevice getDevice(String deviceType) {
        BlockStorageDevice storageDevice = _devices.get(deviceType);
        if (storageDevice == null) {
            // we will use external device
            storageDevice = _devices.get(Constants.EXTERNALDEVICE);
            if (storageDevice == null) {
                throw DeviceControllerException.exceptions.invalidSystemType(deviceType);
            }
        }
        return storageDevice;
    }

    /**
     * Creates a rollback workflow method that does nothing, but allows rollback
     * to continue to prior steps back up the workflow chain.
     *
     * @return A workflow method
     */
    Workflow.Method rollbackMethodNullMethod() {
        return new Workflow.Method(ROLLBACK_METHOD_NULL);
    }

    /**
     * A rollback workflow method that does nothing, but allows rollback
     * to continue to prior steps back up the workflow chain. Can be and is
     * used in workflows in other controllers that invoke operations on this
     * block controller. If the block operation happens to fail, this no-op
     * rollback method is invoked. It says the rollback step succeeded,
     * which will then allow other rollback operations to execute for other
     * workflow steps executed by the other controller.
     *
     * See the VPlexDeviceController restoreVolume method which creates a
     * workflow step that invokes the BlockDeviceController restoreVolume
     * method. The rollback method for this step is this no-op. If the
     * BlockDeviceController restoreVolume step fails, this rollback
     * method is invoked, which simply says the rollback for the step
     * was successful. This in turn allows the other steps in the workflow
     * rollback.
     *
     * @param stepId
     *            The id of the step being rolled back.
     *
     * @throws WorkflowException
     */
    public void rollbackMethodNull(String stepId) throws WorkflowException {
        WorkflowStepCompleter.stepSucceded(stepId);
    }

    /**
     * Fail the task
     *
     * @param clazz
     * @param id
     * @param opId
     * @param msg
     */
    @Deprecated
    private void doFailTask(Class<? extends DataObject> clazz, URI id, String opId, String msg) {
        try {
            _dbClient.updateTaskOpStatus(clazz, id, opId, new Operation(Operation.Status.error.name(), msg));
        } catch (DatabaseException ioe) {
            _log.error(ioe.getMessage());
        }
    }

    /**
     * Set the status of operation to 'ready'
     *
     * @param clazz
     *            The data object class.
     * @param ids
     *            The ids of the data objects for which the task completed.
     * @param opId
     *            The task id.
     */
    private void doSuccessTask(
            Class<? extends DataObject> clazz, List<URI> ids, String opId) {
        try {
            for (URI id : ids) {
                _dbClient.ready(clazz, id, opId);
            }
        } catch (DatabaseException ioe) {
            _log.error(ioe.getMessage());
        }
    }

    /**
     * Fail the task. Called when an exception occurs attempting to
     * execute a task on multiple data objects.
     *
     * @param clazz
     *            The data object class.
     * @param ids
     *            The ids of the data objects for which the task failed.
     * @param opId
     *            The task id.
     * @param serviceCoded
     *            Original exception.
     */
    private void doFailTask(
            Class<? extends DataObject> clazz, List<URI> ids, String opId, ServiceCoded serviceCoded) {
        try {
            for (URI id : ids) {
                _dbClient.error(clazz, id, opId, serviceCoded);
            }
        } catch (DatabaseException ioe) {
            _log.error(ioe.getMessage());
        }
    }

    /**
     * Fail the task. Called when an exception occurs attempting to
     * execute a task.
     *
     * @param clazz
     *            The data object class.
     * @param id
     *            The id of the data object for which the task failed.
     * @param opId
     *            The task id.
     * @param serviceCoded
     *            Original exception.
     */
    private void doFailTask(
            Class<? extends DataObject> clazz, URI id, String opId, ServiceCoded serviceCoded) {

        List<URI> ids = new ArrayList<URI>();
        ids.add(id);
        doFailTask(clazz, ids, opId, serviceCoded);
    }

    static final String CREATE_VOLUMES_STEP_GROUP = "BlockDeviceCreateVolumes";
    static final String MODIFY_VOLUMES_STEP_GROUP = "BlockDeviceModifyVolumes";
    static final String CREATE_MIRRORS_STEP_GROUP = "BlockDeviceCreateMirrors";
    static final String CREATE_CONSISTENCY_GROUP_STEP_GROUP = "BlockDeviceCreateGroup";
    static final String UPDATE_CONSISTENCY_GROUP_STEP_GROUP = "BlockDeviceUpdateGroup";
    static final String CREATE_SNAPSHOTS_STEP_GROUP = "BlockDeviceCreateSnapshots";

    /**
     * {@inheritDoc}
     */
    @Override
    public String addStepsForCreateVolumes(Workflow workflow, String waitFor,
            List<VolumeDescriptor> origVolumes, String taskId) throws ControllerException {

        // Get the list of descriptors the BlockDeviceController needs to create volumes for.
        List<VolumeDescriptor> volumeDescriptors = VolumeDescriptor.filterByType(origVolumes,
                new VolumeDescriptor.Type[] {
                        VolumeDescriptor.Type.BLOCK_DATA,
                        VolumeDescriptor.Type.RP_SOURCE,
                        VolumeDescriptor.Type.RP_JOURNAL,
                        VolumeDescriptor.Type.RP_TARGET,
                        VolumeDescriptor.Type.SRDF_SOURCE,
                        VolumeDescriptor.Type.SRDF_TARGET
                }, null);

        // If no volumes to create, just return
        if (volumeDescriptors.isEmpty()) {
            return waitFor;
        }

        // Segregate by pool to list of volumes.
        Map<URI, Map<Long, List<VolumeDescriptor>>> poolMap = VolumeDescriptor.getPoolSizeMap(volumeDescriptors);

        // Add a Step to create the consistency group if needed
        waitFor = addStepsForCreateConsistencyGroup(workflow, waitFor, volumeDescriptors, CREATE_CONSISTENCY_GROUP_STEP_GROUP);

        waitFor = addStepsForReplicaRollbackCleanup(workflow, waitFor, volumeDescriptors);

        // Add a Step for each Pool in each Device.
        // For meta volumes add Step for each meta volume, except vmax thin meta volumes.
        for (URI poolURI : poolMap.keySet()) {
            for (Long volumeSize : poolMap.get(poolURI).keySet()) {
                List<VolumeDescriptor> descriptors = poolMap.get(poolURI).get(volumeSize);
                List<URI> volumeURIs = VolumeDescriptor.getVolumeURIs(descriptors);
                VolumeDescriptor first = descriptors.get(0);
                URI deviceURI = first.getDeviceURI();
                VirtualPoolCapabilityValuesWrapper capabilities = first.getCapabilitiesValues();

                // Check if volumes have to be created as meta volumes
                _log.debug(String.format("Capabilities : isMeta: %s, Meta Type: %s, Member size: %s, Count: %s",
                        capabilities.getIsMetaVolume(), capabilities.getMetaVolumeType(), capabilities.getMetaVolumeMemberSize(),
                        capabilities.getMetaVolumeMemberCount()));

                Volume volume = _dbClient.queryObject(Volume.class, first.getVolumeURI());
                StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, volume.getStorageController());

                boolean createAsMetaVolume = capabilities.getIsMetaVolume()
                        || MetaVolumeUtils.createAsMetaVolume(first.getVolumeURI(), _dbClient, capabilities);
                if (storageSystem.checkIfVmax3()) {
                    createAsMetaVolume = false; // VMAX3 does not support META and we will get here due to change VPool
                                                // scenario
                }
                if (createAsMetaVolume) {
                    // For vmax thin meta volumes we can create multiple meta volumes in one smis request
                    if (volume.getThinlyProvisioned() && storageSystem.getSystemType().equals(StorageSystem.Type.vmax.toString())) {
                        workflow.createStep(CREATE_VOLUMES_STEP_GROUP,
                                String.format("Creating meta volumes:%n%s", getVolumesMsg(_dbClient, volumeURIs)),
                                waitFor, deviceURI, getDeviceType(deviceURI),
                                this.getClass(),
                                createMetaVolumesMethod(deviceURI, poolURI, volumeURIs, capabilities),
                                rollbackCreateMetaVolumesMethod(deviceURI, volumeURIs), null);
                    } else {
                        // Add workflow step for each meta volume
                        for (URI metaVolumeURI : volumeURIs) {
                            List<URI> metaVolumeURIs = new ArrayList<URI>();
                            metaVolumeURIs.add(metaVolumeURI);
                            String stepId = workflow.createStepId();
                            workflow.createStep(CREATE_VOLUMES_STEP_GROUP,
                                    String.format("Creating meta volume:%n%s", getVolumesMsg(_dbClient, metaVolumeURIs)),
                                    waitFor, deviceURI, getDeviceType(deviceURI),
                                    this.getClass(),
                                    createMetaVolumeMethod(deviceURI, poolURI, metaVolumeURI, capabilities),
                                    rollbackCreateMetaVolumeMethod(deviceURI, metaVolumeURI, stepId), stepId);
                        }
                    }
                } else {
                    workflow.createStep(CREATE_VOLUMES_STEP_GROUP,
                            String.format("Creating volumes:%n%s", getVolumesMsg(_dbClient, volumeURIs)),
                            waitFor, deviceURI, getDeviceType(deviceURI),
                            this.getClass(),
                            createVolumesMethod(deviceURI, poolURI, volumeURIs, capabilities),
                            rollbackCreateVolumesMethod(deviceURI, volumeURIs), null);
                }
                // Following workflow step is only applicable to HDS Thin Volume modification.
                if (getDeviceType(deviceURI).equalsIgnoreCase(Type.hds.name())) {
                    boolean modifyHitachiVolumeToApplyTieringPolicy = HDSUtils.isVolumeModifyApplicable(
                            first.getVolumeURI(), _dbClient);
                    if (modifyHitachiVolumeToApplyTieringPolicy) {
                        workflow.createStep(MODIFY_VOLUMES_STEP_GROUP,
                                String.format("Modifying volumes:%n%s", getVolumesMsg(_dbClient, volumeURIs)),
                                CREATE_VOLUMES_STEP_GROUP, deviceURI, getDeviceType(deviceURI), this.getClass(),
                                moidfyVolumesMethod(deviceURI, poolURI, volumeURIs),
                                rollbackCreateVolumesMethod(deviceURI, volumeURIs), null);
                    }
                }
            }
        }
        waitFor = CREATE_VOLUMES_STEP_GROUP;

        return waitFor;
    }

    private String addStepsForReplicaRollbackCleanup(Workflow workflow, String waitFor, List<VolumeDescriptor> volumeDescriptors) {
        List<URI> volumeURIs = VolumeDescriptor.getVolumeURIs(volumeDescriptors);
        VolumeDescriptor volumeDescriptor = volumeDescriptors.get(0);
        URI deviceURI = volumeDescriptor.getDeviceURI();

        Workflow.Method cleanupReplicasMethod = new Workflow.Method(ROLLBACK_CLEANUP_REPLICAS_METHOD_NAME, deviceURI, volumeURIs);

        waitFor = workflow.createStep(ROLLBACK_CLEANUP_REPLICAS_STEP_GROUP, ROLLBACK_CLEANUP_REPLICAS_STEP_DESC,
                waitFor, deviceURI, getDeviceType(deviceURI), this.getClass(),
                rollbackMethodNullMethod(), cleanupReplicasMethod, null);

        return waitFor;
    }

    public boolean rollbackCleanupReplicas(URI systemURI, List<URI> volumeURIs, String opId) {
        WorkflowStepCompleter.stepExecuting(opId);
        try {
            _log.info("Cleaning up replicas for {} volumes", volumeURIs.size());
            Set<URI> uniqueVolumes = new HashSet<>(volumeURIs);

            ReplicaCleanupContext replicaCleanupContext = ReplicaCleanupFactory.getContext(_dbClient);
            replicaCleanupContext.execute(uniqueVolumes);
        } catch (Exception e) {
            _log.warn("Caught exception whilst rolling back replica cleanup.", e);
        } finally {
            WorkflowStepCompleter.stepSucceded(opId);
        }
        return true;
    }

    /**
     * Add Steps to create any BLOCK_MIRRORs specified in the VolumeDescriptor list.
     *
     * @param workflow
     *            -- The Workflow being built
     * @param waitFor
     *            -- Previous steps to waitFor
     * @param volumes
     *            -- List<VolumeDescriptors> -- volumes of all types to be processed
     * @return last step added to waitFor
     * @throws ControllerException
     */
    public String addStepsForCreateMirrors(Workflow workflow, String waitFor,
            URI storage, URI sourceVolume, List<URI> mirrorList, boolean isCG) throws ControllerException {
        String stepId = waitFor;
        if (!isCG) {
            for (URI mirror : mirrorList) {
                stepId = workflow.createStep(CREATE_MIRRORS_STEP_GROUP,
                        String.format("Creating mirror for %s", sourceVolume), waitFor,
                        storage, getDeviceType(storage),
                        this.getClass(),
                        createMirrorMethod(storage, asList(mirror), isCG, false),
                        rollbackMirrorMethod(storage, asList(mirror)), null);
            }
        } else {
            stepId = workflow.createStep(CREATE_MIRRORS_STEP_GROUP,
                    String.format("Creating mirror for %s", sourceVolume), waitFor,
                    storage, getDeviceType(storage),
                    this.getClass(),
                    createMirrorMethod(storage, mirrorList, isCG, false),
                    rollbackMirrorMethod(storage, mirrorList), null);
        }
        return stepId;
    }

    /**
     * Add Steps to create the required consistency group
     *
     * @param workflow
     *            -- The Workflow being built
     * @param waitFor
     *            -- Previous steps to waitFor
     * @param volumesDescriptors
     *            -- List<VolumeDescriptors> -- volumes of all types to be processed
     * @return last step added to waitFor
     * @throws ControllerException
     */
    public String addStepsForCreateConsistencyGroup(Workflow workflow, String waitFor,
            List<VolumeDescriptor> volumesDescriptors, String stepGroup) throws ControllerException {

        // Filter any BLOCK_DATAs that need to be created.
        List<VolumeDescriptor> volumes = VolumeDescriptor.filterByType(volumesDescriptors,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_DATA },
                new VolumeDescriptor.Type[] {});

        // If no volumes to be created, just return.
        if (volumes.isEmpty()) {
            return waitFor;
        }

        // Get the consistency group. If no consistency group to be created,
        // just return. Get CG from any descriptor.
        final VolumeDescriptor firstVolume = volumes.get(0);
        if (firstVolume == null || NullColumnValueGetter.isNullURI(firstVolume.getConsistencyGroupURI())) {
            return waitFor;
        }
        final URI consistencyGroupURI = firstVolume.getConsistencyGroupURI();
        final BlockConsistencyGroup consistencyGroup = _dbClient.queryObject(BlockConsistencyGroup.class, consistencyGroupURI);

        if (firstVolume.getType() != null) {
            if (VolumeDescriptor.Type.SRDF_SOURCE.toString().equalsIgnoreCase(firstVolume.getType().toString())
                    || VolumeDescriptor.Type.SRDF_TARGET.toString().equalsIgnoreCase(firstVolume.getType().toString())
                    || VolumeDescriptor.Type.SRDF_EXISTING_SOURCE.toString().equalsIgnoreCase(firstVolume.getType().toString())) {
                return waitFor;
            }
        }

        // Create the CG on each system it has yet to be created on.
        Map<URI, Set<String>> deviceURIs = new HashMap<URI, Set<String>>();
        for (VolumeDescriptor descr : volumes) {
            // If the descriptor's associated volume is the backing volume for a RP+VPlex
            // journal/target volume, we want to ignore its storage system. We do not want to
            // create backing array consistency groups for RP+VPlex target volumes. Only
            // source volume.
            // We would create backend CG only if the volume's replicationGroupInstance is set when it is prepared in
            // apisvc level.
            Volume volume = _dbClient.queryObject(Volume.class, descr.getVolumeURI());
            String rgName = volume.getReplicationGroupInstance();
            if (NullColumnValueGetter.isNotNullValue(rgName)) {
                URI deviceURI = descr.getDeviceURI();
                _log.info(String.format("If it doesn't already exist, creating backend CG [%s] on device (%s) for volume [%s](%s).",
                        rgName, deviceURI, volume.getLabel(), volume.getId()));
                Set<String> rgNames = deviceURIs.get(deviceURI);
                if (rgNames == null) {
                    rgNames = new HashSet<String>();
                }
                rgNames.add(rgName);
                deviceURIs.put(deviceURI, rgNames);
            }
        }

        boolean createdCg = false;
        for (Map.Entry<URI, Set<String>> entry : deviceURIs.entrySet()) {
            URI deviceURI = entry.getKey();
            Set<String> rgNames = entry.getValue();
            for (String rgName : rgNames) {
                // If the consistency group has already been created in the array, just return
                if (!consistencyGroup.created(deviceURI, rgName)) {
                    // Create step to create consistency group
                    waitFor = workflow.createStep(stepGroup,
                            String.format("Creating consistency group  %s", consistencyGroupURI), waitFor,
                            deviceURI, getDeviceType(deviceURI),
                            this.getClass(),
                            createConsistencyGroupMethod(deviceURI, consistencyGroupURI, rgName),
                            deleteConsistencyGroupMethod(deviceURI, consistencyGroupURI, rgName, false, false, true), null);
                    createdCg = true;
                    _log.info(String.format("Step created for creating CG [%s] on device [%s]", consistencyGroup.getLabel(), deviceURI));
                }
            }
        }

        if (createdCg) {
            waitFor = stepGroup;
        }

        return waitFor;
    }

    /**
     * Add Steps to add or remove volumes to the required consistency group
     *
     * @param workflow
     *            -- The Workflow being built
     * @param waitFor
     *            -- Previous steps to waitFor
     * @param volumesDescriptorsToAdd
     *            -- List<VolumeDescriptors> -- volumes of all types to be processed for adding to CG
     * @param volumesDescriptorsToRemove
     *            -- List<VolumeDescriptors> -- volumes of all types to be processed for removing from CG
     * @return last step added to waitFor
     * @throws ControllerException
     */
    public String addStepsForUpdateConsistencyGroup(Workflow workflow, String waitFor,
            List<VolumeDescriptor> volumesDescriptorsToAdd, List<VolumeDescriptor> volumesDescriptorsToRemove) throws ControllerException {

        // Filter any BLOCK_DATAs that need to be added to CG.
        List<VolumeDescriptor> addDescriptors = VolumeDescriptor.filterByType(volumesDescriptorsToAdd,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_DATA },
                new VolumeDescriptor.Type[] {});

        // Filter any BLOCK_DATAs that need to be removed from CG.
        List<VolumeDescriptor> removeDescriptors = VolumeDescriptor.filterByType(volumesDescriptorsToRemove,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_DATA },
                new VolumeDescriptor.Type[] {});

        // We need at least one volume to check, so either get it from
        // the add descriptors or the delete descriptors.
        VolumeDescriptor firstVolume = null;
        if (!addDescriptors.isEmpty()) {
            firstVolume = addDescriptors.get(0);
        } else if (!removeDescriptors.isEmpty()) {
            firstVolume = removeDescriptors.get(0);
        } else {
            _log.warn("No volumes to add or remove from CG, skip step.");
            // No volumes to be added or removed, just return.
            return waitFor;
        }

        if (NullColumnValueGetter.isNullURI(firstVolume.getConsistencyGroupURI())) {
            _log.warn(String.format("Volume (%s) has a null CG reference, skip step.", firstVolume.getVolumeURI()));
            return waitFor;
        }

        // Check for SRDF
        if (firstVolume.getType() != null) {
            if (VolumeDescriptor.Type.SRDF_SOURCE.toString().equalsIgnoreCase(firstVolume.getType().toString())
                    || VolumeDescriptor.Type.SRDF_TARGET.toString().equalsIgnoreCase(firstVolume.getType().toString())
                    || VolumeDescriptor.Type.SRDF_EXISTING_SOURCE.toString().equalsIgnoreCase(firstVolume.getType().toString())) {
                _log.warn(String.format("Volume (%s) is of type SRDF, skip step.", firstVolume.getVolumeURI()));
                return waitFor;
            }
        }

        // We want the map to contain both the volumes to ADD and REMOVE segregated by device and also by CG.
        // The map will look like the below:
        // Device URI
        // --> CG URI
        // ----> ADD -> List of Volumes to Add from this CG for this device
        // ----> REMOVE -> List of Volumes to Remove from this CG for this device
        Map<URI, Map<URI, Map<String, List<URI>>>> deviceToCGMap = createDeviceToCGMapFromDescriptors(addDescriptors, removeDescriptors);

        // Distill the steps down to Device -> CG -> ADD and REMOVE volumes
        for (Map.Entry<URI, Map<URI, Map<String, List<URI>>>> deviceEntry : deviceToCGMap.entrySet()) {
            URI deviceURI = deviceEntry.getKey();
            Map<URI, Map<String, List<URI>>> volumesToUpdateByCG = deviceEntry.getValue();
            for (Map.Entry<URI, Map<String, List<URI>>> cgEntry : volumesToUpdateByCG.entrySet()) {
                URI consistencyGroupURI = cgEntry.getKey();
                List<URI> volumesToAdd = cgEntry.getValue().get(ADD_VOLUMES_TO_CG_KEY);
                List<URI> volumesToRemove = cgEntry.getValue().get(REMOVE_VOLUMES_FROM_CG_KEY);

                waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                        String.format("Updating consistency group  %s", consistencyGroupURI), waitFor,
                        deviceURI, getDeviceType(deviceURI),
                        this.getClass(),
                        new Workflow.Method("updateConsistencyGroup", deviceURI, consistencyGroupURI, volumesToAdd, volumesToRemove),
                        rollbackMethodNullMethod(), null);
                if (volumesToAdd != null) {
                    _log.info(String.format("Step created for adding volumes [%s] to CG [%s] on device [%s]",
                            Joiner.on("\t").join(volumesToAdd),
                            consistencyGroupURI,
                            deviceURI));
                }
                if (volumesToRemove != null) {
                    _log.info(String.format("Step created for removing volumes [%s] from CG [%s] on device [%s]",
                            Joiner.on("\t").join(volumesToRemove),
                            consistencyGroupURI,
                            deviceURI));
                }
            }
        }

        return waitFor;
    }

    /**
     * Convenience method to create a map Device to CG to Volume to ADD and REMOVE.
     *
     * We want the map to contain both the volumes to ADD and REMOVE segregated by device and also by CG.
     * The map will look like the below:
     * Device URI
     * --> CG URI
     * ----> ADD -> List of Volumes to Add from this CG for this device
     * ----> REMOVE -> List of Volumes to Remove from this CG for this device
     *
     * @param addDescriptors
     *            BLOCK_DATA descriptors of volumes to add to CG
     * @param removeDescriptors
     *            BLOCK_DATA descriptors of volumes to remove from CG
     * @return populated map
     */
    private Map<URI, Map<URI, Map<String, List<URI>>>> createDeviceToCGMapFromDescriptors(List<VolumeDescriptor> addDescriptors,
            List<VolumeDescriptor> removeDescriptors) {
        Map<URI, Map<URI, Map<String, List<URI>>>> deviceToCGMap = new HashMap<URI, Map<URI, Map<String, List<URI>>>>();

        // Volumes to add
        for (VolumeDescriptor descr : addDescriptors) {
            // Segregated by device
            URI deviceURI = descr.getDeviceURI();
            Map<URI, Map<String, List<URI>>> volumesToUpdateByCG = deviceToCGMap.get(deviceURI);
            if (volumesToUpdateByCG == null) {
                volumesToUpdateByCG = new HashMap<URI, Map<String, List<URI>>>();
                deviceToCGMap.put(deviceURI, volumesToUpdateByCG);
            }
            // Segregated by CG
            URI consistencyGroupURI = descr.getConsistencyGroupURI();
            Map<String, List<URI>> volumesToUpdate = volumesToUpdateByCG.get(consistencyGroupURI);
            if (volumesToUpdate == null) {
                volumesToUpdate = new HashMap<String, List<URI>>();
                volumesToUpdateByCG.put(consistencyGroupURI, volumesToUpdate);
            }
            // Segregated by volumes to ADD
            List<URI> volumesToAdd = volumesToUpdate.get(ADD_VOLUMES_TO_CG_KEY);
            if (volumesToAdd == null) {
                volumesToAdd = new ArrayList<URI>();
                volumesToUpdate.put(ADD_VOLUMES_TO_CG_KEY, volumesToAdd);
            }
            volumesToAdd.add(descr.getVolumeURI());
        }

        // Volumes to remove
        for (VolumeDescriptor descr : removeDescriptors) {
            // Segregated by device
            URI deviceURI = descr.getDeviceURI();
            Map<URI, Map<String, List<URI>>> volumesToUpdateByCG = deviceToCGMap.get(deviceURI);
            if (volumesToUpdateByCG == null) {
                volumesToUpdateByCG = new HashMap<URI, Map<String, List<URI>>>();
                deviceToCGMap.put(deviceURI, volumesToUpdateByCG);
            }
            // Segregated by CG
            URI consistencyGroupURI = descr.getConsistencyGroupURI();
            Map<String, List<URI>> volumesToUpdate = volumesToUpdateByCG.get(consistencyGroupURI);
            if (volumesToUpdate == null) {
                volumesToUpdate = new HashMap<String, List<URI>>();
                volumesToUpdateByCG.put(consistencyGroupURI, volumesToUpdate);
            }
            // Segregated by volumes to REMOVE
            List<URI> volumesToRemove = volumesToUpdate.get(REMOVE_VOLUMES_FROM_CG_KEY);
            if (volumesToRemove == null) {
                volumesToRemove = new ArrayList<URI>();
                volumesToUpdate.put(REMOVE_VOLUMES_FROM_CG_KEY, volumesToRemove);
            }
            volumesToRemove.add(descr.getVolumeURI());
        }

        return deviceToCGMap;
    }

    /**
     * Returns a message containing information about each volume.
     *
     * @param volumeURIs
     * @return
     */
    static public String getVolumesMsg(DbClient dbClient, List<URI> volumeURIs) {
        StringBuilder builder = new StringBuilder();
        for (URI uri : volumeURIs) {
            BlockObject obj = BlockObject.fetch(dbClient, uri);
            if (obj == null) {
                continue;
            }
            builder.append("Volume: " + obj.getLabel() + " (" + obj.getId() + ")");
            if (obj.getWWN() != null) {
                builder.append(" wwn: " + obj.getWWN());
            }
            if (obj.getNativeId() != null) {
                builder.append(" native id: " + obj.getNativeId());
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    /**
     * Return a Workflow.Method for createVolumes.
     *
     * @param systemURI
     * @param poolURI
     * @param volumeURIs
     * @param capabilities
     * @return Workflow.Method
     */
    private Workflow.Method moidfyVolumesMethod(URI systemURI, URI poolURI, List<URI> volumeURIs) {
        return new Workflow.Method("modifyVolumes", systemURI, poolURI, volumeURIs);
    }

    /**
     * {@inheritDoc} NOTE NOTE: The signature here MUST match the Workflow.Method modifyVolumesMethod just above (except
     * opId).
     * Currently this workflow step is used only for Hitachi Thin Volumes modification to update volume tieringPolicy.
     * Hitachi allows setting of tieringpolicy at LDEV level, hence We should have a LDEV id of a LogicalUnit.
     * But LDEV is only created after we LogicalUnit is created. Hence createVolumes workflow includes creation of LU
     * (i.e. LDEV)
     * And LDEV modification (to set tieringPolicy.)
     *
     */
    @Override
    public void modifyVolumes(URI systemURI, URI poolURI, List<URI> volumeURIs, String opId) throws ControllerException {

        List<Volume> volumes = new ArrayList<Volume>();
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class,
                    systemURI);
            List<VolumeTaskCompleter> volumeCompleters = new ArrayList<VolumeTaskCompleter>();
            Iterator<URI> volumeURIsIter = volumeURIs.iterator();
            StringBuilder logMsgBuilder = new StringBuilder(String.format(
                    "moidfyVolumes start - Array:%s Pool:%s", systemURI.toString(),
                    poolURI.toString()));
            while (volumeURIsIter.hasNext()) {
                URI volumeURI = volumeURIsIter.next();
                logMsgBuilder.append(String.format("%nVolume:%s", volumeURI.toString()));
                Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
                volumes.add(volume);
                VolumeUpdateCompleter volumeCompleter = new VolumeUpdateCompleter(
                        volumeURI, opId);
                volumeCompleters.add(volumeCompleter);
            }
            _log.info(logMsgBuilder.toString());
            StoragePool storagePool = _dbClient.queryObject(StoragePool.class, poolURI);
            MultiVolumeTaskCompleter completer = new MultiVolumeTaskCompleter(volumeURIs, volumeCompleters, opId);

            Volume volume = volumes.get(0);
            VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
            WorkflowStepCompleter.stepExecuting(completer.getOpId());
            getDevice(storageSystem.getSystemType()).doModifyVolumes(storageSystem,
                    storagePool, opId, volumes, completer);
            logMsgBuilder = new StringBuilder(String.format(
                    "modifyVolumes end - Array:%s Pool:%s", systemURI.toString(),
                    poolURI.toString()));
            volumeURIsIter = volumeURIs.iterator();
            while (volumeURIsIter.hasNext()) {
                logMsgBuilder.append(String.format("%nVolume:%s", volumeURIsIter.next()
                        .toString()));
            }
            _log.info(logMsgBuilder.toString());
        } catch (InternalException e) {
            _log.error(String.format("modifyVolumes Failed - Array: %s Pool:%s Volume:%s",
                    systemURI.toString(), poolURI.toString(), Joiner.on("\t").join(volumeURIs)));
            doFailTask(Volume.class, volumeURIs, opId, e);
            WorkflowStepCompleter.stepFailed(opId, e);

        } catch (Exception e) {
            _log.error(String.format("modifyVolumes Failed - Array: %s Pool:%s Volume:%s",
                    systemURI.toString(), poolURI.toString(), Joiner.on("\t").join(volumeURIs)));
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            doFailTask(Volume.class, volumeURIs, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    /**
     * Return a Workflow.Method for createVolumes.
     *
     * @param systemURI
     * @param poolURI
     * @param volumeURIs
     * @param capabilities
     * @return Workflow.Method
     */
    private Workflow.Method createVolumesMethod(URI systemURI, URI poolURI, List<URI> volumeURIs,
            VirtualPoolCapabilityValuesWrapper capabilities) {
        return new Workflow.Method("createVolumes", systemURI, poolURI, volumeURIs, capabilities);
    }

    /**
     * {@inheritDoc} NOTE NOTE: The signature here MUST match the Workflow.Method createVolumesMethod just above (except
     * opId).
     */
    @Override
    public void createVolumes(URI systemURI, URI poolURI, List<URI> volumeURIs,
            VirtualPoolCapabilityValuesWrapper capabilities, String opId) throws ControllerException {

        List<Volume> volumes = new ArrayList<Volume>();
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class,
                    systemURI);
            List<VolumeTaskCompleter> volumeCompleters = new ArrayList<VolumeTaskCompleter>();
            Iterator<URI> volumeURIsIter = volumeURIs.iterator();
            StringBuilder logMsgBuilder = new StringBuilder(String.format(
                    "createVolumes start - Array:%s Pool:%s", systemURI.toString(),
                    poolURI.toString()));
            while (volumeURIsIter.hasNext()) {
                URI volumeURI = volumeURIsIter.next();
                logMsgBuilder.append(String.format("%nVolume:%s", volumeURI.toString()));
                Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
                volumes.add(volume);
                VolumeCreateCompleter volumeCompleter = new VolumeCreateCompleter(
                        volumeURI, opId);
                volumeCompleters.add(volumeCompleter);
            }
            _log.info(logMsgBuilder.toString());
            StoragePool storagePool = _dbClient.queryObject(StoragePool.class, poolURI);
            MultiVolumeTaskCompleter completer = new MultiVolumeTaskCompleter(volumeURIs, volumeCompleters, opId);

            WorkflowStepCompleter.stepExecuting(completer.getOpId());
            InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_005);
            getDevice(storageSystem.getSystemType()).doCreateVolumes(storageSystem,
                    storagePool, opId, volumes, capabilities, completer);
            InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_006);
            logMsgBuilder = new StringBuilder(String.format(
                    "createVolumes end - Array:%s Pool:%s", systemURI.toString(),
                    poolURI.toString()));
            volumeURIsIter = volumeURIs.iterator();
            while (volumeURIsIter.hasNext()) {
                logMsgBuilder.append(String.format("%nVolume:%s", volumeURIsIter.next()
                        .toString()));
            }
            _log.info(logMsgBuilder.toString());
        } catch (Exception e) {
            _log.error(String.format("createVolume Failed - Array: %s Pool:%s Volume:%s",
                    systemURI.toString(), poolURI.toString(), Joiner.on("\t").join(volumeURIs)), e);
            Workflow workflow = WorkflowService.getInstance().getWorkflowFromStepId(opId);
            if (workflow != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                doFailTask(Volume.class, volumeURIs, opId, serviceError);
                WorkflowStepCompleter.stepFailed(opId, serviceError);
                _dbClient.markForDeletion(volumes);
            } else {
                _log.info("Workflow is null which means that the workflow has already completed. Not performing any error handling");
            }
        }
    }

    /**
     * Return a Workflow.Method for rollbackCreateVolumes
     *
     * @param systemURI
     * @param volumeURIs
     * @return Workflow.Method
     */
    public static Workflow.Method rollbackCreateVolumesMethod(URI systemURI, List<URI> volumeURIs) {
        return new Workflow.Method("rollBackCreateVolumes", systemURI, volumeURIs);
    }

    /**
     * {@inheritDoc} NOTE: The signature here MUST match the Workflow.Method rollbackCreateVolumesMethod just above
     * (except opId).
     */
    @Override
    public void rollBackCreateVolumes(URI systemURI, List<URI> volumeURIs, String opId) throws ControllerException {
        MultiVolumeTaskCompleter completer = new MultiVolumeTaskCompleter(volumeURIs, opId);
        List<Volume> volumes = new ArrayList<>(volumeURIs.size());

        completer.setRollingBack(true);
        try {
            String logMsg = String.format(
                    "rollbackCreateVolume start - Array:%s, Volume:%s", systemURI.toString(), Joiner.on(',').join(volumeURIs));
            _log.info(logMsg.toString());
            WorkflowStepCompleter.stepExecuting(opId);
            volumes.addAll(_dbClient.queryObject(Volume.class, volumeURIs));
            for (Volume volume : volumes) {
                // CTRL-5597 clean volumes which have failed only in a multi-volume request
                if (null != volume.getNativeGuid()) {
                    StorageSystem system = _dbClient.queryObject(StorageSystem.class,
                            volume.getStorageController());
                    if (Type.xtremio.toString().equalsIgnoreCase(system.getSystemType())) {
                        continue;
                    }
                }
                // clearing targets explicitly, during vpool change if target volume creation failed for same reason,
                // then we need to clear srdfTargets field for source
                if (null != volume.getSrdfTargets()) {
                    _log.info("Clearing targets for existing source");
                    volume.getSrdfTargets().clear();
                    _dbClient.updateObject(volume);
                }
                // for change Virtual Pool, if failed, clear targets for source
                if (!NullColumnValueGetter.isNullNamedURI(volume.getSrdfParent())) {
                    URI sourceUri = volume.getSrdfParent().getURI();
                    Volume sourceVolume = _dbClient.queryObject(Volume.class, sourceUri);
                    if (null != sourceVolume.getSrdfTargets()) {
                        sourceVolume.getSrdfTargets().clear();
                        _dbClient.updateObject(sourceVolume);
                    }

                    // Clearing target CG
                    URI cgUri = volume.getConsistencyGroup();
                    if (null != cgUri) {
                        BlockConsistencyGroup targetCG = _dbClient.queryObject(
                                BlockConsistencyGroup.class, cgUri);
                        if (null != targetCG && (null == targetCG.getTypes()
                                || NullColumnValueGetter.isNullURI(targetCG.getStorageController()))) {
                            _log.info("Set target CG {} inactive", targetCG.getLabel());
                            targetCG.setInactive(true);
                            _dbClient.updateObject(targetCG);
                        }

                        // clear association between target volume and target cg
                        volume.setConsistencyGroup(NullColumnValueGetter.getNullURI());
                        _dbClient.updateAndReindexObject(volume);
                    }
                }
            
            
                // Check for loose export groups associated with this rolled-back volume
                URIQueryResultList exportGroupURIs = new URIQueryResultList();
                _dbClient.queryByConstraint(ContainmentConstraint.Factory.getVolumeExportGroupConstraint(volume.getId()), exportGroupURIs);
                while (exportGroupURIs.iterator().hasNext()) {
                	URI exportGroupURI = exportGroupURIs.iterator().next();
                    ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupURI);
                    if (!exportGroup.getInactive()) {
                    	exportGroup.removeVolume(volume.getId());
                        boolean canRemoveGroup = false;
                        List<ExportMask> exportMasks = ExportMaskUtils.getExportMasks(_dbClient, exportGroup);
                    	// Make sure the volume is not in an export mask
                        for (ExportMask exportMask : exportMasks) {
                        	exportMask.removeVolume(volume.getId());
                    		exportMask.removeFromUserCreatedVolumes(volume);
                            exportMask.removeFromExistingVolumes(volume);
                    		if (!exportMask.getCreatedBySystem() && !exportMask.hasAnyVolumes() && exportMask.emptyVolumes()) {
                    			canRemoveGroup = true;
                    			_dbClient.removeObject(exportMask);
                            } else {
                            	_dbClient.updateObject(exportMask);
                            }
                        }                            

                        // If we didn't find that volume in a mask, it's OK to remove it.
                        if (canRemoveGroup && exportMasks.size() == 1 && exportGroup.getVolumes().isEmpty()) {
                            _dbClient.removeObject(exportGroup);
                        } else {
                            _dbClient.updateObject(exportGroup);
                        }
                    }
                }
            }                  		
                           
            InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_013);
            deleteVolumesWithCompleter(systemURI, volumeURIs, completer);
            InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_014);

            logMsg = String.format(
                    "rollbackCreateVolume end - Array:%s, Volume:%s", systemURI.toString(), Joiner.on(',').join(volumeURIs));
            _log.info(logMsg.toString());
        } catch (Exception e) {
            _log.error(String.format("rollbackCreateVolume Failed - Array:%s, Volume:%s", systemURI.toString(),
                    Joiner.on(',').join(volumeURIs)));
            handleException(e, completer);
        }
    }

    /**
     * Private exception handling method to reduce code repetition.
     * 
     * @param e
     *            exception
     * @param taskCompleter
     *            completer to notify
     */
    private void handleException(Exception e, TaskCompleter taskCompleter) {
        _log.error("Handling exception with task completer: {}", taskCompleter, e);
        if (taskCompleter != null && (taskCompleter.isCompleted() || taskCompleter.isAsynchronous())) {
            _log.warn("Task has been marked as either asynchronous or completed.  Not performing any error handling.");
            return;
        }

        if (e instanceof InternalException) {
            InternalException ie = (InternalException) e;
            doFailTask(Volume.class, taskCompleter.getIds(), taskCompleter.getOpId(), ie);
            WorkflowStepCompleter.stepFailed(taskCompleter.getOpId(), ie);
        } else {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            doFailTask(Volume.class, taskCompleter.getIds(), taskCompleter.getOpId(), serviceError);
            WorkflowStepCompleter.stepFailed(taskCompleter.getOpId(),
                    DeviceControllerException.exceptions.unexpectedCondition(e.getMessage()));
        }

        List<Volume> volumes = _dbClient.queryObject(Volume.class, taskCompleter.getIds());
        _dbClient.markForDeletion(volumes);
    }

    /**
     * Return a Workflow.Method for createMetaVolumes.
     *
     * @param systemURI
     * @param poolURI
     * @param volumeURIs
     * @param capabilities
     * @return Workflow.Method
     */
    private Workflow.Method createMetaVolumesMethod(URI systemURI, URI poolURI, List<URI> volumeURIs,
            VirtualPoolCapabilityValuesWrapper capabilities) {
        return new Workflow.Method("createMetaVolumes", systemURI, poolURI, volumeURIs, capabilities);
    }

    /**
     * Return a Workflow.Method for rollbackCreateMetaVolumes.
     *
     * @param systemURI
     * @param volumeURIs
     * @return Workflow.Method
     */
    public static Workflow.Method rollbackCreateMetaVolumesMethod(URI systemURI, List<URI> volumeURIs) {
        return rollbackCreateVolumesMethod(systemURI, volumeURIs);
    }

    /**
     * Return a Workflow.Method for createVolumes.
     *
     * @param systemURI
     * @param poolURI
     * @param volumeURI
     * @param capabilities
     * @return Workflow.Method
     */
    private Workflow.Method createMetaVolumeMethod(URI systemURI, URI poolURI, URI volumeURI,
            VirtualPoolCapabilityValuesWrapper capabilities) {
        return new Workflow.Method("createMetaVolume", systemURI, poolURI, volumeURI, capabilities);
    }

    /**
     * Return a Workflow.Method for rollbackCreateMetaVolume.
     *
     * @param systemURI
     * @param volumeURI
     * @return Workflow.Method
     */
    public static Workflow.Method rollbackCreateMetaVolumeMethod(URI systemURI, URI volumeURI, String createMetaVolumeStepId) {
        return new Workflow.Method("rollBackCreateMetaVolume", systemURI, volumeURI, createMetaVolumeStepId);
    }

    /**
     * {@inheritDoc} NOTE: The signature here MUST match the Workflow.Method rollbackCreateMetaVolumeMethod just above
     * (except opId).
     */
    @Override
    public void rollBackCreateMetaVolume(URI systemURI, URI volumeURI, String createStepId, String opId) throws ControllerException {

        try {
            String logMsg = String.format(
                    "rollbackCreateMetaVolume start - Array:%s, Volume:%s", systemURI.toString(), volumeURI.toString());
            _log.info(logMsg.toString());

            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, systemURI);
            Volume volume = _dbClient.queryObject(Volume.class, volumeURI);

            CleanupMetaVolumeMembersCompleter cleanupCompleter = null;
            WorkflowStepCompleter.stepExecuting(opId);
            // Check if we need to cleanup dangling meta members volumes on array.
            // Meta members are temporary array volumes. They only exist until they are added to a meta volume.
            // We store these volumes in WF create step data.
            List<String> metaMembers = (ArrayList<String>) _workflowService.loadStepData(createStepId);
            if (metaMembers != null && !metaMembers.isEmpty()) {
                boolean isWFStep = false;
                cleanupCompleter = new CleanupMetaVolumeMembersCompleter(volumeURI, isWFStep, createStepId, opId);
                getDevice(storageSystem.getSystemType()).doCleanupMetaMembers(storageSystem, volume, cleanupCompleter);
            }
            // TEMPER Used for negative testing.
            // Comment out call to doCleanupMetaMembers above
            // cleanupCompleter.setSuccess(false);
            // // TEMPER
            // Delete meta volume.
            // Delete only if meta members cleanup was successful (in case it was executed).
            if (cleanupCompleter == null || cleanupCompleter.isSuccess()) {
                List<URI> volumeURIs = new ArrayList<URI>();
                volumeURIs.add(volumeURI);
                deleteVolumeStep(systemURI, volumeURIs, opId);
            } else {
                ServiceError serviceError;
                if (cleanupCompleter.getError() != null) {
                    serviceError = cleanupCompleter.getError();
                } else {
                    serviceError = DeviceControllerException.errors.jobFailedOp("CleanupMetaVolumeMembers");
                }
                doFailTask(Volume.class, volumeURI, opId, serviceError);
                WorkflowStepCompleter.stepFailed(opId, serviceError);
            }
            logMsg = String.format(
                    "rollbackCreateMetaVolume end - Array:%s, Volume:%s", systemURI.toString(), volumeURI.toString());
            _log.info(logMsg.toString());
        } catch (InternalException e) {
            _log.error(String.format("rollbackCreateMetaVolume Failed - Array:%s, Volume:%s", systemURI.toString(),
                    volumeURI.toString()));
            doFailTask(Volume.class, volumeURI, opId, e);
            WorkflowStepCompleter.stepFailed(opId, e);
        } catch (Exception e) {
            _log.error(String.format("rollbackCreateMetaVolume Failed - Array:%s, Volume:%s", systemURI.toString(),
                    volumeURI.toString()));
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            doFailTask(Volume.class, volumeURI, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    /**
     * Return a Workflow.Method for rollbackExpandVolume.
     *
     * @param systemURI
     * @param volumeURI
     * @return Workflow.Method
     */
    public static Workflow.Method rollbackExpandVolumeMethod(URI systemURI, URI volumeURI, String expandStepId) {
        return new Workflow.Method("rollBackExpandVolume", systemURI, volumeURI, expandStepId);
    }

    /**
     * {@inheritDoc} NOTE: The signature here MUST match the Workflow.Method rollbackExpandVolume just above (except
     * opId).
     */
    @Override
    public void rollBackExpandVolume(URI systemURI, URI volumeURI, String expandStepId, String opId) throws ControllerException {

        try {
            StringBuilder logMsgBuilder = new StringBuilder(String.format(
                    "rollbackExpandVolume start - Array:%s, Volume:%s", systemURI.toString(), volumeURI.toString()));
            _log.info(logMsgBuilder.toString());

            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, systemURI);
            Volume volume = _dbClient.queryObject(Volume.class, volumeURI);

            WorkflowStepCompleter.stepExecuting(opId);

            // Check if we need to cleanup dangling meta members volumes on array.
            // Meta members are temporary array volumes. They only exist until they are added to a meta volume.
            // We store these volumes in WF expand step data.
            List<String> metaMembers = (ArrayList<String>) _workflowService.loadStepData(expandStepId);
            if (metaMembers != null && !metaMembers.isEmpty()) {
                CleanupMetaVolumeMembersCompleter cleanupCompleter = null;
                boolean isWFStep = true;
                cleanupCompleter = new CleanupMetaVolumeMembersCompleter(volumeURI, isWFStep, expandStepId, opId);
                getDevice(storageSystem.getSystemType()).doCleanupMetaMembers(storageSystem, volume, cleanupCompleter);
                // TEMPER Used for negative testing.
                // Comment out call to doCleanupMetaMembers above
                // cleanupCompleter.setSuccess(false);
                // // TEMPER
                if (!cleanupCompleter.isSuccess()) {
                    ServiceError serviceError;
                    if (cleanupCompleter.getError() != null) {
                        serviceError = cleanupCompleter.getError();
                    } else {
                        serviceError = DeviceControllerException.errors.jobFailedOp("CleanupMetaVolumeMembers");
                    }
                    doFailTask(Volume.class, volumeURI, opId, serviceError);
                    WorkflowStepCompleter.stepFailed(opId, serviceError);
                }
            } else {
                // We came here if: a. Volume was expanded as a regular volume. or b. Volume was expanded as a meta
                // volume,
                // but there are no dangling meta members left.
                _log.info("rollbackExpandVolume: nothing to cleanup in rollback.");
                WorkflowStepCompleter.stepSucceded(opId);
            }
            logMsgBuilder = new StringBuilder(String.format(
                    "rollbackExpandVolume end - Array:%s, Volume:%s", systemURI.toString(), volumeURI.toString()));
            _log.info(logMsgBuilder.toString());
        } catch (InternalException e) {
            _log.error(String.format("rollbackExpandVolume Failed - Array:%s,  Volume:%s", systemURI.toString(),
                    volumeURI.toString()));
            doFailTask(Volume.class, volumeURI, opId, e);
            WorkflowStepCompleter.stepFailed(opId, e);
        } catch (Exception e) {
            _log.error(String.format("rollbackExpandVolume Failed - Array:%s, Volume:%s", systemURI.toString(),
                    volumeURI.toString()), e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            doFailTask(Volume.class, volumeURI, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    /**
     * {@inheritDoc} NOTE NOTE: The signature here MUST match the Workflow.Method createMetaVolumesMethod just above
     * (except opId).
     */
    @Override
    public void createMetaVolumes(URI systemURI, URI poolURI, List<URI> volumeURIs,
            VirtualPoolCapabilityValuesWrapper capabilities, String opId) throws ControllerException {
        boolean opCreateFailed = false;
        List<Volume> volumes = new ArrayList<Volume>();
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class,
                    systemURI);

            List<VolumeTaskCompleter> volumeCompleters = new ArrayList<VolumeTaskCompleter>();
            Iterator<URI> volumeURIsIter = volumeURIs.iterator();
            StringBuilder logMsgBuilder = new StringBuilder(String.format(
                    "createMetaVolumes start - Array:%s Pool:%s", systemURI.toString(),
                    poolURI.toString()));
            while (volumeURIsIter.hasNext()) {
                URI volumeURI = volumeURIsIter.next();
                logMsgBuilder.append(String.format("%nVolume:%s", volumeURI.toString()));
                Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
                volumes.add(volume);
                VolumeCreateCompleter volumeCompleter = new VolumeCreateCompleter(
                        volumeURI, opId);
                volumeCompleters.add(volumeCompleter);
            }
            _log.info(logMsgBuilder.toString());
            StoragePool storagePool = _dbClient.queryObject(StoragePool.class, poolURI);
            MultiVolumeTaskCompleter completer = new MultiVolumeTaskCompleter(volumeURIs, volumeCompleters, opId);

            Volume volume = volumes.get(0);
            VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());

            // All volumes are in the same storage pool with the same capacity. Get recommendation for the first volume.
            MetaVolumeRecommendation recommendation = MetaVolumeUtils.getCreateRecommendation(storageSystem, storagePool,
                    volume.getCapacity(), volume.getThinlyProvisioned(), vpool.getFastExpansion(), capabilities);

            for (Volume metaVolume : volumes) {
                MetaVolumeUtils.prepareMetaVolume(metaVolume, recommendation.getMetaMemberSize(), recommendation.getMetaMemberCount(),
                        recommendation.getMetaVolumeType().toString(), _dbClient);
            }

            WorkflowStepCompleter.stepExecuting(completer.getOpId());
            getDevice(storageSystem.getSystemType()).doCreateMetaVolumes(storageSystem,
                    storagePool, volumes, capabilities, recommendation, completer);

            logMsgBuilder = new StringBuilder(String.format(
                    "createMetaVolumes end - Array:%s Pool:%s", systemURI.toString(),
                    poolURI.toString()));
            volumeURIsIter = volumeURIs.iterator();
            while (volumeURIsIter.hasNext()) {
                logMsgBuilder.append(String.format("%nVolume:%s", volumeURIsIter.next()
                        .toString()));
            }
            _log.info(logMsgBuilder.toString());
        } catch (InternalException e) {
            _log.error(String.format("createMetaVolumes Failed - Array: %s Pool:%s Volume:%s",
                    systemURI.toString(), poolURI.toString(), Joiner.on("\t").join(volumeURIs)));
            doFailTask(Volume.class, volumeURIs, opId, e);
            WorkflowStepCompleter.stepFailed(opId, e);
            opCreateFailed = true;
        } catch (Exception e) {
            _log.error(String.format("createMetaVolumes Failed - Array: %s Pool:%s Volume:%s",
                    systemURI.toString(), poolURI.toString(), Joiner.on("\t").join(volumeURIs)));
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            doFailTask(Volume.class, volumeURIs, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            opCreateFailed = true;
        }
        if (opCreateFailed) {
            for (Volume volume : volumes) {
                volume.setInactive(true);
                _dbClient.updateObject(volume);
            }
        }
    }

    /**
     * {@inheritDoc} NOTE NOTE: The signature here MUST match the Workflow.Method createMetaVolumeMethod just above
     * (except opId).
     */
    @Override
    public void createMetaVolume(URI systemURI, URI poolURI, URI volumeURI,
            VirtualPoolCapabilityValuesWrapper capabilities, String opId) throws ControllerException {

        try {
            StringBuilder logMsgBuilder = new StringBuilder(String.format(
                    "createMetaVolume start - Array:%s Pool:%s, Volume:%s", systemURI.toString(),
                    poolURI.toString(), volumeURI.toString()));
            _log.info(logMsgBuilder.toString());

            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, systemURI);
            Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
            StoragePool storagePool = _dbClient.queryObject(StoragePool.class, poolURI);
            VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());

            MetaVolumeRecommendation recommendation = MetaVolumeUtils.getCreateRecommendation(storageSystem, storagePool,
                    volume.getCapacity(), volume.getThinlyProvisioned(), vpool.getFastExpansion(), capabilities);
            MetaVolumeUtils.prepareMetaVolume(volume, recommendation.getMetaMemberSize(), recommendation.getMetaMemberCount(),
                    recommendation.getMetaVolumeType().toString(), _dbClient);

            VolumeCreateCompleter completer = new VolumeCreateCompleter(volumeURI, opId);
            WorkflowStepCompleter.stepExecuting(completer.getOpId());
            getDevice(storageSystem.getSystemType()).doCreateMetaVolume(storageSystem,
                    storagePool, volume, capabilities, recommendation, completer);

            logMsgBuilder = new StringBuilder(String.format(
                    "createMetaVolume end - Array:%s Pool:%s, Volume:%s", systemURI.toString(),
                    poolURI.toString(), volumeURI.toString()));
            _log.info(logMsgBuilder.toString());
        } catch (InternalException e) {
            _log.error(String.format("createMetaVolume Failed - Array:%s Pool:%s, Volume:%s", systemURI.toString(),
                    poolURI.toString(), volumeURI.toString()));
            doFailTask(Volume.class, volumeURI, opId, e);
            WorkflowStepCompleter.stepFailed(opId, e);
        } catch (Exception e) {
            _log.error(String.format("createMetaVolume Failed - Array:%s Pool:%s, Volume:%s", systemURI.toString(),
                    poolURI.toString(), volumeURI.toString()));
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            doFailTask(Volume.class, volumeURI, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    /**
     * Return a Workflow.Method for expandVolume.
     *
     * @param storage
     *            storage system
     * @param pool
     *            storage pool
     * @param volume
     *            volume to expand
     * @param size
     *            size to expand to
     * @return Workflow.Method
     */
    public static Workflow.Method expandVolumesMethod(URI storage, URI pool, URI volume, Long size) {
        return new Workflow.Method("expandVolume", storage, pool, volume, size);
    }

    /*
     * {@inheritDoc}
     * <p>
     * Single step workflow to expand volume with rollback.
     */
    @Override
    public void expandBlockVolume(URI storage, URI pool, URI volume, Long size, String opId)
            throws ControllerException {
        SimpleTaskCompleter completer = new SimpleTaskCompleter(Volume.class, volume, opId);

        try {
            WorkflowStepCompleter.stepExecuting(opId);;
            final String workflowKey = "expandBlockVolume";
            if (!WorkflowService.getInstance().hasWorkflowBeenCreated(opId, workflowKey)) {
                // Get a new workflow to execute volume expand
                Workflow workflow = _workflowService.getNewWorkflow(this,
                        EXPAND_VOLUME_WF_NAME, false, opId);
                _log.info("Created new expansion workflow with operation id {}", opId);

                String stepId = workflow.createStepId();
                workflow.createStep(BLOCK_VOLUME_EXPAND_GROUP, String.format(
                        "Expand volume %s", volume), null,
                        storage, getDeviceType(storage),
                        BlockDeviceController.class,
                        expandVolumesMethod(storage, pool, volume, size),
                        rollbackExpandVolumeMethod(storage, volume, stepId),
                        stepId);
                _log.info("Executing workflow plan {}", BLOCK_VOLUME_EXPAND_GROUP);

                workflow.executePlan(completer, String.format(
                        "Expansion of volume %s completed successfully", volume));
                // Mark this workflow as created/executed so we don't do it again on retry/resume
                WorkflowService.getInstance().markWorkflowBeenCreated(opId, workflowKey);
            }
        } catch (Exception ex) {
            _log.error("Could not expand volume: " + volume, ex);
            String opName = ResourceOperationTypeEnum.EXPAND_BLOCK_VOLUME.getName();
            ServiceError serviceError = DeviceControllerException.errors.expandVolumeFailed(
                    volume.toString(), opName, ex);
            completer.error(_dbClient, serviceError);
        }
    }

    /*
     * Add workflow steps for volume expand.
     */
    @Override
    public String addStepsForExpandVolume(Workflow workflow, String waitFor, List<VolumeDescriptor> volumeDescriptors, String taskId)
            throws InternalException {

        // The the list of Volumes that the BlockDeviceController needs to process.
        volumeDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                new VolumeDescriptor.Type[] {
                        VolumeDescriptor.Type.BLOCK_DATA,
                        VolumeDescriptor.Type.RP_SOURCE,
                        VolumeDescriptor.Type.RP_TARGET,
                        VolumeDescriptor.Type.RP_EXISTING_SOURCE,
                        VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE,
                        VolumeDescriptor.Type.RP_VPLEX_VIRT_TARGET
                }, null);
        if (volumeDescriptors == null || volumeDescriptors.isEmpty()) {
            return waitFor;
        }

        Map<URI, Long> volumesToExpand = new HashMap<URI, Long>();

        // Check to see if there are any migrations
        List<Migration> migrations = null;
        if (volumeDescriptors != null) {
            List<VolumeDescriptor> migrateDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                    new VolumeDescriptor.Type[] { VolumeDescriptor.Type.VPLEX_MIGRATE_VOLUME }, null);

            if (migrateDescriptors != null && !migrateDescriptors.isEmpty()) {
                // Load the migration objects for use later
                migrations = new ArrayList<Migration>();
                Iterator<VolumeDescriptor> migrationIter = migrateDescriptors.iterator();
                while (migrationIter.hasNext()) {
                    Migration migration = _dbClient.queryObject(Migration.class, migrationIter.next().getMigrationId());
                    migrations.add(migration);
                }
            }
        }

        for (VolumeDescriptor descriptor : volumeDescriptors) {
            // Grab the volume, let's see if an expand is really needed
            Volume volume = _dbClient.queryObject(Volume.class, descriptor.getVolumeURI());

            // If this volume is a VPLEX volume, check to see if we need to expand its backend volume.
            if (volume.getAssociatedVolumes() != null && !volume.getAssociatedVolumes().isEmpty()) {
                for (String volStr : volume.getAssociatedVolumes()) {
                    URI volStrURI = URI.create(volStr);
                    Volume associatedVolume = _dbClient.queryObject(Volume.class, volStrURI);

                    boolean migrationExists = false;
                    // If there are any volumes that are tagged for migration, ignore them.
                    if (migrations != null && !migrations.isEmpty()) {
                        for (Migration migration : migrations) {
                            if (migration.getTarget().equals(volume.getId())) {
                                _log.info("Volume [{}] has a migration, ignore this volume for expand.", volume.getLabel());
                                migrationExists = true;
                                break;
                            }
                        }
                    }

                    // Only expand backend volume if there is no existing migration and
                    // the new size > existing backend volume's provisioned capacity, otherwise we can ignore.
                    if (!migrationExists
                            && associatedVolume.getProvisionedCapacity() != null
                            && descriptor.getVolumeSize() > associatedVolume.getProvisionedCapacity().longValue()) {
                        volumesToExpand.put(volStrURI, descriptor.getVolumeSize());
                    }
                }
            } else {
                // Only expand the volume if it's an existing volume (provisoned capacity is not null and not 0) and
                // new size > existing volume's provisioned capacity, otherwise we can ignore.
                if (volume.getProvisionedCapacity() != null
                        && volume.getProvisionedCapacity().longValue() != 0
                        && descriptor.getVolumeSize() > volume.getProvisionedCapacity().longValue()) {
                    volumesToExpand.put(volume.getId(), descriptor.getVolumeSize());
                }
            }
        }

        String nextStep = (volumesToExpand.size() > 0) ? BLOCK_VOLUME_EXPAND_GROUP : waitFor;

        for (Map.Entry<URI, Long> entry : volumesToExpand.entrySet()) {
            _log.info("Creating WF step for Expand Volume for  {}", entry.getKey().toString());
            Volume volumeToExpand = _dbClient.queryObject(Volume.class, entry.getKey());
            StorageSystem storage = _dbClient.queryObject(StorageSystem.class, volumeToExpand.getStorageController());
            String stepId = workflow.createStepId();
            workflow.createStep(
                    BLOCK_VOLUME_EXPAND_GROUP,
                    String.format(
                            "Expand Block volume %s", volumeToExpand),
                    waitFor,
                    storage.getId(),
                    getDeviceType(storage.getId()),
                    BlockDeviceController.class,
                    expandVolumesMethod(volumeToExpand.getStorageController(), volumeToExpand.getPool(), volumeToExpand.getId(),
                            entry.getValue()),
                    rollbackExpandVolumeMethod(volumeToExpand.getStorageController(), volumeToExpand.getId(), stepId),
                    stepId);
            _log.info("Creating workflow step {}", BLOCK_VOLUME_EXPAND_GROUP);
        }

        return nextStep;
    }

    @Override
    public void expandVolume(URI storage, URI pool, URI volume, Long size, String opId)
            throws ControllerException {
        try {
            StorageSystem storageObj = _dbClient
                    .queryObject(StorageSystem.class, storage);
            Volume volumeObj = _dbClient.queryObject(Volume.class, volume);
            _log.info(String.format(
                    "expandVolume start - Array: %s Pool:%s Volume:%s, IsMetaVolume: %s, OldSize: %s, NewSize: %s",
                    storage.toString(), pool.toString(), volume.toString(), volumeObj.getIsComposite(), volumeObj.getCapacity(), size));
            StoragePool poolObj = _dbClient.queryObject(StoragePool.class, pool);
            VolumeExpandCompleter completer = new VolumeExpandCompleter(volume, size, opId);

            long metaMemberSize = volumeObj.getIsComposite() ? volumeObj.getMetaMemberSize() : volumeObj.getCapacity();
            long metaCapacity = volumeObj.getIsComposite() ? volumeObj.getTotalMetaMemberCapacity() : volumeObj.getCapacity();

            VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, volumeObj.getVirtualPool());
            boolean isThinlyProvisioned = volumeObj.getThinlyProvisioned();
            MetaVolumeRecommendation recommendation = MetaVolumeUtils.getExpandRecommendation(storageObj, poolObj,
                    metaCapacity, size, metaMemberSize, isThinlyProvisioned, vpool.getFastExpansion());
            if (recommendation.isCreateMetaVolumes()) {
                // check if we are required to create any members.
                // When expansion size fits into total meta member size, no new members should be created.
                // Also check that this is not recovery to clean dangling meta volumes with zero-capacity expansion.
                if (recommendation.getMetaMemberCount() == 0 && (volumeObj.getMetaVolumeMembers() == null ||
                        volumeObj.getMetaVolumeMembers().isEmpty())) {
                    volumeObj.setCapacity(size);
                    _dbClient.updateObject(volumeObj);
                    _log.info(String
                            .format(
                                    "Expanded volume within its total meta volume capacity (simple case) - Array: %s Pool:%s Volume:%s, IsMetaVolume: %s, Total meta volume capacity: %s, NewSize: %s",
                                    storage.toString(), pool.toString(), volume.toString(), volumeObj.getIsComposite(),
                                    volumeObj.getTotalMetaMemberCapacity(), volumeObj.getCapacity()));
                    completer.ready(_dbClient);
                } else {
                    // set meta related data in task completer
                    long metaMemberCount = volumeObj.getIsComposite() ? recommendation.getMetaMemberCount()
                            + volumeObj.getMetaMemberCount() : recommendation.getMetaMemberCount() + 1;
                    completer.setMetaMemberSize(recommendation.getMetaMemberSize());
                    completer.setMetaMemberCount((int) metaMemberCount);
                    completer.setTotalMetaMembersSize(metaMemberCount * recommendation.getMetaMemberSize());
                    completer.setComposite(true);
                    completer.setMetaVolumeType(recommendation.getMetaVolumeType().toString());

                    getDevice(storageObj.getSystemType()).doExpandAsMetaVolume(storageObj, poolObj,
                            volumeObj, size, recommendation, completer);
                }
            } else {
                // expand as regular volume
                getDevice(storageObj.getSystemType()).doExpandVolume(storageObj, poolObj,
                        volumeObj, size, completer);
            }
            _log.info(String.format("expandVolume end - Array: %s Pool:%s Volume:%s",
                    storage.toString(), pool.toString(), volume.toString()));
        } catch (Exception e) {
            _log.error(String.format("expandVolume Failed - Array: %s Pool:%s Volume:%s",
                    storage.toString(), pool.toString(), volume.toString()), e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            List<URI> volumes = Arrays.asList(volume);
            doFailTask(Volume.class, volumes, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    static final String DELETE_VOLUMES_STEP_GROUP = "BlockDeviceDeleteVolumes";

    /**
     * {@inheritDoc}
     */
    @Override
    public String addStepsForDeleteVolumes(Workflow workflow, String waitFor,
            List<VolumeDescriptor> volumes, String taskId) throws ControllerException {

        // The the list of Volumes that the BlockDeviceController needs to process.
        volumes = VolumeDescriptor.filterByType(volumes,
                new VolumeDescriptor.Type[] {
                        VolumeDescriptor.Type.BLOCK_DATA,
                        VolumeDescriptor.Type.RP_JOURNAL,
                        VolumeDescriptor.Type.RP_TARGET,
                        VolumeDescriptor.Type.RP_VPLEX_VIRT_JOURNAL,
                        VolumeDescriptor.Type.RP_VPLEX_VIRT_TARGET
                }, null);
        // Check to see if there are any volumes flagged to not be fully deleted.
        // Any flagged volumes will be removed from the list of volumes to delete.
        List<VolumeDescriptor> doNotDeleteDescriptors = VolumeDescriptor.getDoNotDeleteDescriptors(volumes);
        if (doNotDeleteDescriptors != null
                && !doNotDeleteDescriptors.isEmpty()) {
            // If there are volumes we do not want fully deleted, remove
            // those volumes here.
            volumes.removeAll(doNotDeleteDescriptors);
        }

        // If there are no volumes, just return
        if (volumes.isEmpty()) {
            return waitFor;
        }

        // Segregate by device.
        Map<URI, List<VolumeDescriptor>> deviceMap = VolumeDescriptor.getDeviceMap(volumes);

        // Add a step to delete the volumes in each device.
        for (URI deviceURI : deviceMap.keySet()) {
            volumes = deviceMap.get(deviceURI);
            List<URI> volumeURIs = VolumeDescriptor.getVolumeURIs(volumes);

            workflow.createStep(DELETE_VOLUMES_STEP_GROUP,
                    String.format("Deleting volumes:%n%s", getVolumesMsg(_dbClient, volumeURIs)),
                    waitFor, deviceURI, getDeviceType(deviceURI),
                    this.getClass(),
                    deleteVolumesMethod(deviceURI, volumeURIs),
                    null, null);
        }
        return DELETE_VOLUMES_STEP_GROUP;
    }

    /**
     * Return a Workflow.Method for deleteVolumes.
     *
     * @param systemURI
     * @param volumeURIs
     * @return
     */
    private Workflow.Method deleteVolumesMethod(URI systemURI, List<URI> volumeURIs) {
        return new Workflow.Method("deleteVolumes", systemURI, volumeURIs);
    }

    /**
     * {@inheritDoc} NOTE NOTE: The arguments here must match deleteVolumesMethod defined above (except opId).
     */
    @Override
    public void deleteVolumes(URI systemURI, List<URI> volumeURIs, String opId) throws ControllerException {
        MultiVolumeTaskCompleter completer = new MultiVolumeTaskCompleter(volumeURIs, opId);
        deleteVolumesWithCompleter(systemURI, volumeURIs, completer);
    }

    /**
     * Deletes the given volumes with an existing task completer.
     *
     * @param systemURI     Storage system URI
     * @param volumeURIs    List of Volume URI
     * @param completer     Task completer
     * @throws ControllerException
     */
    public void deleteVolumesWithCompleter(URI systemURI, List<URI> volumeURIs, MultiVolumeTaskCompleter completer)
            throws ControllerException {

        String opId = completer.getOpId();

        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class,
                    systemURI);
            List<Volume> volumes = new ArrayList<>();
            Iterator<URI> volumeURIsIter = volumeURIs.iterator();
            String arrayName = systemURI.toString();
            StringBuilder entryLogMsgBuilder = new StringBuilder(String.format(
                    "deleteVolume start - Array:%s", arrayName));
            StringBuilder exitLogMsgBuilder = new StringBuilder(String.format(
                    "deleteVolume end - Array:%s", arrayName));
            while (volumeURIsIter.hasNext()) {
                URI volumeURI = volumeURIsIter.next();
                Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
                String poolId = NullColumnValueGetter.isNullURI(volume.getPool()) ? "null" : volume.getPool().toString();
                entryLogMsgBuilder.append(String.format("%nPool:%s Volume:%s", poolId, volumeURI.toString()));
                exitLogMsgBuilder.append(String.format("%nPool:%s Volume:%s", poolId, volumeURI.toString()));
                VolumeDeleteCompleter volumeCompleter = new VolumeDeleteCompleter(volumeURI, opId);
                if (volume.getInactive() == false) {
                    // It is possible that there is a BlockSnaphot instance that references the
                    // same device Volume if a VPLEX virtual volume has been created from the
                    // snapshot. If this is the case then the VPLEX volume is being deleted and
                    // volume is represents the source side backend volume for that VPLEX volume.
                    // In this case, we won't delete the backend volume because the volume is
                    // still a block snapshot target and the deletion would fail. The volume will
                    // be deleted when the BlockSnapshot instance is deleted. All we want to do is
                    // mark the Volume instance inactive.
                    // COP-20875: Native Guid will not be set when there is error during create volume operation
                    if (!NullColumnValueGetter.isNullValue(volume.getNativeGuid())) {
                        List<BlockSnapshot> snapshots = CustomQueryUtility
                                .getActiveBlockSnapshotByNativeGuid(_dbClient, volume.getNativeGuid());
                        if (!snapshots.isEmpty()) {
                            volume.setInactive(true);
                            _dbClient.updateObject(volume);
                            continue;
                        }
                    }

                    // Add the volume to the list to delete
                    volumes.add(volume);
                } else {
                    // Add the proper status, since we won't be deleting this volume
                    String opName = ResourceOperationTypeEnum.DELETE_BLOCK_VOLUME.getName();
                    ServiceError serviceError = DeviceControllerException.errors.jobFailedOp(opName);
                    serviceError.setMessage("Volume does not exist or is already deleted");
                    _log.info("Volume does not exist or is already deleted");
                    volumeCompleter.error(_dbClient, serviceError);
                }
                volumeCompleter.setRollingBack(completer.isRollingBack());
                completer.addVolumeCompleter(volumeCompleter);
            }
            _log.info(entryLogMsgBuilder.toString());
            if (!volumes.isEmpty()) {
                WorkflowStepCompleter.stepExecuting(opId);
                getDevice(storageSystem.getSystemType()).doDeleteVolumes(storageSystem, opId,
                        volumes, completer);
            } else {
                doSuccessTask(Volume.class, volumeURIs, opId);
                WorkflowStepCompleter.stepSucceded(opId);
            }
            _log.info(exitLogMsgBuilder.toString());
        } catch (Exception e) {
            handleException(e, completer);
        }
    }

    /**
     * Add Steps to perform untag operations on underlying array for the volumes passed in.
     *
     * @param workflow
     *            -- The Workflow being built
     * @param waitFor
     *            -- Previous steps to waitFor
     * @param volumes
     *            -- List<Volume> -- volumes of all types to be processed
     * @return last step added to waitFor
     * @throws ControllerException
     */
    public String addStepsForUntagVolumes(Workflow workflow, String waitFor,
            List<VolumeDescriptor> volumes, String taskId) throws ControllerException {
        // The the list of Volumes that the BlockDeviceController needs to process.
        List<VolumeDescriptor> untagVolumeDescriptors = VolumeDescriptor.filterByType(volumes,
                new VolumeDescriptor.Type[] {
                        VolumeDescriptor.Type.BLOCK_DATA },
                null);

        // If there are no volumes, just return
        if (untagVolumeDescriptors.isEmpty()) {
            return waitFor;
        }

        Map<URI, List<VolumeDescriptor>> untagVolumeDeviceMap = VolumeDescriptor.getDeviceMap(untagVolumeDescriptors);

        // Add a step to perform an untag operation for all volumes in each device.
        for (URI deviceURI : untagVolumeDeviceMap.keySet()) {
            if (deviceURI != null) {
                untagVolumeDescriptors = untagVolumeDeviceMap.get(deviceURI);
                List<URI> volumeURIs = VolumeDescriptor.getVolumeURIs(untagVolumeDescriptors);

                workflow.createStep(UNTAG_VOLUME_STEP_GROUP,
                        String.format("Untagging volumes:%n%s", getVolumesMsg(_dbClient, volumeURIs)),
                        waitFor, deviceURI, getDeviceType(deviceURI),
                        this.getClass(),
                        untagVolumesMethod(deviceURI, volumeURIs),
                        rollbackMethodNullMethod(), null);
                _log.info(String.format("Adding step to untag volumes (%s)", Joiner.on(",").join(volumeURIs)));
            }
        }

        return UNTAG_VOLUME_STEP_GROUP;
    }

    /**
     * Return a Workflow.Method for untagVolumes.
     *
     * @param systemURI
     *            The system to perform the action on
     * @param volumeURIs
     *            The volumes to perform the action on
     * @return the new WF
     */
    private Workflow.Method untagVolumesMethod(URI systemURI, List<URI> volumeURIs) {
        return new Workflow.Method("untagVolumes", systemURI, volumeURIs);
    }

    /**
     * Performs an untag operation on all volumes.
     *
     * @param systemURI
     *            Underlying system to perform the untag operation on
     * @param volumeURIs
     *            Volumes to untag
     * @param opId
     *            The opId
     * @throws ControllerException
     */
    public void untagVolumes(URI systemURI, List<URI> volumeURIs, String opId)
            throws ControllerException {
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class,
                    systemURI);
            List<Volume> volumes = new ArrayList<Volume>();
            List<VolumeTaskCompleter> volumeCompleters = new ArrayList<VolumeTaskCompleter>();
            Iterator<URI> volumeURIsIter = volumeURIs.iterator();
            String arrayName = systemURI.toString();
            StringBuilder entryLogMsgBuilder = new StringBuilder(String.format(
                    "untagVolume start - Array:%s", arrayName));
            StringBuilder exitLogMsgBuilder = new StringBuilder(String.format(
                    "untagVolume end - Array:%s", arrayName));
            while (volumeURIsIter.hasNext()) {
                URI volumeURI = volumeURIsIter.next();
                Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
                if (volume != null) {
                    entryLogMsgBuilder.append(String.format("%nUntag operation: Volume: [%s](%s)",
                            volume.getLabel(), volumeURI.toString()));
                    exitLogMsgBuilder.append(String.format("%nUntag operation: Volume: [%s](%s)",
                            volume.getLabel(), volumeURI.toString()));
                    if (!volume.getInactive()) {
                        volumes.add(volume);
                    } else {
                        // Nothing to do for an inactive volume
                        continue;
                    }
                    // Generic completer is fine here
                    VolumeWorkflowCompleter volumeCompleter = new VolumeWorkflowCompleter(volumeURI, opId);
                    volumeCompleters.add(volumeCompleter);
                }
            }
            _log.info(entryLogMsgBuilder.toString());
            if (!volumes.isEmpty()) {
                WorkflowStepCompleter.stepExecuting(opId);
                TaskCompleter completer = new MultiVolumeTaskCompleter(volumeURIs,
                        volumeCompleters, opId);
                getDevice(storageSystem.getSystemType()).doUntagVolumes(storageSystem, opId,
                        volumes, completer);
            }
            doSuccessTask(Volume.class, volumeURIs, opId);
            WorkflowStepCompleter.stepSucceded(opId);
            _log.info(exitLogMsgBuilder.toString());
        } catch (InternalException e) {
            doFailTask(Volume.class, volumeURIs, opId, e);
            WorkflowStepCompleter.stepFailed(opId, e);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            doFailTask(Volume.class, volumeURIs, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, DeviceControllerException.exceptions.unexpectedCondition(e.getMessage()));
        }
    }

    /**
     * Workflow step to delete a volume
     *
     * @param storageURI
     *            the storage system ID
     * @param volumes
     *            the volume IDs
     * @param token
     *            the task ID from the workflow
     * @return true if the step was fired off properly.
     * @throws WorkflowException
     */
    public boolean deleteVolumeStep(URI storageURI, List<URI> volumes, String token) throws WorkflowException {
        boolean status = true;
        String volumeList = Joiner.on(',').join(volumes);
        try {
            WorkflowStepCompleter.stepExecuting(token);
            _log.info("Delete Volume Step Started. " + volumeList);
            deleteVolumes(storageURI, volumes, token);
            _log.info("Delete Volume Step Dispatched: " + volumeList);
        } catch (Exception ex) {
            _log.error("Delete Volume Step Failed." + volumeList);
            String opName = ResourceOperationTypeEnum.DELETE_VOLUME_WORKFLOW_STEP.getName();
            ServiceError serviceError = DeviceControllerException.errors.jobFailedOp(opName);
            WorkflowStepCompleter.stepFailed(token, serviceError);
            status = false;
        }
        return status;
    }

    @Override
    public void createSingleSnapshot(URI storage, List<URI> snapshotList, Boolean createInactive, Boolean readOnly, String opId)
            throws ControllerException {
        WorkflowStepCompleter.stepExecuting(opId);
        TaskCompleter completer = null;
        try {
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockSnapshotCreateCompleter(snapshotList, opId);
            getDevice(storageObj.getSystemType()).doCreateSingleSnapshot(storageObj, snapshotList, createInactive, readOnly, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                WorkflowStepCompleter.stepFailed(opId, serviceError);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.createVolumeSnapshotFailed(e);
            }
        }
    }

    @Override
    public void createSnapshot(URI storage, List<URI> snapshotList, Boolean createInactive, Boolean readOnly, String opId)
            throws ControllerException {
        TaskCompleter completer = null;
        try {
            boolean isListReplicaFlow = false;
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            BlockSnapshot snapshotObj = _dbClient.queryObject(BlockSnapshot.class, snapshotList.get(0));
            Volume sourceVolumeObj = _dbClient.queryObject(Volume.class, snapshotObj.getParent().getURI());
            URI cgURI = null;
            completer = new BlockSnapshotCreateCompleter(snapshotList, opId);

            /**
             * VPLEX/RP CG volumes may not be having back end Array Group.
             * In this case we should create element replica using createListReplica.
             * We should not use createGroup replica as backend cg will not be available in this case.
             */
            boolean isVnxVolume = ControllerUtils.isVnxVolume(sourceVolumeObj, _dbClient);
            // VNX doesn't support list replica for snapshot
            isListReplicaFlow = !isVnxVolume && isListReplicaFlow(sourceVolumeObj);

            if (!isListReplicaFlow) {
                getDevice(storageObj.getSystemType()).doCreateSnapshot(storageObj, snapshotList, createInactive, readOnly, completer);
            } else {
                // List Replica
                completer.addConsistencyGroupId(cgURI);
                getDevice(storageObj.getSystemType()).doCreateListReplica(storageObj, snapshotList, createInactive, completer);
            }

        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.createVolumeSnapshotFailed(e);
            }
        }
    }

    @Override
    public void activateSnapshot(URI storage, List<URI> snapshotList, String opId)
            throws ControllerException {
        TaskCompleter completer = null;
        try {
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockSnapshotActivateCompleter(snapshotList, opId);
            getDevice(storageObj.getSystemType()).doActivateSnapshot(storageObj, snapshotList, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.activateVolumeSnapshotFailed(e);
            }
        }
    }

    @Override
    public void deleteSnapshot(URI storage, URI snapshot, String opId) throws ControllerException {
        _log.info("START deleteSnapshot");
        TaskCompleter completer = null;
        WorkflowStepCompleter.stepExecuting(opId);
        try {
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            BlockSnapshot snapObj = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            completer = BlockSnapshotDeleteCompleter.createCompleter(_dbClient, snapObj, opId);
            getDevice(storageObj.getSystemType()).doDeleteSnapshot(storageObj, snapshot, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.deleteVolumeSnapshotFailed(e);
            }
        }
    }

    @Override
    public void establishVolumeAndSnapshotGroupRelation(URI storage, URI sourceVolume, URI snapshot, String opId)
            throws ControllerException {
        _log.info("START establishVolumeAndSnapshotGroupRelation workflow");

        Workflow workflow = _workflowService.getNewWorkflow(this, ESTABLISH_VOLUME_SNAPSHOT_GROUP_WF_NAME, false, opId);
        TaskCompleter taskCompleter = null;
        StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);

        try {
            workflow.createStep("establishStep", "create group relation between Volume group and Snapshot group", null, storage,
                    storageObj.getSystemType(),
                    this.getClass(), establishVolumeAndSnapshotGroupRelationMethod(storage, sourceVolume, snapshot), null, null);

            taskCompleter = new BlockSnapshotEstablishGroupTaskCompleter(snapshot, opId);
            workflow.executePlan(taskCompleter, "Successfully created group relation between Volume group and Snapshot group");
        } catch (Exception e) {
            String msg = String.format("Failed to create group relation between Volume group and Snapshot group."
                    + "Source volume: %s, Snapshot: %s",
                    sourceVolume, snapshot);
            _log.error(msg, e);
            if (taskCompleter != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                taskCompleter.error(_dbClient, serviceError);
            }
        }
    }

    private Workflow.Method establishVolumeAndSnapshotGroupRelationMethod(URI storage, URI sourceVolume, URI snapshot) {
        return new Workflow.Method("establishVolumeSnapshotGroupRelation", storage, sourceVolume, snapshot);
    }

    public void establishVolumeSnapshotGroupRelation(
            URI storage, URI sourceVolume, URI snapshot, String opId)
                    throws ControllerException {
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            TaskCompleter completer = new BlockSnapshotEstablishGroupTaskCompleter(snapshot, opId);
            getDevice(storageObj.getSystemType())
                    .doEstablishVolumeSnapshotGroupRelation(
                            storageObj, sourceVolume, snapshot, completer);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    @Override
    public String addStepsForRestoreVolume(Workflow workflow, String waitFor,
            URI storage, URI pool, URI volume, URI snapshot, Boolean updateOpStatus, String syncDirection, String taskId,
            BlockSnapshotRestoreCompleter completer) throws ControllerException {

        BlockSnapshot snap = _dbClient.queryObject(BlockSnapshot.class, snapshot);

        URI parentVolumeURI = snap.getParent().getURI();
        Volume parentVolume = _dbClient.queryObject(Volume.class, parentVolumeURI);
        Volume associatedVPlexVolume = Volume.fetchVplexVolume(_dbClient, parentVolume);

        // Do nothing if this is not a native snapshot
        // Do not add block restore steps if this is a snapshot of a VPlex volume. The
        // VPlex controller will add the required block restore steps.
        if (NullColumnValueGetter.isNotNullValue(snap.getTechnologyType()) &&
                !snap.getTechnologyType().equals(TechnologyType.NATIVE.toString()) ||
                associatedVPlexVolume != null) {
            return waitFor;
        }

        Workflow.Method restoreVolumeMethod = new Workflow.Method(
                RESTORE_VOLUME_METHOD_NAME, storage, pool,
                volume, snapshot, Boolean.TRUE, syncDirection);
        workflow.createStep(RESTORE_VOLUME_STEP, String.format(
                "Restore volume %s from snapshot %s",
                volume, snapshot), waitFor,
                storage, getDeviceType(storage),
                BlockDeviceController.class, restoreVolumeMethod, rollbackMethodNullMethod(), null);
        _log.info(
                "Created workflow step to restore block volume {} from snapshot {}",
                volume, snapshot);

        return RESTORE_VOLUME_STEP;
    }

    private static final String BLOCK_VOLUME_RESTORE_GROUP = "BlockDeviceRestoreVolumeGroup";
    private static final String POST_BLOCK_VOLUME_RESTORE_GROUP = "PostBlockDeviceRestoreVolumeGroup";

    @Override
    public void restoreVolume(URI storage, URI pool, URI volumeURI, URI snapshot, Boolean updateOpStatus, String syncDirection, String opId)
            throws ControllerException {

        SimpleTaskCompleter completer = new SimpleTaskCompleter(BlockSnapshot.class, snapshot, opId);

        try {
            Workflow workflow = _workflowService.getNewWorkflow(this, RESTORE_VOLUME_WF_NAME, false, opId);
            _log.info("Created new restore workflow with operation id {}", opId);

            Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
            BlockSnapshot blockSnapshot = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            StorageSystem system = _dbClient.queryObject(StorageSystem.class, storage);

            String description = String.format("Restore volume %s from snapshot %s", volumeURI, snapshot);
            String waitFor = null;

            URI srdfSourceStorageSystemURI = null;
            Volume srdfSourceVolume = null;
            Volume srdfTargetVolume = null;
            boolean active = false;
            /**
             * We need to split the SRDF link for R2 snap restore if it is not paused already.
             * Refer OPT#476788
             */
            if (isNonSplitSRDFTargetVolume(volume)) {
                URI srdfSourceVolumeURI = volume.getSrdfParent().getURI();
                srdfSourceVolume = _dbClient.queryObject(Volume.class, srdfSourceVolumeURI);
                srdfTargetVolume = volume;
                srdfSourceStorageSystemURI = srdfSourceVolume.getStorageController();
                if (Mode.ACTIVE.equals(Mode.valueOf(volume.getSrdfCopyMode()))) {
                    active = true;
                    waitFor = suspendSRDFLinkWorkflowStep(waitFor, srdfSourceStorageSystemURI,
                            srdfSourceVolumeURI, volumeURI, workflow);
                } else {
                    // split all members the group
                    Workflow.Method splitMethod = srdfDeviceController.splitSRDFLinkMethod(srdfSourceStorageSystemURI,
                            srdfSourceVolumeURI, volumeURI, false);
                    Workflow.Method splitRollbackMethod = srdfDeviceController.resumeSyncPairMethod(srdfSourceStorageSystemURI,
                            srdfSourceVolumeURI, volumeURI);

                    waitFor = workflow.createStep(SRDFDeviceController.SPLIT_SRDF_MIRRORS_STEP_GROUP,
                            SRDFDeviceController.SPLIT_SRDF_MIRRORS_STEP_DESC, waitFor, srdfSourceStorageSystemURI,
                            getDeviceType(srdfSourceStorageSystemURI), SRDFDeviceController.class, splitMethod,
                            splitRollbackMethod, null);
                }
            } else if (isNonSplitSRDFSourceVolume(volume)) {
                srdfSourceVolume = volume;
                srdfSourceStorageSystemURI = volume.getStorageController();
                StringSet targets = volume.getSrdfTargets();
                if (null != targets) {
                    for (String target : targets) {
                        if (NullColumnValueGetter.isNotNullValue(target)) {
                            srdfTargetVolume = _dbClient.queryObject(Volume.class, URI.create(target));
                            if (null != srdfTargetVolume && Mode.ACTIVE.equals(Mode.valueOf(srdfTargetVolume.getSrdfCopyMode()))) {
                                active = true;
                                waitFor = suspendSRDFLinkWorkflowStep(waitFor, srdfSourceStorageSystemURI,
                                        volume.getId(), srdfTargetVolume.getId(), workflow);
                            }
                            break;
                        }
                    }
                }
            }

            if (system.checkIfVmax3()) {
                _log.info("Creating workflow for restore VMAX3 snapshot {}", blockSnapshot.getId());

                // To restore the source from a linked target volume for VMAX3 SnapVX, we must
                // do the following:
                //
                // 1. Terminate any stale restore sessions on the source.
                // 2. Create a temporary snapvx snapshot session of the linked target volume or target group.
                // 3. Link the source volume(s) of the BlockSnapshot(s) to the temporary snapshot session in copy mode.
                // 4. Wait for the data from the session to be copied to the source volume(s)
                // 5. Unlink the source volume(s) from the temporary snapvx snapshot session.
                // 6. Delete the temporary session.
                //
                // This is essentially restoring by creating a cascaded snapshot session or group
                // snapshot session on the linked target volume associated with the passed block
                // snapshot or associated linked target group in the case of a group operation.

                // Create a workflow step to terminate stale restore sessions.
                waitFor = workflow.createStep(BLOCK_VOLUME_RESTORE_GROUP,
                        String.format("Terminating VMAX restore session from %s to %s", blockSnapshot.getId(), volume.getId()),
                        waitFor, system.getId(), system.getSystemType(), BlockDeviceController.class,
                        terminateRestoreSessionsMethod(system.getId(), volume.getId(), blockSnapshot.getId()),
                        rollbackMethodNullMethod(), null);

                // Get all snapshots if this is a group snapshot.
                String replicationGroupName = null;
                List<BlockSnapshot> allSnapshots = new ArrayList<>();
                String replicationGroupId = blockSnapshot.getReplicationGroupInstance();
                if (!NullColumnValueGetter.isNullValue(replicationGroupId)) {
                    allSnapshots.addAll(ControllerUtils.getSnapshotsPartOfReplicationGroup(blockSnapshot, _dbClient));
                    int nameStartIndex = replicationGroupId.indexOf("+") + 1;
                    replicationGroupName = replicationGroupId.substring(nameStartIndex);
                } else {
                    allSnapshots.add(blockSnapshot);
                }

                // Create a temporary BlockSnapshot instance to represent the parent source volumes
                // for each block snapshot. Linking to a session required BlockSnapshot instances so
                // we need to create some to represent the source volume(s).
                StringSet linkedTargets = new StringSet();
                List<BlockSnapshot> sourceSnapshots = new ArrayList<>();
                List<URI> sourceSnapshotURIs = new ArrayList<>();
                URI cgURI = blockSnapshot.getConsistencyGroup();
                for (BlockSnapshot aSnapshot : allSnapshots) {
                    BlockObject aSourceObj = BlockObject.fetch(_dbClient, aSnapshot.getParent().getURI());
                    BlockSnapshot sourceSnapshot = new BlockSnapshot();
                    URI sourceSnapshotURI = URIUtil.createId(BlockSnapshot.class);
                    sourceSnapshot.setId(sourceSnapshotURI);
                    sourceSnapshot.setNativeId(aSourceObj.getNativeId());
                    sourceSnapshot.setParent(new NamedURI(aSnapshot.getId(), aSnapshot.getLabel()));
                    sourceSnapshot.setSourceNativeId(aSnapshot.getNativeId());
                    sourceSnapshot.setStorageController(storage);
                    sourceSnapshot.setSystemType(system.getSystemType());
                    if (!NullColumnValueGetter.isNullURI(cgURI)) {
                        sourceSnapshot.setConsistencyGroup(cgURI);
                    }
                    sourceSnapshot.addInternalFlags(Flag.INTERNAL_OBJECT);
                    sourceSnapshots.add(sourceSnapshot);
                    sourceSnapshotURIs.add(sourceSnapshotURI);
                    linkedTargets.add(sourceSnapshotURI.toString());
                }
                _dbClient.createObject(sourceSnapshots);

                // Create a BlockSnapshotSession instance to represent the temporary snapshot session.
                BlockSnapshotSession snapSession = new BlockSnapshotSession();
                URI snapSessionURI = URIUtil.createId(BlockSnapshotSession.class);
                snapSession.setId(snapSessionURI);
                snapSession.setLabel(blockSnapshot.getLabel() + System.currentTimeMillis());
                snapSession.setSessionLabel(snapSession.getLabel());
                snapSession.setProject(blockSnapshot.getProject());
                snapSession.setStorageController(storage);
                snapSession.addInternalFlags(Flag.INTERNAL_OBJECT);

                if (!NullColumnValueGetter.isNullURI(cgURI) && NullColumnValueGetter.isNotNullValue(replicationGroupName)) {
                    snapSession.setConsistencyGroup(cgURI);
                    snapSession.setReplicationGroupInstance(replicationGroupName);
                    snapSession.setSessionSetName(replicationGroupName);
                } else {
                    snapSession.setParent(new NamedURI(blockSnapshot.getId(), blockSnapshot.getLabel()));
                }
                snapSession.setLinkedTargets(linkedTargets);
                _dbClient.createObject(snapSession);

                // Now create a workflow step that will create the snapshot session.
                // This will create a group session in the case of a group operation.
                waitFor = workflow.createStep(CREATE_SNAPSHOT_SESSION_STEP_GROUP,
                        String.format("Create snapshot session %s for snapshot target volume %s", snapSessionURI, snapshot),
                        waitFor, storage, getDeviceType(storage), BlockDeviceController.class,
                        createBlockSnapshotSessionMethod(storage, snapSessionURI, replicationGroupName),
                        deleteBlockSnapshotSessionMethod(storage, snapSessionURI, replicationGroupName, Boolean.TRUE), null);

                // Create a workflow step to link the source volume for the passed snapshot
                // to the snapshot session create by the previous step. We link the source
                // volume in copy mode so that that the point-in-time copy of the snapshot
                // target volume represented by the snapshot session is copied to the source
                // volume. This is essentially the restore step so that the source will now
                // reflect the data on the snapshot target volume. This step will not complete
                // until the data is copied and the link has achieved the copied state. If this
                // is group operation the source target group will be linked to the created
                // group session.
                Workflow.Method linkMethod;

                if (!NullColumnValueGetter.isNullURI(cgURI) && NullColumnValueGetter.isNotNullValue(replicationGroupName)) {
                    linkMethod = linkBlockSnapshotSessionTargetGroupMethod(storage, snapSessionURI, sourceSnapshotURIs,
                            BlockSnapshotSession.CopyMode.copy.name(), Boolean.TRUE);
                } else {
                    linkMethod = linkBlockSnapshotSessionTargetMethod(storage, snapSessionURI, sourceSnapshotURIs.get(0),
                            BlockSnapshotSession.CopyMode.copy.name(), Boolean.TRUE);
                }

                waitFor = workflow.createStep(
                        LINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP,
                        String.format("Link source volume %s to snapshot session for snapshot target volume %s", volume, snapshot),
                        waitFor, storage, getDeviceType(storage), BlockDeviceController.class, linkMethod,
                        unlinkBlockSnapshotSessionTargetMethod(storage, snapSessionURI, sourceSnapshotURIs.get(0), Boolean.FALSE), null);

                // Once the data is fully copied to the source, we can unlink the source from the session.
                // Again, for a group operation, this will unlink the source group from the group session.
                waitFor = workflow.createStep(
                        UNLINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP,
                        String.format("Unlink source volume %s from snapshot session for snapshot target volume %s", volumeURI, snapshot),
                        waitFor, storage, getDeviceType(storage), BlockDeviceController.class,
                        unlinkBlockSnapshotSessionTargetMethod(storage, snapSessionURI, sourceSnapshotURIs.get(0), Boolean.FALSE),
                        rollbackMethodNullMethod(), null);

                // Finally create a step to delete the snapshot session we created on the snapshot
                // target volume.
                waitFor = workflow.createStep(
                        DELETE_SNAPSHOT_SESSION_STEP_GROUP,
                        String.format("Delete snapshot session %s for snapshot target volume %s", snapSessionURI, snapshot),
                        waitFor, storage, getDeviceType(storage), BlockDeviceController.class,
                        deleteBlockSnapshotSessionMethod(storage, snapSessionURI, replicationGroupName, Boolean.TRUE),
                        rollbackMethodNullMethod(), null);

                /*
                 * If Active mode then create a step to resume srdf group or restore R2 To R1 or do nothing.
                 * If syncdirection is not specified means its null then after R1 snapshot restore, resume.
                 * If syncdirection is not specified means its null then after R2 snapshot restore, restore R2 to R1.
                 * If syncdirection is SOURCE_TO_TARGET then after R1 or R2 snapshot restore, resume.
                 * If syncdirection is TARGET_TO_SOURCE then after R1 or R2 snapshot restore, restore R2 to R1.
                 * If syncdirection is NONE then do nothing, RDF group will stay in suspend state.
                 */
                if (active) {
                    if (null == syncDirection) {
                        if (null != srdfSourceVolume && volumeURI.equals(srdfSourceVolume.getId())) {
                            resumeSRDFLinkWorkflowStep(waitFor, srdfSourceStorageSystemURI, srdfSourceVolume.getId(),
                                    srdfTargetVolume.getId(), workflow);
                        } else if (null != srdfTargetVolume && volumeURI.equals(srdfTargetVolume.getId())) {
                            restoreWorkflowStep(waitFor, srdfTargetVolume.getStorageController(), srdfSourceVolume.getId(),
                                    srdfTargetVolume.getId(), workflow);
                        }
                    } else if (null != syncDirection) {
                        if (SRDFUtils.SyncDirection.SOURCE_TO_TARGET.toString().equals(syncDirection)) {
                            resumeSRDFLinkWorkflowStep(waitFor, srdfSourceStorageSystemURI, srdfSourceVolume.getId(),
                                    srdfTargetVolume.getId(), workflow);
                        } else if (SRDFUtils.SyncDirection.TARGET_TO_SOURCE.toString().equals(syncDirection)) {
                            restoreWorkflowStep(waitFor, srdfTargetVolume.getStorageController(), srdfSourceVolume.getId(),
                                    srdfTargetVolume.getId(), workflow);
                        } else if (SRDFUtils.SyncDirection.NONE.toString().equals(syncDirection)) {
                            _log.info("Sync direction is specified as {} hence no action will be done after retsore snapshot which"
                                    + " means the RDF group for volume {} will be in a suspended state.",
                                    syncDirection, volume.getLabel());
                        }
                    }
                }
            } else {
                waitFor = workflow.createStep(BLOCK_VOLUME_RESTORE_GROUP, description, waitFor,
                        storage, getDeviceType(storage), BlockDeviceController.class,
                        restoreVolumeMethod(storage, pool, volumeURI, snapshot, updateOpStatus),
                        rollbackMethodNullMethod(), null);

                // Skip the step for VMAX3, as restore operation may still be in progress (OPT#476325)
                // Regardless, termination of restore session should be call before restore
                // Note this is not needed for VNX
                addPostRestoreVolumeSteps(workflow, system, volume, blockSnapshot, waitFor);
            }

            _log.info("Executing workflow {}", BLOCK_VOLUME_RESTORE_GROUP);

            String msg = String.format("Restore of volume %s from %s completed successfully", volumeURI, snapshot);
            workflow.executePlan(completer, msg);
        } catch (Exception e) {
            String msg = String.format("Could not restore volume %s from snapshot %s", volumeURI, snapshot);
            _log.error(msg, e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            completer.error(_dbClient, serviceError);
        }
    }

    private boolean isNonSplitSRDFTargetVolume(Volume volume) {
        _log.info("volume.getPersonality() : {} volume.getLinkStatus() : {} ", volume.getPersonality(), volume.getLinkStatus());
        return (volume != null && !NullColumnValueGetter.isNullNamedURI(volume.getSrdfParent()) &&
                Volume.PersonalityTypes.TARGET.toString().equalsIgnoreCase(volume.getPersonality())
                && !(Volume.LinkStatus.FAILED_OVER.name().equalsIgnoreCase(volume.getLinkStatus())
                        || Volume.LinkStatus.SUSPENDED.name().equalsIgnoreCase(volume.getLinkStatus())
                        || Volume.LinkStatus.SPLIT.name().equalsIgnoreCase(volume.getLinkStatus())));
    }

    private boolean isNonSplitSRDFSourceVolume(Volume volume) {
        _log.info("volume.getPersonality() : {} volume.getLinkStatus() : {} ", volume.getPersonality(), volume.getLinkStatus());
        return (volume != null && (volume.getSrdfTargets() != null && !volume.getSrdfTargets().isEmpty()) &&
                Volume.PersonalityTypes.SOURCE.toString().equalsIgnoreCase(volume.getPersonality())
                && !(Volume.LinkStatus.FAILED_OVER.name().equalsIgnoreCase(volume.getLinkStatus())
                        || Volume.LinkStatus.SUSPENDED.name().equalsIgnoreCase(volume.getLinkStatus())
                        || Volume.LinkStatus.SPLIT.name().equalsIgnoreCase(volume.getLinkStatus())));
    }

    private String suspendSRDFLinkWorkflowStep(String waitFor, URI srdfSourceStorageSystemURI,
            URI sourceURI, URI targetURI, Workflow workflow) {
        Workflow.Method suspendMethod = srdfDeviceController.suspendSRDFLinkMethod(srdfSourceStorageSystemURI, sourceURI, targetURI, false);
        Workflow.Method resumeMethod = srdfDeviceController.resumeSyncPairMethod(srdfSourceStorageSystemURI, sourceURI, targetURI);
        return workflow.createStep(SRDFDeviceController.SUSPEND_SRDF_MIRRORS_STEP_GROUP,
                SRDFDeviceController.SUSPEND_SRDF_MIRRORS_STEP_DESC, waitFor, srdfSourceStorageSystemURI,
                getDeviceType(srdfSourceStorageSystemURI), SRDFDeviceController.class, suspendMethod,
                resumeMethod, null);
    }

    private String resumeSRDFLinkWorkflowStep(String waitFor, URI srdfSourceStorageSystemURI,
            URI sourceURI, URI targetURI, Workflow workflow) {
        Workflow.Method resumeMethod = srdfDeviceController.resumeSyncPairMethod(srdfSourceStorageSystemURI, sourceURI, targetURI);
        return workflow.createStep(SRDFDeviceController.RESUME_SRDF_MIRRORS_STEP_GROUP,
                SRDFDeviceController.RESUME_SRDF_MIRRORS_STEP_DESC, waitFor, srdfSourceStorageSystemURI,
                getDeviceType(srdfSourceStorageSystemURI), SRDFDeviceController.class, resumeMethod,
                null, null);
    }

    private String restoreWorkflowStep(String waitFor, URI srdfTargetStorageSystemURI,
            URI sourceURI, URI targetURI, Workflow workflow) {
        Workflow.Method restoreMethod = srdfDeviceController.restoreMethod(srdfTargetStorageSystemURI, sourceURI, targetURI);
        return workflow.createStep(SRDFDeviceController.RESTORE_SRDF_MIRRORS_STEP_GROUP,
                SRDFDeviceController.RESTORE_SRDF_MIRRORS_STEP_DESC, waitFor, srdfTargetStorageSystemURI,
                getDeviceType(srdfTargetStorageSystemURI), SRDFDeviceController.class, restoreMethod,
                null, null);
    }

    /**
     * Return a Workflow.Method for restoreVolume
     *
     * @param storage
     *            storage system
     * @param pool
     *            storage pool
     * @param volume
     *            target of restore operation
     * @param snapshot
     *            snapshot to restore from
     * @param updateOpStatus
     *            update operation status flag
     * @return Workflow.Method
     */
    public static Workflow.Method restoreVolumeMethod(URI storage, URI pool, URI volume, URI snapshot,
            Boolean updateOpStatus) {
        return new Workflow.Method("restoreVolumeStep", storage, pool, volume, snapshot, updateOpStatus);
    }

    public boolean restoreVolumeStep(URI storage, URI pool, URI volume, URI snapshot, Boolean updateOpStatus,
            String opId) throws ControllerException {
        TaskCompleter completer = null;
        try {
            StorageSystem storageDevice = _dbClient.queryObject(StorageSystem.class, storage);
            BlockSnapshot snapObj = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            completer = new BlockSnapshotRestoreCompleter(snapObj, opId, updateOpStatus);
            getDevice(storageDevice.getSystemType()).doRestoreFromSnapshot(storageDevice, volume, snapshot, completer);
        } catch (Exception e) {
            _log.error(String.format("restoreVolume failed - storage: %s, pool: %s, volume: %s, snapshot: %s",
                    storage.toString(), pool.toString(), volume.toString(), snapshot.toString()));
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            completer.error(_dbClient, serviceError);
            doFailTask(BlockSnapshot.class, snapshot, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            return false;
        }
        return true;
    }

    private void addPostRestoreVolumeSteps(Workflow workflow, StorageSystem system, Volume sourceVolume,
            BlockSnapshot blockSnapshot, String waitFor) {
        _log.info("Creating post restore volume steps");
        if (Type.vmax.toString().equalsIgnoreCase(system.getSystemType())) {
            _log.info("Adding terminate restore session post-step for VMAX snapshot {}", blockSnapshot.getId());
            String description = String.format("Terminating VMAX restore session from %s to %s", blockSnapshot.getId(),
                    sourceVolume.getId());
            workflow.createStep(POST_BLOCK_VOLUME_RESTORE_GROUP, description, waitFor,
                    system.getId(), system.getSystemType(), BlockDeviceController.class,
                    terminateRestoreSessionsMethod(system.getId(), sourceVolume.getId(), blockSnapshot.getId()),
                    rollbackMethodNullMethod(), null);
        }
    }

    public static Workflow.Method terminateRestoreSessionsMethod(URI storage, URI source, URI snapshot) {
        return new Workflow.Method(TERMINATE_RESTORE_SESSIONS_METHOD, storage, source, snapshot);
    }

    public boolean terminateRestoreSessions(URI storage, URI source, URI snapshot, String opId) {
        _log.info("Terminating restore sessions for snapshot: {}", snapshot);
        TaskCompleter completer = null;
        try {
            StorageSystem storageDevice = _dbClient.queryObject(StorageSystem.class, storage);
            BlockSnapshot snapObj = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            completer = new SimpleTaskCompleter(BlockSnapshot.class, snapshot, opId);
            WorkflowStepCompleter.stepExecuting(opId);
            // Synchronous operation
            getDevice(storageDevice.getSystemType()).doTerminateAnyRestoreSessions(storageDevice, source, snapObj,
                    completer);
            completer.ready(_dbClient);
        } catch (Exception e) {
            _log.error(
                    String.format("Terminate restore sessions step failed - storage: %s, volume: %s, snapshot: %s",
                            storage.toString(), source.toString(), snapshot.toString()));
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            completer.error(_dbClient, serviceError);
            doFailTask(BlockSnapshot.class, snapshot, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            return false;
        }

        return true;
    }

    public Workflow.Method createMirrorMethod(URI storage, List<URI> mirrorList, Boolean isCG, Boolean createInactive) {
        return new Workflow.Method("createMirror", storage, mirrorList, isCG, createInactive);
    }

    /**
     * {@inheritDoc} NOTE NOTE: The signature here MUST match the Workflow.Method createMirrorMethod just above (except
     * opId).
     */
    @Override
    public void createMirror(URI storage, List<URI> mirrorList, Boolean isCG, Boolean createInactive, String opId)
            throws ControllerException {
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            if (!isCG && mirrorList.size() == 1) {
                completer = new BlockMirrorCreateCompleter(mirrorList.get(0), opId);
                getDevice(storageObj.getSystemType()).doCreateMirror(storageObj, mirrorList.get(0), createInactive, completer);
            } else {

                boolean isListReplicaFlow = false;
                BlockMirror mirrorObj = _dbClient.queryObject(BlockMirror.class, mirrorList.get(0));
                Volume sourceVolume = _dbClient.queryObject(Volume.class, mirrorObj.getSource().getURI());

                /**
                 * VPLEX/RP CG volumes may not be having back end Array Group.
                 * In this case we should create element replica using createListReplica.
                 * We should not use createGroup replica as backend cg will not be available in this case.
                 */
                isListReplicaFlow = isListReplicaFlow(sourceVolume);
                completer = new BlockMirrorCreateCompleter(mirrorList, opId);
                if (!isListReplicaFlow) {
                    getDevice(storageObj.getSystemType()).doCreateGroupMirrors(storageObj, mirrorList, createInactive, completer);
                } else {
                    // List Replica
                    getDevice(storageObj.getSystemType()).doCreateListReplica(storageObj, mirrorList, createInactive, completer);
                }
            }
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            if (completer != null) {
                completer.error(_dbClient, serviceError);
            }
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    public Workflow.Method rollbackMirrorMethod(URI storage, List<URI> mirrorList) {
        return new Workflow.Method("rollbackMirror", storage, mirrorList);
    }

    /**
     * {@inheritDoc} NOTE NOTE: The signature here MUST match the Workflow.Method rollbackMirrorMethod just above
     * (except opId).
     */
    public void rollbackMirror(URI storage, List<URI> mirrorList, String taskId) {
        WorkflowStepCompleter.stepExecuting(taskId);

        try {
            List<BlockMirror> mirrors = _dbClient.queryObject(BlockMirror.class, mirrorList);
            boolean isCG = isCGMirror(mirrorList.get(0), _dbClient);
            List<BlockMirror> mirrorsNoRollback = new ArrayList<BlockMirror>();
            for (BlockMirror mirror : mirrors) {
                // for CG mirror, filter out mirrors with invalid replication group. It is necessary as the
                // fracture/detach/delete will
                // expect mirrors with valid replication group
                // for non CG mirror, filter out mirror with no native Id
                if ((isCG && NullColumnValueGetter.isNullValue(mirror.getReplicationGroupInstance()) || (!isCG && isNullOrEmpty(mirror
                        .getNativeId())))) {
                    mirror.setInactive(true);
                    mirrorsNoRollback.add(mirror);
                }
            }

            if (!mirrorsNoRollback.isEmpty()) {
                _dbClient.updateObject(mirrorsNoRollback);
                mirrors.removeAll(mirrorsNoRollback);
            }

            if (!mirrors.isEmpty()) {
                List<URI> mirrorURIsToRollback = new ArrayList<URI>(transform(mirrors, FCTN_MIRROR_TO_URI));
                String mirrorNativeIds = Joiner.on(", ").join(transform(mirrors, fctnBlockObjectToNativeID()));

                if (mirrorIsPausable(mirrors)) {
                    _log.info("Attempting to fracture {} for rollback", mirrorNativeIds);
                    fractureMirror(storage, mirrorURIsToRollback, isCG, false, taskId);
                }

                _log.info("Attempting to detach {} for rollback", mirrorNativeIds);
                detachMirror(storage, mirrorURIsToRollback, isCG, false, taskId);
                _log.info("Attempting to delete {} for rollback", mirrorNativeIds);
                deleteMirror(storage, mirrorURIsToRollback, isCG, taskId);
            }
            WorkflowStepCompleter.stepSucceded(taskId);
        } catch (InternalException ie) {
            _log.error(String.format("rollbackMirror Failed - Array:%s, Mirror:%s", storage, Joiner.on("\t").join(mirrorList)));
            doFailTask(Volume.class, mirrorList, taskId, ie);
            WorkflowStepCompleter.stepFailed(taskId, ie);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(taskId, serviceError);
            doFailTask(Volume.class, mirrorList, taskId, serviceError);
        }
    }

    @Override
    public void attachNativeContinuousCopies(URI storage, URI sourceVolume, List<URI> mirrorList, String opId) throws ControllerException {
        _log.info("START attach continuous copies workflow");

        Workflow workflow = _workflowService.getNewWorkflow(this, ATTACH_MIRRORS_WF_NAME, true, opId);
        TaskCompleter taskCompleter = null;

        Volume sourceVolumeObj = _dbClient.queryObject(Volume.class, sourceVolume);
        boolean isCG = sourceVolumeObj.isInCG();
        try {
            addStepsForCreateMirrors(workflow, null, storage, sourceVolume, mirrorList, isCG);
            taskCompleter = new BlockMirrorTaskCompleter(BlockMirror.class, mirrorList, opId);
            workflow.executePlan(taskCompleter, "Successfully attached continuous copies");
        } catch (Exception e) {
            String msg = String.format("Failed to execute attach continuous copies workflow for volume %s",
                    sourceVolume);

            _log.error(msg, e);
            if (taskCompleter != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                taskCompleter.error(_dbClient, serviceError);
            }
        }
    }

    @Override
    public void detachNativeContinuousCopies(URI storage, List<URI> mirrors, List<URI> promotees,
            String opId) throws ControllerException {
        _log.info("START detach continuous copies workflow");

        Workflow workflow = _workflowService.getNewWorkflow(this, DETACH_MIRRORS_WF_NAME, false, opId);
        TaskCompleter taskCompleter = null;

        try {
            addStepsForPromoteMirrors(workflow, null, mirrors, promotees);

            // There is a task for the source volume, as well as for each newly promoted volume
            List<URI> volumesWithTasks = new ArrayList<URI>(promotees);
            volumesWithTasks.addAll(getSourceVolumesFromURIs(mirrors));
            taskCompleter = new BlockMirrorTaskCompleter(Volume.class, volumesWithTasks, opId);
            ControllerUtils.checkMirrorConsistencyGroup(mirrors, _dbClient, taskCompleter);

            workflow.executePlan(taskCompleter, "Successfully detached continuous copies");
        } catch (Exception e) {
            List<Volume> promotedVolumes = _dbClient.queryObject(Volume.class, promotees);
            for (Volume promotedVolume : promotedVolumes) {
                promotedVolume.setInactive(true);
            }
            _dbClient.updateObject(promotedVolumes);

            String msg = String.format("Failed to execute detach continuous copies workflow for mirrors: %s", mirrors);
            _log.error(msg, e);
        }
    }

    private List<URI> getSourceVolumesFromURIs(List<URI> mirrorList) {
        List<URI> sourceVolumes = new ArrayList<URI>();
        for (URI mirror : mirrorList) {
            BlockMirror mirrorObj = _dbClient.queryObject(BlockMirror.class, mirror);
            sourceVolumes.add(mirrorObj.getSource().getURI());
        }

        return sourceVolumes;
    }

    private List<URI> getSourceVolumes(List<BlockMirror> mirrorList) {
        List<URI> sourceVolumes = new ArrayList<URI>();
        for (BlockMirror mirror : mirrorList) {
            sourceVolumes.add(mirror.getSource().getURI());
        }

        return sourceVolumes;
    }

    public Workflow.Method removeMirrorFromGroupMethod(URI storage, List<URI> mirrorList) {
        return new Workflow.Method("removeMirrorFromGroup", storage, mirrorList);
    }

    public void removeMirrorFromGroup(URI storage, List<URI> mirrorList, String opId) throws ControllerException {
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockMirrorTaskCompleter(BlockMirror.class, mirrorList, opId);
            getDevice(storageObj.getSystemType()).doRemoveMirrorFromDeviceMaskingGroup(storageObj,
                    mirrorList, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            }
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    public Workflow.Method fractureMirrorMethod(URI storage, List<URI> mirrorList, Boolean isCG, Boolean sync) {
        return new Workflow.Method("fractureMirror", storage, mirrorList, isCG, sync);
    }

    /**
     * {@inheritDoc} NOTE NOTE: The signature here MUST match the Workflow.Method fractureMirrorMethod just above
     * (except opId).
     */
    public void fractureMirror(URI storage, List<URI> mirrorList, Boolean isCG, Boolean sync, String opId) throws ControllerException {
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockMirrorFractureCompleter(mirrorList, opId);
            if (!isCG) {
                getDevice(storageObj.getSystemType()).doFractureMirror(storageObj, mirrorList.get(0), sync, completer);
            } else {
                completer.addConsistencyGroupId(ConsistencyGroupUtils.getMirrorsConsistencyGroup(mirrorList, _dbClient).getId());
                getDevice(storageObj.getSystemType()).doFractureGroupMirrors(storageObj, mirrorList, sync, completer);
            }
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            }
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    private boolean isCGMirror(URI mirrorURI, DbClient dbClient) {
        BlockMirror mirror = dbClient.queryObject(BlockMirror.class, mirrorURI);
        Volume sourceVolume = dbClient.queryObject(Volume.class, mirror.getSource());
        return sourceVolume.isInCG();
    }

    @Override
    public void pauseNativeContinuousCopies(URI storage, List<URI> mirrors, Boolean sync,
            String opId) throws ControllerException {
        _log.info("START pause continuous copies workflow");

        boolean isCG = isCGMirror(mirrors.get(0), _dbClient);
        if (mirrors.size() == 1 || isCG) {
            fractureMirror(storage, mirrors, isCG, sync, opId);
            return;
        }

        Workflow workflow = _workflowService.getNewWorkflow(this, PAUSE_MIRRORS_WF_NAME, false, opId);
        TaskCompleter taskCompleter = null;
        BlockMirror mirror = _dbClient.queryObject(BlockMirror.class, mirrors.get(0));
        StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);

        try {
            for (URI mirrorUri : mirrors) {
                BlockMirror blockMirror = _dbClient.queryObject(BlockMirror.class, mirrorUri);
                if (!mirrorIsPausable(asList(blockMirror))) {
                    String errorMsg = format("Can not pause continuous copy %s with synchronization state %s for volume %s",
                            blockMirror.getId(), blockMirror.getSyncState(), blockMirror.getSource().getURI());
                    _log.error(errorMsg);
                    String opName = ResourceOperationTypeEnum.PAUSE_NATIVE_CONTINUOUS_COPIES.getName();
                    ServiceError serviceError = DeviceControllerException.errors.jobFailedOp(opName);
                    WorkflowStepCompleter.stepFailed(opId, serviceError);
                    throw new IllegalStateException(errorMsg);
                }
                workflow.createStep("pauseMirror", "pause mirror", null, storage, storageObj.getSystemType(),
                        this.getClass(), fractureMirrorMethod(storage, asList(mirrorUri), isCG, sync), null, null);
            }

            taskCompleter = new BlockMirrorTaskCompleter(Volume.class, asList(mirror.getSource().getURI()), opId);
            workflow.executePlan(taskCompleter, "Successfully paused continuous copies");
        } catch (Exception e) {
            String msg = String.format("Failed to execute pause continuous copies workflow for volume %s",
                    mirror.getSource().getURI());

            _log.error(msg, e);
            if (taskCompleter != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                taskCompleter.error(_dbClient, serviceError);
            }
        }
    }

    @Override
    public void resumeNativeContinuousCopies(URI storage, List<URI> mirrors, String opId) throws ControllerException {
        _log.info("START resume continuous copies workflow");

        Workflow workflow = _workflowService.getNewWorkflow(this, RESUME_MIRRORS_WF_NAME, false, opId);
        TaskCompleter taskCompleter = null;
        List<BlockMirror> mirrorList = _dbClient.queryObject(BlockMirror.class, mirrors);
        StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
        List<URI> sourceVolumes = getSourceVolumes(mirrorList);

        try {
            taskCompleter = new BlockMirrorTaskCompleter(Volume.class, sourceVolumes, opId);
            boolean isCG = ControllerUtils.checkMirrorConsistencyGroup(mirrors, _dbClient, taskCompleter);

            if (!isCG) {
                for (BlockMirror blockMirror : mirrorList) {
                    if (SynchronizationState.FRACTURED.toString().equals(blockMirror.getSyncState())) {
                        workflow.createStep("resumeStep", "resume", null, storage, storageObj.getSystemType(),
                                this.getClass(), resumeNativeContinuousCopyMethod(storage, asList(blockMirror.getId()), isCG), null, null);
                    }
                }
            } else {
                if (hasFracturedState(mirrorList)) {
                    workflow.createStep("resumeStep", "resume", null, storage, storageObj.getSystemType(),
                            this.getClass(), resumeNativeContinuousCopyMethod(storage, mirrors, isCG), null, null);
                }
            }

            workflow.executePlan(taskCompleter, "Successfully resumed continuous copies");
        } catch (Exception e) {
            String msg = String.format("Failed to execute resume continuous copies workflow for volume %s",
                    Joiner.on("\t").join(sourceVolumes));

            _log.error(msg, e);
            if (taskCompleter != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                taskCompleter.error(_dbClient, serviceError);
            }
        }
    }

    // Any of mirrors in Fractured state will return true
    private boolean hasFracturedState(List<BlockMirror> mirrorList) {
        for (BlockMirror mirror : mirrorList) {
            if (SynchronizationState.FRACTURED.toString().equals(mirror.getSyncState())) {
                return true;
            }
        }

        return false;
    }

    private Workflow.Method resumeNativeContinuousCopyMethod(URI storage, List<URI> mirrorList, Boolean isCG) {
        return new Workflow.Method("resumeNativeContinuousCopy", storage, mirrorList, isCG);
    }

    public void resumeNativeContinuousCopy(URI storage, List<URI> mirrorList, Boolean isCG, String opId) throws ControllerException {
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            TaskCompleter completer = new BlockMirrorResumeCompleter(mirrorList, opId);
            if (!isCG) {
                getDevice(storageObj.getSystemType()).doResumeNativeContinuousCopy(storageObj, mirrorList.get(0), completer);
            } else {
                completer.addConsistencyGroupId(ConsistencyGroupUtils.getMirrorsConsistencyGroup(mirrorList, _dbClient).getId());
                getDevice(storageObj.getSystemType()).doResumeGroupNativeContinuousCopies(storageObj, mirrorList, completer);
            }
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    public Workflow.Method detachMirrorMethod(URI storage, List<URI> mirrorList, Boolean isCG) {
        return new Workflow.Method("detachMirror", storage, mirrorList, isCG, true);
    }

    /**
     * {@inheritDoc} NOTE NOTE: The signature here MUST match the Workflow.Method detachMirrorMethod just above (except
     * opId).
     */
    @Override
    public void detachMirror(URI storage, List<URI> mirrorList, Boolean isCG,
            Boolean deleteGroup, String opId) throws ControllerException {
        TaskCompleter completer = null;
        try {
            _log.info("Start detach Mirror for mirror {}, isCG {}", mirrorList, isCG);
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockMirrorDetachCompleter(mirrorList, opId);
            if (!isCG) {
                getDevice(storageObj.getSystemType()).doDetachMirror(storageObj, mirrorList.get(0), completer);
            } else {
                completer.addConsistencyGroupId(ConsistencyGroupUtils.getMirrorsConsistencyGroup(mirrorList, _dbClient).getId());
                getDevice(storageObj.getSystemType()).doDetachGroupMirrors(storageObj, mirrorList, deleteGroup, completer);
            }
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            if (completer != null) {
                completer.error(_dbClient, serviceError);
            }
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    public String addStepsForDetachMirror(Workflow workflow, String waitFor,
            String stepGroup, List<URI> mirrorList, Boolean isCG) throws ControllerException {
        List<BlockMirror> mirrors = _dbClient.queryObject(BlockMirror.class, mirrorList);
        URI controller = mirrors.get(0).getStorageController();

        String stepId = null;

        // Optionally create a step to pause (fracture) the mirror
        if (mirrorIsPausable(mirrors)) {

            stepId = workflow.createStep(stepGroup,
                    String.format("Fracture mirror: %s", mirrorList.get(0)),
                    waitFor, controller, getDeviceType(controller),
                    this.getClass(),
                    fractureMirrorMethod(controller, mirrorList, isCG, false),
                    null, null);
        }

        // Create a step to detach the mirror
        stepId = workflow.createStep(stepGroup,
                String.format("Detach mirror: %s", mirrorList.get(0)),
                stepId == null ? waitFor : stepId,
                controller, getDeviceType(controller),
                this.getClass(),
                detachMirrorMethod(controller, mirrorList, isCG),
                null, null);

        return stepId;
    }

    private String addStepsToRemoveMirrorFromGroup(Workflow workflow,
            String waitFor, String stepGroup, List<URI> mirrorList) {
        List<BlockMirror> mirrors = _dbClient.queryObject(BlockMirror.class, mirrorList);
        URI controller = mirrors.get(0).getStorageController();
        String stepId = workflow.createStep(stepGroup,
                String.format("Remove mirror from DeviceMaskingGroup: %s", mirrorList.get(0)),
                waitFor, controller, getDeviceType(controller),
                this.getClass(),
                removeMirrorFromGroupMethod(controller, mirrorList),
                null, null);

        return stepId;
    }

    public static final String PROMOTE_MIRROR_STEP_GROUP = "BlockDevicePromoteMirror";

    /**
     * Adds the additional steps necessary to promote mirrors to regular block volumes
     *
     * @param workflow
     * @param waitFor
     * @param descriptors
     * @param promotees
     * @return
     * @throws ControllerException
     */
    public String addStepsForPromoteMirrors(Workflow workflow, String waitFor,
            List<URI> mirrorList, List<URI> promotees)
                    throws ControllerException {
        boolean isCG = isCGMirror(mirrorList.get(0), _dbClient);
        List<Volume> promotedVolumes = _dbClient.queryObject(Volume.class, promotees);
        if (!isCG) {
            List<BlockMirror> mirrors = _dbClient.queryObject(BlockMirror.class, mirrorList);
            for (BlockMirror mirror : mirrors) {
                URI controller = mirror.getStorageController();

                // Add steps for detaching the mirror
                String stepId = addStepsForDetachMirror(workflow, waitFor, PROMOTE_MIRROR_STEP_GROUP, mirrorList, isCG);

                // Find the volume this mirror will be promoted to
                URI promotedVolumeForMirror = findPromotedVolumeForMirror(mirror.getId(), promotedVolumes);

                // Create a step for promoting the mirror.
                stepId = workflow.createStep(PROMOTE_MIRROR_STEP_GROUP,
                        String.format("Promote mirror: %s", mirror.getId()),
                        stepId, controller, getDeviceType(controller),
                        this.getClass(),
                        promoteMirrorMethod(asList(mirror.getId()), asList(promotedVolumeForMirror), isCG),
                        null, null);
            }
        } else {
            BlockMirror mirror = _dbClient.queryObject(BlockMirror.class, mirrorList.get(0));
            URI controller = mirror.getStorageController();

            // Add steps for detaching the mirror
            String stepId = addStepsForDetachMirror(workflow, waitFor, PROMOTE_MIRROR_STEP_GROUP, mirrorList, isCG);

            // Find the volumes this set of mirrors will be promoted to
            List<URI> promotedVolumesForMirrors = findPromotedVolumesForMirrors(mirrorList, promotedVolumes);

            // Create a step for promoting the mirrors.
            stepId = workflow.createStep(PROMOTE_MIRROR_STEP_GROUP,
                    String.format("Promote mirrors: %s", Joiner.on("\t").join(mirrorList)),
                    stepId, controller, getDeviceType(controller),
                    this.getClass(),
                    promoteMirrorMethod(mirrorList, promotedVolumesForMirrors, isCG),
                    null, null);
        }

        return PROMOTE_MIRROR_STEP_GROUP;
    }

    private URI findPromotedVolumeForMirror(URI mirror, List<Volume> promotedVolumes) {
        for (Volume promotee : promotedVolumes) {
            OpStatusMap statusMap = promotee.getOpStatus();
            for (Map.Entry<String, Operation> entry : statusMap.entrySet()) {
                Operation operation = entry.getValue();
                if (operation.getAssociatedResourcesField().contains(mirror.toString())) {
                    return promotee.getId();
                }
            }
        }
        throw new IllegalStateException("No volume available for the promotion of mirror " + mirror);
    }

    private List<URI> findPromotedVolumesForMirrors(List<URI> mirrorList, List<Volume> promotedVolumes) {
        List<URI> orderedPromotedVolumes = new ArrayList<URI>(mirrorList.size());
        for (URI mirror : mirrorList) {
            orderedPromotedVolumes.add(findPromotedVolumeForMirror(mirror, promotedVolumes));
        }

        return orderedPromotedVolumes;
    }

    public Workflow.Method promoteMirrorMethod(List<URI> mirrorList, List<URI> promotedVolumeList, Boolean isCG) {
        return new Workflow.Method("promoteMirror", mirrorList, promotedVolumeList, isCG);
    }

    public void promoteMirror(List<URI> mirrorList, List<URI> promotedVolumeList, Boolean isCG, String opId) {
        _log.info("START promoteMirror");
        Volume promoted = null;
        try {
            List<BlockMirror> mirrors = new ArrayList<BlockMirror>(mirrorList.size());
            List<Volume> promotedVolumes = new ArrayList<Volume>(mirrorList.size());
            int count = 0;

            for (URI id : mirrorList) {
                BlockMirror mirror = _dbClient.queryObject(BlockMirror.class, id);
                Volume source = _dbClient.queryObject(Volume.class, mirror.getSource().getURI());
                String promotedLabel = ControllerUtils.getMirrorLabel(source.getLabel(), mirror.getLabel());
                if (isCG) {
                    promotedLabel = mirror.getLabel();
                }

                promoted = VolumeFactory.newInstance(mirror);
                promoted.setId(promotedVolumeList.get(count++));
                promoted.setLabel(promotedLabel);
                promotedVolumes.add(promoted);
                _log.info("Promoted mirror {} to volume {}", mirror.getId(), promoted.getId());
                // If there are exports masks/export groups associated, then
                // remove the mirror from them and add the promoted volume.
                ExportUtils.updatePromotedMirrorExports(mirror, promoted, _dbClient);
                mirror.setInactive(true);
                mirrors.add(mirror);
            }
            _dbClient.updateObject(mirrors);
            _dbClient.updateObject(promotedVolumes);

            WorkflowStepCompleter.stepSucceded(opId);
        } catch (Exception e) {
            String msg = String.format("Failed to promote mirror %s", Joiner.on("\t").join(mirrorList));
            _log.error(msg, e);
            WorkflowStepCompleter.stepFailed(opId, DeviceControllerException.exceptions.stopVolumeMirrorFailed(mirrorList.get(0)));
        }
    }

    public Workflow.Method deleteMirrorMethod(URI storage, List<URI> mirrorList, Boolean isCG) {
        return new Workflow.Method("deleteMirror", storage, mirrorList, isCG);
    }

    /**
     * {@inheritDoc} NOTE NOTE: The signature here MUST match the Workflow.Method deleteMirrorMethod just above (except
     * opId).
     */
    @Override
    public void deleteMirror(URI storage, List<URI> mirrorList, Boolean isCG, String opId) throws ControllerException {
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockMirrorDeleteCompleter(mirrorList, opId);
            if (!isCG) {
                getDevice(storageObj.getSystemType()).doDeleteMirror(storageObj, mirrorList.get(0), completer);
            } else {
                getDevice(storageObj.getSystemType()).doDeleteGroupMirrors(storageObj, mirrorList, completer);
            }
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            if (completer != null) {
                completer.error(_dbClient, serviceError);
            }
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    @Override
    public void createConsistencyGroup(URI storage, URI consistencyGroup, String opId) throws ControllerException {

        TaskCompleter completer = new BlockConsistencyGroupCreateCompleter(consistencyGroup, opId);

        try {
            WorkflowStepCompleter.stepExecuting(opId);

            // Lock the CG for the step duration.
            List<String> lockKeys = new ArrayList<String>();
            lockKeys.add(ControllerLockingUtil.getConsistencyGroupStorageKey(_dbClient, consistencyGroup, storage));
            _workflowService.acquireWorkflowStepLocks(opId, lockKeys, LockTimeoutValue.get(LockType.ARRAY_CG));

            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            // Check if already created, if not create, if so just complete.
            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, consistencyGroup);
            String groupName = ControllerUtils.generateReplicationGroupName(storageObj, cg, null, _dbClient);
            if (!cg.created(storage, groupName)) {
                getDevice(storageObj.getSystemType()).doCreateConsistencyGroup(storageObj, consistencyGroup, groupName, completer);
            } else {
                _log.info(String.format("Consistency group %s (%s) already created", cg.getLabel(), cg.getId()));
                completer.ready(_dbClient);
            }
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            completer.error(_dbClient, serviceError);
            throw DeviceControllerException.exceptions.createConsistencyGroupFailed(e);
        }
    }

    public Workflow.Method deleteConsistencyGroupMethod(URI storage, URI consistencyGroup, String groupName, Boolean keepRGName,
            Boolean markInactive, Boolean throwErrorIfNotDeleted) {
        return new Workflow.Method("deleteReplicationGroupInConsistencyGroup", storage, consistencyGroup, groupName, keepRGName,
                markInactive, throwErrorIfNotDeleted);
    }

    @Override
    public void deleteConsistencyGroup(URI storage, URI consistencyGroup, Boolean markInactive, String opId) throws ControllerException {
        _log.info("START delete consistency group");
        TaskCompleter wfCompleter = null;

        try {
            Workflow workflow = _workflowService.getNewWorkflow(this, "deleteReplicationGroupInConsistencyGroup", true, opId);
            wfCompleter = new SimpleTaskCompleter(BlockConsistencyGroup.class, consistencyGroup, opId);

            StorageSystem system = _dbClient.queryObject(StorageSystem.class, storage);
            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, consistencyGroup);
            Set<String> groupNames = BlockConsistencyGroupUtils.getGroupNamesForSystemCG(cg, system);

            String stepId = null;
            for (String groupName : groupNames) {
                Workflow.Method deleteStep = new Workflow.Method("deleteReplicationGroupInConsistencyGroup",
                        storage, consistencyGroup, groupName, false, markInactive, true);
                stepId = workflow.createStep("DeleteReplicationGroup", "Deleting replication group", stepId, storage,
                        system.getSystemType(), this.getClass(), deleteStep, rollbackMethodNullMethod(), null);
            }

            String successMsg = String.format("Successfully deleted replication groups %s", Joiner.on(',').join(groupNames));
            workflow.executePlan(wfCompleter, successMsg);
        } catch (Exception e) {
            if (wfCompleter != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                wfCompleter.error(_dbClient, serviceError);
            }
            throw DeviceControllerException.exceptions.deleteConsistencyGroupFailed(e);
        }
    }

    public void deleteReplicationGroupInConsistencyGroup(URI storage, URI consistencyGroup, String groupName, Boolean keepRGName,
            Boolean markInactive, Boolean throwErrorIfNotDeleted, String opId) throws ControllerException {
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            completer = new BlockConsistencyGroupDeleteCompleter(consistencyGroup, storage, groupName, keepRGName, markInactive, opId);
            List<String> lockKeys = new ArrayList<String>();
            if (groupName != null && !groupName.isEmpty()) {
                lockKeys.add(ControllerLockingUtil.getReplicationGroupStorageKey(_dbClient, groupName, storage));
            } else if (!NullColumnValueGetter.isNullURI(consistencyGroup)) {
                // Lock the CG for the step duration.
                lockKeys.add(ControllerLockingUtil.getConsistencyGroupStorageKey(_dbClient, consistencyGroup, storage));
            }
            if (!lockKeys.isEmpty()) {
                _workflowService.acquireWorkflowStepLocks(opId, lockKeys, LockTimeoutValue.get(LockType.ARRAY_CG));
            }
            // Check if there is any members in the replication group before delete it.
            if (groupName != null && !groupName.isEmpty()) {
                List<Volume> groupVolumes = ControllerUtils.getVolumesPartOfRG(storage, groupName, _dbClient);
                if (groupVolumes != null && !groupVolumes.isEmpty()) {
                    String msg = String.format("The replication group %s still have volumes, will not delete the replication group",
                            groupName);
                    _log.warn(msg);
                    if (throwErrorIfNotDeleted) {
                        completer.error(_dbClient, DeviceControllerException.exceptions.couldNotDeleteReplicationGroup(msg));
                    } else {
                        completer.ready(_dbClient);
                    }
                    return;
                }
            }

            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);

            getDevice(storageObj.getSystemType()).doDeleteConsistencyGroup(storageObj, consistencyGroup, groupName, keepRGName,
                    markInactive, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            }
            throw DeviceControllerException.exceptions.deleteConsistencyGroupFailed(e);
        }
    }

    /**
     * An orchestration controller method for detaching and deleting a mirror
     *
     * @param storage
     *            URI of storage controller.
     * @param mirrorList
     *            List of URIs of block mirrors
     * @param promotees
     *            List of URIs of promoted volumes
     * @param isCG
     *            CG mirror or not
     * @param opId
     *            Operation ID
     * @throws ControllerException
     */
    @Override
    public void deactivateMirror(URI storage, List<URI> mirrorList, List<URI> promotees, Boolean isCG, String opId)
            throws ControllerException {
        _log.info("deactivateMirror: START");
        TaskCompleter taskCompleter = null;
        String mirrorStr = Joiner.on("\t").join(mirrorList);

        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            Workflow workflow = _workflowService.getNewWorkflow(this, "deactivateMirror", true, opId);
            taskCompleter = new BlockMirrorDeactivateCompleter(mirrorList, promotees, opId);
            ControllerUtils.checkMirrorConsistencyGroup(mirrorList, _dbClient, taskCompleter);

            String detachStep = workflow.createStepId();
            Workflow.Method detach = detachMirrorMethod(storage, mirrorList, isCG);
            workflow.createStep("deactivate", "detaching mirror volume: " + mirrorStr, null, storage,
                    storageSystem.getSystemType(), getClass(), detach, null, detachStep);

            // for single volume mirror, the mirror will be deleted
            List<URI> mirrorsToDelete = mirrorList;
            // for group mirror, find mirrors to be deleted and mirrors to be promoted, and do the promotion
            if (isCG) {
                mirrorsToDelete = new ArrayList<URI>();
                List<Volume> promotedVolumes = _dbClient.queryObject(Volume.class, promotees);
                List<URI> orderedMirrorsToPromote = new ArrayList<URI>();
                List<URI> orderedPromotedVolumes = new ArrayList<URI>();

                for (URI mirror : mirrorList) {
                    URI promotedVolume = null;
                    for (Volume promotee : promotedVolumes) {
                        OpStatusMap statusMap = promotee.getOpStatus();
                        for (Map.Entry<String, Operation> entry : statusMap.entrySet()) {
                            Operation operation = entry.getValue();
                            if (operation.getAssociatedResourcesField().contains(mirror.toString())) {
                                promotedVolume = promotee.getId();
                            }
                        }
                    }

                    if (promotedVolume != null) {
                        orderedMirrorsToPromote.add(mirror);
                        orderedPromotedVolumes.add(promotedVolume);
                    } else {
                        mirrorsToDelete.add(mirror);
                    }
                }

                if (!orderedMirrorsToPromote.isEmpty()) {
                    // Create a step for promoting the mirrors.
                    String stepId = workflow.createStep(PROMOTE_MIRROR_STEP_GROUP,
                            String.format("Promote mirrors : %s", Joiner.on("\t").join(orderedMirrorsToPromote)),
                            detachStep, storage, storageSystem.getSystemType(),
                            this.getClass(),
                            promoteMirrorMethod(orderedMirrorsToPromote, orderedPromotedVolumes, isCG),
                            null, null);
                }
            }

            String deleteStep = workflow.createStepId();
            Workflow.Method delete = deleteMirrorMethod(storage, mirrorsToDelete, isCG);

            workflow.createStep("deactivate", "deleting mirror volume: " + Joiner.on("\t").join(mirrorsToDelete), detachStep, storage,
                    storageSystem.getSystemType(), getClass(), delete, null, deleteStep);

            String successMessage = String.format("Successfully deactivated mirror %s on StorageArray %s",
                    mirrorStr, storage);
            workflow.executePlan(taskCompleter, successMessage);
        } catch (Exception e) {
            if (_log.isErrorEnabled()) {
                String msg = String.format("Deactivate mirror failed for mirror %s", mirrorStr);
                _log.error(msg);
            }
            if (taskCompleter != null) {
                String opName = ResourceOperationTypeEnum.DEACTIVATE_VOLUME_MIRROR.getName();
                ServiceError serviceError = DeviceControllerException.errors.jobFailedOp(opName);
                taskCompleter.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.deactivateMirrorFailed(e);
            }
        }
    }

    @Override
    public void establishVolumeAndNativeContinuousCopyGroupRelation(URI storage, URI sourceVolume, URI mirror, String opId)
            throws ControllerException {
        _log.info("START establishVolumeAndNativeContinuousCopyGroupRelation workflow");

        Workflow workflow = _workflowService.getNewWorkflow(this, ESTABLISH_VOLUME_MIRROR_GROUP_WF_NAME, false, opId);
        TaskCompleter taskCompleter = null;
        StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);

        try {
            workflow.createStep("establishStep", "create group relation between Volume group and Mirror group", null, storage,
                    storageObj.getSystemType(),
                    this.getClass(), establishVolumeAndNativeContinuousCopyGroupRelationMethod(storage, sourceVolume, mirror), null, null);

            taskCompleter = new BlockMirrorTaskCompleter(Volume.class, asList(sourceVolume), opId);
            workflow.executePlan(taskCompleter, "Successfully created group relation between Volume group and Mirror group");
        } catch (Exception e) {
            String msg = String.format("Failed to create group relation between Volume group and Mirror group."
                    + "Source volume: %s, Mirror: %s",
                    sourceVolume, mirror);
            _log.error(msg, e);
            if (taskCompleter != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                taskCompleter.error(_dbClient, serviceError);
            }
        }
    }

    private Workflow.Method establishVolumeAndNativeContinuousCopyGroupRelationMethod(URI storage, URI sourceVolume, URI mirror) {
        return new Workflow.Method("establishVolumeNativeContinuousCopyGroupRelation", storage, sourceVolume, mirror);
    }

    public void establishVolumeNativeContinuousCopyGroupRelation(
            URI storage, URI sourceVolume, URI mirror, String opId)
                    throws ControllerException {
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            TaskCompleter completer = new BlockMirrorTaskCompleter(BlockMirror.class, mirror, opId);
            getDevice(storageObj.getSystemType())
                    .doEstablishVolumeNativeContinuousCopyGroupRelation(
                            storageObj, sourceVolume, mirror, completer);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    static final String FULL_COPY_WORKFLOW = "fullCopyVolumes";
    static final String FULL_COPY_CREATE_STEP_GROUP = "createFullCopiesStepGroup";
    static final String SNAPSHOT_SESSION_CREATE_STEP_GROUP = "createSnapshotSessionStepGroup";
    static final String FULL_COPY_WFS_STEP_GROUP = "waitForSyncStepGroup";
    static final String FULL_COPY_DETACH_STEP_GROUP = "detachFullCopyStepGroup";
    static final String FULL_COPY_FRACTURE_STEP_GROUP = "fractureFullCopyStepGroup";
    static final String SNAPSHOT_DELETE_STEP_GROUP = "deleteSnapshotStepGroup";
    static final String MIRROR_FRACTURE_STEP_GROUP = "fractureMirrorStepGroup";
    static final String MIRROR_DETACH_STEP_GROUP = "detachMirrorStepGroup";
    static final String FULL_COPY_CREATE_ORCHESTRATION_STEP = "createFullCopiesOrchestrationStep";
    static final String SNAPSHOT_SESSION_CREATE_ORCHESTRATION_STEP = "createSnapshotSessionOrchestrationStep";

    @Override
    public void createFullCopy(URI storage, List<URI> fullCopyVolumes, Boolean createInactive,
            String taskId)
                    throws ControllerException {
        _log.info("START fullCopyVolumes");
        TaskCompleter taskCompleter = new CloneCreateWorkflowCompleter(fullCopyVolumes, taskId);
        Volume clone = _dbClient.queryObject(Volume.class, fullCopyVolumes.get(0));
        URI sourceVolume = clone.getAssociatedSourceVolume();

        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            Workflow workflow = _workflowService.getNewWorkflow(this, FULL_COPY_WORKFLOW, true, taskId);
            boolean isCG = false;

            Volume source = URIUtil.isType(sourceVolume, Volume.class) ? _dbClient.queryObject(Volume.class, sourceVolume) : null;
            VolumeGroup volumeGroup = (source != null)
                    ? source.getApplication(_dbClient) : null;
            if (volumeGroup != null) {
                /**
                 * If a Volume is in Volume Group (COPY type),
                 * Query all volumes belonging to that Volume Group,
                 * Group full-copies by Array Replication Group and create workflow step for each Array Group,
                 * these steps runs in parallel
                 */
                _log.info("Creating full copy for Application {}", volumeGroup.getLabel());
                createFullCopyForApplicationCGs(workflow, volumeGroup, fullCopyVolumes, createInactive, taskCompleter);

            } else if (checkCloneConsistencyGroup(fullCopyVolumes.get(0), _dbClient, taskCompleter)) {
                // check if the clone is in a CG
                isCG = true;
                _log.info("Creating group full copy");
                createCGFullCopy(storage, sourceVolume, fullCopyVolumes, storageSystem, workflow, createInactive, isCG);

            } else {
                for (URI uri : fullCopyVolumes) {
                    Workflow.Method createMethod = createFullCopyVolumeMethod(storage, sourceVolume, Arrays.asList(uri), createInactive,
                            isCG);
                    Workflow.Method rollbackMethod = rollbackFullCopyVolumeMethod(storage, asList(uri));
                    workflow.createStep(FULL_COPY_CREATE_STEP_GROUP, "Creating full copy", null, storage,
                            storageSystem.getSystemType(), getClass(), createMethod,
                            rollbackMethod, null);
                    // For driver managed arrays, we rely on drivers to complete synchronization if needed and to set
                    // clone state.
                    if (!createInactive && !getDriverManager().isDriverManaged(storageSystem.getSystemType())) {
                        // After all full copies have been created, wait for synchronization to complete
                        Workflow.Method waitForSyncMethod = waitForSynchronizedMethod(Volume.class, storage, Arrays.asList(uri), isCG);
                        String waitForSyncStep = workflow.createStep(FULL_COPY_WFS_STEP_GROUP,
                                "Waiting for synchronization", FULL_COPY_CREATE_STEP_GROUP, storage,
                                storageSystem.getSystemType(), getClass(), waitForSyncMethod, rollbackMethodNullMethod(), null);
                        Volume cloneVol = _dbClient.queryObject(Volume.class, uri);
                        BlockObject sourceObj = BlockObject.fetch(_dbClient, cloneVol.getAssociatedSourceVolume());
                        // detach if source is snapshot, or storage system is not vmax/vnx/hds
                        if (storageSystem.deviceIsType(Type.openstack)) {
                            setCloneReplicaStateStep(workflow, storageSystem, asList(uri), waitForSyncStep, ReplicationState.SYNCHRONIZED);
                        } else if (sourceObj instanceof BlockSnapshot
                                || !(storageSystem.deviceIsType(Type.vmax) || storageSystem.deviceIsType(Type.hds)
                                        || storageSystem.deviceIsType(Type.vnxblock))) {

                            Workflow.Method detachMethod = detachFullCopyMethod(storage, asList(uri));
                            workflow.createStep(FULL_COPY_DETACH_STEP_GROUP, "Detaching full copy", waitForSyncStep,
                                    storage, storageSystem.getSystemType(), getClass(), detachMethod, rollbackMethodNullMethod(), null);
                        } else if (storageSystem.deviceIsType(Type.vnxblock)) {
                            workflow.createStep(FULL_COPY_FRACTURE_STEP_GROUP, "fracture full copy", waitForSyncStep,
                                    storage, storageSystem.getSystemType(), BlockDeviceController.class,
                                    fractureCloneMethod(storage, Arrays.asList(uri), isCG), rollbackMethodNullMethod(), null);
                        } else {
                            setCloneReplicaStateStep(workflow, storageSystem, asList(uri), waitForSyncStep, ReplicationState.SYNCHRONIZED);
                        }
                    }
                }

            }
            String successMsg = String.format("Full copy of %s to %s successful", sourceVolume, fullCopyVolumes);
            workflow.executePlan(taskCompleter, successMsg);
        } catch (InternalException e) {
            _log.error("Failed to create full copy of volume", e);
            doFailTask(Volume.class, sourceVolume, taskId, e);
            WorkflowStepCompleter.stepFailed(taskId, e);
        } catch (Exception e) {
            _log.error("Failed to create full copy of volume", e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            doFailTask(Volume.class, sourceVolume, taskId, serviceError);
            WorkflowStepCompleter.stepFailed(taskId, serviceError);
        }
    }

    /**
     * Create Full Copies for volumes in Volume Group (COPY type),
     * Query all volumes belonging to that Volume Group,
     * Group full-copies by Array Replication Group and create workflow step for each Array Group,
     * these steps runs in parallel
     */
    private void createFullCopyForApplicationCGs(Workflow workflow, VolumeGroup volumeGroup,
            List<URI> fullCopyVolumes, Boolean createInactive, TaskCompleter taskCompleter) {
        boolean isCG = true; // VolumeGroup Volumes will be in CG
        // add VolumeGroup to taskCompleter
        taskCompleter.addVolumeGroupId(volumeGroup.getId());

        List<Volume> allVolumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);
        Map<String, List<Volume>> arrayGroupToVolumes = ControllerUtils.groupVolumesByArrayGroup(allVolumes, _dbClient);
        for (String arrayGroupName : arrayGroupToVolumes.keySet()) { // AG - Array Group
            List<Volume> arrayGroupVolumes = arrayGroupToVolumes.get(arrayGroupName);
            List<URI> fullCopyVolumesAG = getFullCopiesForVolumes(fullCopyVolumes, arrayGroupVolumes);
            if (fullCopyVolumesAG.isEmpty()) {
                _log.debug("Looks Full copy not requested for array group {}", arrayGroupName);
                // This is to support future case where there may be a request for subset of array groups
                continue;
            }
            Volume sourceVolumeAG = arrayGroupVolumes.iterator().next();
            // add CG to taskCompleter
            if (!NullColumnValueGetter.isNullURI(sourceVolumeAG.getConsistencyGroup())) {
                taskCompleter.addConsistencyGroupId(sourceVolumeAG.getConsistencyGroup());
            }
            URI storage = sourceVolumeAG.getStorageController();
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            _log.info("Creating full copy for group {}", arrayGroupName);
            createCGFullCopy(storage, sourceVolumeAG.getId(), fullCopyVolumesAG, storageSystem, workflow, createInactive,
                    isCG);
        }
    }

    private void createCGFullCopy(URI storage, URI sourceVolume, List<URI> fullCopyVolumes, StorageSystem storageSystem,
            Workflow workflow, Boolean createInactive, boolean isCG) {
        Workflow.Method createMethod = createFullCopyVolumeMethod(storage, sourceVolume, fullCopyVolumes, createInactive, isCG);
        Workflow.Method rollbackMethod = rollbackFullCopyVolumeMethod(storage, fullCopyVolumes);
        String createFullCopyStep = workflow.createStep(FULL_COPY_CREATE_STEP_GROUP, "Creating full copy", null, storage,
                storageSystem.getSystemType(), getClass(), createMethod,
                rollbackMethod, null);

        if (!createInactive) {
            // After all full copies have been created, wait for synchronization to complete
            if (!storageSystem.deviceIsType(Type.vnxblock)) {
                Workflow.Method waitForSyncMethod = waitForSynchronizedMethod(Volume.class, storage, fullCopyVolumes, isCG);
                String waitForSyncStep = workflow.createStep(FULL_COPY_WFS_STEP_GROUP,
                        "Waiting for synchronization", createFullCopyStep, storage,
                        storageSystem.getSystemType(), getClass(), waitForSyncMethod, rollbackMethodNullMethod(), null);
                setCloneReplicaStateStep(workflow, storageSystem, fullCopyVolumes, waitForSyncStep, ReplicationState.SYNCHRONIZED);
            } else {
                String previousStep = createFullCopyStep;
                for (URI cloneUri : fullCopyVolumes) {
                    Workflow.Method waitForSyncMethod = waitForSynchronizedMethod(Volume.class, storage, Arrays.asList(cloneUri),
                            false);
                    String waitForSyncStep = workflow.createStep(FULL_COPY_WFS_STEP_GROUP,
                            "Waiting for synchronization", previousStep, storage,
                            storageSystem.getSystemType(), getClass(), waitForSyncMethod, rollbackMethodNullMethod(), null);
                    previousStep = waitForSyncStep;
                }

                workflow.createStep(FULL_COPY_FRACTURE_STEP_GROUP, "fracture full copy", previousStep,
                        storage, storageSystem.getSystemType(), BlockDeviceController.class,
                        fractureCloneMethod(storage, fullCopyVolumes, isCG), rollbackMethodNullMethod(), null);
            }
        }
    }

    public Workflow.Method createFullCopyVolumeMethod(URI storage, URI sourceVolume, List<URI> fullCopyVolumes,
            Boolean createInactive, boolean isCG) {
        return new Workflow.Method("createFullCopyVolume", storage, sourceVolume, fullCopyVolumes, createInactive, isCG);
    }

    public void createFullCopyVolume(URI storage, URI sourceVolume, List<URI> fullCopyVolumes, Boolean createInactive, boolean isCG,
            String taskId) {
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            TaskCompleter taskCompleter = new CloneCreateCompleter(fullCopyVolumes, taskId);
            WorkflowStepCompleter.stepExecuting(taskId);
            // if number of clones more than 1 we need to use createListReplica
            if (isCG || fullCopyVolumes.size() > 1) {
                boolean isListReplicaFlow = false;
                Volume sourceVolumeObj = _dbClient.queryObject(Volume.class, sourceVolume);

                // take a lock on the group and storage system to prevent two clones of the same group from being
                // created at the same time
                if (NullColumnValueGetter.isNotNullValue(sourceVolumeObj.getReplicationGroupInstance())) {
                    List<String> lockKeys = new ArrayList<>();
                    lockKeys.add(ControllerLockingUtil.getReplicationGroupStorageKey(_dbClient,
                            sourceVolumeObj.getReplicationGroupInstance(), storage));
                    _workflowService.acquireWorkflowStepLocks(taskId, lockKeys, LockTimeoutValue.get(LockType.ARRAY_CG));
                }

                /**
                 * VPLEX/RP CG volumes may not be having back end Array Group.
                 * In this case we should create element replica using createListReplica.
                 * We should not use createGroup replica as backend cg will not be available in this case.
                 */
                isListReplicaFlow = isListReplicaFlow(sourceVolumeObj);
                if (!isListReplicaFlow) {
                    getDevice(storageSystem.getSystemType()).doCreateGroupClone(storageSystem, fullCopyVolumes,
                            createInactive, taskCompleter);
                } else {
                    // List Replica
                    createListClone(storage, fullCopyVolumes, createInactive, taskId);
                }

            } else {
                getDevice(storageSystem.getSystemType()).doCreateClone(storageSystem, sourceVolume, fullCopyVolumes.get(0),
                        createInactive, taskCompleter);
            }
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(taskId, serviceError);
            doFailTask(Volume.class, fullCopyVolumes, taskId, serviceError);
        }
    }

    private boolean isListReplicaFlow(Volume sourceVolumeObj) {
        boolean isListReplicaFlow = false;
        if (sourceVolumeObj != null && NullColumnValueGetter.isNotNullValue(sourceVolumeObj.getReplicationGroupInstance())
                && !ControllerUtils.checkCGCreatedOnBackEndArray(sourceVolumeObj)) {
            isListReplicaFlow = true;
        }
        _log.info("isListReplicaFlow:{}", isListReplicaFlow);
        return isListReplicaFlow;
    }

    public Workflow.Method rollbackFullCopyVolumeMethod(URI storage, List<URI> fullCopy) {
        return new Workflow.Method("rollbackFullCopyVolume", storage, fullCopy);
    }

    public void rollbackFullCopyVolume(URI storage, List<URI> fullCopy, String taskId) {
        WorkflowStepCompleter.stepExecuting(taskId);

        List<Volume> volumes = _dbClient.queryObject(Volume.class, fullCopy);
        try {
            if (!isNullOrEmpty(volumes.get(0).getNativeId()) && !volumes.get(0).getInactive()) {
                _log.info("Attempting to detach for rollback");
                detachFullCopies(storage, fullCopy, taskId);
                _log.info("Attempting to delete for rollback");
                deleteVolumes(storage, fullCopy, taskId);
            } else {
                for (Volume volume : volumes) {
                    volume.setInactive(true);
                    _dbClient.updateObject(volume);
                }
                WorkflowStepCompleter.stepSucceded(taskId);
            }
        } catch (InternalException ie) {
            _log.error(String.format("rollbackFullCopyVolume Failed - Array:%s, Volume:%s", storage, fullCopy));
            doFailTask(Volume.class, fullCopy, taskId, ie);
            WorkflowStepCompleter.stepFailed(taskId, ie);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(taskId, serviceError);
            doFailTask(Volume.class, fullCopy, taskId, serviceError);
        }
    }

    private static final String ACTIVATE_CLONE_WF_NAME = "ACTIVATE_CLONE_WORKFLOW";
    private static final String ACTIVATE_CLONE_GROUP = "BlockDeviceActivateClone";

    @Override
    public void activateFullCopy(URI storage, List<URI> fullCopy, String opId) {
        TaskCompleter completer = new CloneWorkflowCompleter(fullCopy, opId);
        try {
            // need to create a workflow to wait sync finish, then do fracture/activate
            Workflow workflow = _workflowService.getNewWorkflow(this, ACTIVATE_CLONE_WF_NAME, false, opId);
            _log.info("Created new activate workflow with operation id {}", opId);

            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            // add CG to taskCompleter
            boolean isCG = checkCloneConsistencyGroup(fullCopy.get(0), _dbClient, completer);

            if (storageSystem.deviceIsType(Type.vnxblock)) {
                String previousStep = null;
                if (isCG) {
                    for (URI cloneUri : fullCopy) {
                        Workflow.Method waitForSyncMethod = waitForSynchronizedMethod(Volume.class, storage, Arrays.asList(cloneUri),
                                false);
                        String waitForSyncStep = workflow.createStep(FULL_COPY_WFS_STEP_GROUP,
                                "Waiting for synchronization", previousStep, storage,
                                storageSystem.getSystemType(), getClass(), waitForSyncMethod, null, null);
                        previousStep = waitForSyncStep;
                    }
                } else {
                    Workflow.Method waitForSyncMethod = waitForSynchronizedMethod(Volume.class, storage, fullCopy, isCG);
                    String waitForSyncStep = workflow.createStep(FULL_COPY_WFS_STEP_GROUP,
                            "Waiting for synchronization", previousStep, storage,
                            storageSystem.getSystemType(), getClass(), waitForSyncMethod, null, null);
                    previousStep = waitForSyncStep;
                }
                workflow.createStep(ACTIVATE_CLONE_GROUP, "Activating clone", previousStep,
                        storage, getDeviceType(storage), BlockDeviceController.class,
                        activateCloneMethod(storage, fullCopy), rollbackMethodNullMethod(), null);

            } else {
                workflow.createStep(ACTIVATE_CLONE_GROUP, "Activating clone", null,
                        storage, getDeviceType(storage), BlockDeviceController.class,
                        activateCloneMethod(storage, fullCopy), rollbackMethodNullMethod(), null);
            }

            _log.info("Executing Activate workflow");
            String msg = String.format("Actitvate %s completed successfully", fullCopy.get(0));
            workflow.executePlan(completer, msg);

        } catch (Exception e) {
            String msg = String.format("Could not activate the clone %s", fullCopy.get(0));
            _log.error(msg, e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            completer.error(_dbClient, serviceError);
        }

    }

    public static Workflow.Method activateCloneMethod(URI storage, List<URI> clone) {
        return new Workflow.Method("activateFullCopyStep", storage, clone);
    }

    public void activateFullCopyStep(URI storage, List<URI> fullCopy, String opId) {
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            TaskCompleter taskCompleter = new CloneActivateCompleter(fullCopy, opId);
            if (checkCloneConsistencyGroup(fullCopy.get(0), _dbClient, taskCompleter)) {
                getDevice(storageSystem.getSystemType()).doActivateGroupFullCopy(storageSystem, fullCopy, taskCompleter);
            } else {
                getDevice(storageSystem.getSystemType()).doActivateFullCopy(storageSystem, fullCopy.get(0), taskCompleter);
            }
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            doFailTask(Volume.class, fullCopy, opId, serviceError);
        }
    }

    public Workflow.Method detachFullCopyMethod(URI storage, List<URI> fullCopyVolume) {
        return new Workflow.Method("detachFullCopies", storage, fullCopyVolume);
    }

    public void detachFullCopies(URI storage, List<URI> fullCopyVolumes, String taskId)
            throws ControllerException {
        _log.info("detach FullCopy: {}", fullCopyVolumes);

        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            TaskCompleter taskCompleter = new VolumeDetachCloneCompleter(fullCopyVolumes, taskId);

            if (checkCloneConsistencyGroup(fullCopyVolumes.get(0), _dbClient, taskCompleter)) {
                _log.info("detach group full copy");
                getDevice(storageSystem.getSystemType()).doDetachGroupClone(storageSystem, fullCopyVolumes, taskCompleter);
            } else {
                getDevice(storageSystem.getSystemType()).doDetachClone(storageSystem, fullCopyVolumes.get(0),
                        taskCompleter);
            }
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(taskId, serviceError);
            doFailTask(Volume.class, fullCopyVolumes, taskId, serviceError);
        }
    }

    private static final String DETACH_CLONE_WF_NAME = "DETACH_CLONE_WORKFLOW";

    @Override
    public void detachFullCopy(URI storage, List<URI> fullCopyVolumes, String taskId)
            throws ControllerException {
        _log.info("START detachFullCopy: {}", fullCopyVolumes);
        TaskCompleter taskCompleter = new CloneWorkflowCompleter(fullCopyVolumes, taskId);
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);

            Workflow workflow = _workflowService.getNewWorkflow(this, DETACH_CLONE_WF_NAME, true, taskId);
            _log.info("Created new detach workflow with operation id {}", taskId);

            // add CG to taskCompleter
            checkCloneConsistencyGroup(fullCopyVolumes.get(0), _dbClient, taskCompleter);

            Workflow.Method detachMethod = detachFullCopyMethod(storage, fullCopyVolumes);
            workflow.createStep(FULL_COPY_DETACH_STEP_GROUP, "Detaching full copy", null,
                    storage, storageSystem.getSystemType(), getClass(), detachMethod, rollbackMethodNullMethod(), null);

            String msg = String.format("Detach %s completed successfully", fullCopyVolumes.get(0));
            workflow.executePlan(taskCompleter, msg);
        } catch (Exception e) {
            String msg = String.format("Could not detach the clone %s", fullCopyVolumes.get(0));
            _log.error(msg, e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            taskCompleter.error(_dbClient, serviceError);
        }
    }

    @Override
    public void establishVolumeAndFullCopyGroupRelation(URI storage, URI sourceVolume, URI fullCopy, String opId)
            throws ControllerException {
        _log.info("START establishVolumeAndFullCopyGroupRelation workflow");

        Workflow workflow = _workflowService.getNewWorkflow(this, ESTABLISH_VOLUME_FULL_COPY_GROUP_WF_NAME, false, opId);
        TaskCompleter taskCompleter = null;
        StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);

        try {
            workflow.createStep("establishStep", "create group relation between Volume group and Full copy group", null, storage,
                    storageObj.getSystemType(),
                    this.getClass(), establishVolumeAndFullCopyGroupRelationMethod(storage, sourceVolume, fullCopy), null, null);

            taskCompleter = new CloneTaskCompleter(fullCopy, opId);
            workflow.executePlan(taskCompleter, "Successfully created group relation between Volume group and Full copy group");
        } catch (Exception e) {
            String msg = String.format("Failed to create group relation between Volume group and Full copy group."
                    + "Source volume: %s, Full copy: %s",
                    sourceVolume, fullCopy);
            _log.error(msg, e);
            if (taskCompleter != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                taskCompleter.error(_dbClient, serviceError);
            }
        }
    }

    private Workflow.Method establishVolumeAndFullCopyGroupRelationMethod(URI storage, URI sourceVolume, URI fullCopy) {
        return new Workflow.Method("establishVolumeFullCopyGroupRelation", storage, sourceVolume, fullCopy);
    }

    public void establishVolumeFullCopyGroupRelation(
            URI storage, URI sourceVolume, URI fullCopy, String opId)
                    throws ControllerException {
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            TaskCompleter completer = new CloneTaskCompleter(fullCopy, opId);
            getDevice(storageObj.getSystemType())
                    .doEstablishVolumeFullCopyGroupRelation(
                            storageObj, sourceVolume, fullCopy, completer);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    @Override
    public Integer checkSyncProgress(URI storage, URI source, URI target, String task) {
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            return getDevice(storageSystem.getSystemType()).checkSyncProgress(storage, source, target);
        } catch (Exception e) {
            String msg = String.format("Failed to check synchronization progress for %s", target);
            _log.error(msg, e);
        }
        return null;
    }

    /**
     * Creates a connection to monitor events generated by the storage
     * identified by the passed URI.
     *
     * @param storage
     *            A database client URI that identifies the storage to be
     *            monitored.
     *
     * @throws ControllerException
     *             When errors occur connecting the storage for
     *             event monitoring.
     */
    @Override
    public void connectStorage(URI storage) throws ControllerException {
        // Retrieve the storage device info from the database.
        StorageSystem storageObj = null;
        try {
            storageObj = _dbClient.queryObject(StorageSystem.class, storage);
        } catch (Exception e) {
            throw DeviceControllerException.exceptions.connectStorageFailedDb(e);
        }
        // Verify non-null storage device returned from the database client.
        if (storageObj == null) {
            throw DeviceControllerException.exceptions.connectStorageFailedNull();
        }
        // Get the block device reference for the type of block device managed
        // by the controller.
        BlockStorageDevice storageDevice = getDevice(storageObj.getSystemType());
        storageDevice.doConnect(storageObj);
        _log.info("Adding to storage device to work pool: {}", storageObj.getId());

    }

    /**
     * Removes a connection that was previously established for monitoring
     * events from the storage identified by the passed URI.
     *
     * @param storage
     *            A database client URI that identifies the storage to be
     *            disconnected.
     *
     * @throws ControllerException
     *             When errors occur disconnecting the storage
     *             for event monitoring.
     */
    @Override
    public void disconnectStorage(URI storage) throws ControllerException {
        // Retrieve the storage device info from the database.
        StorageSystem storageObj = null;
        try {
            storageObj = _dbClient.queryObject(StorageSystem.class, storage);
        } catch (Exception e) {
            throw DeviceControllerException.exceptions.disconnectStorageFailedDb(e);
        }

        // Verify non-null storage device returned from the database client.
        if (storageObj == null) {
            throw DeviceControllerException.exceptions.disconnectStorageFailedNull();
        }

        // Get the block device reference for the type of block device managed
        // by the controller.
        BlockStorageDevice storageDevice = getDevice(storageObj.getSystemType());
        if (storageDevice == null) {
            throw DeviceControllerException.exceptions.disconnectStorageFailedNull();
        }

        storageDevice.doDisconnect(storageObj);
        _log.info("Removing storage device from work pool: {}", storageObj.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void discoverStorageSystem(AsyncTask[] tasks)
            throws ControllerException {
        throw DeviceControllerException.exceptions.blockDeviceOperationNotSupported();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void scanStorageProviders(AsyncTask[] tasks)
            throws ControllerException {
        throw DeviceControllerException.exceptions.blockDeviceOperationNotSupported();
    }

    private String addStorageToSMIS(StorageSystem storageSystem, StorageProvider provider)
            throws DataBindingException, ControllerException {
        String system = "";
        if (provider != null) {
            // Populate provider info. This information normally corresponds to the active provider.
            // Do not persist system information in the
            storageSystem.setSmisPassword(provider.getPassword());
            storageSystem.setSmisUserName(provider.getUserName());
            storageSystem.setSmisPortNumber(provider.getPortNumber());
            storageSystem.setSmisProviderIP(provider.getIPAddress());
            storageSystem.setSmisUseSSL(provider.getUseSSL());
            system = getDevice(storageSystem.getSystemType()).doAddStorageSystem(storageSystem);
            _log.info("Storage is added to the SMI-S provider : " + provider.getProviderID());
        }
        return system;
    }

    private boolean scanProvider(StorageProvider provider, StorageSystem storageSystem,
            boolean activeProvider, String opId) throws DatabaseException,
                    BaseCollectionException,
                    ControllerException {

        Map<String, StorageSystemViewObject> storageCache = new HashMap<String, StorageSystemViewObject>();
        _dbClient.createTaskOpStatus(StorageProvider.class, provider.getId(), opId,
                ResourceOperationTypeEnum.SCAN_SMISPROVIDER);
        ScanTaskCompleter scanCompleter = new ScanTaskCompleter(StorageProvider.class, provider.getId(), opId);
        try {
            scanCompleter.statusPending(_dbClient, "Scan for storage system is Initiated");
            provider.setLastScanStatusMessage("");
            _dbClient.updateObject(provider);
            ControllerServiceImpl.performScan(provider.getId(), scanCompleter, storageCache);
            scanCompleter.statusReady(_dbClient, "Scan for storage system has completed");
        } catch (Exception ex) {
            _log.error("Scan failed for {}--->", provider, ex);
            scanCompleter.statusError(_dbClient, DeviceControllerErrors.dataCollectionErrors.scanFailed(ex.getLocalizedMessage(), ex));
            throw DeviceControllerException.exceptions.scanProviderFailed(storageSystem.getNativeGuid(),
                    provider.getId().toString());
        }
        if (!storageCache.containsKey(storageSystem.getNativeGuid())) {
            return false;
        } else {
            StorageSystemViewObject vo = storageCache.get(storageSystem.getNativeGuid());

            String model = vo.getProperty(StorageSystemViewObject.MODEL);
            if (StringUtils.isNotBlank(model)) {
                storageSystem.setModel(model);
            }
            String serialNo = vo.getProperty(StorageSystemViewObject.SERIAL_NUMBER);
            if (StringUtils.isNotBlank(serialNo)) {
                storageSystem.setSerialNumber(serialNo);
            }
            String version = vo.getProperty(StorageSystemViewObject.VERSION);
            if (StringUtils.isNotBlank(version)) {
                storageSystem.setMajorVersion(version);
            }
            String name = vo.getProperty(StorageSystemViewObject.STORAGE_NAME);
            if (StringUtils.isNotBlank(name)) {
                storageSystem.setLabel(name);
            }
            provider.addStorageSystem(_dbClient, storageSystem, activeProvider);
            return true;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws WorkflowException
     */
    @Override
    public void addStorageSystem(URI storage, URI[] providers, boolean activeProvider, String opId) throws ControllerException {

        if (providers == null) {
            return;
        }
        String allProviders = Joiner.on("\t").join(providers);

        DiscoverTaskCompleter completer = new DiscoverTaskCompleter(StorageSystem.class, storage, opId, ControllerServiceImpl.DISCOVERY);
        StringBuilder failedProviders = new StringBuilder();
        boolean exceptionIntercepted = false;
        boolean needDiscovery = false;

        boolean acquiredLock = false;
        try {
            acquiredLock = ControllerServiceImpl.Lock.SCAN_COLLECTION_LOCK.acquire(SCAN_LOCK_TIMEOUT);
        } catch (Exception ex) {
            _log.error("Exception while acquiring a lock ", ex);
            acquiredLock = false;
        }

        if (acquiredLock) {
            try {
                StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);

                for (int ii = 0; ii < providers.length; ii++) {
                    try {
                        StorageProvider providerSMIS = _dbClient.queryObject(StorageProvider.class, providers[ii]);

                        if (providerSMIS == null) {
                            throw DeviceControllerException.exceptions.entityNullOrEmpty(null);
                        }
                        if (providerSMIS.getInactive()) {
                            throw DeviceControllerException.exceptions.entityInactive(providerSMIS.getId());
                        }
                        boolean found = scanProvider(providerSMIS, storageSystem, activeProvider && ii == 0, opId);
                        if (!found) {
                            if (storageSystem.getSystemType().equals(Type.vnxblock.toString()) &&
                                    StringUtils.isNotBlank(storageSystem.getIpAddress())) {
                                String system = addStorageToSMIS(storageSystem, providerSMIS);
                                if (!system.equalsIgnoreCase(storageSystem.getNativeGuid())) {
                                    throw DeviceControllerException.exceptions.addStorageSystemFailed(storageSystem.getNativeGuid(),
                                            providerSMIS.getId().toString());
                                }
                                providerSMIS.addStorageSystem(_dbClient, storageSystem, activeProvider && ii == 0);
                                if (activeProvider && ii == 0) {
                                    providerSMIS.removeDecommissionedSystem(_dbClient, storageSystem.getNativeGuid());
                                }
                                storageSystem.setReachableStatus(true);
                                _dbClient.updateObject(storageSystem);
                            } else {
                                throw DeviceControllerException.exceptions.scanFailedToFindSystem(providerSMIS.getId().toString(),
                                        storageSystem.getNativeGuid());
                            }
                        } else {
                            storageSystem.setReachableStatus(true);
                            _dbClient.updateObject(storageSystem);
                        }
                        if (providers.length > 1) {
                            completer.statusPending(_dbClient,
                                    "Adding storage to SMIS Providers : completed " + (ii + 1) + " providers out of " + providers.length);
                        }
                    } catch (Exception ex) {// any type of exceptions for a particular provider
                        _log.error("Failed to add storage from the following provider: " + providers[ii], ex);
                        failedProviders.append(providers[ii]).append(' ');
                        exceptionIntercepted = true;
                    }
                }
                if (activeProvider) {
                    updateActiveProvider(storageSystem);
                    _dbClient.updateObject(storageSystem);
                }

                DecommissionedResource.removeDecommissionedFlag(_dbClient, storageSystem.getNativeGuid(), StorageSystem.class);

                if (exceptionIntercepted) {
                    String opName = ResourceOperationTypeEnum.ADD_STORAGE_SYSTEM.getName();
                    ServiceError serviceError = DeviceControllerException.errors.jobFailedOp(opName);
                    completer.error(_dbClient, serviceError);
                } else {
                    recordBourneStorageEvent(RecordableEventManager.EventType.StorageDeviceAdded,
                            storageSystem, "Added SMI-S Storage");
                    _log.info("Storage is added to the SMI-S providers: ");
                    if (activeProvider) {
                        needDiscovery = true;

                        storageSystem.setLastDiscoveryRunTime(new Long(0));
                        completer.statusPending(_dbClient,
                                "Storage is added to the specified SMI-S providers : " + allProviders);
                        // We need to set timer back to 0 to indicate that it is a new system ready for discovery.
                        _dbClient.updateObject(storageSystem);
                    } else {
                        completer.ready(_dbClient);
                    }
                }
            } catch (Exception outEx) {
                exceptionIntercepted = true;
                _log.error("Failed to add SMIS providers", outEx);
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(outEx);
                completer.error(_dbClient, serviceError);
            } finally {
                try {
                    ControllerServiceImpl.Lock.SCAN_COLLECTION_LOCK.release();
                } catch (Exception ex) {
                    _log.error("Failed to release SCAN lock; scanning might become disabled", ex);
                }
                if (needDiscovery && !exceptionIntercepted) {
                    try {
                        ControllerServiceImpl.scheduleDiscoverJobs(
                                new AsyncTask[] { new AsyncTask(StorageSystem.class, storage, opId) },
                                Lock.DISCOVER_COLLECTION_LOCK, ControllerServiceImpl.DISCOVERY);
                    } catch (Exception ex) {
                        _log.error("Failed to start discovery : " + storage, ex);
                    }
                }

            }
        } else {
            String opName = ResourceOperationTypeEnum.ADD_STORAGE_SYSTEM.getName();
            ServiceError serviceError = DeviceControllerException.errors.jobFailedOp(opName);
            completer.error(_dbClient, serviceError);
            _log.debug("Not able to Acquire Scanning lock-->{}", Thread.currentThread().getId());
        }
    }

    private void recordBourneStorageEvent(RecordableEventManager.EventType evtType, StorageSystem storage, String desc) {

        RecordableBourneEvent event = ControllerUtils
                .convertToRecordableBourneEvent(storage, evtType.toString(),
                        desc, "", _dbClient,
                        ControllerUtils.BLOCK_EVENT_SERVICE,
                        RecordType.Event.name(),
                        ControllerUtils.BLOCK_EVENT_SOURCE);

        try {
            _eventManager.recordEvents(event);
            _log.info("Bourne {} event recorded", evtType.name());
        } catch (Exception ex) {
            _log.error(
                    "Failed to record event. Event description: {}. Error: ",
                    evtType.name(), ex);
        }
    }

    private void updateActiveProvider(StorageSystem storage) throws DatabaseException {
        if (storage.getActiveProviderURI() == null) {
            return;
        }
        StorageProvider mainProvider = _dbClient.queryObject(StorageProvider.class, storage.getActiveProviderURI());
        if (mainProvider != null) {
            storage.setSmisPassword(mainProvider.getPassword());
            storage.setSmisUserName(mainProvider.getUserName());
            storage.setSmisPortNumber(mainProvider.getPortNumber());
            storage.setSmisProviderIP(mainProvider.getIPAddress());
            storage.setSmisUseSSL(mainProvider.getUseSSL());
            _dbClient.updateObject(storage);
        }
    }

    @Override
    public void startMonitoring(AsyncTask task, Type deviceType)
            throws ControllerException {
        // TODO Auto-generated method stub
    }

    public <T extends BlockObject> Workflow.Method waitForSynchronizedMethod(Class clazz, URI storage, List<URI> target, boolean isCG) {
        return new Workflow.Method("waitForSynchronized", clazz, storage, target, isCG);
    }

    public void waitForSynchronized(Class<? extends BlockObject> clazz, URI storage, List<URI> target, boolean isCG, String opId)
            throws ControllerException {
        _log.info("START waitForSynchronized for {}", target);
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockWaitForSynchronizedCompleter(clazz, target, opId);
            if (!isCG) {
                getDevice(storageObj.getSystemType()).doWaitForSynchronized(clazz, storageObj, target.get(0), completer);
            } else {
                Volume cloneVolume = _dbClient.queryObject(Volume.class, target.get(0));

                Volume sourceVolumeObj = _dbClient.queryObject(Volume.class, cloneVolume.getAssociatedSourceVolume());
                boolean canIgnoreThisStep = false;

                /**
                 * VPLEX/RP CG volumes may not be having back end Array Group.
                 * In this case we should create element replica using createListReplica.
                 * We can ignore the wait for sync step in this case.
                 */
                canIgnoreThisStep = isListReplicaFlow(sourceVolumeObj);
                if (!canIgnoreThisStep) {
                    getDevice(storageObj.getSystemType()).doWaitForGroupSynchronized(storageObj, target, completer);
                } else {
                    completer.ready(_dbClient); // Workaround
                }
            }
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            if (completer != null) {
                completer.error(_dbClient, serviceError);
            }
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    /**
     * Get the deviceType for a StorageSystem.
     *
     * @param deviceURI
     *            -- StorageSystem URI
     * @return deviceType String
     */
    String getDeviceType(URI deviceURI) throws ControllerException {
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, deviceURI);
        if (storageSystem == null) {
            throw DeviceControllerException.exceptions.getDeviceTypeFailed(deviceURI.toString());
        }
        return storageSystem.getSystemType();
    }

    /**
     * Checks if given BlockMirrors are applicable for pausing.
     * A mirror is valid for pausing if it is
     * 1) Not null
     * 2) Has valid sync state integer value
     * 3) Sync state is not already fractured (paused) and not resynchronizing
     *
     * @param mirrorList
     *            The BlockMirrors to test
     * @return true, if at least one mirror is applicable for pause operation
     */
    private boolean mirrorIsPausable(List<BlockMirror> mirrorList) {
        for (BlockMirror mirror : mirrorList) {
            try {
                boolean hasPausableMirror = mirror != null &&
                        mirror.getInactive() == false &&
                        !SynchronizationState.FRACTURED.toString().equals(mirror.getSyncState()) &&
                        !SynchronizationState.RESYNCHRONIZING.toString().equals(mirror.getSyncState());
                if (hasPausableMirror) { // will continue to look up if it is false
                    return hasPausableMirror;
                }
            } catch (NumberFormatException nfe) {
                _log.warn("Failed to parse sync state ({}) for mirror {}", mirror.getSyncState(), mirror.getId());
            }
        }
        return false;
    }

    /**
     * Check if a mirror exists in ViPR as an active model and is pending creation on the
     * storage array.
     *
     * @param mirror
     * @return true if the mirror is pending creation
     */
    private boolean isPending(BlockMirror mirror) {
        return !isInactive(mirror) && isNullOrEmpty(mirror.getSynchronizedInstance());
    }

    private boolean isInactive(BlockMirror mirror) {
        return mirror == null || (mirror.getInactive() != null && mirror.getInactive());
    }

    @Override
    public void noActionRollBackStep(URI deviceURI, String opID) {
        _log.info("Running empty Roll back step for storage system {}", deviceURI);
        WorkflowStepCompleter.stepSucceded(opID);

    }

    @Override
    public void updateConsistencyGroup(URI storage, URI consistencyGroup,
            List<URI> addVolumesList,
            List<URI> removeVolumesList, String task) {
        TaskCompleter completer = new BlockConsistencyGroupUpdateCompleter(consistencyGroup, task);
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, consistencyGroup);
            boolean srdfCG = false;

            // check if SRDF
            if (addVolumesList != null && !addVolumesList.isEmpty()) {
                URI volumeURI = addVolumesList.get(0);
                if (URIUtil.isType(volumeURI, Volume.class)) {
                    Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
                    if (volume.isSRDFSource()) {
                        srdfCG = true;
                        cg.getRequestedTypes().add(Types.SRDF.name());
                        _dbClient.updateObject(cg);
                    }
                }
            }

            // Generate the Workflow.
            Workflow workflow = _workflowService.getNewWorkflow(this,
                    UPDATE_CONSISTENCY_GROUP_WF_NAME, false, task);
            String waitFor = null;
            // check if cg is created, if not create it
            if (!cg.created()) {
                _log.info("Consistency group not created. Creating it");
                String groupName = ControllerUtils.generateReplicationGroupName(storageSystem, cg, null, _dbClient);
                waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                        String.format("Creating consistency group %s", consistencyGroup),
                        waitFor, storage, storageSystem.getSystemType(),
                        this.getClass(),
                        createConsistencyGroupMethod(storage, consistencyGroup, groupName),
                        deleteConsistencyGroupMethod(storage, consistencyGroup, groupName, false, false, true), null);
            }

            if (addVolumesList != null && !addVolumesList.isEmpty()) {
                String groupName = ControllerUtils.generateReplicationGroupName(storageSystem, cg, null, _dbClient);
                waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                        String.format("Adding volumes to consistency group %s", consistencyGroup),
                        waitFor, storage, storageSystem.getSystemType(),
                        this.getClass(),
                        addToConsistencyGroupMethod(storage, consistencyGroup, groupName, addVolumesList),
                        removeFromConsistencyGroupMethod(storage, consistencyGroup, addVolumesList, false), null);

                // call ReplicaDeviceController
                waitFor = _replicaDeviceController.addStepsForAddingSessionsToCG(workflow, waitFor, consistencyGroup, addVolumesList,
                        groupName, task);
            }

            if (removeVolumesList != null && !removeVolumesList.isEmpty()) {
                Volume volume = _dbClient.queryObject(Volume.class, removeVolumesList.get(0));
                if (volume != null && !volume.getInactive()) {
                    String groupName = volume.getReplicationGroupInstance();
                    // call ReplicaDeviceController
                    waitFor = _replicaDeviceController.addStepsForRemovingVolumesFromCG(workflow, waitFor, consistencyGroup,
                            removeVolumesList, task);
                    waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                            String.format("Removing volumes from consistency group %s", consistencyGroup),
                            waitFor, storage, storageSystem.getSystemType(),
                            this.getClass(),
                            removeFromConsistencyGroupMethod(storage, consistencyGroup, removeVolumesList, false),
                            addToConsistencyGroupMethod(storage, consistencyGroup, groupName, removeVolumesList), null);

                    // remove replication group if the CG will become empty
                    if ((addVolumesList == null || addVolumesList.isEmpty()) &&
                            ControllerUtils.cgHasNoOtherVolume(_dbClient, consistencyGroup, removeVolumesList)) {
                        waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                                String.format("Deleting replication group for consistency group %s", consistencyGroup),
                                waitFor, storage, storageSystem.getSystemType(),
                                this.getClass(),
                                deleteConsistencyGroupMethod(storage, consistencyGroup, groupName, false, false, true),
                                createConsistencyGroupMethod(storage, consistencyGroup, groupName), null);
                    }
                }
            }

            // For SRDF, we need to create target consistency group and
            // add target volumes to that target consistency group.
            if (srdfCG) {
                createTargetConsistencyGroup(cg, addVolumesList, workflow, waitFor, task);
            }
            // Finish up and execute the plan.
            _log.info("Executing workflow plan {}", UPDATE_CONSISTENCY_GROUP_STEP_GROUP);
            String successMessage = String.format(
                    "Update consistency group successful for %s", consistencyGroup);
            workflow.executePlan(completer, successMessage);
        } catch (Exception e) {
            _log.error("Error updating consistency group: {}", consistencyGroup, e);
            completer.error(_dbClient, DeviceControllerException.exceptions.failedToUpdateConsistencyGroup(e.getMessage()));
        }
    }

    private static Workflow.Method createConsistencyGroupMethod(URI storage, URI consistencyGroup, String replicationGroupName) {
        return new Workflow.Method("createConsistencyGroupStep", storage, consistencyGroup, replicationGroupName);
    }

    public boolean createConsistencyGroupStep(URI storage, URI consistencyGroup, String replicationGroupName, String opId)
            throws ControllerException {
        TaskCompleter taskCompleter = null;
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            taskCompleter = new BlockConsistencyGroupCreateCompleter(consistencyGroup, opId);
            String groupName = ControllerUtils.generateReplicationGroupName(storageSystem, consistencyGroup, replicationGroupName,
                    _dbClient);
            String lockKey = groupName;
            boolean isVNX = storageSystem.deviceIsType(Type.vnxblock);
            if (isVNX && lockKey == null) {
                lockKey = replicationGroupName;
            }

            if (lockKey == null) {
                BlockConsistencyGroup cgObj = _dbClient.queryObject(BlockConsistencyGroup.class, consistencyGroup);
                lockKey = cgObj.getAlternateLabel() != null ? cgObj.getAlternateLabel() : cgObj.getLabel();
            }
            // Lock the CG for the step duration.
            List<String> lockKeys = new ArrayList<>();
            lockKeys.add(ControllerLockingUtil.getReplicationGroupStorageKey(_dbClient, lockKey, storage));
            _workflowService.acquireWorkflowStepLocks(opId, lockKeys, LockTimeoutValue.get(LockType.ARRAY_CG));

            if (isVNX) {
                // replication group may have been just created by another thread, in that case,
                // group name for VNX will be array generated name (if arrayConsistency is true), or
                // replicationGroupName if arrayConsistency is false
                // so get the group name again here to be used in ControllerUtils.replicationGroupExists call
                groupName = ControllerUtils.generateReplicationGroupName(storageSystem, consistencyGroup, replicationGroupName,
                        _dbClient);
            }

            // make sure this array consistency group was not just created by another thread that held the lock
            if (groupName != null && ControllerUtils.replicationGroupExists(storage, groupName, _dbClient)) {
                taskCompleter.ready(_dbClient);
                return true;
            }

            getDevice(storageSystem.getSystemType()).doCreateConsistencyGroup(
                    storageSystem, consistencyGroup, groupName, taskCompleter);
        } catch (Exception e) {
            _log.error("create consistency group job failed:", e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            taskCompleter.error(_dbClient, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            return false;
        }
        return true;
    }

    public String addStepToAddToConsistencyGroup(Workflow workflow, URI storage, URI consistencyGroup, String replicationGroupName,
            List<URI> addVolumesList, String waitFor) {
        String stepId = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                String.format("Adding volumes to consistency group %s", consistencyGroup),
                waitFor, storage, getDeviceType(storage),
                this.getClass(),
                addToConsistencyGroupMethod(storage, consistencyGroup, replicationGroupName, addVolumesList),
                rollbackMethodNullMethod(), null);
        return stepId;
    }

    private static Workflow.Method addToConsistencyGroupMethod(URI storage, URI consistencyGroup, String replicationGroupName,
            List<URI> addVolumesList) {
        return new Workflow.Method("addToConsistencyGroup", storage, consistencyGroup, replicationGroupName, addVolumesList);
    }

    public boolean addToConsistencyGroup(URI storage, URI consistencyGroup, String replicationGroupName, List<URI> addVolumesList,
            String opId)
                    throws ControllerException {
        TaskCompleter taskCompleter = null;
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            taskCompleter = new BlockConsistencyGroupAddVolumeCompleter(consistencyGroup, addVolumesList, replicationGroupName, opId);

            // Lock the CG for the step duration.
            List<String> lockKeys = new ArrayList<String>();
            if (replicationGroupName == null) {
                replicationGroupName = ControllerUtils.generateReplicationGroupName(storageSystem, consistencyGroup, null, _dbClient);
            }
            lockKeys.add(ControllerLockingUtil.getReplicationGroupStorageKey(_dbClient, replicationGroupName, storage));
            _workflowService.acquireWorkflowStepLocks(opId, lockKeys, LockTimeoutValue.get(LockType.ARRAY_CG));

            getDevice(storageSystem.getSystemType()).doAddToConsistencyGroup(
                    storageSystem, consistencyGroup, replicationGroupName, addVolumesList, taskCompleter);
        } catch (Exception e) {
            _log.error("Error whilst adding to consistency group", e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            taskCompleter.error(_dbClient, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            return false;
        }
        return true;
    }

    public String addStepToRemoveFromConsistencyGroup(Workflow workflow, URI storage, URI consistencyGroup, List<URI> removeVolumesList,
            String waitFor, boolean keepRGReference) {
        String stepId = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                String.format("Removing volumes from consistency group %s", consistencyGroup),
                waitFor, storage, getDeviceType(storage),
                this.getClass(),
                removeFromConsistencyGroupMethod(storage, consistencyGroup, removeVolumesList, keepRGReference),
                rollbackMethodNullMethod(), null);
        return stepId;
    }

    private static Workflow.Method removeFromConsistencyGroupMethod(URI storage, URI consistencyGroup, List<URI> removeVolumesList,
            boolean keepRGReference) {
        return new Workflow.Method("removeFromConsistencyGroup", storage, consistencyGroup, removeVolumesList, keepRGReference);
    }

    public boolean removeFromConsistencyGroup(URI storage, URI consistencyGroup, List<URI> removeVolumesList, boolean keepRGReference,
            String opId)
                    throws ControllerException {
        TaskCompleter taskCompleter = null;
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            taskCompleter = new BlockConsistencyGroupRemoveVolumeCompleter(consistencyGroup, removeVolumesList, keepRGReference, opId);

            // Lock the CG for the step duration.
            if (removeVolumesList != null && !removeVolumesList.isEmpty()) {
                List<String> lockKeys = new ArrayList<String>();
                URI volumeUri = removeVolumesList.get(0);
                Volume volume = _dbClient.queryObject(Volume.class, volumeUri);
                String repGroup = volume.getReplicationGroupInstance();
                if (NullColumnValueGetter.isNotNullValue(repGroup)) {
                    lockKeys.add(ControllerLockingUtil.getReplicationGroupStorageKey(_dbClient, repGroup, storage));
                } else {
                    lockKeys.add(ControllerLockingUtil.getConsistencyGroupStorageKey(_dbClient, consistencyGroup, storage));
                }
                _workflowService.acquireWorkflowStepLocks(opId, lockKeys, LockTimeoutValue.get(LockType.ARRAY_CG));
            }
            getDevice(storageSystem.getSystemType()).doRemoveFromConsistencyGroup(
                    storageSystem, consistencyGroup, removeVolumesList, taskCompleter);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            taskCompleter.error(_dbClient, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            return false;
        }
        return true;
    }

    /**
     * This method creates SRDF target consistency group for the SRDF source consistency group 1. Creates target
     * consistency group in ViPR
     * DB & Array 2. Get the target volumes for the SRDF source volumes and adds them to the target consistency group
     * Note: Target CG will
     * be created using source system provider
     *
     * @param sourceCG
     *            the source cg
     * @param addVolumesList
     *            the add volumes list
     * @param workflow
     *            the workflow
     * @param waitFor
     *            the wait for
     */
    private void createTargetConsistencyGroup(BlockConsistencyGroup sourceCG,
            List<URI> addVolumesList, Workflow workflow, String waitFor, String task) {
        Volume sourceVolume = _dbClient.queryObject(Volume.class, addVolumesList.get(0));
        VirtualPool vPool = _dbClient.queryObject(VirtualPool.class, sourceVolume.getVirtualPool());
        VirtualArray protectionVirtualArray = null;
        if (vPool.getProtectionRemoteCopySettings() != null) {
            for (String protectionVarray : vPool.getProtectionRemoteCopySettings().keySet()) {
                protectionVirtualArray = _dbClient.queryObject(VirtualArray.class, URI.create(protectionVarray));
            }
        }
        String vArrayName = null;
        if (protectionVirtualArray != null) {
            vArrayName = protectionVirtualArray.getLabel();
        }
        String cgName = sourceCG.getLabel() + "-Target-" + vArrayName;
        List<BlockConsistencyGroup> groups = CustomQueryUtility
                .queryActiveResourcesByConstraint(_dbClient,
                        BlockConsistencyGroup.class, PrefixConstraint.Factory
                                .getFullMatchConstraint(
                                        BlockConsistencyGroup.class, "label",
                                        cgName));
        BlockConsistencyGroup targetCG = null;
        boolean createGroup = false;
        if (groups.isEmpty()) {
            // create target CG
            targetCG = new BlockConsistencyGroup();
            targetCG.setId(URIUtil.createId(BlockConsistencyGroup.class));
            targetCG.setLabel(cgName);
            // TODO verify project and tenant NamedURIs
            targetCG.setProject(sourceCG.getProject());
            targetCG.setTenant(sourceCG.getTenant());
            targetCG.setAlternateLabel(sourceCG.getLabel());
            targetCG.getRequestedTypes().add(Types.SRDF.name());
            _dbClient.createObject(targetCG);
            createGroup = true;
        } else {
            targetCG = groups.get(0);
        }

        // Get SRDF Target volume list for the source volume list
        List<URI> addTargetVolumesList = getSRDFTargetVolumes(addVolumesList);

        if (!addTargetVolumesList.isEmpty()) {
            Volume targetVolume = _dbClient.queryObject(Volume.class, addTargetVolumesList.get(0));
            StorageSystem targetSystem = _dbClient.queryObject(StorageSystem.class, targetVolume.getStorageController());
            String groupName = ControllerUtils.generateReplicationGroupName(targetSystem, targetCG, null, _dbClient);
            if (createGroup || !targetCG.created()) {
                _log.info("Creating target Consistency group on Array.");
                waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                        String.format("Creating consistency group %s", targetCG.getId()),
                        waitFor, targetSystem.getId(), targetSystem.getSystemType(),
                        this.getClass(),
                        createConsistencyGroupMethod(targetSystem.getId(), targetCG.getId(), groupName),
                        rollbackMethodNullMethod(), null);
            }

            _log.info("Adding target volumes to target Consistency group.");
            waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                    String.format("Updating consistency group %s", targetCG.getId()),
                    waitFor, targetSystem.getId(), targetSystem.getSystemType(),
                    this.getClass(),
                    addToConsistencyGroupMethod(targetSystem.getId(), targetCG.getId(), null, addTargetVolumesList),
                    rollbackMethodNullMethod(), null);

            // call ReplicaDeviceController
            waitFor = _replicaDeviceController.addStepsForAddingSessionsToCG(workflow, waitFor, targetCG.getId(), addTargetVolumesList,
                    groupName, task);

        }
    }

    /**
     *
     * @param sourceVolumeList
     * @return URI list of target volumes for the given source volumes
     */
    private List<URI> getSRDFTargetVolumes(List<URI> sourceVolumeList) {
        List<URI> addTargetVolumesList = new ArrayList<URI>();

        Iterator<URI> it = sourceVolumeList.iterator();
        while (it.hasNext()) {
            URI sourceVolumeURI = it.next();
            Volume sourceVolume = _dbClient.queryObject(Volume.class, sourceVolumeURI);
            if (sourceVolume.isSRDFSource()) {
                StringSet targets = sourceVolume.getSrdfTargets();
                for (String target : targets) {
                    addTargetVolumesList.add(URI.create(target));
                }
            }
        }
        return addTargetVolumesList;
    }

    @Override
    public boolean validateStorageProviderConnection(String ipAddress,
            Integer portNumber, String interfaceType) {
        List<String> systemType = InterfaceType
                .getSystemTypesForInterfaceType(InterfaceType
                        .valueOf(interfaceType));
        return getDevice(systemType.get(0)).validateStorageProviderConnection(
                ipAddress, portNumber);
    }

    @Override
    public String addStepsForPostDeleteVolumes(Workflow workflow, String waitFor,
            List<VolumeDescriptor> volumes, String taskId,
            VolumeWorkflowCompleter completer) {
        // delete replication group if it becomes empty
        // Get the list of descriptors which represent source volumes to be deleted
        List<VolumeDescriptor> volumeDescriptors = VolumeDescriptor.filterByType(volumes,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_DATA },
                null);

        // If no source volumes, just return
        if (volumeDescriptors.isEmpty()) {
            _log.info("No post deletion step required");
            return waitFor;
        }

        // Sort the volumes by its system, and replicationGroup
        Map<String, Set<URI>> rgVolsMap = new HashMap<String, Set<URI>>();
        for (VolumeDescriptor volumeDescriptor : volumeDescriptors) {
            URI volumeURI = volumeDescriptor.getVolumeURI();
            Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
            if (volume != null) {
                /*
                 * No need to remove replication group as SRDF volume's
                 * rep group will be removed as part of srdf volume delete steps.
                 */
                if (!Volume.isSRDFProtectedVolume(volume)) {
                    String replicationGroup = volume.getReplicationGroupInstance();
                    if (NullColumnValueGetter.isNotNullValue(replicationGroup)) {
                        URI storage = volume.getStorageController();
                        String key = storage.toString() + replicationGroup;
                        Set<URI> rgVolumeList = rgVolsMap.get(key);
                        if (rgVolumeList == null) {
                            rgVolumeList = new HashSet<URI>();
                            rgVolsMap.put(key, rgVolumeList);
                        }
                        rgVolumeList.add(volumeURI);
                    }
                } else {
                    _log.info("post delete not required for SRDF Volume :{}", volume.getId());
                }
            }
        }

        if (rgVolsMap.isEmpty()) {
            return waitFor;
        }

        for (Set<URI> volumeURIs : rgVolsMap.values()) {
            // find member volumes in the group
            List<Volume> volumeList = new ArrayList<Volume>();
            Iterator<Volume> volumeIterator = _dbClient.queryIterativeObjects(Volume.class, volumeURIs);
            while (volumeIterator.hasNext()) {
                Volume volume = volumeIterator.next();
                if (volume != null && !volume.getInactive()) {
                    volumeList.add(volume);
                }
            }
            Volume firstVol = volumeList.get(0);
            String rgName = firstVol.getReplicationGroupInstance();
            URI storage = firstVol.getStorageController();
            URI cgURI = firstVol.getConsistencyGroup();
            // delete replication group from array
            if (ControllerUtils.replicationGroupHasNoOtherVolume(_dbClient, rgName, volumeURIs, storage)) {
                _log.info(String.format("Adding step to delete the replication group %s", rgName));
                StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
                waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                        String.format("Deleting replication group  %s", rgName),
                        waitFor, storage, storageSystem.getSystemType(),
                        this.getClass(),
                        deleteConsistencyGroupMethod(storage, cgURI, rgName, false, false, false),
                        rollbackMethodNullMethod(), null);
            }
        }

        return waitFor;
    }

    @Override
    public String addStepsForChangeVirtualPool(Workflow workflow,
            String waitFor, List<VolumeDescriptor> volumes, String taskId) throws InternalException {
        // Nothing to do, no steps to add
        return waitFor;
    }

    @Override
    public String addStepsForChangeVirtualArray(Workflow workflow, String waitFor,
            List<VolumeDescriptor> volumes, String taskId) throws InternalException {
        // Nothing to do, no steps to add
        return waitFor;
    }

    private static final String RESTORE_FROM_CLONE_WF_NAME = "RESTORE_FROM_CLONE_WORKFLOW";
    private static final String RESTORE_FROM_CLONE_GROUP = "BlockDeviceRestoreFromClone";
    private static final String FRACTURE_CLONE_GROUP = "PostBlockDeviceFractureClone";

    @Override
    public void restoreFromFullCopy(URI storage, List<URI> clones,
            Boolean updateOpStatus, String opId) throws InternalException {
        TaskCompleter completer = new CloneWorkflowCompleter(clones, opId);
        try {
            Workflow workflow = _workflowService.getNewWorkflow(this, RESTORE_FROM_CLONE_WF_NAME, false, opId);
            _log.info("Created new restore workflow with operation id {}", opId);
            String waitFor = null;

            Volume clone = _dbClient.queryObject(Volume.class, clones.get(0));
            URI source = clone.getAssociatedSourceVolume();
            BlockObject sourceObj = BlockObject.fetch(_dbClient, source);
            /**
             * We need to detach SRDF link before performing clone restore to SRDF R2 volume.
             * OPT#477320
             */
            if (sourceObj instanceof Volume && isNonSplitSRDFTargetVolume((Volume) sourceObj)) {
                // PRIOR to Restoring R2 Device from its Full copy Clone, we need to
                // a) SUSPEND the R1-R2 pair if the Copy Mode is ACTIVE Or
                // b) SPLIT the R1-R2 pair if the Copy Mode is SYNC/ ASYNC
                Volume sourceVolume = (Volume) sourceObj;
                URI srdfSourceVolumeURI = sourceVolume.getSrdfParent().getURI();
                Volume srdfSourceVolume = _dbClient.queryObject(Volume.class, srdfSourceVolumeURI);
                URI srdfSourceStorageSystemURI = srdfSourceVolume.getStorageController();
                if (Mode.ACTIVE.equals(Mode.valueOf(sourceVolume.getSrdfCopyMode()))) {
                    waitFor = suspendSRDFLinkWorkflowStep(waitFor, srdfSourceStorageSystemURI,
                            srdfSourceVolumeURI, source, workflow);
                } else {
                    // split all members the group
                    Workflow.Method splitMethod = srdfDeviceController.splitSRDFLinkMethod(srdfSourceStorageSystemURI,
                            srdfSourceVolumeURI, source, false);

                    Workflow.Method splitRollbackMethod = srdfDeviceController.resumeSyncPairMethod(srdfSourceStorageSystemURI,
                            srdfSourceVolumeURI, source);

                    waitFor = workflow.createStep(SRDFDeviceController.SPLIT_SRDF_MIRRORS_STEP_GROUP,
                            SRDFDeviceController.SPLIT_SRDF_MIRRORS_STEP_DESC, waitFor, srdfSourceStorageSystemURI,
                            getDeviceType(srdfSourceStorageSystemURI), SRDFDeviceController.class, splitMethod,
                            splitRollbackMethod, null);
                }
            } else if (sourceObj instanceof Volume && isNonSplitSRDFSourceVolume((Volume) sourceObj)) {
                // PRIOR to Restoring R1 Device from its Full copy Clone, we need to SUSPEND the R1-R2 pair if the Copy
                // Mode is ACTIVE
                Volume srdfSourceVolume = (Volume) sourceObj;
                URI srdfSourceStorageSystemURI = srdfSourceVolume.getStorageController();
                StringSet targets = srdfSourceVolume.getSrdfTargets();
                if (null != targets) {
                    for (String target : targets) {
                        if (NullColumnValueGetter.isNotNullValue(target)) {
                            Volume srdfTargetVolume = _dbClient.queryObject(Volume.class, URI.create(target));
                            if (null != srdfTargetVolume && Mode.ACTIVE.equals(Mode.valueOf(srdfTargetVolume.getSrdfCopyMode()))) {
                                waitFor = suspendSRDFLinkWorkflowStep(waitFor, srdfSourceStorageSystemURI,
                                        srdfSourceVolume.getId(), srdfTargetVolume.getId(), workflow);
                            }
                            break;
                        }
                    }
                }
            }

            StorageSystem system = _dbClient.queryObject(StorageSystem.class, storage);
            // add CG to taskCompleter
            boolean isCG = checkCloneConsistencyGroup(clones.get(0), _dbClient, completer);
            String description = String.format("Restore volume from %s", clones.get(0));
            String previousStep = workflow.createStep(RESTORE_FROM_CLONE_GROUP, description, waitFor,
                    storage, getDeviceType(storage), BlockDeviceController.class,
                    restoreFromCloneMethod(storage, clones, updateOpStatus, isCG),
                    rollbackMethodNullMethod(), null);

            if (isCG && system.deviceIsType(Type.vnxblock)) {
                for (URI cloneUri : clones) {
                    Workflow.Method waitForSyncMethod = waitForSynchronizedMethod(Volume.class, storage, Arrays.asList(cloneUri), false);
                    String waitForSyncStep = workflow.createStep(FULL_COPY_WFS_STEP_GROUP,
                            "Waiting for synchronization", previousStep, storage,
                            system.getSystemType(), getClass(), waitForSyncMethod, rollbackMethodNullMethod(), null);
                    previousStep = waitForSyncStep;
                }

            } else {
                Workflow.Method waitForSyncMethod = waitForSynchronizedMethod(Volume.class, storage, clones, isCG);
                String waitForSyncStep = workflow.createStep(FULL_COPY_WFS_STEP_GROUP,
                        "Waiting for synchronization", previousStep, storage,
                        system.getSystemType(), getClass(), waitForSyncMethod, rollbackMethodNullMethod(), null);
                previousStep = waitForSyncStep;
            }
            if (system.deviceIsType(Type.vmax) || system.deviceIsType(Type.vnxblock)) {
                addFractureSteps(workflow, system, clones, previousStep, isCG);
            }

            _log.info("Executing workflow {}", RESTORE_FROM_CLONE_GROUP);
            String msg = String.format("Restore from %s completed successfully", clones.get(0));
            workflow.executePlan(completer, msg);

        } catch (Exception e) {
            String msg = String.format("Could not restore from the clone %s", clones.get(0));
            _log.error(msg, e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            completer.error(_dbClient, serviceError);
        }

    }

    /**
     * Return a Workflow.Method for restoreVolume
     *
     * @param storage
     *            storage system
     * @param pool
     *            storage pool
     * @param volume
     *            target of restore operation
     * @param snapshot
     *            snapshot to restore from
     * @param updateOpStatus
     *            update operation status flag
     * @return Workflow.Method
     */
    public static Workflow.Method restoreFromCloneMethod(URI storage, List<URI> clone, Boolean updateOpStatus, boolean isCG) {
        return new Workflow.Method("restoreFromCloneStep", storage, clone, updateOpStatus, isCG);
    }

    public boolean restoreFromCloneStep(URI storage, List<URI> clones, Boolean updateOpStatus, boolean isCG, String opId)
            throws ControllerException {
        TaskCompleter completer = null;
        try {
            StorageSystem storageDevice = _dbClient.queryObject(StorageSystem.class, storage);
            if (!isCG) {
                completer = new CloneRestoreCompleter(clones.get(0), opId);
                getDevice(storageDevice.getSystemType()).doRestoreFromClone(storageDevice, clones.get(0), completer);
            } else {
                CloneRestoreCompleter taskCompleter = new CloneRestoreCompleter(clones, opId);
                getDevice(storageDevice.getSystemType()).doRestoreFromGroupClone(storageDevice, clones, taskCompleter);
            }

        } catch (Exception e) {
            _log.error(String.format("restoreFromClone failed - storage: %s,clone: %s",
                    storage.toString(), clones.get(0).toString()));
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            completer.error(_dbClient, serviceError);
            doFailTask(Volume.class, clones, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            return false;
        }
        return true;
    }

    private void addFractureSteps(Workflow workflow, StorageSystem system, List<URI> clone, String previousStep, boolean isCG) {

        _log.info("Adding fracture restore post-step for clone {}", clone.toString());
        String description = String.format("Fracture clone %s", clone.toString());
        workflow.createStep(FRACTURE_CLONE_GROUP, description, previousStep,
                system.getId(), system.getSystemType(), BlockDeviceController.class,
                fractureCloneMethod(system.getId(), clone, isCG),
                rollbackMethodNullMethod(), null);

    }

    public static Workflow.Method fractureCloneMethod(URI storage, List<URI> clone, boolean isCG) {
        return new Workflow.Method(FRACTURE_CLONE_METHOD, storage, clone, isCG);
    }

    public boolean fractureClone(URI storage, List<URI> clone, boolean isCG, String opId) {
        _log.info("Fracture clone: {}", clone.get(0));
        TaskCompleter completer = null;
        try {
            StorageSystem storageDevice = _dbClient.queryObject(StorageSystem.class, storage);
            WorkflowStepCompleter.stepExecuting(opId);
            if (!isCG) {
                Volume cloneVol = _dbClient.queryObject(Volume.class, clone.get(0));
                completer = new CloneFractureCompleter(clone.get(0), opId);
                WorkflowStepCompleter.stepExecuting(opId);
                // Synchronous operation
                getDevice(storageDevice.getSystemType()).doFractureClone(storageDevice, cloneVol.getAssociatedSourceVolume(), clone.get(0),
                        completer);
            } else {
                _log.info("Fracture group clone.");
                completer = new CloneFractureCompleter(clone, opId);
                WorkflowStepCompleter.stepExecuting(opId);
                // Synchronous operation
                getDevice(storageDevice.getSystemType()).doFractureGroupClone(storageDevice, clone, completer);
            }
        } catch (Exception e) {
            _log.error(
                    String.format("Fracture restore sessions step failed - storage: %s, clone: %s",
                            storage.toString(), clone.toString()));
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            if (completer != null) {
                completer.error(_dbClient, serviceError);
            }
            doFailTask(Volume.class, clone, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            return false;
        }

        return true;
    }

    private static final String RESYNC_CLONE_WF_NAME = "RESYNC_CLONE_WORKFLOW";
    private static final String RESYNC_CLONE_GROUP = "BlockDeviceResyncClone";

    @Override
    public void resyncFullCopy(URI storage, List<URI> clones,
            Boolean updateOpStatus, String opId) throws InternalException {
        TaskCompleter completer = new CloneWorkflowCompleter(clones, opId);
        try {
            Workflow workflow = _workflowService.getNewWorkflow(this, RESYNC_CLONE_WF_NAME, false, opId);
            _log.info("Created new resync workflow with operation id {}", opId);

            StorageSystem system = _dbClient.queryObject(StorageSystem.class, storage);
            // add CG to taskCompleter
            boolean isCG = checkCloneConsistencyGroup(clones.get(0), _dbClient, completer);

            String description = String.format("Resync clone %s", clones.get(0));
            String previousStep = workflow.createStep(RESYNC_CLONE_GROUP, description, null,
                    storage, getDeviceType(storage), BlockDeviceController.class,
                    resyncCloneMethod(storage, clones, updateOpStatus, isCG),
                    rollbackMethodNullMethod(), null);

            if (isCG && system.deviceIsType(Type.vnxblock)) {
                for (URI cloneUri : clones) {
                    Workflow.Method waitForSyncMethod = waitForSynchronizedMethod(Volume.class, storage, Arrays.asList(cloneUri), false);
                    String waitForSyncStep = workflow.createStep(FULL_COPY_WFS_STEP_GROUP,
                            "Waiting for synchronization", previousStep, storage,
                            system.getSystemType(), getClass(), waitForSyncMethod, null, null);
                    previousStep = waitForSyncStep;
                }
            } else {
                Workflow.Method waitForSyncMethod = waitForSynchronizedMethod(Volume.class, storage, clones, isCG);
                String waitForSyncStep = workflow.createStep(FULL_COPY_WFS_STEP_GROUP,
                        "Waiting for synchronization", previousStep, storage,
                        system.getSystemType(), getClass(), waitForSyncMethod, null, null);
                previousStep = waitForSyncStep;
            }
            if (system.deviceIsType(Type.vnxblock)) {
                addFractureSteps(workflow, system, clones, previousStep, isCG);
            } else {
                setCloneReplicaStateStep(workflow, system, clones, previousStep, ReplicationState.SYNCHRONIZED);
            }

            _log.info("Executing workflow {}", RESYNC_CLONE_GROUP);
            String msg = String.format("Resync %s completed successfully", clones.get(0));
            workflow.executePlan(completer, msg);

        } catch (Exception e) {
            String msg = String.format("Could not resync the clone %s", clones.get(0));
            _log.error(msg, e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            completer.error(_dbClient, serviceError);
        }

    }

    /**
     * Return a Workflow.Method for resync
     *
     * @param storage
     *            storage system
     * @param clone
     *            list of clones
     * @param updateOpStatus
     *            update operation status flag
     * @return Workflow.Method
     */
    public static Workflow.Method resyncCloneMethod(URI storage, List<URI> clone, Boolean updateOpStatus, boolean isCG) {
        return new Workflow.Method("resyncFullCopyStep", storage, clone, updateOpStatus, isCG);
    }

    public boolean resyncFullCopyStep(URI storage, List<URI> clone, Boolean updateOpStatus, boolean isCG, String opId)
            throws ControllerException {
        _log.info("Start resync full copy");
        CloneResyncCompleter taskCompleter = new CloneResyncCompleter(clone, opId);
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            if (checkCloneConsistencyGroup(clone.get(0), _dbClient, taskCompleter)) {
                _log.info("resync group full copy");
                getDevice(storageSystem.getSystemType()).doResyncGroupClone(storageSystem, clone, taskCompleter);
            } else {
                getDevice(storageSystem.getSystemType()).doResyncClone(storageSystem, clone.get(0), taskCompleter);
            }
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            taskCompleter.error(_dbClient, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            doFailTask(Volume.class, clone, opId, serviceError);
            return false;
        }
        return true;
    }

    private void setCloneReplicaStateStep(Workflow workflow, StorageSystem system, List<URI> clones, String previousStep,
            ReplicationState state) {
        _log.info("Setting the clone replica state.");
        workflow.createStep("SET_FINAL_REPLICA_STATE", "Set the clones replica state", previousStep,
                system.getId(), system.getSystemType(), BlockDeviceController.class,
                setCloneStateMethod(clones, state), rollbackMethodNullMethod(), null);

    }

    private final static String SET_CLONE_STATE_METHOD = "setCloneState";

    public static Workflow.Method setCloneStateMethod(List<URI> clone, ReplicationState state) {
        return new Workflow.Method(SET_CLONE_STATE_METHOD, clone, state);
    }

    public void setCloneState(List<URI> clones, ReplicationState state, String opId) {
        _log.info("Set clones state");
        List<Volume> cloneVols = _dbClient.queryObject(Volume.class, clones);
        for (Volume cloneVol : cloneVols) {
            cloneVol.setReplicaState(state.name());
        }
        _dbClient.updateObject(cloneVols);
        CloneCreateWorkflowCompleter completer = new CloneCreateWorkflowCompleter(clones, opId);
        completer.ready(_dbClient);
    }

    public void setSrdfDeviceController(SRDFDeviceController srdfDeviceController) {
        this.srdfDeviceController = srdfDeviceController;
    }

    @Override
    public void resyncSnapshot(URI storage, URI volume, URI snapshot, Boolean updateOpStatus, String opId)
            throws ControllerException {
        TaskCompleter completer = null;
        try {
            StorageSystem storageDevice = _dbClient.queryObject(StorageSystem.class, storage);
            BlockSnapshot snapObj = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            completer = new BlockSnapshotResyncCompleter(snapObj, opId, updateOpStatus);
            getDevice(storageDevice.getSystemType()).doResyncSnapshot(storageDevice, volume, snapshot, completer);
        } catch (Exception e) {
            _log.error(String.format("resync snapshot failed - storage: %s, volume: %s, snapshot: %s",
                    storage.toString(), volume.toString(), snapshot.toString()));
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            completer.error(_dbClient, serviceError);
            doFailTask(BlockSnapshot.class, snapshot, opId, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    public String detachCloneStep(Workflow workflow, String waitFor, StorageSystem storageSystem, List<URI> cloneList,
            boolean isRemoveAll) {
        URI storage = storageSystem.getId();
        if (isRemoveAll) {
            Workflow.Method detachMethod = detachFullCopyMethod(storage, cloneList);
            waitFor = workflow.createStep(FULL_COPY_DETACH_STEP_GROUP, "Detaching group clone", waitFor,
                    storage, storageSystem.getSystemType(), getClass(), detachMethod, null, null);
        } else {
            Workflow.Method detachMethod = detachListCloneMethod(storage, cloneList);
            waitFor = workflow.createStep(FULL_COPY_DETACH_STEP_GROUP, "Detaching list clone", waitFor,
                    storage, storageSystem.getSystemType(), getClass(), detachMethod, null, null);
        }

        return waitFor;
    }

    public Workflow.Method detachListCloneMethod(URI storage, List<URI> cloneList) {
        return new Workflow.Method("detachListClone", storage, cloneList);
    }

    public void detachListClone(URI storage, List<URI> cloneList, String taskId)
            throws ControllerException {
        _log.info("START detachListClone: {}", Joiner.on("\t").join(cloneList));

        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            TaskCompleter taskCompleter = new VolumeDetachCloneCompleter(cloneList, taskId);
            getDevice(storageSystem.getSystemType()).doDetachListReplica(storageSystem, cloneList, taskCompleter);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(taskId, serviceError);
            doFailTask(Volume.class, cloneList, taskId, serviceError);
        }
    }

    public String deleteListMirrorStep(Workflow workflow, String waitFor, URI storage, StorageSystem storageSystem, List<URI> mirrorList,
            boolean isRemoveAll) {
        String mirrorStr = Joiner.on("\t").join(mirrorList);
        _log.info("Start deleteMirror Step for mirror:{}", mirrorStr);
        List<BlockMirror> mirrors = _dbClient.queryObject(BlockMirror.class, mirrorList);
        if (isRemoveAll) {
            // Optionally create a step to pause (fracture) the mirror
            if (mirrorIsPausable(mirrors)) {
                _log.info("Adding group fracture mirror step");
                waitFor = workflow.createStep("deactivate",
                        String.format("fracture group mirror: %s", mirrorStr),
                        waitFor, storage, getDeviceType(storage),
                        this.getClass(),
                        fractureMirrorMethod(storage, mirrorList, isRemoveAll, false),
                        null, null);
            }
            _log.info("Adding group detach mirror step");
            Workflow.Method detach = detachMirrorMethod(storage, mirrorList, isRemoveAll);
            waitFor = workflow.createStep("deactivate", "detaching grop mirror: " + mirrorStr, waitFor, storage,
                    storageSystem.getSystemType(), getClass(), detach, null, null);
            _log.info("Adding group delete mirror step");
            Workflow.Method delete = deleteMirrorMethod(storage, mirrorList, isRemoveAll);
            waitFor = workflow.createStep("deactivate", "deleting group mirror: " + mirrorStr, waitFor, storage,
                    storageSystem.getSystemType(), getClass(), delete, null, null);
        } else {
            // Optionally create a step to pause (fracture) the mirrors
            if (mirrorIsPausable(mirrors)) {
                _log.info("Adding fracture mirror step");
                waitFor = workflow.createStep("deactivate",
                        String.format("fracture list mirror: %s", mirrorStr),
                        waitFor, storage, getDeviceType(storage),
                        this.getClass(),
                        fractureListMirrorMethod(storage, mirrorList, false), null, null);
            }

            _log.info("Adding detach mirror step");
            Workflow.Method detach = detachListMirrorMethod(storage, mirrorList);
            waitFor = workflow.createStep("deactivate", "detaching list mirror: " + mirrorStr, waitFor, storage,
                    storageSystem.getSystemType(), getClass(), detach, null, null);
            _log.info("Adding delete mirror step");
            Workflow.Method delete = deleteListMirrorMethod(storage, mirrorList);
            waitFor = workflow.createStep("deactivate", "deleting list mirror: " + mirrorStr, waitFor, storage,
                    storageSystem.getSystemType(), getClass(), delete, null, null);
        }
        return waitFor;
    }

    public Workflow.Method fractureListMirrorMethod(URI storage, List<URI> mirrorList, Boolean sync) {
        return new Workflow.Method("fractureListMirror", storage, mirrorList, sync);
    }

    public void fractureListMirror(URI storage, List<URI> mirrorList, Boolean sync, String opId) throws ControllerException {
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockMirrorFractureCompleter(mirrorList, opId);
            getDevice(storageObj.getSystemType()).doFractureListReplica(storageObj, mirrorList, sync, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            }
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    public Workflow.Method detachListMirrorMethod(URI storage, List<URI> mirrorList) {
        return new Workflow.Method("detachListMirror", storage, mirrorList);
    }

    public void detachListMirror(URI storage, List<URI> mirrorList, String opId) throws ControllerException {
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockMirrorDetachCompleter(mirrorList, opId);
            getDevice(storageObj.getSystemType()).doDetachListReplica(storageObj, mirrorList, completer);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            if (completer != null) {
                completer.error(_dbClient, serviceError);
            }
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    public Workflow.Method deleteListMirrorMethod(URI storage, List<URI> mirrorList) {
        return new Workflow.Method("deleteListMirror", storage, mirrorList);
    }

    public void deleteListMirror(URI storage, List<URI> mirrorList, String opId) throws ControllerException {
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockMirrorDeleteCompleter(mirrorList, opId);
            getDevice(storageObj.getSystemType()).doDeleteListReplica(storageObj, mirrorList, completer);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            if (completer != null) {
                completer.error(_dbClient, serviceError);
            }
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    public String deleteSnapshotStep(Workflow workflow, String waitFor, URI storage, StorageSystem storageSystem,
            List<URI> snapshotList, boolean isRemoveAll) {
        if (isRemoveAll) {
            _log.info("Adding group remove snapshot step");
            Workflow.Method deleteMethod = deleteSnapshotMethod(storage, snapshotList.get(0));
            waitFor = workflow.createStep(SNAPSHOT_DELETE_STEP_GROUP, "Deleting snapshot", waitFor,
                    storage, storageSystem.getSystemType(), getClass(), deleteMethod, null, null);
        } else {
            for (URI uri : snapshotList) {
                _log.info("Adding single remove snapshot step: {}", uri);
                Workflow.Method deleteMethod = deleteSelectedSnapshotMethod(storage, uri);
                waitFor = workflow.createStep(SNAPSHOT_DELETE_STEP_GROUP, "Deleting snapshot", waitFor,
                        storage, storageSystem.getSystemType(), getClass(), deleteMethod, null, null);
            }
        }

        return waitFor;
    }

    public Workflow.Method deleteSnapshotMethod(URI storage, URI snapshot) {
        return new Workflow.Method("deleteSnapshot", storage, snapshot);
    }

    public Workflow.Method deleteSelectedSnapshotMethod(URI storage, URI snapshot) {
        return new Workflow.Method("deleteSelectedSnapshot", storage, snapshot);
    }

    public void deleteSelectedSnapshot(URI storage, URI snapshot, String opId) throws ControllerException {
        _log.info("START deleteSelectedSnapshot");
        TaskCompleter completer = null;
        WorkflowStepCompleter.stepExecuting(opId);
        try {
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            BlockSnapshot snapObj = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            completer = BlockSnapshotDeleteCompleter.createCompleter(_dbClient, snapObj, opId);
            getDevice(storageObj.getSystemType()).doDeleteSelectedSnapshot(storageObj, snapshot, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                WorkflowStepCompleter.stepFailed(opId, serviceError);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.deleteVolumeSnapshotFailed(e);
            }
        }
    }

    /**
     * Add step to create list clone.
     *
     * @param workflow
     *            The Workflow being built
     * @param storageSystem
     *            Storage system
     * @param waitFor
     *            Previous step to waitFor
     * @param cloneList
     *            List of URIs for clones to be created
     * @return last step added to waitFor
     */
    public String createListCloneStep(Workflow workflow, StorageSystem storageSystem, List<URI> cloneList, String waitFor) {
        URI storage = storageSystem.getId();
        Workflow.Method createMethod = createListCloneMethod(storage, cloneList, false);
        Workflow.Method rollbackMethod = rollbackListCloneMethod(storage, cloneList);
        waitFor = workflow.createStep(BlockDeviceController.FULL_COPY_CREATE_STEP_GROUP, "Creating list clone", waitFor, storage,
                storageSystem.getSystemType(), getClass(), createMethod, rollbackMethod, null);

        if (storageSystem.deviceIsType(Type.vnxblock)) {
            waitFor = workflow.createStep(BlockDeviceController.FULL_COPY_CREATE_STEP_GROUP, "fracture list clone", waitFor,
                    storage, storageSystem.getSystemType(), BlockDeviceController.class,
                    fractureListCloneMethod(storage, cloneList, false), null, null);
        }

        return waitFor;
    }

    public Workflow.Method createListCloneMethod(URI storage, List<URI> cloneList, Boolean createInactive) {
        return new Workflow.Method("createListClone", storage, cloneList, createInactive);
    }

    public void createListClone(URI storage, List<URI> cloneList, Boolean createInactive, String taskId) {
        try {
            WorkflowStepCompleter.stepExecuting(taskId);
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            TaskCompleter taskCompleter = new CloneCreateCompleter(cloneList, taskId);
            getDevice(storageSystem.getSystemType()).doCreateListReplica(storageSystem, cloneList, createInactive, taskCompleter);
        } catch (Exception e) {
            _log.error(e.getMessage(), e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(taskId, serviceError);
            doFailTask(Volume.class, cloneList, taskId, serviceError);
        }
    }

    public Workflow.Method rollbackListCloneMethod(URI storage, List<URI> cloneList) {
        return new Workflow.Method("rollbackListClone", storage, cloneList);
    }

    public void rollbackListClone(URI storage, List<URI> cloneList, String taskId) {
        WorkflowStepCompleter.stepExecuting(taskId);
        _log.info("Rollback list clone");
        List<Volume> clones = _dbClient.queryObject(Volume.class, cloneList);
        List<Volume> clonesNoRollback = new ArrayList<Volume>();
        List<URI> clonesToRollback = new ArrayList<URI>();
        try {
            for (Volume clone : clones) {
                if (isNullOrEmpty(clone.getNativeId())) {
                    clone.setInactive(true);
                    clonesNoRollback.add(clone);
                } else {
                    clonesToRollback.add(clone.getId());
                }
            }

            if (!clonesNoRollback.isEmpty()) {
                _dbClient.updateObject(clonesNoRollback);
            }

            if (!clonesToRollback.isEmpty()) {
                _log.info("Detach list clone for rollback");
                detachListClone(storage, clonesToRollback, generateStepIdForDependentCallDuringRollback());
                _log.info("Delete clones for rollback");
                deleteVolumes(storage, clonesToRollback, generateStepIdForDependentCallDuringRollback());
            }

            WorkflowStepCompleter.stepSucceded(taskId);
        } catch (InternalException ie) {
            _log.error(String.format("rollbackListClone failed - Array: %s, clones: %s", storage, Joiner.on("\t").join(cloneList)));
            _log.error(ie.getMessage(), ie);
            doFailTask(Volume.class, cloneList, taskId, ie);
            WorkflowStepCompleter.stepFailed(taskId, ie);
        } catch (Exception e) {
            _log.error(e.getMessage(), e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(taskId, serviceError);
            doFailTask(Volume.class, cloneList, taskId, serviceError);
        }
    }

    public Workflow.Method fractureListCloneMethod(URI storage, List<URI> cloneList, Boolean sync) {
        return new Workflow.Method("fractureListClone", storage, cloneList, sync);
    }

    public void fractureListClone(URI storage, List<URI> cloneList, Boolean sync, String taskId) {
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(taskId);
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new CloneFractureCompleter(cloneList, taskId);
            getDevice(storageSystem.getSystemType()).doFractureListReplica(storageSystem, cloneList, sync, completer);
        } catch (Exception e) {
            _log.error(e.getMessage(), e);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            doFailTask(Volume.class, cloneList, taskId, serviceError);
            if (completer != null) {
                completer.error(_dbClient, serviceError);
            }
            WorkflowStepCompleter.stepFailed(taskId, serviceError);
        }
    }

    /**
     * Add Steps to create list mirror.
     *
     * @param workflow
     *            The Workflow being built
     * @param storageSystem
     *            Storage system
     * @param waitFor
     *            Previous step to waitFor
     * @param mirrorList
     *            List of URIs for mirrors to be created
     * @return last step added to waitFor
     */
    public String createListMirrorStep(Workflow workflow, String waitFor, StorageSystem storageSystem, List<URI> mirrorList)
            throws ControllerException {
        URI storage = storageSystem.getId();
        waitFor = workflow.createStep(CREATE_MIRRORS_STEP_GROUP, "Creating list mirror", waitFor,
                storage, storageSystem.getSystemType(),
                this.getClass(),
                createListMirrorMethod(storage, mirrorList, false),
                rollbackListMirrorMethod(storage, mirrorList), null);

        return waitFor;
    }

    public Workflow.Method createListMirrorMethod(URI storage, List<URI> mirrorList, Boolean createInactive) {
        return new Workflow.Method("createListMirror", storage, mirrorList, createInactive);
    }

    public void createListMirror(URI storage, List<URI> mirrorList, Boolean createInactive, String opId)
            throws ControllerException {
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockMirrorCreateCompleter(mirrorList, opId);
            getDevice(storageObj.getSystemType()).doCreateListReplica(storageObj, mirrorList, createInactive, completer);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            if (completer != null) {
                completer.error(_dbClient, serviceError);
            }
            WorkflowStepCompleter.stepFailed(opId, serviceError);
        }
    }

    public Workflow.Method rollbackListMirrorMethod(URI storage, List<URI> mirrorList) {
        return new Workflow.Method("rollbackListMirror", storage, mirrorList);
    }

    public void rollbackListMirror(URI storage, List<URI> mirrorList, String taskId) {
        WorkflowStepCompleter.stepExecuting(taskId);

        try {
            List<BlockMirror> mirrorsNoRollback = new ArrayList<BlockMirror>();
            List<BlockMirror> mirrorsToRollback = new ArrayList<BlockMirror>();
            Iterator<BlockMirror> mirrorIterator = _dbClient.queryIterativeObjects(BlockMirror.class, mirrorList);
            while (mirrorIterator.hasNext()) {
                BlockMirror mirror = mirrorIterator.next();
                if (mirror != null && !mirror.getInactive()) {
                    if (isNullOrEmpty(mirror.getNativeId())) {
                        mirror.setInactive(true);
                        mirrorsNoRollback.add(mirror);
                    } else {
                        mirrorsToRollback.add(mirror);
                    }
                }
            }

            if (!mirrorsNoRollback.isEmpty()) {
                _dbClient.updateObject(mirrorsNoRollback);
            }

            if (!mirrorsToRollback.isEmpty()) {
                List<URI> mirrorURIsToRollback = new ArrayList<URI>(transform(mirrorsToRollback, FCTN_MIRROR_TO_URI));
                String mirrorNativeIds = Joiner.on(", ").join(transform(mirrorsToRollback, fctnBlockObjectToNativeID()));
                if (mirrorIsPausable(mirrorsToRollback)) {
                    _log.info("Attempting to fracture {} for rollback", mirrorNativeIds);
                    fractureMirror(storage, mirrorURIsToRollback, false, false, generateStepIdForDependentCallDuringRollback());
                }

                _log.info("Attempting to detach {} for rollback", mirrorNativeIds);
                detachMirror(storage, mirrorURIsToRollback, false, false, generateStepIdForDependentCallDuringRollback());
                _log.info("Attempting to delete {} for rollback", mirrorNativeIds);
                deleteMirror(storage, mirrorURIsToRollback, false, generateStepIdForDependentCallDuringRollback());
            }
            WorkflowStepCompleter.stepSucceded(taskId);
        } catch (InternalException ie) {
            _log.error(String.format("rollbackListMirror failed - Array:%s, Mirror:%s", storage, Joiner.on("\t").join(mirrorList)));
            doFailTask(BlockMirror.class, mirrorList, taskId, ie);
            WorkflowStepCompleter.stepFailed(taskId, ie);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(taskId, serviceError);
            doFailTask(BlockMirror.class, mirrorList, taskId, serviceError);
        }
    }

    /**
     * Add Steps to create list snapshot.
     *
     * @param workflow
     *            The Workflow being built
     * @param storageSystem
     *            Storage system
     * @param waitFor
     *            Previous step to waitFor
     * @param snapshotList
     *            List of URIs for snapshots to be created
     * @return last step added to waitFor
     */
    public String createListSnapshotStep(Workflow workflow, String waitFor, StorageSystem storageSystem, List<URI> snapshotList)
            throws ControllerException {
        URI storage = storageSystem.getId();
        waitFor = workflow.createStep(CREATE_SNAPSHOTS_STEP_GROUP,
                "Create list snapshot", waitFor, storage, storageSystem.getSystemType(),
                this.getClass(),
                createListSnapshotMethod(storage, snapshotList, false, false),
                rollbackListSnapshotMethod(storage, snapshotList), null);

        return waitFor;
    }

    public Workflow.Method createListSnapshotMethod(URI storage, List<URI> snapshotList, Boolean createInactive, Boolean readOnly) {
        return new Workflow.Method("createListSnapshot", storage, snapshotList, createInactive, readOnly);
    }

    public void createListSnapshot(URI storage, List<URI> snapshotList, Boolean createInactive, Boolean readOnly, String opId)
            throws ControllerException {
        WorkflowStepCompleter.stepExecuting(opId);
        TaskCompleter completer = null;
        try {
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockSnapshotCreateCompleter(snapshotList, opId);
            getDevice(storageObj.getSystemType()).doCreateListReplica(storageObj, snapshotList, createInactive, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                WorkflowStepCompleter.stepFailed(opId, serviceError);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.createVolumeSnapshotFailed(e);
            }
        }
    }

    public Workflow.Method rollbackListSnapshotMethod(URI storage, List<URI> snapshotList) {
        return new Workflow.Method("rollbackListSnapshot", storage, snapshotList);
    }

    public void rollbackListSnapshot(URI storage, List<URI> snapshotList, String taskId) {
        WorkflowStepCompleter.stepExecuting(taskId);
        try {
            List<BlockSnapshot> snapshotsNoRollback = new ArrayList<BlockSnapshot>();
            List<BlockSnapshot> snapshotsToRollback = new ArrayList<BlockSnapshot>();
            Iterator<BlockSnapshot> itr = _dbClient.queryIterativeObjects(BlockSnapshot.class, snapshotList);
            while (itr.hasNext()) {
                BlockSnapshot snapshot = itr.next();
                if (snapshot != null && !snapshot.getInactive()) {
                    if (isNullOrEmpty(snapshot.getNativeId())) {
                        snapshot.setInactive(true);
                        snapshotsNoRollback.add(snapshot);
                    } else {
                        snapshotsToRollback.add(snapshot);
                    }
                }
            }

            if (!snapshotsNoRollback.isEmpty()) {
                _dbClient.updateObject(snapshotsNoRollback);
            }
            if (!snapshotsToRollback.isEmpty()) {
                List<URI> snapshotURIsToRollback = new ArrayList<URI>(transform(snapshotsToRollback, fctnDataObjectToID()));
                String snapshotNativeIds = Joiner.on(", ").join(transform(snapshotsToRollback, fctnBlockObjectToNativeID()));
                _log.info("Attempting to delete {} for rollback", snapshotNativeIds);
                for (URI snapshotURI : snapshotURIsToRollback) {
                    deleteSelectedSnapshot(storage, snapshotURI, generateStepIdForDependentCallDuringRollback());
                }
            }
            WorkflowStepCompleter.stepSucceded(taskId);
        } catch (InternalException ie) {
            _log.error(String.format("rollbackListSnapshot failed - Array:%s, Snapshots:%s", storage, Joiner.on("\t").join(snapshotList)));
            doFailTask(BlockSnapshot.class, snapshotList, taskId, ie);
            WorkflowStepCompleter.stepFailed(taskId, ie);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            WorkflowStepCompleter.stepFailed(taskId, serviceError);
            doFailTask(BlockSnapshot.class, snapshotList, taskId, serviceError);
        }
    }

    /**
     * Generates step id for dependent calls during rollback step execution.
     * As we cannot add new step for dependent calls to ROLLBACK steps group during workflow execution,
     * generate a new operation id for the dependent call. This is to avoid dependent call marking the rollback step
     * completed.
     * TODO find a solution
     *
     * @return the string
     */
    private String generateStepIdForDependentCallDuringRollback() {
        return UUID.randomUUID().toString();
    }

    /**
     * Adding/removing volumes to/from an application is ViPR DB only operation (no controller side involved),
     * except for adding VNX volumes to an application, if the VNX volumes are in a real replication group.
     *
     * 1. remove volumes from replication group, keep volume's replicationGroupInstance unchanged
     * 2. delete the replication group from array, keep CG's systemConsistencyGroup unchanged
     * 3. change CG's arrayConsistency to false, update volume's volumeGroupIds, update clone's fullCopySetName
     * (performed in the completer class)
     */
    @Override
    public void updateApplication(URI storage, ApplicationAddVolumeList addVolList, URI application,
            String opId) throws ControllerException {

        TaskCompleter completer = null;
        String waitFor = null;

        try {
            // Generate the Workflow.
            Workflow workflow = _workflowService.getNewWorkflow(this,
                    UPDATE_VOLUMES_FOR_APPLICATION_WS_NAME, false, opId);

            List<URI> volumesToAdd = null;
            if (addVolList != null) {
                volumesToAdd = addVolList.getVolumes();
            }

            if (volumesToAdd != null && !volumesToAdd.isEmpty()) {
                Map<URI, List<URI>> addVolsMap = new HashMap<URI, List<URI>>();
                for (URI voluri : volumesToAdd) {
                    Volume vol = _dbClient.queryObject(Volume.class, voluri);
                    if (vol != null && !vol.getInactive()) {
                        if (ControllerUtils.isVnxVolume(vol, _dbClient) && vol.isInCG()
                                && !ControllerUtils.isNotInRealVNXRG(vol, _dbClient)) {
                            URI cguri = vol.getConsistencyGroup();
                            List<URI> vols = addVolsMap.get(cguri);
                            if (vols == null) {
                                vols = new ArrayList<URI>();
                            }
                            vols.add(voluri);
                            addVolsMap.put(cguri, vols);
                        }
                    }
                }
                List<URI> cgs = new ArrayList<URI>(addVolsMap.keySet());
                completer = new ApplicationTaskCompleter(application, volumesToAdd, null, cgs, opId);

                for (Map.Entry<URI, List<URI>> entry : addVolsMap.entrySet()) {
                    _log.info("Creating workflows for adding CG volumes to application");
                    URI cguri = entry.getKey();
                    List<URI> cgVolsToAdd = entry.getValue();

                    URI voluri = cgVolsToAdd.get(0);
                    Volume vol = _dbClient.queryObject(Volume.class, voluri);
                    StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, vol.getStorageController());
                    BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, cguri);
                    String groupName = ControllerUtils.generateReplicationGroupName(storageSystem, cguri, vol.getReplicationGroupInstance(),
                            _dbClient);

                    // remove volumes from array replication group, and delete the group, but keep volumes reference
                    waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                            String.format("Removing volumes from consistency group %s", cg.getLabel()),
                            waitFor, storage, storageSystem.getSystemType(),
                            this.getClass(),
                            removeFromConsistencyGroupMethod(storage, cguri, cgVolsToAdd, true),
                            rollbackMethodNullMethod(), null);

                    // remove replication group
                    waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                            String.format("Deleting replication group for consistency group %s", cg.getLabel()),
                            waitFor, storage, storageSystem.getSystemType(),
                            this.getClass(),
                            deleteConsistencyGroupMethod(storage, cguri, groupName, true, false, false),
                            rollbackMethodNullMethod(), null);
                }
            }

            // Finish up and execute the plan.
            _log.info("Executing workflow plan {}", UPDATE_VOLUMES_FOR_APPLICATION_WS_NAME);
            String successMessage = String.format(
                    "Update application successful for %s", application.toString());
            workflow.executePlan(completer, successMessage);
        } catch (Exception e) {
            _log.error("Exception while updating the application", e);
            if (completer != null) {
                completer.error(_dbClient,
                        DeviceControllerException.exceptions.failedToUpdateVolumesFromAppication(application.toString(), e.getMessage()));
            }
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createSnapshotSession(URI systemURI, URI snapSessionURI,
            List<List<URI>> sessionSnapshotURIs, String copyMode, String opId)
                    throws InternalException {

        TaskCompleter completer = new BlockSnapshotSessionCreateWorkflowCompleter(snapSessionURI, sessionSnapshotURIs, opId);
        try {
            // Get a new workflow to execute creation of the snapshot session and if
            // necessary creation and linking of target volumes to the new session.
            Workflow workflow = _workflowService.getNewWorkflow(this, CREATE_SAPSHOT_SESSION_WF_NAME, false, opId);
            _log.info("Created new workflow to create a new snapshot session for source with operation id {}", opId);

            // When creating a group snapshot we need the name of the group.
            String groupName = null;
            boolean isCG = checkSnapshotSessionConsistencyGroup(snapSessionURI, _dbClient, completer);
            if (isCG) {
                BlockSnapshotSession snapSession = _dbClient.queryObject(BlockSnapshotSession.class, snapSessionURI);
                groupName = snapSession.getReplicationGroupInstance();
            }

            // Create a step to create the session.
            String waitFor = workflow.createStep(CREATE_SNAPSHOT_SESSION_STEP_GROUP, String.format("Creating block snapshot session"),
                    null, systemURI, getDeviceType(systemURI), getClass(),
                    createBlockSnapshotSessionMethod(systemURI, snapSessionURI, groupName),
                    rollbackMethodNullMethod(), null);

            // Add steps to create any new targets and link them to the session, if necessary
            if ((sessionSnapshotURIs != null) && (!sessionSnapshotURIs.isEmpty())) {

                if (isCG) {
                    for (List<URI> snapshotURIs : sessionSnapshotURIs) {
                        workflow.createStep(
                                LINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP,
                                String.format("Linking group targets snapshot sessions %s", snapSessionURI),
                                waitFor,
                                systemURI,
                                getDeviceType(systemURI),
                                getClass(),
                                linkBlockSnapshotSessionTargetGroupMethod(systemURI, snapSessionURI, snapshotURIs, copyMode,
                                        Boolean.FALSE),
                                rollbackLinkBlockSnapshotSessionTargetMethod(systemURI, snapSessionURI, snapshotURIs.get(0)), null);
                    }
                } else {
                    for (List<URI> snapshotURIs : sessionSnapshotURIs) {
                        if ((snapshotURIs != null) && (!snapshotURIs.isEmpty())) {
                            for (URI snapshotURI : snapshotURIs) {
                                workflow.createStep(
                                        LINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP,
                                        String.format("Linking targets for snapshot session %s", snapSessionURI),
                                        waitFor,
                                        systemURI,
                                        getDeviceType(systemURI),
                                        getClass(),
                                        linkBlockSnapshotSessionTargetMethod(systemURI, snapSessionURI, snapshotURI, copyMode,
                                                Boolean.FALSE),
                                        rollbackLinkBlockSnapshotSessionTargetMethod(systemURI, snapSessionURI, snapshotURI), null);
                            }
                        }
                    }
                }
            }
            workflow.executePlan(completer, "Create block snapshot session successful");
        } catch (Exception e) {
            _log.error("Create block snapshot session failed", e);
            ServiceCoded serviceException = DeviceControllerException.exceptions.createBlockSnapshotSessionFailed(e);
            completer.error(_dbClient, serviceException);
        }
    }

    /**
     * Adds the step to create block snapshot session.
     *
     * @param workflow
     *            the workflow
     * @param systemURI
     *            the system uri
     * @param session
     *            the snapshot session
     * @param repGroupName
     *            the replication group name
     * @param waitFor
     *            the wait for
     * @return the stepId
     */
    public String addStepToCreateSnapshotSession(Workflow workflow, URI systemURI, URI session,
            String repGroupName, String waitFor) {
        String stepId = workflow.createStep(BlockDeviceController.CREATE_SNAPSHOT_SESSION_STEP_GROUP,
                String.format("Creating block snapshot session"),
                waitFor, systemURI, getDeviceType(systemURI), getClass(),
                createBlockSnapshotSessionMethod(systemURI, session, repGroupName),
                rollbackMethodNullMethod(), null);
        return stepId;
    }

    /**
     * Create the workflow method that is invoked by the workflow service
     * to create block snapshot sessions.
     *
     * @param systemURI
     *            The URI of the storage system on which the snapshot sessions are created.
     * @param snapSessionURI
     *            The URIs of the sessions in ViPR.
     * @param groupName
     *            The group name when creating a group session.
     *
     * @return A reference to a Workflow.Method for creating an array snapshot.
     */
    public static Workflow.Method createBlockSnapshotSessionMethod(URI systemURI, URI snapSessionURI, String groupName) {
        return new Workflow.Method(CREATE_SNAPSHOT_SESSION_METHOD, systemURI, snapSessionURI, groupName);
    }

    /**
     * Creates array snapshots on the array with the passed URI and associates these
     * with the BlockSnapshotSession instances with the passed URIs.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionURI
     *            The URIs of the BlockSnapshotSessioninstances representing the array snapshots.
     * @param groupName
     *            The group name when creating a group session.
     * @param stepId
     *            The unique id of the workflow step in which the snapshots are be created.
     */
    public void createBlockSnapshotSession(URI systemURI, URI snapSessionURI, String groupName, String stepId) {
        TaskCompleter completer = null;
        try {
            StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
            completer = new BlockSnapshotSessionCreateCompleter(snapSessionURI, stepId);
            WorkflowStepCompleter.stepExecuting(stepId);
            getDevice(system.getSystemType()).doCreateSnapshotSession(system, snapSessionURI, groupName, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.createBlockSnapshotSessionFailed(e);
            }
        }
    }

    /**
     * Create the workflow method that is invoked by the workflow service
     * to link a target volume to the array snapshot.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionSnapshotMap
     *            Map of BlockSnapshotSession URI's to their BlockSnapshot instance URI,
     *            representing the linked target.
     * @param copyMode
     *            The manner in which the target is linked to the array snapshot.
     * @param targetsExist
     *            true if the target exists, false if a new one needs to be created.
     *
     * @return A reference to a Workflow.Method for linking a target volume to an array snapshot.
     */
    public static Workflow.Method linkBlockSnapshotSessionTargetGroupMethod(URI systemURI,
            URI snapshotSessionURI,
            List<URI> snapshotURIs,
            String copyMode, Boolean targetsExist) {
        return new Workflow.Method(LINK_SNAPSHOT_SESSION_TARGET_GROUP_METHOD, systemURI,
                snapshotSessionURI,
                snapshotURIs, copyMode,
                targetsExist);
    }

    /**
     * Create and link a target volume group to an array snapshot on the storage system
     * with the passed URI. The new target group is linked to the array snapshot
     * based on the passed copy mode and is associated with the BlockSnapshot
     * instances with the passed URI.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionSnapshotMap
     *            Map of BlockSnapshotSession URI's to their BlockSnapshot instance URI,
     *            representing the linked target.
     * @param copyMode
     *            The manner in which the target is linked to the array snapshot.
     * @param targetsExist
     *            true if the target exists, false if a new one needs to be created.
     * @param stepId
     *            The unique id of the workflow step in which the target is linked.
     */
    public boolean linkBlockSnapshotSessionTargetGroup(URI systemURI, URI snapshotSessionURI, List<URI> snapshotURIs,
            String copyMode, Boolean targetsExist, String stepId) {
        TaskCompleter completer = null;
        try {
            StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
            completer = new BlockSnapshotSessionLinkTargetCompleter(snapshotSessionURI, snapshotURIs, stepId);
            getDevice(system.getSystemType()).doLinkBlockSnapshotSessionTargetGroup(system, snapshotSessionURI,
                    snapshotURIs, copyMode, targetsExist, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.linkBlockSnapshotSessionTargetsFailed(e);
            }
        }
        return true;
    }

    public String addStepToLinkBlockSnapshotSessionTarget(Workflow workflow, URI systemURI,
            BlockSnapshotSession session, URI snapshot, String copyMode, String waitFor) {
        String stepId = workflow.createStep(
                BlockDeviceController.LINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP,
                String.format("Linking targets for snapshot session %s", session.getId()),
                waitFor, systemURI, getDeviceType(systemURI), getClass(),
                linkBlockSnapshotSessionTargetMethod(systemURI, session.getId(), snapshot, copyMode, Boolean.FALSE),
                rollbackLinkBlockSnapshotSessionTargetMethod(systemURI, session.getId(), snapshot), null);
        return stepId;
    }

    /**
     * Create the workflow method that is invoked by the workflow service
     * to link a target volume to the array snapshot.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionURI
     *            The URI of the BlockSnapshotSession instance.
     * @param snapshotURI
     *            The URI of the BlockSnapshot instance representing the linked target volume.
     * @param copyMode
     *            The manner in which the target is linked to the array snapshot.
     * @param targetExists
     *            true if the target exists, false if a new one needs to be created.
     *
     * @return A reference to a Workflow.Method for linking a target volume to an array snapshot.
     */
    public static Workflow.Method linkBlockSnapshotSessionTargetMethod(URI systemURI,
            URI snapSessionURI, URI snapshotURI, String copyMode, Boolean targetExists) {
        return new Workflow.Method(LINK_SNAPSHOT_SESSION_TARGET_METHOD, systemURI, snapSessionURI, snapshotURI, copyMode, targetExists);
    }

    /**
     * Creates and link a target volume to an array snapshot on the storage system
     * with the passed URI. The new target volume is linked to the array snapshot
     * based on the passed copy mode and is associated with the BlockSnapshot
     * instance with the passed URI.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionURI
     *            The URI of the BlockSnapshotSession instance.
     * @param snapshotURI
     *            The URI of the BlockSnapshot instance representing the linked target volume.
     * @param copyMode
     *            The manner in which the target is linked to the array snapshot.
     * @param targetExists
     *            true if the target exists, false if a new one needs to be created.
     * @param stepId
     *            The unique id of the workflow step in which the target is linked.
     */
    public void linkBlockSnapshotSessionTarget(URI systemURI, URI snapSessionURI,
            URI snapshotURI, String copyMode, Boolean targetExists, String stepId) {
        TaskCompleter completer = null;
        try {
            StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
            completer = new BlockSnapshotSessionLinkTargetCompleter(snapSessionURI, Lists.newArrayList(snapshotURI), stepId);
            getDevice(system.getSystemType()).doLinkBlockSnapshotSessionTarget(system, snapSessionURI,
                    snapshotURI, copyMode, targetExists, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.linkBlockSnapshotSessionTargetsFailed(e);
            }
        }
    }

    /**
     * Create the workflow method that is invoked by the workflow service
     * to rollback a failed attempt to link a target volume to the array
     * snapshot.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionURI
     *            The URI of the BlockSnapshotSession instance.
     * @param snapshotURI
     *            The URI of the BlockSnapshot instance.
     *
     * @return A reference to a Workflow.Method for rolling back a failed attempt to link
     *         a target volume to an array snapshot.
     */
    public static Workflow.Method rollbackLinkBlockSnapshotSessionTargetMethod(URI systemURI, URI snapSessionURI, URI snapshotURI) {
        return new Workflow.Method(RB_LINK_SNAPSHOT_SESSION_TARGET_METHOD, systemURI, snapSessionURI, snapshotURI);
    }

    /**
     * Rollback a failed attempt to link a target volume to the array snapshot.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionURI
     *            The URI of the BlockSnapshotSession instance.
     * @param snapshotURI
     *            The URI of the BlockSnapshot instance.
     * @param stepId
     *            The unique id of the workflow step in which the rollback is executed.
     */
    public void rollbackLinkBlockSnapshotSessionTarget(URI systemURI, URI snapSessionURI, URI snapshotURI, String stepId) {
        // We do not rollback successfully linked targets. If the target
        // was not successfully created and linked, it could in one of two
        // states. Either the target is not provisioned, or the target is
        // provisioned but not linked to the array snapshot. We call the
        // method to unlink the target and make sure the unlink target
        // algorithm accounts for these possibilities. Successfully linked
        // targets will be in the list of linked targets for the session.
        BlockSnapshotSession snapSession = _dbClient.queryObject(BlockSnapshotSession.class, snapSessionURI);
        StringSet linkedTargets = snapSession.getLinkedTargets();
        if ((linkedTargets == null) || (!linkedTargets.contains(snapshotURI.toString()))) {
            unlinkBlockSnapshotSessionTarget(systemURI, snapSessionURI, snapshotURI, Boolean.TRUE, stepId);
        } else {
            WorkflowStepCompleter.stepSucceded(stepId);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void linkNewTargetVolumesToSnapshotSession(URI systemURI, URI snapSessionURI, List<List<URI>> snapshotURIs,
            String copyMode, String opId) throws InternalException {
        TaskCompleter completer = new BlockSnapshotSessionLinkTargetsWorkflowCompleter(snapSessionURI, snapshotURIs, opId);
        try {
            // Get a new workflow to execute the linking of the target volumes
            // to the new session.
            Workflow workflow = _workflowService.getNewWorkflow(this, LINK_SNAPSHOT_SESSION_TARGETS_WF_NAME, false, opId);
            _log.info("Created new workflow to create and link new targets for snapshot session {} with operation id {}",
                    snapSessionURI, opId);

            if (checkSnapshotSessionConsistencyGroup(snapSessionURI, _dbClient, completer)) {
                String waitFor = null;
                for (List<URI> snapshotURI : snapshotURIs) {
                    waitFor = workflow.createStep(
                            LINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP,
                            String.format("Linking target for snapshot session %s", snapSessionURI),
                            waitFor, systemURI, getDeviceType(systemURI), getClass(),
                            linkBlockSnapshotSessionTargetGroupMethod(systemURI, snapSessionURI, snapshotURI, copyMode, Boolean.FALSE),
                            null, null);
                }
            } else {
                for (List<URI> snapshotURI : snapshotURIs) {
                    workflow.createStep(
                            LINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP,
                            String.format("Linking target for snapshot session %s", snapSessionURI),
                            null, systemURI, getDeviceType(systemURI), getClass(),
                            linkBlockSnapshotSessionTargetMethod(systemURI, snapSessionURI, snapshotURI.get(0), copyMode, Boolean.FALSE),
                            rollbackLinkBlockSnapshotSessionTargetMethod(systemURI, snapSessionURI, snapshotURI.get(0)), null);
                }
            }
            workflow.executePlan(completer, "Create and link new target volumes for block snapshot session successful");
        } catch (Exception e) {
            _log.error("Create and link new target volumes for block snapshot session failed", e);
            ServiceCoded serviceException = DeviceControllerException.exceptions.linkBlockSnapshotSessionTargetsFailed(e);
            completer.error(_dbClient, serviceException);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void relinkTargetsToSnapshotSession(URI systemURI, URI tgtSnapSessionURI, List<URI> snapshotURIs,
            Boolean updateStatus, String opId) throws InternalException {
        TaskCompleter completer = new BlockSnapshotSessionRelinkTargetsWorkflowCompleter(tgtSnapSessionURI, updateStatus, opId);
        try {
            // Get a new workflow to execute the linking of the target volumes
            // to the new session.
            Workflow workflow = _workflowService.getNewWorkflow(this, RELINK_SNAPSHOT_SESSION_TARGETS_WF_NAME, false, opId);
            _log.info("Created new workflow to re-link targets to snapshot session {} with operation id {}",
                    tgtSnapSessionURI, opId);

            Iterable<URI> snapshotsIterable = snapshotURIs;
            BlockSnapshotSession tgtSnapSession = _dbClient.queryObject(BlockSnapshotSession.class, tgtSnapSessionURI);

            // For CG's, ensure 1 target per ReplicationGroup
            if (tgtSnapSession.hasConsistencyGroup()
                    && NullColumnValueGetter.isNotNullValue(tgtSnapSession.getReplicationGroupInstance())) {
                snapshotsIterable = ControllerUtils.ensureOneSnapshotPerReplicationGroup(snapshotURIs, _dbClient);
            }

            String waitFor = null;
            for (URI snapshotURI : snapshotsIterable) {
                waitFor = workflow.createStep(
                        RELINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP,
                        String.format("Re-linking target to snapshot session %s", tgtSnapSessionURI),
                        waitFor, systemURI, getDeviceType(systemURI), getClass(),
                        relinkBlockSnapshotSessionTargetMethod(systemURI, tgtSnapSessionURI, snapshotURI),
                        null, null);
            }
            workflow.executePlan(completer, "Re-link target volumes to block snapshot session successful");
        } catch (Exception e) {
            _log.error("Re-link target volumes to block snapshot session failed", e);
            ServiceCoded serviceException = DeviceControllerException.exceptions.relinkBlockSnapshotSessionTargetsFailed(e);
            completer.error(_dbClient, serviceException);
        }
    }

    /**
     * Create the workflow method that is invoked by the workflow service
     * to re-link a target volume to the target array snapshot.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param tgtSnapSessionURI
     *            The URI of the target BlockSnapshotSession instance.
     * @param snapshotURI
     *            The URI of the BlockSnapshot instance representing the linked target volume.
     *
     * @return A reference to a Workflow.Method for re-linking a target volume to an array snapshot.
     */
    public static Workflow.Method relinkBlockSnapshotSessionTargetMethod(URI systemURI,
            URI tgtSnapSessionURI, URI snapshotURI) {
        return new Workflow.Method(RELINK_SNAPSHOT_SESSION_TARGET_METHOD, systemURI, tgtSnapSessionURI, snapshotURI);
    }

    /**
     * Re-link a linked target volume to the target array snapshot on the storage
     * system with the passed URI.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param tgtSnapSessionURI
     *            The URI of the target BlockSnapshotSession instance.
     * @param snapshotURI
     *            The URI of the BlockSnapshot instance representing the linked target volume.
     * @param stepId
     *            The unique id of the workflow step in which the target is re-linked.
     */
    public void relinkBlockSnapshotSessionTarget(URI systemURI, URI tgtSnapSessionURI,
            URI snapshotURI, String stepId) {
        TaskCompleter completer = null;
        try {
            StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
            completer = new BlockSnapshotSessionRelinkTargetCompleter(tgtSnapSessionURI, snapshotURI, stepId);
            getDevice(system.getSystemType()).doRelinkBlockSnapshotSessionTarget(system, tgtSnapSessionURI,
                    snapshotURI, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.relinkBlockSnapshotSessionTargetsFailed(e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unlinkTargetsFromSnapshotSession(URI systemURI, URI snapSessionURI,
            Map<URI, Boolean> snapshotDeletionMap, OperationTypeEnum opType, String opId) {
        TaskCompleter completer = new BlockSnapshotSessionUnlinkTargetsWorkflowCompleter(snapSessionURI, opType, opId);
        try {
            // Get a new workflow to unlinking of the targets from session.
            Workflow workflow = _workflowService.getNewWorkflow(this, UNLINK_SNAPSHOT_SESSION_TARGETS_WF_NAME, false, opId);
            _log.info("Created new workflow to unlink targets for snapshot session {} with operation id {}",
                    snapSessionURI, opId);
            Set<URI> targetKeys = snapshotDeletionMap.keySet();
            BlockSnapshotSession snapSession = _dbClient.queryObject(BlockSnapshotSession.class, snapSessionURI);

            // For CG's, ensure 1 target per ReplicationGroup
            if (snapSession.hasConsistencyGroup() && NullColumnValueGetter.isNotNullValue(snapSession.getReplicationGroupInstance())) {
                Iterator<BlockSnapshot> snapshots = _dbClient.queryIterativeObjects(BlockSnapshot.class, snapshotDeletionMap.keySet());
                final Set<String> replicationGroups = new HashSet<>();
                final Map<URI, BlockSnapshot> uriToSnapshotCache = new HashMap<>();
                while (snapshots.hasNext()) {
                    BlockSnapshot snapshot = snapshots.next();
                    uriToSnapshotCache.put(snapshot.getId(), snapshot);
                }

                Map<URI, Boolean> filtered = Maps.filterEntries(snapshotDeletionMap, new Predicate<Map.Entry<URI, Boolean>>() {
                    @Override
                    public boolean apply(Map.Entry<URI, Boolean> input) {
                        BlockSnapshot blockSnapshot = uriToSnapshotCache.get(input.getKey());
                        String repGrpInstance = blockSnapshot.getReplicationGroupInstance();
                        if (replicationGroups.contains(repGrpInstance)) {
                            return false;
                        }

                        replicationGroups.add(repGrpInstance);
                        return true;
                    }
                });
                // assign to targetKeys filtered keySet view of snapshotDeletionMap.
                targetKeys = filtered.keySet();
            }

            // TODO Use ModifyListSettingsDefineState here and remove the for-loop.
            String waitFor = null;
            // Create a workflow step to unlink each target specified in targetKeys
            for (URI snapshotURI : targetKeys) {
                waitFor = workflow.createStep(UNLINK_SNAPSHOT_SESSION_TARGET_STEP_GROUP,
                        String.format("Unlinking target for snapshot session %s", snapSessionURI),
                        waitFor, systemURI, getDeviceType(systemURI), getClass(),
                        unlinkBlockSnapshotSessionTargetMethod(systemURI, snapSessionURI, snapshotURI,
                                snapshotDeletionMap.get(snapshotURI)),
                        null, null);
            }

            // Execute the workflow.
            workflow.executePlan(completer, "Unlink block snapshot session targets successful");
        } catch (Exception e) {
            _log.error("Unlink block snapshot session targets failed", e);
            ServiceCoded serviceException = DeviceControllerException.exceptions.unlinkBlockSnapshotSessionTargetsFailed(e);
            completer.error(_dbClient, serviceException);
        }
    }

    /**
     * Create the workflow method that is invoked by the workflow service
     * to unlink a target from an array snapshot.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionURI
     *            The URI of the BlockSnapshotSession instance.
     * @param snapshotURI
     *            The URI of the BlockSnapshot instance representing the linked target volume.
     * @param deleteTarget
     *            True if the target volume should be deleted.
     *
     * @return A reference to a Workflow.Method for linking a target volume to an array snapshot.
     */
    public static Workflow.Method unlinkBlockSnapshotSessionTargetMethod(URI systemURI,
            URI snapSessionURI, URI snapshotURI, Boolean deleteTarget) {
        return new Workflow.Method(UNLINK_SNAPSHOT_SESSION_TARGET_METHOD, systemURI, snapSessionURI, snapshotURI, deleteTarget);
    }

    /**
     * Unlinks the target from the array snapshot on the storage system
     * with the passed URI. Additionally, the target device will be deleted
     * if so requested.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionURI
     *            The URI of the BlockSnapshotSession instance.
     * @param snapshotURI
     *            The URI of the BlockSnapshot instance representing the linked target volume.
     * @param deleteTarget
     *            True if the target volume should be deleted.
     * @param stepId
     *            The unique id of the workflow step in which the target is unlinked.
     */
    public void unlinkBlockSnapshotSessionTarget(URI systemURI, URI snapSessionURI,
            URI snapshotURI, Boolean deleteTarget, String stepId) {
        TaskCompleter completer = null;
        try {
            StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
            completer = new BlockSnapshotSessionUnlinkTargetCompleter(snapSessionURI, snapshotURI, deleteTarget, stepId);
            getDevice(system.getSystemType()).doUnlinkBlockSnapshotSessionTarget(system, snapSessionURI,
                    snapshotURI, deleteTarget, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.unlinkBlockSnapshotSessionTargetsFailed(e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void restoreSnapshotSession(URI systemURI, URI snapSessionURI, Boolean updateStatus, String opId) {
        BlockSnapshotSession snapshotSession = _dbClient.queryObject(BlockSnapshotSession.class, snapSessionURI);
        TaskCompleter completer = new BlockSnapshotSessionRestoreWorkflowCompleter(snapshotSession.getId(), updateStatus, opId);
        try {
            // Get a new workflow to restore the snapshot session.
            Workflow workflow = _workflowService.getNewWorkflow(this, RESTORE_SNAPSHOT_SESSION_WF_NAME, false, opId);
            _log.info("Created new workflow to restore snapshot session {} with operation id {}",
                    snapSessionURI, opId);
            String waitFor = null;

            // Check if we are dealing with a single volume or a group...
            BlockObject sourceObj = null;
            if (snapshotSession.hasConsistencyGroup()
                    && NullColumnValueGetter.isNotNullValue(snapshotSession.getReplicationGroupInstance())) {
                // We need a single source volume for the session.
                BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, snapshotSession.getConsistencyGroup());
                List<Volume> nativeVolumes = BlockConsistencyGroupUtils.getActiveNativeVolumesInCG(cg, _dbClient);
                // get source group name from the session.
                String sourceGroupName = snapshotSession.getReplicationGroupInstance();
                for (Volume volume : nativeVolumes) {
                    if (sourceGroupName.equals(volume.getReplicationGroupInstance())) {
                        sourceObj = volume;
                        break; // get source volume which matches session's RG name
                    }
                }
            } else {
                sourceObj = BlockObject.fetch(_dbClient, snapshotSession.getParent().getURI());
            }

            if (sourceObj instanceof Volume && isNonSplitSRDFTargetVolume((Volume) sourceObj)) {
                // PRIOR to Restoring R2 Device from its session, we need to
                // a) SUSPEND the R1-R2 pair if the Copy Mode is ACTIVE Or
                // b) SPLIT the R1-R2 pair if the Copy Mode is SYNC/ ASYNC
                Volume sourceVolume = (Volume) sourceObj;
                URI srdfSourceVolumeURI = sourceVolume.getSrdfParent().getURI();
                Volume srdfSourceVolume = _dbClient.queryObject(Volume.class, srdfSourceVolumeURI);
                URI srdfSourceStorageSystemURI = srdfSourceVolume.getStorageController();
                if (Mode.ACTIVE.equals(Mode.valueOf(sourceVolume.getSrdfCopyMode()))) {
                    waitFor = suspendSRDFLinkWorkflowStep(waitFor, srdfSourceStorageSystemURI,
                            srdfSourceVolumeURI, sourceObj.getId(), workflow);
                } else {
                    // split all members the group
                    Workflow.Method splitMethod = srdfDeviceController.splitSRDFLinkMethod(srdfSourceStorageSystemURI,
                            srdfSourceVolumeURI, sourceObj.getId(), false);

                    Workflow.Method splitRollbackMethod = srdfDeviceController.resumeSyncPairMethod(srdfSourceStorageSystemURI,
                            srdfSourceVolumeURI, sourceObj.getId());

                    waitFor = workflow.createStep(SRDFDeviceController.SPLIT_SRDF_MIRRORS_STEP_GROUP,
                            SRDFDeviceController.SPLIT_SRDF_MIRRORS_STEP_DESC, waitFor, srdfSourceStorageSystemURI,
                            getDeviceType(srdfSourceStorageSystemURI), SRDFDeviceController.class, splitMethod,
                            splitRollbackMethod, null);
                }
            } else if (sourceObj instanceof Volume && isNonSplitSRDFSourceVolume((Volume) sourceObj)) {
                // PRIOR to Restoring R1 Device from its session, we need to SUSPEND the R1-R2 pair if the Copy Mode is
                // ACTIVE
                Volume srdfSourceVolume = (Volume) sourceObj;
                URI srdfSourceStorageSystemURI = srdfSourceVolume.getStorageController();
                StringSet targets = srdfSourceVolume.getSrdfTargets();
                if (null != targets) {
                    for (String target : targets) {
                        if (NullColumnValueGetter.isNotNullValue(target)) {
                            Volume srdfTargetVolume = _dbClient.queryObject(Volume.class, URI.create(target));
                            if (null != srdfTargetVolume && Mode.ACTIVE.equals(Mode.valueOf(srdfTargetVolume.getSrdfCopyMode()))) {
                                waitFor = suspendSRDFLinkWorkflowStep(waitFor, srdfSourceStorageSystemURI,
                                        srdfSourceVolume.getId(), srdfTargetVolume.getId(), workflow);
                            }
                            break;
                        }
                    }
                }
            }

            // Create the workflow step to restore the snapshot session.
            waitFor = workflow.createStep(RESTORE_SNAPSHOT_SESSION_STEP_GROUP,
                    String.format("Restore snapshot session %s", snapSessionURI),
                    waitFor, systemURI, getDeviceType(systemURI), getClass(),
                    restoreBlockSnapshotSessionMethod(systemURI, snapSessionURI),
                    rollbackMethodNullMethod(), null);

            // Execute the workflow.
            workflow.executePlan(completer, "Restore block snapshot session successful");
        } catch (Exception e) {
            _log.error("Restore block snapshot session failed", e);
            ServiceCoded serviceException = DeviceControllerException.exceptions.restoreBlockSnapshotSessionFailed(e);
            completer.error(_dbClient, serviceException);
        }
    }

    /**
     * Create the workflow method that is invoked by the workflow service
     * to restore the data on the array snapshot represented by the
     * BlockSnapshotSession instance with the passed URI to its source.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionURI
     *            The URI of the BlockSnapshotSession instance.
     *
     * @return A reference to a Workflow.Method for restoring the array snapshot to its source.
     */
    public static Workflow.Method restoreBlockSnapshotSessionMethod(URI systemURI, URI snapSessionURI) {
        return new Workflow.Method(RESTORE_SNAPSHOT_SESSION_METHOD, systemURI, snapSessionURI);
    }

    /**
     * Restore the data on the array snapshot represented by the
     * BlockSnapshotSession instance with the passed URI to its source.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionURI
     *            The URI of the BlockSnapshotSession instance.
     * @param stepId
     *            The unique id of the workflow step in which the session is restored.
     */
    public void restoreBlockSnapshotSession(URI systemURI, URI snapSessionURI, String stepId) {
        TaskCompleter completer = null;
        try {
            StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
            completer = new BlockSnapshotSessionRestoreCompleter(snapSessionURI, stepId);
            getDevice(system.getSystemType()).doRestoreBlockSnapshotSession(system, snapSessionURI, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.restoreBlockSnapshotSessionFailed(e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteSnapshotSession(URI systemURI, URI snapSessionURI, String opId) {
        TaskCompleter completer = new BlockSnapshotSessionDeleteWorkflowCompleter(snapSessionURI, opId);
        try {
            // Get a new workflow delete the snapshot session.
            Workflow workflow = _workflowService.getNewWorkflow(this, DELETE_SNAPSHOT_SESSION_WF_NAME, false, opId);
            _log.info("Created new workflow to delete snapshot session {} with operation id {}",
                    snapSessionURI, opId);

            // When deleting a group snapshot we need the name of the group.
            String groupName = null;
            boolean isCG = checkSnapshotSessionConsistencyGroup(snapSessionURI, _dbClient, completer);
            if (isCG) {
                BlockSnapshotSession snapSession = _dbClient.queryObject(BlockSnapshotSession.class, snapSessionURI);
                groupName = snapSession.getReplicationGroupInstance();
            }

            // Create the workflow step to delete the snapshot session.
            workflow.createStep(DELETE_SNAPSHOT_SESSION_STEP_GROUP,
                    String.format("Delete snapshot session %s", snapSessionURI),
                    null, systemURI, getDeviceType(systemURI), getClass(),
                    deleteBlockSnapshotSessionMethod(systemURI, snapSessionURI, groupName, Boolean.FALSE),
                    null, null);

            // Execute the workflow.
            workflow.executePlan(completer, "Delete block snapshot session successful");
        } catch (Exception e) {
            _log.error("Delete block snapshot session failed", e);
            ServiceCoded serviceException = DeviceControllerException.exceptions.deleteBlockSnapshotSessionFailed(e);
            completer.error(_dbClient, serviceException);
        }
    }

    /**
     * Create the workflow method that is invoked by the workflow service
     * to delete the array snapshot represented by the BlockSnapshotSession
     * instance with the passed URI to its source.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionURI
     *            The URI of the BlockSnapshotSession instance.
     * @param markInactive
     *            true if the step should mark the session inactive, false otherwise.
     * @param groupName
     *            The group name when deleting a group snapshot session.
     *
     * @return A reference to a Workflow.Method for deleting the array snapshot.
     */
    public static Workflow.Method deleteBlockSnapshotSessionMethod(URI systemURI, URI snapSessionURI, String groupName,
            Boolean markInactive) {
        return new Workflow.Method(DELETE_SNAPSHOT_SESSION_METHOD, systemURI, snapSessionURI, groupName, markInactive);
    }

    /**
     * Delete the array snapshot represented by the BlockSnapshotSession instance
     * with the passed URI to its source.
     *
     * @param systemURI
     *            The URI of the storage system.
     * @param snapSessionURI
     *            The URI of the BlockSnapshotSession instance.
     * @param stepId
     *            The unique id of the workflow step in which the session is deleted.
     * @param groupName
     *            The group name when deleting a group snapshot session.
     * @param markInactive
     *            true if the step should mark the session inactive, false otherwise.
     */
    public void deleteBlockSnapshotSession(URI systemURI, URI snapSessionURI, String groupName, Boolean markInactive, String stepId) {
        TaskCompleter completer = null;
        try {
            StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
            completer = new BlockSnapshotSessionDeleteCompleter(snapSessionURI, markInactive, stepId);
            WorkflowStepCompleter.stepExecuting(stepId);
            getDevice(system.getSystemType()).doDeleteBlockSnapshotSession(system, snapSessionURI, groupName, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            } else {
                throw DeviceControllerException.exceptions.restoreBlockSnapshotSessionFailed(e);
            }
        }
    }

    /**
     * Gets the full copies for the given volumes.
     *
     * @param allFullCopies
     *            the requested full copies to be created
     * @param volumes
     *            the volumes
     * @return the full copies
     */
    private List<URI> getFullCopiesForVolumes(List<URI> allFullCopies, List<Volume> volumes) {
        List<URI> fullCopyURIs = new ArrayList<URI>();
        for (Volume vol : volumes) {
            if (vol.getFullCopies() != null) {
                for (String fullCopy : vol.getFullCopies()) {
                    URI fullCopyURI = URI.create(fullCopy);
                    if (allFullCopies.contains(fullCopyURI)) {
                        fullCopyURIs.add(fullCopyURI);
                    }
                }
            }
        }
        return fullCopyURIs;
    }

    /**
     * Given a list of volumes, returns a table of storage id, replication group name, and volume list
     *
     * If given newRGName is same as the volume's RG name, the volume will be skipped, if it is non VNX volume.
     * For VNX volume, it need to be removed from real replication group, so it cannot be skipped.
     *
     * @param volumeIds
     * @param newRGName
     *            (for adding volumes to application only, null otherwise)
     * @return table with storage URI, replication group name, and volume URIs
     */
    private Table<URI, String, List<URI>> getStorageSystemRGVolumes(Collection<URI> volumeIds, String newRGName) {
        Table<URI, String, List<URI>> storgeRGToVolumes = HashBasedTable.create();
        if (volumeIds != null && !volumeIds.isEmpty()) {
            Iterator<Volume> volumes = _dbClient.queryIterativeObjects(Volume.class, volumeIds);
            while (volumes.hasNext()) {
                Volume volume = volumes.next();
                if (volume != null && !volume.getInactive()) {
                    URI storage = volume.getStorageController();
                    String rgName = volume.getReplicationGroupInstance();
                    if (rgName == null) {
                        rgName = NullColumnValueGetter.getNullStr();
                    } else if (newRGName != null && rgName.equals(newRGName) && !ControllerUtils.isVnxVolume(volume, _dbClient)) {
                        // volume is already in the designated group
                        _log.info("Volume {} is already in the desired group {}", volume.getLabel(), newRGName);
                        continue;
                    }

                    List<URI> volumeUris = storgeRGToVolumes.get(storage, rgName);
                    if (volumeUris == null) {
                        volumeUris = new ArrayList<URI>();
                        storgeRGToVolumes.put(storage, rgName, volumeUris);
                    }

                    volumeUris.add(volume.getId());
                }
            }
        }

        return storgeRGToVolumes;
    }

    /**
     * add step for update application, using by VPLEX and RP to
     * add/remove block volumes to/from replication groups on multiple storage systems
     *
     * @param workflow
     * @param addVolList
     * @param removeVolumeURIs
     * @param waitForStep
     * @param taskId
     * @return
     */
    public String addStepsForUpdateApplication(Workflow workflow, ApplicationAddVolumeList addVolList, List<URI> removeVolumeURIs,
            String waitForStep, String taskId) {
        String waitFor = waitForStep;
        // split up volumes by storage system, replication group, and add steps for each storage system and RG
        Table<URI, String, List<URI>> storageRGToRemoveVolumes = getStorageSystemRGVolumes(removeVolumeURIs, null);

        // map volumes to add by storage system, replication group
        if (addVolList != null) {
            // add source and target volumes from array replication groups
            Table<URI, String, List<URI>> storageRGToAddVolumes = getStorageSystemRGVolumes(addVolList.getVolumes(),
                    addVolList.getReplicationGroupName());
            for (Cell<URI, String, List<URI>> cell : storageRGToAddVolumes.cellSet()) {
                URI storage = cell.getRowKey();
                String rgName = cell.getColumnKey();
                List<URI> addVolumes = cell.getValue();

                List<URI> removeVolumes = new ArrayList<URI>();
                if (NullColumnValueGetter.isNotNullValue(rgName)) {
                    // volumes have already been in a RG, need remove them from original RG
                    removeVolumes.addAll(addVolumes);
                }

                if (storageRGToRemoveVolumes.contains(storage, rgName)) {
                    removeVolumes.addAll(storageRGToRemoveVolumes.remove(storage, rgName));
                }

                waitFor = addStepsForUpdateApplicationSingleStorage(workflow, storage, addVolList.getReplicationGroupName(),
                        addVolumes, removeVolumes, waitFor, taskId);
            }
        }

        // process remaining storage system and RG that has volumes to remove from application
        for (Cell<URI, String, List<URI>> cell : storageRGToRemoveVolumes.cellSet()) {
            waitFor = addStepsForUpdateApplicationSingleStorage(workflow, cell.getRowKey(), null, null, cell.getValue(), waitFor, taskId);
        }

        return waitFor;
    }

    /**
     * add step for update application
     * adds volumes to replication groups on a single storage systems
     *
     * @param workflow
     * @param storage
     * @param rgName
     * @param addVolumeList
     * @param removeVolumeList
     * @param waitForStep
     * @param opId
     * @return
     * @throws ControllerException
     */
    private String addStepsForUpdateApplicationSingleStorage(Workflow workflow, URI storage, String rgName, List<URI> addVolumeList,
            List<URI> removeVolumeList,
            String waitForStep, String opId) throws ControllerException {
        String waitFor = waitForStep;
        // Note volumes could be in both addVolumeList and removeVolumeList, e.g., remove from original RG, and add to a
        // new RG
        // Need to process remove list first
        if (removeVolumeList != null && !removeVolumeList.isEmpty()) {
            Volume vol = _dbClient.queryObject(Volume.class, removeVolumeList.get(0));
            URI cgUri = vol.getConsistencyGroup();
            String groupName = vol.getReplicationGroupInstance();
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            // call ReplicaDeviceController
            waitFor = _replicaDeviceController.addStepsForRemovingVolumesFromCG(workflow, waitFor, cgUri, removeVolumeList, opId);
            // Remove the volumes from the consistency group
            waitFor = workflow.createStep(REMOVE_VOLUMES_FROM_CG_STEP_GROUP,
                    String.format("Remove volumes from consistency group %s", cgUri.toString()),
                    waitFor, storage, storageSystem.getSystemType(),
                    this.getClass(),
                    removeFromConsistencyGroupMethod(storage, cgUri, removeVolumeList, false),
                    addToConsistencyGroupMethod(storage, cgUri, groupName, removeVolumeList), null);

            // remove replication group if the CG will become empty
            if (ControllerUtils.replicationGroupHasNoOtherVolume(_dbClient, groupName, removeVolumeList, storage)) {
                waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                        String.format("Deleting replication group for consistency group %s", cgUri.toString()),
                        waitFor, storage, storageSystem.getSystemType(),
                        this.getClass(),
                        deleteConsistencyGroupMethod(storage, cgUri, groupName, false, false, false),
                        createConsistencyGroupMethod(storage, cgUri, groupName), null);
            }
        }

        if (addVolumeList != null && !addVolumeList.isEmpty()) {
            _log.info("Creating workflows for adding volumes to CG and application");
            Volume vol = _dbClient.queryObject(Volume.class, addVolumeList.get(0));
            URI cgUri = vol.getConsistencyGroup();
            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, cgUri);
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);

            // check if cg is created, if not create it
            boolean isNewRG = false;
            if (!cg.created(rgName, storage)) {
                _log.info("Consistency group not created. Creating it");
                isNewRG = true;

                if (storageSystem.deviceIsType(Type.vnxblock)) {
                    // set arrayConsistency to false, so that no replication group will be created on array
                    cg.setArrayConsistency(false);
                    _dbClient.updateObject(cg);
                }

                waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                        String.format("Creating consistency group %s", rgName),
                        waitFor, storage, storageSystem.getSystemType(),
                        this.getClass(),
                        createConsistencyGroupMethod(storage, cgUri, rgName),
                        deleteConsistencyGroupMethod(storage, cgUri, rgName, false, false, false), null);
            }

            waitFor = workflow.createStep(UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                    String.format("Adding volumes to consistency group %s", cgUri.toString()),
                    waitFor, storage, storageSystem.getSystemType(),
                    this.getClass(),
                    addToConsistencyGroupMethod(storage, cgUri, rgName, addVolumeList),
                    removeFromConsistencyGroupMethod(storage, cgUri, addVolumeList, false), null);

            if (!isNewRG) {
                // call ReplicaDeviceController
                waitFor = _replicaDeviceController.addStepsForAddingVolumesToRG(workflow, waitFor, cgUri, addVolumeList, rgName, opId);
            }
        }

        return waitFor;
    }

    /**
     * Create a method for workflow to delete array clone replication group
     * 
     * @param storage
     *            storage system
     * @param consistencyGroup
     *            consistency group URI
     * @param groupName
     *            clone group name
     * @param keepRGName
     * @param markInactive
     * @param sourceGroupName
     *            source group name
     * @return the created workflow Method
     */
    public Workflow.Method deleteReplicationGroupMethod(URI storage, URI consistencyGroup, String groupName, Boolean keepRGName,
            Boolean markInactive, String sourceGroupName) {
        return new Workflow.Method("deleteReplicationGroup", storage, consistencyGroup, groupName, keepRGName, markInactive,
                sourceGroupName);
    }

    /**
     * Delete array clone replication group
     * 
     * @param storage
     *            storage system
     * @param consistencyGroup
     *            consistency group URI
     * @param groupName
     *            clone group name
     * @param keepRGName
     * @param markInactive
     * @param sourceGroupName
     *            source group name
     * @return the created workflow Method
     */
    public void deleteReplicationGroup(URI storage, URI consistencyGroup, String groupName, Boolean keepRGName, Boolean markInactive,
            String sourceGroupName, String opId) throws ControllerException {
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem storageObj = _dbClient.queryObject(StorageSystem.class, storage);
            completer = new BlockConsistencyGroupDeleteCompleter(consistencyGroup, storage, groupName, keepRGName, markInactive, opId);
            getDevice(storageObj.getSystemType()).doDeleteConsistencyGroup(storageObj, consistencyGroup, groupName, keepRGName,
                    markInactive,
                    sourceGroupName, completer);
        } catch (Exception e) {
            if (completer != null) {
                ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
                completer.error(_dbClient, serviceError);
            }
            throw DeviceControllerException.exceptions.deleteConsistencyGroupFailed(e);
        }
    }

    /**
     * Add steps to restore full copy
     * 
     * @param workflow
     *            - the workflow the steps would be added to
     * @param waitFor
     *            - the step would be waited before the added steps would be executed
     * @param storage
     *            - the storage controller URI
     * @param fullcopies
     *            - the full copies to restore
     * @param opId
     * @param completer
     *            - the CloneRestoreCompleter
     * @return the step id for the added step
     * @throws InternalException
     */
    public String addStepsForRestoreFromFullcopy(Workflow workflow,
            String waitFor, URI storage, List<URI> fullcopies, String opId,
            TaskCompleter completer) throws InternalException {

        Volume firstFullCopy = _dbClient.queryObject(Volume.class, fullcopies.get(0));
        // Don't do anything if this is VPLEX full copy
        if (firstFullCopy.isVPlexVolume(_dbClient)) {
            return waitFor;
        }
        BlockObject firstSource = BlockObject.fetch(_dbClient, firstFullCopy.getAssociatedSourceVolume());
        if (!NullColumnValueGetter.isNullURI(firstSource.getConsistencyGroup())) {
            completer.addConsistencyGroupId(firstSource.getConsistencyGroup());
        }
        StorageSystem system = _dbClient.queryObject(StorageSystem.class, storage);

        Workflow.Method restoreFromFullcopyMethod = new Workflow.Method(
                RESTORE_FROM_FULLCOPY_METHOD_NAME, storage, fullcopies, Boolean.TRUE);
        waitFor = workflow.createStep(RESTORE_FROM_FULLCOPY_STEP,
                "Restore volumes from full copies", waitFor,
                storage, system.getSystemType(),
                this.getClass(), restoreFromFullcopyMethod, null, null);
        _log.info("Created workflow step to restore volume from full copies");

        return waitFor;
    }

    /* (non-Javadoc)
     * @see com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationInterface#addStepsForCreateFullCopy(com.emc.storageos.workflow.Workflow, java.lang.String, java.util.List, java.lang.String)
     */
    @Override
    public String addStepsForCreateFullCopy(Workflow workflow, String waitFor, List<VolumeDescriptor> volumeDescriptors,
            String taskId) throws InternalException {
        
        List<VolumeDescriptor> blockVolmeDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_DATA, VolumeDescriptor.Type.VPLEX_IMPORT_VOLUME },
                new VolumeDescriptor.Type[] {});
        
        // If no volumes to create, just return
        if (blockVolmeDescriptors.isEmpty()) {
            return waitFor;
        }
        
        URI storageURI = null;
        boolean createInactive = false;

        List<URI> fullCopyList = new ArrayList<URI>();
        for (VolumeDescriptor descriptor : blockVolmeDescriptors) {
            Volume volume = _dbClient.queryObject(Volume.class, descriptor.getVolumeURI());
            if (volume != null && !volume.getInactive()) {
                URI parentId = volume.getAssociatedSourceVolume();
                if (!NullColumnValueGetter.isNullURI(parentId)) {
                    fullCopyList.add(volume.getId());
                    storageURI = volume.getStorageController();
                    createInactive = Boolean.getBoolean(descriptor.getCapabilitiesValues().getReplicaCreateInactive());
                }
            }
        }
        
        if (!fullCopyList.isEmpty()) {
            String stepId = workflow.createStepId();
            // Now add the steps to create the block full copy on the storage system
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storageURI);
            Workflow.Method createFullCopyMethod = new Workflow.Method(METHOD_CREATE_FULL_COPY_STEP, storageURI, fullCopyList,
                    createInactive);
            Workflow.Method createFullCopyOrchestrationExecutionRollbackMethod = new Workflow.Method(METHOD_CREATE_FULLCOPY_ORCHESTRATE_ROLLBACK_STEP,
                    workflow.getWorkflowURI(), stepId);

            waitFor = workflow.createStep(FULL_COPY_CREATE_ORCHESTRATION_STEP, "Create Block Full Copy", waitFor, storageSystem.getId(),
                    storageSystem.getSystemType(), this.getClass(), createFullCopyMethod, createFullCopyOrchestrationExecutionRollbackMethod, stepId);
            _log.info(String.format("Added %s step [%s] in workflow", FULL_COPY_CREATE_STEP_GROUP, stepId));
        }
        
        return waitFor;
    }
    
    /**
     * calls the child workflow step rollback methods
     * 
     * @param parentWorkflow
     * @param orchestrationStepId
     * @param token
     * @return
     * @throws WorkflowException
     */
    public boolean createFullCopyOrchestrationRollbackSteps(URI parentWorkflow, String orchestrationStepId, String token)
            throws WorkflowException {
        // The workflow service now provides a rollback facility for a child workflow. It rolls back every step in an already
        // (successfully) completed child workflow. The child workflow is located by the parentWorkflow URI and
        // exportOrchestrationStepId.
        _workflowService.rollbackChildWorkflow(parentWorkflow, orchestrationStepId, token);

        return true;
    }

    /* (non-Javadoc)
     * @see com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationInterface#addStepsForPostCreateReplica(com.emc.storageos.workflow.Workflow, java.lang.String, java.util.List, java.lang.StringBuffer, java.lang.String)
     */
    @Override
    public String addStepsForPostCreateReplica(Workflow workflow, String waitFor, List<VolumeDescriptor> volumeDescriptors,
            String taskId) throws InternalException {
        // nothing to do post create replica
        return waitFor;
    }

    /**
     * @param workflow
     * @param waitFor
     * @param volumeDescriptors
     * @param taskId
     * @return
     */
    public String addStepsForCreateSnapshotSession(Workflow workflow, String waitFor, List<VolumeDescriptor> volumeDescriptors,
            String taskId) {
        
        List<VolumeDescriptor> blockVolmeDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_SNAPSHOT_SESSION },
                new VolumeDescriptor.Type[] {});
        
        // If no volumes to create, just return
        if (blockVolmeDescriptors.isEmpty()) {
            return waitFor;
        }
        
        // we expect just one snapshot session volume descriptor
        VolumeDescriptor descriptor = blockVolmeDescriptors.get(0);
        BlockSnapshotSession session = _dbClient.queryObject(BlockSnapshotSession.class, descriptor.getVolumeURI());
        if (session != null && !session.getInactive()) {
            String stepId = workflow.createStepId();
            // Now add the steps to create the snapshot session on the storage system
            URI storageURI = session.getStorageController();
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storageURI);
            Workflow.Method createSnapshotSessionMethod = new Workflow.Method(METHOD_CREATE_SNAPSHOT_SESSION_STEP, storageURI, 
                    descriptor.getVolumeURI(),  
                    descriptor.getSnapSessionSnapshotURIs(), 
                    descriptor.getCapabilitiesValues().getSnapshotSessionCopyMode());
            Workflow.Method nullRollbackMethod = new Workflow.Method(ROLLBACK_METHOD_NULL);

            waitFor = workflow.createStep(SNAPSHOT_SESSION_CREATE_ORCHESTRATION_STEP, "Create Block Snapshot Session", waitFor, storageSystem.getId(),
                    storageSystem.getSystemType(), this.getClass(), createSnapshotSessionMethod, nullRollbackMethod, stepId);
            _log.info(String.format("Added %s step [%s] in workflow", SNAPSHOT_SESSION_CREATE_STEP_GROUP, stepId));
            
        }
        
        return waitFor;
    }

    /* (non-Javadoc)
     * @see com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationInterface#addStepsForPreCreateReplica(com.emc.storageos.workflow.Workflow, java.lang.String, java.util.List, java.lang.String)
     */
    @Override
    public String addStepsForPreCreateReplica(Workflow workflow, String waitFor, List<VolumeDescriptor> volumeDescriptors, String taskId)
            throws InternalException {
        return waitFor;
    }

    @Override
    public void setInitiatorAlias(URI systemURI, URI initiatorURI, String initiatorAlias) throws Exception {
        StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
        Initiator initiator = _dbClient.queryObject(Initiator.class, initiatorURI);

        getDevice(system.getSystemType()).doInitiatorAliasSet(
                system, initiator, initiatorAlias);
    }

    @Override
    public String getInitiatorAlias(URI systemURI, URI initiatorURI) throws Exception {
        StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
        Initiator initiator = _dbClient.queryObject(Initiator.class, initiatorURI);

        return getDevice(system.getSystemType()).doInitiatorAliasGet(
                system, initiator);
    }
}
