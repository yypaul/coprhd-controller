/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.blockorchestrationcontroller;

import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.Controller;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.Migration;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.locking.LockRetryException;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.protectioncontroller.impl.recoverpoint.RPDeviceController;
import com.emc.storageos.protectioncontroller.impl.recoverpoint.RPHelper;
import com.emc.storageos.srdfcontroller.SRDFDeviceController;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.ControllerLockingService;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.block.BlockDeviceController;
import com.emc.storageos.volumecontroller.impl.block.ReplicaDeviceController;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotRestoreCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotSessionCreateWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CloneCreateWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.CloneRestoreCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeCreateWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeVarrayChangeTaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeVpoolChangeTaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.utils.ConsistencyGroupUtils;
import com.emc.storageos.volumecontroller.impl.validators.ValCk;
import com.emc.storageos.volumecontroller.impl.validators.ValidatorFactory;
import com.emc.storageos.vplexcontroller.VPlexDeviceController;
import com.emc.storageos.workflow.Workflow;
import com.emc.storageos.workflow.WorkflowException;
import com.emc.storageos.workflow.WorkflowService;
import com.emc.storageos.workflow.WorkflowState;

public class BlockOrchestrationDeviceController implements BlockOrchestrationController, Controller {
    private static final Logger s_logger = LoggerFactory.getLogger(BlockOrchestrationDeviceController.class);
    private WorkflowService _workflowService;
    private static DbClient s_dbClient;
    private static BlockDeviceController _blockDeviceController;
    private static VPlexDeviceController _vplexDeviceController;
    private static RPDeviceController _rpDeviceController;
    private static SRDFDeviceController _srdfDeviceController;
    private static ReplicaDeviceController _replicaDeviceController;
    private static ValidatorFactory validator;
    private ControllerLockingService _locker;

    static final String CREATE_VOLUMES_WF_NAME = "CREATE_VOLUMES_WORKFLOW";
    static final String DELETE_VOLUMES_WF_NAME = "DELETE_VOLUMES_WORKFLOW";
    static final String EXPAND_VOLUMES_WF_NAME = "EXPAND_VOLUMES_WORKFLOW";
    static final String RESTORE_VOLUME_FROM_SNAPSHOT_WF_NAME = "RESTORE_VOLUME_FROM_SNAPSHOT_WORKFLOW";
    static final String CHANGE_VPOOL_WF_NAME = "CHANGE_VPOOL_WORKFLOW";
    static final String CHANGE_VARRAY_WF_NAME = "CHANGE_VARRAY_WORKFLOW";
    static final String RESTORE_FROM_FULLCOPY_WF_NAME = "RESTORE_FROM_FULLCOPY_WORKFLOW";
    static final String CREATE_FULL_COPIES_WF_NAME = "CREATE_FULL_COPIES_WORKFLOW";
    static final String CREATE_SNAPSHOT_SESSION_WF_NAME = "CREATE_SNAPSHOT_SESSION_WORKFLOW";

    /*
     * (non-Javadoc)
     * 
     * @see com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationController#createVolumes(java.util.List,
     * java.lang.String)
     */
    @Override
    public void createVolumes(List<VolumeDescriptor> volumes, String taskId) throws ControllerException {
        List<URI> volUris = VolumeDescriptor.getVolumeURIs(volumes);
        VolumeCreateWorkflowCompleter completer = new VolumeCreateWorkflowCompleter(volUris, taskId, volumes);
        Workflow workflow = null;
        try {
            // Generate the Workflow.
            workflow = _workflowService.getNewWorkflow(this,
                    CREATE_VOLUMES_WF_NAME, true, taskId);
            String waitFor = null; // the wait for key returned by previous call

            s_logger.info("Generating steps for create Volume");
            // First, call the BlockDeviceController to add its methods.
            waitFor = _blockDeviceController.addStepsForCreateVolumes(
                    workflow, waitFor, volumes, taskId);

            s_logger.info("Checking for SRDF steps");
            // Call the SRDFDeviceController to add its methods if there are SRDF volumes.
            waitFor = _srdfDeviceController.addStepsForCreateVolumes(
                    workflow, waitFor, volumes, taskId);

            s_logger.info("Checking for VPLEX steps");
            // Call the VPlexDeviceController to add its methods if there are VPLEX volumes.
            waitFor = _vplexDeviceController.addStepsForCreateVolumes(
                    workflow, waitFor, volumes, taskId);

            s_logger.info("Checking for RP steps");
            // Call the RPDeviceController to add its methods if there are RP protections
            waitFor = _rpDeviceController.addStepsForCreateVolumes(
                    workflow, waitFor, volumes, taskId);

            s_logger.info("Checking for Replica steps");
            // Call the ReplicaDeviceController to add its methods if volumes are added to CG, and the CG associated
            // with replication
            // group(s)
            waitFor = _replicaDeviceController.addStepsForCreateVolumes(
                    workflow, waitFor, volumes, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = "Create volumes successful for: " + volUris.toString();
            Object[] callbackArgs = new Object[] { volUris };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);
        } catch (LockRetryException ex) {
            /**
             * Added this catch block to mark the current workflow as completed so that lock retry will not get exception while creating new
             * workflow using the same taskid.
             */
            s_logger.info(String.format("Lock retry exception key: %s remaining time %d", ex.getLockIdentifier(),
                    ex.getRemainingWaitTimeSeconds()));
            if (workflow != null && !NullColumnValueGetter.isNullURI(workflow.getWorkflowURI())
                    && workflow.getWorkflowState() == WorkflowState.CREATED) {
                com.emc.storageos.db.client.model.Workflow wf = s_dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                        workflow.getWorkflowURI());
                if (!wf.getCompleted()) {
                    s_logger.error("Marking the status to completed for the newly created workflow {}", wf.getId());
                    wf.setCompleted(true);
                    s_dbClient.updateObject(wf);
                }
            }
            throw ex;
        } catch (Exception ex) {
            s_logger.error("Could not create volumes: " + volUris, ex);
            releaseWorkflowLocks(workflow);
            String opName = ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME.getName();
            ServiceError serviceError = DeviceControllerException.errors.createVolumesFailed(
                    volUris.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

    @SuppressWarnings("serial")
    private static class WorkflowCallback implements Workflow.WorkflowCallbackHandler, Serializable {
        @SuppressWarnings("unchecked")
        @Override
        public void workflowComplete(Workflow workflow, Object[] args)
                throws WorkflowException {
            List<URI> volumes = (List<URI>) args[0];
            String msg = BlockDeviceController.getVolumesMsg(s_dbClient, volumes);
            s_logger.info("Processed volumes:\n" + msg);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationController#deleteVolumes(java.util.List,
     * java.lang.String)
     */
    @Override
    public void deleteVolumes(List<VolumeDescriptor> volumes, String taskId) throws ControllerException {
        List<URI> volUris = VolumeDescriptor.getVolumeURIs(volumes);
        VolumeWorkflowCompleter completer = new VolumeWorkflowCompleter(volUris, taskId);
        Workflow workflow = null;

        try {
            // Validate the volume identities before proceeding
            validator.volumeURIs(volUris, true, true, ValCk.ID, ValCk.VPLEX);

            // Generate the Workflow.
            workflow = _workflowService.getNewWorkflow(this,
                    DELETE_VOLUMES_WF_NAME, true, taskId);
            String waitFor = null; // the wait for key returned by previous call

            // Call the RPDeviceController to add its methods if there are RP protections.
            waitFor = _rpDeviceController.addStepsForDeleteVolumes(
                    workflow, waitFor, volumes, taskId);

            // Call the ReplicaDeviceController to add its methods if volumes are removed from,
            // and the CG associated with replication group(s)
            waitFor = _replicaDeviceController.addStepsForDeleteVolumes(
                    workflow, waitFor, volumes, taskId);

            // Call the VPlexDeviceController to add its methods if there are VPLEX volumes.
            waitFor = _vplexDeviceController.addStepsForDeleteVolumes(
                    workflow, waitFor, volumes, taskId);
            
            // Call the RPDeviceController to add its post-delete methods.
            waitFor = _rpDeviceController.addStepsForPostDeleteVolumes(
                    workflow, waitFor, volumes, taskId, completer, _blockDeviceController);

            // Call the SRDFDeviceController to add its methods if there are SRDF volumes.
            waitFor = _srdfDeviceController.addStepsForDeleteVolumes(
                    workflow, waitFor, volumes, taskId);

            // Next, call the BlockDeviceController to add its methods.
            waitFor = _blockDeviceController.addStepsForDeleteVolumes(
                    workflow, waitFor, volumes, taskId);

            // Next, call the BlockDeviceController to add post deletion methods.
            waitFor = _blockDeviceController.addStepsForPostDeleteVolumes(
                    workflow, waitFor, volumes, taskId, completer);

            // Call the VPlexDeviceController to add its post-delete methods.
            waitFor = _vplexDeviceController.addStepsForPostDeleteVolumes(
                    workflow, waitFor, volumes, taskId, completer);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = "Delete volumes successful for: " + volUris.toString();
            Object[] callbackArgs = new Object[] { volUris };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);
        } catch (Exception ex) {
            s_logger.error("Could not delete volumes: " + volUris, ex);
            releaseWorkflowLocks(workflow);
            String opName = ResourceOperationTypeEnum.DELETE_BLOCK_VOLUME.getName();
            ServiceError serviceError = DeviceControllerException.errors.deleteVolumesFailed(
                    volUris.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationController#expandVolume(java.net.URI, long,
     * java.lang.String)
     */
    @Override
    public void expandVolume(List<VolumeDescriptor> volumes, String taskId) throws ControllerException {
        List<URI> volUris = VolumeDescriptor.getVolumeURIs(volumes);
        VolumeWorkflowCompleter completer = new VolumeWorkflowCompleter(volUris, taskId);
        try {
            // Validate the volume identities before proceeding
            validator.volumeURIs(volUris, true, true, ValCk.ID, ValCk.VPLEX);
            
            // Generate the Workflow.
            Workflow workflow = _workflowService.getNewWorkflow(this,
                    EXPAND_VOLUMES_WF_NAME, true, taskId);
            String waitFor = null; // the wait for key returned by previous call

            // First, call the RP controller to add methods for RP CG delete
            waitFor = _rpDeviceController.addPreVolumeExpandSteps(
                    workflow, volumes, taskId);

            // Call the BlockDeviceController to add its methods if there are block or VPLEX backend volumes.
            waitFor = _blockDeviceController.addStepsForExpandVolume(
                    workflow, waitFor, volumes, taskId);

            // Call the SRDFDeviceController to add its methods for SRDF Source / SRDF Target volumes.
            waitFor = _srdfDeviceController.addStepsForExpandVolume(
                    workflow, waitFor, volumes, taskId);

            // Call the VPlexDeviceController to add its methods if there are VPLEX volumes.
            waitFor = _vplexDeviceController.addStepsForExpandVolume(
                    workflow, waitFor, volumes, taskId);

            // Call the RPDeviceController to add its methods for post volume expand ie. recreate RPCG
            waitFor = _rpDeviceController.addPostVolumeExpandSteps(
                    workflow, waitFor, volumes, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = "Expand volume successful for: " + volUris.toString();
            Object[] callbackArgs = new Object[] { new ArrayList<URI>(volUris) };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);
        } catch (Exception ex) {
            s_logger.error("Could not expand volume: " + volUris, toString(), ex);
            String opName = ResourceOperationTypeEnum.EXPAND_BLOCK_VOLUME.getName();
            ServiceError serviceError = DeviceControllerException.errors.expandVolumeFailed(volUris.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationController#restoreVolume(java.net.URI,
     * java.net.URI,
     * java.net.URI, java.net.URI, java.lang.String)
     */
    @Override
    public void restoreVolume(URI storage, URI pool, URI volume, URI snapshot, String syncDirection, String taskId)
            throws ControllerException {
        List<URI> volUris = Arrays.asList(volume);
        BlockSnapshotRestoreCompleter completer = new BlockSnapshotRestoreCompleter(snapshot, taskId);
        try {
            // Validate the volume identities before proceeding
            validator.volumeURIs(volUris, true, true, ValCk.ID, ValCk.VPLEX);
            
            // Generate the Workflow.
            Workflow workflow = _workflowService.getNewWorkflow(this,
                    RESTORE_VOLUME_FROM_SNAPSHOT_WF_NAME, true, taskId);
            String waitFor = null; // the wait for key returned by previous call

            // First, call the RP controller to add RP steps for volume restore from snapshot
            waitFor = _rpDeviceController.addPreRestoreVolumeSteps(
                    workflow, storage, volume, snapshot, taskId);

            // Call the VplexDeviceController to add its steps for restore volume from snapshot
            waitFor = _vplexDeviceController.addStepsForRestoreVolume(
                    workflow, waitFor, storage, pool, volume, snapshot, null, syncDirection, taskId, completer);

            // Call the BlockDeviceController to add its steps for restore volume from snapshot
            waitFor = _blockDeviceController.addStepsForRestoreVolume(
                    workflow, waitFor, storage, pool, volume, snapshot, Boolean.TRUE, syncDirection, taskId, completer);

            // Call the RPDeviceController to add its steps for post restore volume from snapshot
            waitFor = _rpDeviceController.addStepsForRestoreVolume(
                    workflow, waitFor, storage, pool, volume, snapshot, null, syncDirection, taskId, completer);

            // Call the RP controller to add RP post restore steps
            waitFor = _rpDeviceController.addPostRestoreVolumeSteps(
                    workflow, waitFor, storage, volume, snapshot, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = String.format("Restore of volume %s from %s completed successfully", volume, snapshot);
            Object[] callbackArgs = new Object[] { new ArrayList<URI>(volUris) };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);
        } catch (Exception ex) {
            s_logger.error("Could not restore volume: " + volUris.toString(), ex);
            String opName = ResourceOperationTypeEnum.RESTORE_VOLUME_SNAPSHOT.getName();
            ServiceError serviceError = DeviceControllerException.errors.restoreVolumeFromSnapshotFailed(volUris.toString(),
                    snapshot.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationController#changeVirtualPool(java.util.List,
     * java.lang.String)
     */
    @Override
    public void changeVirtualPool(List<VolumeDescriptor> volumes, String taskId)
            throws ControllerException {
        Map<URI, URI> volumeToOldVpoolsMap = VolumeDescriptor.createVolumeToOldVpoolMap(volumes);
        Map<URI, URI> volumeToNewVpoolsMap = VolumeDescriptor.createVolumeToNewVpoolMap(volumes);
        
        List<URI> volURIs = VolumeDescriptor.getVolumeURIs(volumes);
        List<URI> cgIds = null;
        List<URI> migrationURIs = new ArrayList<URI>();
        for (VolumeDescriptor desc : volumes) {
            URI migrationURI = desc.getMigrationId();
            if (!NullColumnValueGetter.isNullURI(migrationURI)) {
                migrationURIs.add(migrationURI);
            }
            cgIds = Volume.fetchCgIds(s_dbClient, volURIs);
        }

        VolumeVpoolChangeTaskCompleter completer = new VolumeVpoolChangeTaskCompleter(
                volURIs, migrationURIs, volumeToOldVpoolsMap, volumeToNewVpoolsMap, taskId);

        try {
            // Validate the volume identities before proceeding
            validator.volumeURIs(volURIs, true, true, ValCk.ID, ValCk.VPLEX);
            
            // Generate the Workflow.
            Workflow workflow = _workflowService.getNewWorkflow(this,
                    CHANGE_VPOOL_WF_NAME, true, taskId, completer);
            String waitFor = null; // the wait for key returned by previous call

            // Mainly for RP+VPLEX as a change vpool would require new volumes (source-journal, target(s),
            // target-journal) to be created.
            waitFor = _blockDeviceController.addStepsForCreateVolumes(
                    workflow, waitFor, volumes, taskId);

            // Call the VPlexDeviceController to add change virtual pool steps.
            waitFor = _vplexDeviceController.addStepsForChangeVirtualPool(
                    workflow, waitFor, volumes, taskId);

            // Last, call the RPDeviceController to add change virtual pool steps.
            waitFor = _rpDeviceController.addStepsForChangeVirtualPool(
                    workflow, waitFor, volumes, taskId);

            // This step is currently used to ensure that any existing resources get added to native
            // CGs. Mainly used for VPLEX->RP+VPLEX change vpool. The existing VPLEX volume would not be
            // in any CG and we now need its backing volume(s) to be added to their local array CG.
            waitFor = postRPChangeVpoolSteps(workflow, waitFor, volumes, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = "Change Virtual Pool suceeded for volumes: " + volURIs.toString();
            Object[] callbackArgs = new Object[] { volURIs };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);
        } catch (Exception ex) {
            s_logger.error("Could not change Virtual Pool for volumes: " + volURIs, ex);
            String opName = ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VPOOL.getName();
            ServiceError serviceError = DeviceControllerException.errors.changeVirtualPoolFailed(
                    volURIs.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

    @Override
    public void changeVirtualArray(List<VolumeDescriptor> volumeDescriptors, String taskId)
            throws ControllerException {

        // The descriptors that contain descriptor parameters
        // specifying the new target varray are the volumes being
        // moved to the new virtual array.
        List<URI> changeVArrayVolURIList = new ArrayList<URI>();
        List<URI> migrationURIs = new ArrayList<URI>();
        for (VolumeDescriptor volumeDescriptor : volumeDescriptors) {
            Map<String, Object> descrParams = volumeDescriptor.getParameters();
            if ((descrParams != null) && (!descrParams.isEmpty())) {
                changeVArrayVolURIList.add(volumeDescriptor.getVolumeURI());
            }
            URI migrationURI = volumeDescriptor.getMigrationId();
            if (!NullColumnValueGetter.isNullURI(migrationURI)) {
                migrationURIs.add(migrationURI);
            }
        }

        // Create a completer that will update the task status for these
        // volumes and associated migrations when the workflow completes.
        VolumeVarrayChangeTaskCompleter completer = new VolumeVarrayChangeTaskCompleter(
                VolumeDescriptor.getVolumeURIs(volumeDescriptors), migrationURIs, taskId);

        try {
            // Validate the volume identities before proceeding
            validator.volumeURIs(changeVArrayVolURIList, true, true, ValCk.ID, ValCk.VPLEX);
            
            // Generate the Workflow.
            String waitFor = null;
            Workflow workflow = _workflowService.getNewWorkflow(this,
                    CHANGE_VARRAY_WF_NAME, true, taskId);

            // First, call the BlockDeviceController to add its steps.
            // This will create the migration target volumes.
            waitFor = _blockDeviceController.addStepsForCreateVolumes(workflow, waitFor,
                    volumeDescriptors, taskId);

            // Then call the VPlexDeviceController to add change virtual array steps.
            waitFor = _vplexDeviceController.addStepsForChangeVirtualArray(workflow,
                    waitFor, volumeDescriptors, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = String.format(
                    "Change virtual array suceeded for volumes: %s", changeVArrayVolURIList);
            Object[] callbackArgs = new Object[] { changeVArrayVolURIList };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);
        } catch (Exception ex) {
            s_logger.error("Could not change virtual array for volumes: " + changeVArrayVolURIList, ex);
            String opName = ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VARRAY.getName();
            ServiceError serviceError = DeviceControllerException.errors
                    .changeVirtualArrayFailed(changeVArrayVolURIList.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

    /**
     * Needed to perform post change vpool operations on RP volumes.
     * 
     * @param workflow
     *            The current workflow
     * @param waitFor
     *            The previous operation to wait for
     * @param volumeDescriptors
     *            All the volume descriptors
     * @param taskId
     *            The current task id
     * @return The previous operation id
     */
    private String postRPChangeVpoolSteps(Workflow workflow, String waitFor,
            List<VolumeDescriptor> volumeDescriptors, String taskId) {
        // Get the list of descriptors needed for post change virtual pool operations on RP.
        List<VolumeDescriptor> rpVolumeDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                new VolumeDescriptor.Type[] {
                        VolumeDescriptor.Type.RP_EXISTING_SOURCE,
                }, null);

        // If no volume descriptors match, just return
        if (rpVolumeDescriptors.isEmpty()) {
            return waitFor;
        }

        List<VolumeDescriptor> migratedBlockDataDescriptors = new ArrayList<VolumeDescriptor>();

        // We could be performing a change vpool for RP+VPLEX / MetroPoint. This means
        // we could potentially have migrations that need to be done on the backend
        // volumes. If migration info exists we need to collect that ahead of time.
        List<URI> volumesWithMigration = new ArrayList<URI>();
        if (volumeDescriptors != null) {
            List<VolumeDescriptor> migrateDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                    new VolumeDescriptor.Type[] { VolumeDescriptor.Type.VPLEX_MIGRATE_VOLUME }, null);

            if (migrateDescriptors != null && !migrateDescriptors.isEmpty()) {
                s_logger.info("Data Migration detected, this is due to a change virtual pool operation on RP+VPLEX or MetroPoint.");
                // Load the migration objects for use later
                Iterator<VolumeDescriptor> migrationIter = migrateDescriptors.iterator();
                while (migrationIter.hasNext()) {
                    VolumeDescriptor migrationDesc = migrationIter.next();
                    Migration migration = s_dbClient.queryObject(Migration.class, migrationDesc.getMigrationId());
                    volumesWithMigration.add(migration.getSource());

                    Volume migratedVolume = s_dbClient.queryObject(Volume.class, migration.getVolume());
                    VolumeDescriptor migratedBlockDataDesc = new VolumeDescriptor(VolumeDescriptor.Type.BLOCK_DATA,
                            migratedVolume.getStorageController(), migratedVolume.getId(), null,
                            migratedVolume.getConsistencyGroup(), migrationDesc.getCapabilitiesValues());

                    migratedBlockDataDescriptors.add(migratedBlockDataDesc);
                }
            }
        }

        List<VolumeDescriptor> blockDataDescriptors = new ArrayList<VolumeDescriptor>();

        for (VolumeDescriptor descr : rpVolumeDescriptors) {
            // If there are RP_EXISTING_SOURCE volume descriptors, we need to ensure the
            // existing volumes are added to their native CGs for the change vpool request.
            // Before any existing resource can be protected by RP they have to be removed
            // from their existing CGs but now will need to be added to the new CG needed
            // for RecoverPoint protection.
            // NOTE: Only relevant for RP+VPLEX and MetroPoint. Regular RP does not enforce local
            // array CGs.
            Volume rpExistingSource = s_dbClient.queryObject(Volume.class, descr.getVolumeURI());

            // Check to see if the existing is not already protected by RP and that
            // there are associated volumes (meaning it's a VPLEX volume)
            if (RPHelper.isVPlexVolume(rpExistingSource, s_dbClient)) {
                s_logger.info(String.format("Adding post RP Change Vpool steps for existing VPLEX source volume [%s].",
                        rpExistingSource.getLabel()));
                // VPLEX, use associated backing volumes
                // NOTE: If migrations exist for this volume the VPLEX Device Controller will clean these up
                // newly added CGs because we won't need them as the migration volumes will create their own CGs.
                // This is OK.
                if (null != rpExistingSource.getAssociatedVolumes()) {
                    for (String assocVolumeId : rpExistingSource.getAssociatedVolumes()) {
                        Volume assocVolume = s_dbClient.queryObject(Volume.class, URI.create(assocVolumeId));

                        // If there is a migration for this backing volume, we don't have to
                        // do any extra steps for ensuring that this volume gets gets added to the backing array CG
                        // because the migration volume will trump this volume. This volume will eventually be
                        // deleted so let's skip it.
                        if (volumesWithMigration.contains(assocVolume.getId())) {
                            s_logger.info(String.format("Migration exists for [%s] so no need to add this volume to a backing array CG.",
                                    assocVolume.getLabel()));
                            continue;
                        }

                        // Only add the change vpool volume's backend volumes to the backend CGs if the
                        // getReplicationGroupInstance
                        // field has been populated during the API prepare volume steps.
                        if (NullColumnValueGetter.isNotNullValue(assocVolume.getReplicationGroupInstance())) {
                            // Create the BLOCK_DATA descriptor with the correct info
                            // for creating the CG and adding the backing volume to it.
                            VolumeDescriptor blockDataDesc = new VolumeDescriptor(VolumeDescriptor.Type.BLOCK_DATA,
                                    assocVolume.getStorageController(), assocVolume.getId(), null,
                                    rpExistingSource.getConsistencyGroup(), descr.getCapabilitiesValues());
                            blockDataDescriptors.add(blockDataDesc);

                            // Good time to update the backing volume with its new CG
                            assocVolume.setConsistencyGroup(rpExistingSource.getConsistencyGroup());
                            s_dbClient.updateObject(assocVolume);

                            s_logger.info(
                                    String.format("Backing volume [%s] needs to be added to CG [%s] on storage system [%s].",
                                            assocVolume.getLabel(), rpExistingSource.getConsistencyGroup(),
                                            assocVolume.getStorageController()));
                        }
                    }
                }
            }
        }

        if (!blockDataDescriptors.isEmpty()) {
            // Add a step to create the local array consistency group
            waitFor = _blockDeviceController.addStepsForCreateConsistencyGroup(workflow, waitFor, blockDataDescriptors,
                    "postRPChangeVpoolCreateCG");

            // Add a step to update the local array consistency group with the volumes to add
            waitFor = _blockDeviceController.addStepsForUpdateConsistencyGroup(workflow, waitFor, blockDataDescriptors, null);
        }

        // Consolidate all the block data descriptors to see if any replica steps are needed.
        blockDataDescriptors.addAll(migratedBlockDataDescriptors);
        s_logger.info("Checking for Replica steps");
        // Call the ReplicaDeviceController to add its methods if volumes are added to CG, and the CG associated with
        // replication
        // group(s)
        waitFor = _replicaDeviceController.addStepsForCreateVolumes(workflow, waitFor, blockDataDescriptors, taskId);

        return waitFor;
    }

    public WorkflowService getWorkflowService() {
        return _workflowService;
    }

    public void setWorkflowService(WorkflowService workflowService) {
        this._workflowService = workflowService;
    }

    public DbClient getDbClient() {
        return s_dbClient;
    }

    public void setDbClient(DbClient dbClient) {
        this.s_dbClient = dbClient;
    }

    public void setLocker(ControllerLockingService locker) {
        this._locker = locker;
    }

    public BlockDeviceController getBlockDeviceController() {
        return _blockDeviceController;
    }

    public void setBlockDeviceController(BlockDeviceController blockDeviceController) {
        this._blockDeviceController = blockDeviceController;
    }

    public static VPlexDeviceController getVplexDeviceController() {
        return _vplexDeviceController;
    }

    public static void setVplexDeviceController(VPlexDeviceController vplexDeviceController) {
        BlockOrchestrationDeviceController._vplexDeviceController = vplexDeviceController;
    }

    public static RPDeviceController getRpDeviceController() {
        return _rpDeviceController;
    }

    public static void setRpDeviceController(RPDeviceController rpDeviceController) {
        BlockOrchestrationDeviceController._rpDeviceController = rpDeviceController;
    }

    public static SRDFDeviceController getSrdfDeviceController() {
        return _srdfDeviceController;
    }

    public static void setSrdfDeviceController(SRDFDeviceController srdfDeviceController) {
        BlockOrchestrationDeviceController._srdfDeviceController = srdfDeviceController;
    }

    public static ReplicaDeviceController getReplicaDeviceController() {
        return BlockOrchestrationDeviceController._replicaDeviceController;
    }

    public static void setReplicaDeviceController(ReplicaDeviceController replicaDeviceController) {
        BlockOrchestrationDeviceController._replicaDeviceController = replicaDeviceController;
    }

    private void releaseWorkflowLocks(Workflow workflow) {
        if (workflow == null) {
            return;
        }
        s_logger.info("Releasing all workflow locks with owner: {}", workflow.getWorkflowURI());
        _workflowService.releaseAllWorkflowLocks(workflow);
    }

    @Override
    public void restoreFromFullCopy(URI storage, List<URI> fullCopyURIs, String taskId)
            throws InternalException {
        CloneRestoreCompleter completer = new CloneRestoreCompleter(fullCopyURIs, taskId);

        // add the CG to the completer if this is a CG restore
        Iterator<Volume> iter = getDbClient().queryIterativeObjects(Volume.class, fullCopyURIs);
        while (iter.hasNext()) {
            Volume fc = iter.next();
            if (!NullColumnValueGetter.isNullURI(fc.getAssociatedSourceVolume())) {
                BlockObject firstSource = BlockObject.fetch(getDbClient(), fc.getAssociatedSourceVolume());
                if (firstSource != null) {
                    if (firstSource instanceof Volume && !NullColumnValueGetter.isNullURI(firstSource.getConsistencyGroup())) {
                        completer.addConsistencyGroupId(firstSource.getConsistencyGroup());
                    }
                    break;
                }
            }
        }

        s_logger.info("Creating steps for restore from full copy.");
        try {
            // Validate the volume identities before proceeding
            validator.volumeURIs(fullCopyURIs, true, true, ValCk.ID, ValCk.VPLEX);
            
            // Generate the Workflow.
            Workflow workflow = _workflowService.getNewWorkflow(this,
                    RESTORE_FROM_FULLCOPY_WF_NAME, true, taskId);
            String waitFor = null; // the wait for key returned by previous call

            // First, call the RP controller to add RP steps for volume restore
            waitFor = _rpDeviceController.addPreRestoreFromFullcopySteps(
                    workflow, waitFor, storage, fullCopyURIs, taskId);

            // Call the VplexDeviceController to add its steps for restore volume from full copy
            waitFor = _vplexDeviceController.addStepsForRestoreFromFullcopy(
                    workflow, waitFor, storage, fullCopyURIs, taskId, completer);

            // Call the BlockDeviceController to add its steps for restore volume from full copy
            waitFor = _blockDeviceController.addStepsForRestoreFromFullcopy(workflow, waitFor, storage, fullCopyURIs, taskId, completer);

            // Call the RPDeviceController to add its steps for post restore volume from full copy
            waitFor = _rpDeviceController.addPostRestoreFromFullcopySteps(workflow, waitFor, storage, fullCopyURIs, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = "Restore from full copy completed successfully";
            Object[] callbackArgs = new Object[] { new ArrayList<URI>(fullCopyURIs) };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);
        } catch (Exception ex) {
            s_logger.error("Could not restore volume: ", ex);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

    public static ValidatorFactory getValidator() {
        return validator;
    }

    public static void setValidator(ValidatorFactory validator) {
        BlockOrchestrationDeviceController.validator = validator;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationController#createFullCopy(java.util.List,
     * java.lang.String)
     */
    @Override
    public void createFullCopy(List<VolumeDescriptor> volumeDescriptors, String taskId) throws InternalException {
        // The volume descriptors include the VPLEX source volume, which we do not want
        // to pass to the completer. In case of error constructing the WF, the completer
        // must mark all volumes prepared for this request inactive. However, we must not
        // mark the VPLEX source volume inactive!
        List<URI> volUris = new ArrayList<>();
        URI vplexSourceURI = null;
        for (VolumeDescriptor descriptor : volumeDescriptors) {
            if (descriptor.getParameters().get(VolumeDescriptor.PARAM_IS_COPY_SOURCE_ID) == null) {
                volUris.add(descriptor.getVolumeURI());
            } else {
                vplexSourceURI = descriptor.getVolumeURI();
            }
        }
        TaskCompleter completer = new CloneCreateWorkflowCompleter(volUris, taskId);
        
        Workflow workflow = null;
        List<VolumeDescriptor> blockVolmeDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_DATA, VolumeDescriptor.Type.VPLEX_IMPORT_VOLUME },
                new VolumeDescriptor.Type[] {});
        List<URI> blockVolUris = VolumeDescriptor.getVolumeURIs(blockVolmeDescriptors);

        // add all consistency groups to the completer
        Set<URI> cgIds = new HashSet<URI>();
        for (URI blockId : blockVolUris) {
            Volume fcVolume = getDbClient().queryObject(Volume.class, blockId);
            // need to check for a null associated source volume here because the list of full copy volume descriptors
            // includes
            // the HA side of a vplex distributed volume. By design, this volume is not a clone and so it won't have
            // associated
            // source volume set. The change was added here to reduce regression testing scope but really belongs in the
            // utility
            // method
            // Filed COP-23075 to move this check for null associated source volume to the utility method in x-wing
            if (fcVolume != null && !fcVolume.getInactive() && !NullColumnValueGetter.isNullURI(fcVolume.getAssociatedSourceVolume())) {
                BlockConsistencyGroup group = ConsistencyGroupUtils.getCloneConsistencyGroup(blockId, getDbClient());
                if (group != null) {
                    cgIds.add(group.getId());
                }
            }
        }
        for (URI cgId : cgIds) {
            completer.addConsistencyGroupId(cgId);
        }
        for (URI appId : ControllerUtils.getApplicationsForFullCopies(blockVolUris, getDbClient())) {
            completer.addVolumeGroupId(appId);
        }

        try {
            // For VPLEX full copies, validate the VPLEX source volume.
            if (vplexSourceURI != null) {
                validator.volumeURIs(Arrays.asList(vplexSourceURI), true, true, ValCk.ID, ValCk.VPLEX);
            }
            
            // Generate the Workflow.
            workflow = _workflowService.getNewWorkflow(this,
                    CREATE_FULL_COPIES_WF_NAME, false, taskId, completer);
            String waitFor = null; // the wait for key returned by previous call

            s_logger.info("Adding steps for RecoverPoint create full copy");
            // Call the RPDeviceController to add its methods if there are RP protections
            waitFor = _rpDeviceController.addStepsForPreCreateReplica(
                    workflow, waitFor, volumeDescriptors, taskId);

            s_logger.info("Adding steps for storage array create full copies");
            // First, call the BlockDeviceController to add its methods.
            waitFor = _blockDeviceController.addStepsForCreateFullCopy(
                    workflow, waitFor, volumeDescriptors, taskId);

            // post recoverpoint steps disables image access which should be done after the
            // create clone steps but before the vplex steps.
            s_logger.info("Adding steps for RecoverPoint post create full copy");
            // Call the RPDeviceController to add its methods if there are RP protections
            waitFor = _rpDeviceController.addStepsForPostCreateReplica(
                    workflow, waitFor, volumeDescriptors, taskId);

            s_logger.info("Checking for VPLEX steps");
            // Call the VPlexDeviceController to add its methods if there are VPLEX volumes.
            waitFor = _vplexDeviceController.addStepsForCreateFullCopy(
                    workflow, waitFor, volumeDescriptors, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = "Create volumes successful for: " + volUris.toString();
            Object[] callbackArgs = new Object[] { volUris };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);
        } catch (Exception ex) {
            s_logger.error("Could not create full copy volumes: " + volUris, ex);
            releaseWorkflowLocks(workflow);
            String opName = ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME.getName();
            ServiceError serviceError = DeviceControllerException.errors.createVolumesFailed(
                    volUris.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationController#createSnapshotSession(java.util.List,
     * java.lang.String)
     */
    @Override
    public void createSnapshotSession(List<VolumeDescriptor> volumeDescriptors, String taskId) throws InternalException {

        Workflow workflow = null;

        List<VolumeDescriptor> snapshotSessionDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_SNAPSHOT_SESSION },
                new VolumeDescriptor.Type[] {});
        List<URI> snapshotSessionURIs = VolumeDescriptor.getVolumeURIs(snapshotSessionDescriptors);

        // we expect just one snapshot session volume descriptor per create snapshot session operation
        TaskCompleter completer = new BlockSnapshotSessionCreateWorkflowCompleter(snapshotSessionURIs.get(0),
                snapshotSessionDescriptors.get(0).getSnapSessionSnapshotURIs(), taskId);
        ControllerUtils.checkSnapshotSessionConsistencyGroup(snapshotSessionURIs.get(0), getDbClient(), completer);

        try {
            // Generate the Workflow.
            workflow = _workflowService.getNewWorkflow(this,
                    CREATE_SNAPSHOT_SESSION_WF_NAME, false, taskId, completer);
            String waitFor = null; // the wait for key returned by previous call

            s_logger.info("Adding steps for RecoverPoint create snapshot session");
            // Call the RPDeviceController to add its methods if there are RP protections
            waitFor = _rpDeviceController.addStepsForPreCreateReplica(
                    workflow, waitFor, volumeDescriptors, taskId);

            s_logger.info("Adding steps for storage array create snapshot session");
            // First, call the BlockDeviceController to add its methods.
            waitFor = _blockDeviceController.addStepsForCreateSnapshotSession(
                    workflow, waitFor, volumeDescriptors, taskId);

            s_logger.info("Adding steps for RecoverPoint post create snapshot session");
            // Call the RPDeviceController to add its methods if there are RP protections
            waitFor = _rpDeviceController.addStepsForPostCreateReplica(
                    workflow, waitFor, volumeDescriptors, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = "Create volumes successful for: " + snapshotSessionURIs.toString();
            Object[] callbackArgs = new Object[] { snapshotSessionURIs };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);
        } catch (Exception ex) {
            s_logger.error("Could not create snapshot session: " + snapshotSessionURIs, ex);
            releaseWorkflowLocks(workflow);
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

}
