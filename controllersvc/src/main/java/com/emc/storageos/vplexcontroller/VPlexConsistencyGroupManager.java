/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.vplexcontroller;

import static com.emc.storageos.vplexcontroller.VPlexControllerUtils.getDataObject;
import static com.emc.storageos.vplexcontroller.VPlexControllerUtils.getVPlexAPIClient;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockConsistencyGroup.Types;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.util.BlockConsistencyGroupUtils;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.locking.LockTimeoutValue;
import com.emc.storageos.locking.LockType;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.svcs.errorhandling.resources.InternalServerErrorException;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCode;
import com.emc.storageos.util.VPlexSrdfUtil;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.ControllerLockingUtil;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.block.BlockDeviceController;
import com.emc.storageos.volumecontroller.impl.utils.ClusterConsistencyGroupWrapper;
import com.emc.storageos.vplex.api.VPlexApiClient;
import com.emc.storageos.vplex.api.VPlexApiException;
import com.emc.storageos.vplexcontroller.VPlexDeviceController.VPlexTaskCompleter;
import com.emc.storageos.workflow.Workflow;
import com.emc.storageos.workflow.WorkflowException;
import com.emc.storageos.workflow.WorkflowStepCompleter;

public class VPlexConsistencyGroupManager extends AbstractConsistencyGroupManager {

    private static final String ADD_VOLUMES_TO_CG_METHOD_NAME = "addVolumesToCG";
    private static final String REMOVE_VOLUMES_FROM_CG_METHOD_NAME = "removeVolumesFromCG";
    private static final String SET_CG_PROPERTIES_METHOD_NAME = "setCGProperties";

    private static final String SET_CG_PROPERTIES_STEP = "setCGProperties";
    private static final String UPDATE_LOCAL_CG_STEP = "updateLocalCG";

    // logger reference.
    private static final Logger log = LoggerFactory
            .getLogger(VPlexConsistencyGroupManager.class);

    @Override
    public String addStepsForCreateConsistencyGroup(Workflow workflow, String waitFor,
            StorageSystem vplexSystem, List<URI> vplexVolumeURIs,
            boolean willBeRemovedByEarlierStep) throws ControllerException {

        // No volumes, all done.
        if (vplexVolumeURIs.isEmpty()) {
            log.info("No volumes specified consistency group.");
            return waitFor;
        }

        // Grab the first volume
        Volume firstVolume = getDataObject(Volume.class, vplexVolumeURIs.get(0), dbClient);
        URI cgURI = firstVolume.getConsistencyGroup();

        if (cgURI == null) {
            log.info("No consistency group for volume creation.");
            return waitFor;
        }
        return addStepsForCreateConsistencyGroup(workflow, waitFor, vplexSystem, vplexVolumeURIs,
                willBeRemovedByEarlierStep, cgURI);

    }

    /**
     * Create consistency group and add volumes to it
     * 
     * @param workflow The workflow
     * @param waitFor The previous step that it needs to wait for
     * @param vplexSystem The vplex system
     * @param vplexVolumeURIs The vplex volumes to be added to the consistency group
     * @param willBeRemovedByEarlierStep if the consistency group could be removed by previous step
     * @param cgURI The consistency group URI
     * @return
     * @throws ControllerException
     */
    private String addStepsForCreateConsistencyGroup(Workflow workflow, String waitFor,
            StorageSystem vplexSystem, List<URI> vplexVolumeURIs,
            boolean willBeRemovedByEarlierStep, URI cgURI) throws ControllerException {

        // No volumes, all done.
        if (vplexVolumeURIs.isEmpty()) {
            log.info(String.format("No volumes specified to add to the consistency group %s", cgURI.toString()));
            return waitFor;
        }

        URI vplexURI = vplexSystem.getId();
        String nextStep = waitFor;
        BlockConsistencyGroup cg = null;

        // Load the CG.
        cg = getDataObject(BlockConsistencyGroup.class, cgURI, dbClient);

        // Get a list of the active VPLEX volumes associated to this CG.
        List<Volume> cgVPLEXVolumes = getActiveVPLEXVolumesForCG(cgURI);

        // Determine the list of volumes to be added to the CG. For straight VPLEX,
        // this is just the passed VPLEX volumes, for RP+VPLEX this will just be the
        // one volume that is currently associated to the CG.
        List<URI> volumeList = new ArrayList<URI>();
        volumeList.addAll(vplexVolumeURIs);

        // Check to see if the CG has been created on the VPlex already
        // or if the CG will be removed by an earlier step such that
        // when the workflow executes, the CG will no longer be on the
        // array.
        if ((!cg.created(vplexURI)) || (willBeRemovedByEarlierStep)) {
            // If the CG doesn't exist at all.
            log.info("Consistency group not created.");
            String stepId = workflow.createStepId();
            // Create a step to create the CG.
            nextStep = workflow.createStep(CREATE_CG_STEP,
                    String.format("VPLEX %s creating consistency group %s", vplexURI, cgURI),
                    nextStep, vplexURI, vplexSystem.getSystemType(), this.getClass(),
                    createCGMethod(vplexURI, cgURI, volumeList), rollbackCreateCGMethod(vplexURI, cgURI, stepId), stepId);
            log.info("Created step for consistency group creation.");
        } else {
            // See if the CG is created but contains no volumes.
            // That is there should be no volumes other than these
            // volumes we are trying to create and add to the CG.
            // If so, we need to make sure the visibility and storage
            // cluster info for the VPLEX CG is correct for these
            // volumes we are adding. It is the case this CG existed
            // previously for other volumes, but the volumes were
            // deleted and removed from the CG. The visibility and
            // cluster info would have been set for those volumes
            // and may not be appropriate for these volumes.
            if (cgVPLEXVolumes.size() == vplexVolumeURIs.size()) {
                // There are no volumes for the CG, other than these
                // we are adding, so we need to add a step to ensure
                // the visibility and cluster info for the CG is
                // correct.

                nextStep = workflow.createStep(SET_CG_PROPERTIES_STEP, String.format(
                        "Setting consistency group %s properties", cgURI), nextStep,
                        vplexURI, vplexSystem.getSystemType(), this.getClass(),
                        createSetCGPropertiesMethod(vplexURI, cgURI, volumeList),
                        rollbackMethodNullMethod(), null);
                log.info("Created step for setting consistency group properties.");
            }
        }
        // Create a step to add the volumes to the CG.
        nextStep = workflow.createStep(ADD_VOLUMES_TO_CG_STEP, String.format(
                "VPLEX %s adding volumes to consistency group %s", vplexURI, cgURI),
                nextStep, vplexURI, vplexSystem.getSystemType(), this.getClass(),
                createAddVolumesToCGMethod(vplexURI, cgURI, volumeList),
                createRemoveVolumesFromCGMethod(vplexURI, cgURI, volumeList), null);
        log.info(String.format("Created step for adding volumes to the consistency group %s", cgURI.toString()));

        return nextStep;
    }

    /**
     * Create the workflow method to rollback a CG creation on a VPLEX system.
     *
     * @param cgURI The consistency group URI
     * @param createStepId The step that created the CG.
     *
     * @return A reference to the workflow method
     */
    private Workflow.Method rollbackCreateCGMethod(URI vplexURI, URI cgURI, String createStepId) {
        return new Workflow.Method(RB_CREATE_CG_METHOD_NAME, vplexURI, cgURI, createStepId);
    }

    /**
     * Method call when we need to rollback the deletion of a consistency group.
     *
     * @param vplexSystemURI The URI of the VPlex system.
     * @param cgURI The consistency group URI
     * @param deleteStepId The step that deleted the CG.
     * @param stepId The step id.
     */
    public void rollbackCreateCG(URI vplexSystemURI, URI cgURI, String createStepId, String stepId) {
        try {
            // Update step state to executing.
            WorkflowStepCompleter.stepExecuting(stepId);
            log.info("Updated workflow step to executing");

            // Get the rollback data.
            Object rbDataObj = workflowService.loadStepData(createStepId);
            if (rbDataObj == null) {
                // Update step state to done.
                log.info("CG was not created, nothing to do.");
                cleanUpVplexCG(vplexSystemURI, cgURI, null, false);
                WorkflowStepCompleter.stepSucceded(stepId);
                return;
            }

            StorageSystem vplexSystem = getDataObject(StorageSystem.class,
                    vplexSystemURI, dbClient);
            // Get the CG.
            BlockConsistencyGroup cg = getDataObject(BlockConsistencyGroup.class, cgURI, dbClient);
            // Get the VPlex API client.
            VPlexApiClient client = getVPlexAPIClient(vplexApiFactory, vplexSystem,
                    dbClient);
            log.info("Got VPlex API client for VPlex system {}", vplexSystemURI);
            // We need to examine the association of VPlex systems to VPlex CGs that
            // have been created. We can't depend on the Volume's in the CG to determine
            // the VPlex systems and CG names because there may not be any volumes in the CG
            // at this point.
            if (BlockConsistencyGroupUtils.referencesVPlexCGs(cg, dbClient)) {
                for (StorageSystem storageSystem : BlockConsistencyGroupUtils.getVPlexStorageSystems(cg, dbClient)) {
                    URI vplexSystemUri = storageSystem.getId();

                    // Iterate over the VPlex consistency groups that need to be
                    // deleted.
                    Map<String, String> vplexCgsToDelete = new HashMap<String, String>();
                    for (String clusterCgName : cg.getSystemConsistencyGroups().get(vplexSystemUri.toString())) {
                        String cgName = BlockConsistencyGroupUtils.fetchCgName(clusterCgName);
                        String clusterName = BlockConsistencyGroupUtils.fetchClusterName(clusterCgName);

                        if (!vplexCgsToDelete.containsKey(cgName)) {
                            vplexCgsToDelete.put(cgName, clusterName);
                        }
                    }

                    for (Map.Entry<String, String> vplexCg : vplexCgsToDelete.entrySet()) {
                        String cgName = vplexCg.getKey();
                        // Make a call to the VPlex API client to delete the consistency group.
                        client.deleteConsistencyGroup(cgName);
                        log.info(String.format("Deleted consistency group %s", cgName));
                        cleanUpVplexCG(vplexSystemURI, cgURI, cgName, false);
                    }
                }
            }

            // Update step state to done.
            WorkflowStepCompleter.stepSucceded(stepId);
        } catch (VPlexApiException vae) {
            log.error("Exception rolling back VPLEX consistency group creation: " + vae.getMessage(), vae);
            WorkflowStepCompleter.stepFailed(stepId, vae);
        } catch (Exception ex) {
            log.error("Exception rolling back VPLEX consistency group creation: " + ex.getMessage(), ex);
            String opName = ResourceOperationTypeEnum.DELETE_CONSISTENCY_GROUP.getName();
            ServiceError serviceError = VPlexApiException.errors.deleteCGFailed(opName, ex);
            WorkflowStepCompleter.stepFailed(stepId, serviceError);
        }
    }

    /**
     * A method the creates the method to create a new VPLEX consistency group.
     * 
     * @param vplexURI The URI of the VPLEX storage system.
     * @param cgURI The URI of the consistency group to be created.
     * @param vplexVolumeURIs The URIs of the VPLEX volumes that will be used
     *            to create a VPlex consistency group.
     * 
     * @return A reference to the consistency group creation workflow method.
     */
    protected Workflow.Method createCGMethod(URI vplexURI, URI cgURI, List<URI> vplexVolumeURIs) {
        return new Workflow.Method(CREATE_CG_METHOD_NAME, vplexURI, cgURI, vplexVolumeURIs);
    }

    /**
     * Called by the workflow to create a new VPLEX consistency group.
     * 
     * @param vplexURI The URI of the VPLEX storage system.
     * @param cgURI The URI of the Bourne consistency group
     * @param vplexVolumeURIs The URI of the VPLEX used to determine the VPlex
     *            cluster/distributed information.
     * @param stepId The workflow step id.
     * 
     * @throws WorkflowException When an error occurs updating the workflow step
     *             state.
     */
    public void createCG(URI vplexURI, URI cgURI, List<URI> vplexVolumeURIs, String stepId)
            throws WorkflowException {
        try {
            // Update workflow step.
            WorkflowStepCompleter.stepExecuting(stepId);
            log.info("Updated step state for consistency group creation to execute.");

            // Lock the CG for the step duration.
            List<String> lockKeys = new ArrayList<String>();
            lockKeys.add(ControllerLockingUtil.getConsistencyGroupStorageKey(dbClient, cgURI, vplexURI));
            workflowService.acquireWorkflowStepLocks(stepId, lockKeys, LockTimeoutValue.get(LockType.RP_VPLEX_CG));

            // Get the API client.
            StorageSystem vplexSystem = getDataObject(StorageSystem.class, vplexURI, dbClient);
            VPlexApiClient client = getVPlexAPIClient(vplexApiFactory, vplexSystem, dbClient);
            log.info("Got VPLEX API client.");

            // Get the consistency group
            BlockConsistencyGroup cg = getDataObject(BlockConsistencyGroup.class, cgURI, dbClient);

            // Check to see if it was created since we defined the workflow.
            if (cg.created(vplexURI)) {
                StringSet cgNames = cg.getSystemConsistencyGroups().get(vplexURI.toString());
                log.info("Consistency group(s) already created: " + cgNames.toString());
                if (!cg.getTypes().contains(Types.VPLEX.name())) {
                    // SRDF will reset the CG types. If the CG was existing on VPLEX need to make sure it is in types.
                    cg.addConsistencyGroupTypes(Types.VPLEX.name());
                    dbClient.updateObject(cg);
                }
                WorkflowStepCompleter.stepSucceded(stepId);
                return;
            }

            // We need to know on what cluster to create the consistency group.
            // The cluster would be determined by the virtual array specified in
            // a volume creation request, which is the virtual array of the
            // passed virtual volumes. Get the virtual array for one of the
            // vplex volumes.
            Volume firstVPlexVolume = getDataObject(Volume.class, vplexVolumeURIs.get(0), dbClient);

            // Lets determine the VPlex consistency group that need to be created for this volume.
            ClusterConsistencyGroupWrapper clusterConsistencyGroup =
                    getClusterConsistencyGroup(firstVPlexVolume, cg);

            String cgName = clusterConsistencyGroup.getCgName();
            String clusterName = clusterConsistencyGroup.getClusterName();
            boolean isDistributed = clusterConsistencyGroup.isDistributed();

            URI vaURI = firstVPlexVolume.getVirtualArray();
            log.info("Got virtual array for VPLEX volume.");

            // Now we can create the consistency group.
            client.createConsistencyGroup(cgName, clusterName, isDistributed);
            log.info("Created VPLEX consistency group.");

            // Create the rollback data in case this needs to be deleted.
            VPlexCGRollbackData rbData = new VPlexCGRollbackData();
            rbData.setVplexSystemURI(vplexURI);
            rbData.setCgName(cgName);
            rbData.setClusterName(clusterName);
            rbData.setIsDistributed(new Boolean(getIsCGDistributed(client, cgName, clusterName)));
            workflowService.storeStepData(stepId, rbData);

            // Now update the CG in the DB.
            cg.setVirtualArray(vaURI);
            cg.setStorageController(vplexURI);
            cg.addSystemConsistencyGroup(vplexSystem.getId().toString(),
                    BlockConsistencyGroupUtils.buildClusterCgName(clusterName, cgName));
            cg.addConsistencyGroupTypes(Types.VPLEX.name());
            dbClient.persistObject(cg);
            log.info("Updated consistency group in DB.");

            // Update workflow step state to success.
            WorkflowStepCompleter.stepSucceded(stepId);
            log.info("Updated workflow step for consistency group creation to success.");
        } catch (VPlexApiException vex) {
            log.error("Exception creating consistency group: " + vex.getMessage(), vex);
            WorkflowStepCompleter.stepFailed(stepId, vex);
        } catch (Exception ex) {
            log.error("Exception creating consistency group: " + ex.getMessage(), ex);
            String opName = ResourceOperationTypeEnum.CREATE_CONSISTENCY_GROUP.getName();
            ServiceError serviceError = VPlexApiException.errors.createConsistencyGroupFailed(opName, ex);
            WorkflowStepCompleter.stepFailed(stepId, serviceError);
        }
    }

    
    
    /**
     * A method that creates the workflow method for adding VPLEX volumes to a
     * consistency group.
     * 
     * @param vplexURI The URI of the VPLEX storage system.
     * @param cgURI The URI of the consistency group.
     * @param vplexVolumeURIs The URIs of the volumes to be added to the
     *            consistency group.
     * 
     * @return A reference to the workflow method to add VPLEX volumes to a
     *         consistency group.
     */
    protected Workflow.Method createAddVolumesToCGMethod(URI vplexURI, URI cgURI,
            List<URI> vplexVolumeURIs) {
        return new Workflow.Method(ADD_VOLUMES_TO_CG_METHOD_NAME, vplexURI, cgURI,
                vplexVolumeURIs);
    }

    /**
     * The method called by the workflow to add VPLEX volumes to a VPLEX
     * consistency group.
     * 
     * @param vplexURI The URI of the VPLEX storage system.
     * @param cgURI The URI of the consistency group.
     * @param vplexVolumeURIs The URIs of the volumes to be added to the
     *            consistency group.
     * @param stepId The workflow step id.
     * 
     * @throws WorkflowException When an error occurs updating the workflow step
     *             state.
     */
    public void addVolumesToCG(URI vplexURI, URI cgURI, List<URI> vplexVolumeURIs,
            String stepId) throws WorkflowException {
        try {
            // Update workflow step.
            WorkflowStepCompleter.stepExecuting(stepId);
            log.info("Updated workflow step state to execute for add volumes to consistency group.");

            // Get the API client.
            StorageSystem vplexSystem = getDataObject(StorageSystem.class, vplexURI, dbClient);
            VPlexApiClient client = getVPlexAPIClient(vplexApiFactory, vplexSystem, dbClient);
            log.info("Got VPLEX API client.");

            Volume firstVPlexVolume = getDataObject(Volume.class, vplexVolumeURIs.get(0), dbClient);
            String cgName = getVplexCgName(firstVPlexVolume, cgURI);

            // Get the names of the volumes to be added.
            List<Volume> vplexVolumes = new ArrayList<Volume>();
            List<String> vplexVolumeNames = new ArrayList<String>();
            for (URI vplexVolumeURI : vplexVolumeURIs) {
                Volume vplexVolume = getDataObject(Volume.class, vplexVolumeURI, dbClient);
                vplexVolumes.add(vplexVolume);
                vplexVolumeNames.add(vplexVolume.getDeviceLabel());
                log.info("VPLEX volume:" + vplexVolume.getDeviceLabel());
            }
            log.info("Got VPLEX volume names.");

            long startTime = System.currentTimeMillis();
            // Add the volumes to the CG.
            client.addVolumesToConsistencyGroup(cgName, vplexVolumeNames);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info(String.format("TIMER: Adding %s virtual volume(s) %s to the consistency group %s took %f seconds",
                    vplexVolumeNames.size(), vplexVolumeNames, cgName, (double) elapsed / (double) 1000));

            // Make sure the volumes are updated. Necessary when
            // adding volumes to a CG after volume creation.
            for (Volume vplexVolume : vplexVolumes) {
                vplexVolume.setConsistencyGroup(cgURI);
                dbClient.updateObject(vplexVolume);
            }

            // Update workflow step state to success.
            WorkflowStepCompleter.stepSucceded(stepId);
            log.info("Updated workflow step state to success for add volumes to consistency group.");
        } catch (VPlexApiException vae) {
            log.error("Exception adding volumes to consistency group: " + vae.getMessage(), vae);
            WorkflowStepCompleter.stepFailed(stepId, vae);
        } catch (Exception ex) {
            log.error(
                    "Exception adding volumes to consistency group: " + ex.getMessage(), ex);
            ServiceError svcError = VPlexApiException.errors.jobFailed(ex);
            WorkflowStepCompleter.stepFailed(stepId, svcError);
        }
    }

    /**
     * A method the creates the method to set the properties for an existing
     * VPLEX consistency group with no volumes.
     * 
     * @param vplexURI The URI of the VPLEX storage system.
     * @param cgURI The URI of the consistency group to be created.
     * @param vplexVolumeURIs The URIs of the VPLEX volumes that will be added
     *            to the consistency group.
     * 
     * @return A reference to the consistency group creation workflow method.
     */
    private Workflow.Method createSetCGPropertiesMethod(URI vplexURI, URI cgURI,
            List<URI> vplexVolumeURIs) {
        return new Workflow.Method(SET_CG_PROPERTIES_METHOD_NAME, vplexURI, cgURI,
                vplexVolumeURIs);
    }

    /**
     * Called by the workflow to set the properties for an existing VPLEX
     * consistency group with no volumes.
     * 
     * @param vplexURI The URI of the VPLEX storage system.
     * @param cgURI The URI of the Bourne consistency group.
     * @param vplexVolumeURIs The URIs of the VPLEX volumes to be added to the
     *            consistency group.
     * @param stepId The workflow step id.
     * 
     * @throws WorkflowException When an error occurs updating the workflow step
     *             state.
     */
    public void setCGProperties(URI vplexURI, URI cgURI, List<URI> vplexVolumeURIs,
            String stepId) throws WorkflowException {
        try {
            // Update workflow step.
            WorkflowStepCompleter.stepExecuting(stepId);
            log.info("Updated step state for consistency group properties to execute.");

            // Lock the CG for the step duration.
            List<String> lockKeys = new ArrayList<String>();
            lockKeys.add(ControllerLockingUtil.getConsistencyGroupStorageKey(dbClient, cgURI, vplexURI));
            workflowService.acquireWorkflowStepLocks(stepId, lockKeys, LockTimeoutValue.get(LockType.RP_VPLEX_CG));

            // Get the API client.
            StorageSystem vplexSystem = getDataObject(StorageSystem.class, vplexURI, dbClient);
            VPlexApiClient client = getVPlexAPIClient(vplexApiFactory, vplexSystem, dbClient);
            log.info("Got VPLEX API client.");

            // Get the consistency group
            BlockConsistencyGroup cg = getDataObject(BlockConsistencyGroup.class, cgURI, dbClient);

            // We need to know on what cluster to find the consistency group.
            // The cluster would be determined by the virtual array specified in
            // a volume creation request, which is the virtual array of the
            // passed virtual volumes. Get the virtual array for one of the
            // vplex volumes.
            Volume firstVPlexVolume = getDataObject(Volume.class, vplexVolumeURIs.get(0), dbClient);

            ClusterConsistencyGroupWrapper clusterConsistencyGroup =
                    getClusterConsistencyGroup(firstVPlexVolume, cg);

            String cgName = clusterConsistencyGroup.getCgName();
            String clusterName = clusterConsistencyGroup.getClusterName();
            boolean isDistributed = clusterConsistencyGroup.isDistributed();

            // Now we can update the consistency group properties.
            client.updateConsistencyGroupProperties(cgName, clusterName, isDistributed);
            log.info("Updated VPLEX consistency group properties.");

            // Update workflow step state to success.
            WorkflowStepCompleter.stepSucceded(stepId);
            log.info("Updated workflow step for consistency group properties to success.");
        } catch (VPlexApiException vae) {
            log.error("Exception updating consistency group properties: " + vae.getMessage(), vae);
            WorkflowStepCompleter.stepFailed(stepId, vae);
        } catch (Exception ex) {
            log.error("Exception updating consistency group properties: " + ex.getMessage(), ex);
            ServiceError serviceError = VPlexApiException.errors.jobFailed(ex);
            WorkflowStepCompleter.stepFailed(stepId, serviceError);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateConsistencyGroup(Workflow workflow, URI vplexURI, URI cgURI,
            List<URI> addVolumesList, List<URI> removeVolumesList, String opId)
            throws InternalException {

        try {
            String waitFor = null;
            StorageSystem vplexSystem = getDataObject(StorageSystem.class, vplexURI, dbClient);
            BlockConsistencyGroup cg = getDataObject(BlockConsistencyGroup.class, cgURI, dbClient);

            // Lock the CG for the duration of update CG workflow.
            List<String> lockKeys = new ArrayList<String>();
            lockKeys.add(ControllerLockingUtil.getConsistencyGroupStorageKey(dbClient, cgURI, vplexURI));
            boolean acquiredLocks = workflowService.acquireWorkflowLocks(workflow, lockKeys, LockTimeoutValue.get(LockType.RP_VPLEX_CG));
            if (!acquiredLocks) {
                throw DeviceControllerException.exceptions.failedToAcquireLock(lockKeys.toString(),
                        "UpdateConsistencyGroup: " + cg.getLabel());
            }

            // The addVolumesList could be full copies or volumes.
            boolean isFullCopy = false;
            if (addVolumesList != null && !addVolumesList.isEmpty()) {
                URI volURI = addVolumesList.get(0);
                Volume vol = getDataObject(Volume.class, volURI, dbClient);
                isFullCopy = ControllerUtils.isVolumeFullCopy(vol, dbClient);
            }
            // Users could use updateConsistencyGroup operation to add backend CGs for ingested CGs.
            // if that's the case, we will only add the backend CGs, but not add those virtual volumes to
            // the VPlex CG.
            boolean isIngestedCG = isAddingBackendCGForIngestedCG(cg, addVolumesList);

            // Check if the CG has been created in VPlex yet
            boolean isNewCg = !cg.created();
            // If necessary, create a step to update the local CGs.
            if (cg.getTypes().contains(Types.LOCAL.toString()) || isIngestedCG || isNewCg) {
                // We need to determine the backend systems that own the local CGs and the
                // volumes to be added/removed from each. There should really only be either
                // one of two backend systems depending upon whether or not the volumes are
                // local or distributed. In addition, when volumes are being both added and
                // removed, the maps should contains the same key set so it doesn't matter
                // which is used.
                Map<URI, List<URI>> localAddVolumesMap = getLocalVolumesForUpdate(addVolumesList);
                Map<URI, List<URI>> localRemoveVolumesMap = getLocalVolumesForRemove(removeVolumesList);
                Set<URI> localSystems = localAddVolumesMap.keySet();
                if (localSystems.isEmpty()) {
                    localSystems = localRemoveVolumesMap.keySet();
                }

                // Now we need to iterate over the backend systems and create a step to
                // update the corresponding consistency groups on the backend arrays.
                Iterator<URI> localSystemIter = localSystems.iterator();
                while (localSystemIter.hasNext()) {
                    URI localSystemURI = localSystemIter.next();
                    StorageSystem localSystem = getDataObject(StorageSystem.class, localSystemURI, dbClient);
                    List<URI> localAddVolumesList = localAddVolumesMap.get(localSystemURI);
                    List<URI> localRemoveVolumesList = localRemoveVolumesMap.get(localSystemURI);
                    Workflow.Method updateLocalMethod = new Workflow.Method(
                            UPDATE_CONSISTENCY_GROUP_METHOD_NAME, localSystemURI, cgURI,
                            localAddVolumesList, localRemoveVolumesList);
                    Workflow.Method rollbackLocalMethod = new Workflow.Method(
                            UPDATE_CONSISTENCY_GROUP_METHOD_NAME, localSystemURI, cgURI,
                            localRemoveVolumesList, localAddVolumesList);
                    workflow.createStep(UPDATE_LOCAL_CG_STEP, String.format(
                            "Updating consistency group %s on system %s",
                            cgURI, localSystemURI), null,
                            localSystemURI, localSystem.getSystemType(),
                            BlockDeviceController.class, updateLocalMethod,
                            rollbackLocalMethod, null);
                }
                if (!localSystems.isEmpty()) {
                    waitFor = UPDATE_LOCAL_CG_STEP;
                    log.info("Created steps to remove volumes from native consistency groups.");
                }
            }

            // First remove any volumes to be removed.
            int removeVolumeCount = 0;
            if ((removeVolumesList != null) && !removeVolumesList.isEmpty()) {
                removeVolumeCount = removeVolumesList.size();
                addStepForRemoveVolumesFromCG(workflow, waitFor, vplexSystem,
                        removeVolumesList, cgURI);
            }

            // Now create a step to add volumes to the CG.
            if ((addVolumesList != null) && !addVolumesList.isEmpty() && !isIngestedCG && !isNewCg && !isFullCopy) {
                // See if the CG contains no volumes. If so, we need to
                // make sure the visibility and storage cluster info for
                // the VPLEX CG is correct for these volumes we are adding.
                // It is the case this CG existed previously for other
                // volumes, but the volumes were deleted and removed from
                // the CG. The visibility and cluster info would have been
                // set for those volumes and may not be appropriate for these
                // volumes. It could also be that this request removes all
                // the existing volumes and the volumes being added have
                // different property requirements.
                List<Volume> cgVPLEXVolumes = getActiveVPLEXVolumesForCG(cgURI);
                if ((cgVPLEXVolumes.isEmpty()) || (cgVPLEXVolumes.size() == removeVolumeCount)) {
                    Workflow.Method setPropsMethod = createSetCGPropertiesMethod(vplexURI,
                            cgURI, addVolumesList);
                    // We only need to reset the properties if it's empty because
                    // we just removed all the volumes. The properties are reset
                    // back to those appropriate for the removed volumes before
                    // they get added back in.
                    Workflow.Method rollbackSetPropsMethod =
                            (removeVolumesList != null && !removeVolumesList.isEmpty()) ?
                                    createSetCGPropertiesMethod(vplexURI, cgURI, removeVolumesList) :
                                    rollbackMethodNullMethod();
                    waitFor = workflow.createStep(SET_CG_PROPERTIES_STEP, String.format(
                            "Setting consistency group %s properties", cgURI), waitFor,
                            vplexURI, vplexSystem.getSystemType(), this.getClass(),
                            setPropsMethod, rollbackSetPropsMethod, null);
                    log.info("Created step for setting consistency group properties.");
                }

                // Now create a step to add the volumes.
                Workflow.Method addMethod = createAddVolumesToCGMethod(vplexURI, cgURI,
                        addVolumesList);
                workflow.createStep(ADD_VOLUMES_TO_CG_STEP, String.format(
                        "VPLEX %s adding volumes to consistency group %s", vplexURI, cgURI),
                        waitFor, vplexURI, vplexSystem.getSystemType(), this.getClass(),
                        addMethod, rollbackMethodNullMethod(), null);
                log.info("Created step for add volumes to consistency group.");
            } else if (isNewCg && addVolumesList != null && !addVolumesList.isEmpty() && !isFullCopy) {
                addStepsForCreateConsistencyGroup(workflow, waitFor, vplexSystem, addVolumesList, false, cgURI);
            }

            TaskCompleter completer = new VPlexTaskCompleter(BlockConsistencyGroup.class,
                    Arrays.asList(cgURI), opId, null);
            log.info("Executing workflow plan");
            workflow.executePlan(completer, String.format(
                    "Update of consistency group %s completed successfully", cgURI));
            log.info("Workflow plan executed");
        } catch (Exception e) {
            String failMsg = String.format("Update of consistency group %s failed",
                    cgURI);
            log.error(failMsg, e);
            // Release the locks 
            workflowService.releaseAllWorkflowLocks(workflow);
            TaskCompleter completer = new VPlexTaskCompleter(BlockConsistencyGroup.class,
                    Arrays.asList(cgURI), opId, null);
            String opName = ResourceOperationTypeEnum.UPDATE_CONSISTENCY_GROUP.getName();
            ServiceError serviceError = VPlexApiException.errors.updateConsistencyGroupFailed(
                    cgURI.toString(), opName, e);
            completer.error(dbClient, serviceError);
        }
    }

    /**
     * Maps a VPlex cluster/consistency group to its volumes.
     * 
     * @param vplexVolume The virtual volume from which to obtain the VPlex cluster.
     * @param clusterConsistencyGroupVolumes The map to store cluster/cg/volume relationships.
     * @param cgName The VPlex consistency group name.
     * @throws Exception
     */
    @Override
    public ClusterConsistencyGroupWrapper getClusterConsistencyGroup(Volume vplexVolume, BlockConsistencyGroup cg) throws Exception {
        ClusterConsistencyGroupWrapper clusterConsistencyGroup = new ClusterConsistencyGroupWrapper();

        // If there are no associated volumes, we cannot determine the cluster name and if the
        // volume is distributed or not. This is typical in VPlex ingested volume cases. So for
        // these cases, we just set the cgName value only.
        if (vplexVolume.getAssociatedVolumes() != null && !vplexVolume.getAssociatedVolumes().isEmpty()) {
            String clusterName = VPlexControllerUtils.getVPlexClusterName(dbClient, vplexVolume);
            StringSet assocVolumes = vplexVolume.getAssociatedVolumes();
            boolean distributed = false;

            if (assocVolumes.size() > 1) {
                distributed = true;
            }

            clusterConsistencyGroup.setClusterName(clusterName);
            clusterConsistencyGroup.setDistributed(distributed);
        }

        clusterConsistencyGroup.setCgName(cg.getLabel());

        return clusterConsistencyGroup;
    }

    /**
     * Gets the active VPLEX volumes in the CG.
     * 
     * @param cgURI The consistency group URI
     * 
     * @return A list of the active VPLEX volumes in the CG.
     */
    private List<Volume> getActiveVPLEXVolumesForCG(URI cgURI) {
        List<Volume> cgVPLEXVolumes = new ArrayList<Volume>();
        List<Volume> cgVolumes = CustomQueryUtility.queryActiveResourcesByConstraint(
                dbClient, Volume.class, ContainmentConstraint.Factory.
                        getVolumesByConsistencyGroup(cgURI));
        for (Volume cgVolume : cgVolumes) {
            if (!Volume.checkForVplexBackEndVolume(dbClient, cgVolume)) {
                cgVPLEXVolumes.add(cgVolume);
            }
        }
        return cgVPLEXVolumes;
    }

    /**
     * Create a map of the backend volumes for the passed VPLEX volumes key'd by
     * the backend systems. Called during a consistency group update so that
     * the corresponding backend consistency groups can be updated.
     * 
     * @param vplexVolumes A list of VPLEX volumes.
     * 
     * @return A map of the backend volumes for the passed VPLEX volumes key'd
     *         by the backend systems.
     */
    private Map<URI, List<URI>> getLocalVolumesForUpdate(List<URI> vplexVolumes) {
        Map<URI, List<URI>> localVolumesMap = new HashMap<URI, List<URI>>();
        if ((vplexVolumes != null) && (!vplexVolumes.isEmpty())) {
            for (URI vplexVolumeURI : vplexVolumes) {
                Volume vplexVolume = getDataObject(Volume.class, vplexVolumeURI, dbClient);
                StringSet associatedVolumes = vplexVolume.getAssociatedVolumes();
                if (null == associatedVolumes || associatedVolumes.isEmpty()) {
                    log.warn("VPLEX volume {} has no backend volumes.", vplexVolume.forDisplay());
                } else {
                    for (String assocVolumeId : associatedVolumes) {
                        URI assocVolumeURI = URI.create(assocVolumeId);
                        Volume assocVolume = getDataObject(Volume.class, assocVolumeURI, dbClient);
                        URI assocSystemURI = assocVolume.getStorageController();
                        if (!localVolumesMap.containsKey(assocSystemURI)) {
                            List<URI> systemVolumes = new ArrayList<URI>();
                            localVolumesMap.put(assocSystemURI, systemVolumes);
                        }
                        localVolumesMap.get(assocSystemURI).add(assocVolumeURI);
                    }
                }
            }
        }
        return localVolumesMap;
    }

    /**
     * Adds a step to the passed workflow to remove the passed volumes from the
     * consistency group with the passed URI.
     * 
     * @param workflow The workflow to which the step is added
     * @param waitFor The step for which this step should wait.
     * @param vplexSystem The VPLEX system
     * @param volumes The volumes to be removed
     * @param cgURI The URI of the consistency group
     * 
     * @return The step id of the added step.
     */
    public String addStepForRemoveVolumesFromCG(Workflow workflow, String waitFor,
            StorageSystem vplexSystem, List<URI> volumes, URI cgURI) {
        URI vplexURI = vplexSystem.getId();
        Workflow.Method removeMethod = createRemoveVolumesFromCGMethod(vplexURI, cgURI, volumes);
        Workflow.Method removeRollbackMethod = createAddVolumesToCGMethod(vplexURI, cgURI, volumes);
        waitFor = workflow.createStep(REMOVE_VOLUMES_FROM_CG_STEP, String.format(
                "Removing volumes %s from consistency group %s on VPLEX %s", volumes, cgURI,
                vplexURI), waitFor, vplexURI, vplexSystem.getSystemType(), this.getClass(),
                removeMethod, removeRollbackMethod, null);
        log.info("Created step for remove volumes from consistency group.");
        return waitFor;
    }

    /**
     * Check if update consistency group operation is for adding back end consistency groups for ingested CG.
     * 
     * @param cg
     * @param addVolumesList
     * @return true or false
     */
    private boolean isAddingBackendCGForIngestedCG(BlockConsistencyGroup cg, List<URI> addVolumesList) {
        boolean result = false;
        if (cg.getTypes().contains(Types.LOCAL.toString())) {
            // Not ingested CG
            return result;
        }
        List<Volume> cgVolumes = BlockConsistencyGroupUtils.getActiveVplexVolumesInCG(cg, dbClient, null);
        Set<String> cgVolumeURIs = new HashSet<String>();
        for (Volume cgVolume : cgVolumes) {
            cgVolumeURIs.add(cgVolume.getId().toString());
        }
        if (!addVolumesList.isEmpty() && cgVolumeURIs.contains(addVolumesList.get(0).toString())) {
            result = true;
        }
        return result;
    }
    
    
    /**
     * Create a map of the backend volumes that need to remove from backend CG.
     * Called during a consistency group update so that the corresponding backend 
     * consistency groups can be updated.
     * 
     * @param vplexVolumes A list of VPLEX volumes.
     * 
     * @return A map of the backend volumes for the passed VPLEX volumes key'd
     *         by the backend systems.
     */
    private Map<URI, List<URI>> getLocalVolumesForRemove(List<URI> vplexVolumes) {
        Map<URI, List<URI>> localVolumesMap = new HashMap<URI, List<URI>>();
        if ((vplexVolumes != null) && (!vplexVolumes.isEmpty())) {
            for (URI vplexVolumeURI : vplexVolumes) {
                Volume vplexVolume = getDataObject(Volume.class, vplexVolumeURI, dbClient);
                StringSet associatedVolumes = vplexVolume.getAssociatedVolumes();
                if (null == associatedVolumes || associatedVolumes.isEmpty()) {
                    log.warn("VPLEX volume {} has no backend volumes.", vplexVolume.forDisplay());
                } else {
                    for (String assocVolumeId : associatedVolumes) {
                        URI assocVolumeURI = URI.create(assocVolumeId);
                        Volume assocVolume = getDataObject(Volume.class, assocVolumeURI, dbClient);
                        if (NullColumnValueGetter.isNotNullValue(assocVolume.getReplicationGroupInstance())) { 
                            // The backend volume is in a backend CG
                            URI assocSystemURI = assocVolume.getStorageController();
                            if (!localVolumesMap.containsKey(assocSystemURI)) {
                                List<URI> systemVolumes = new ArrayList<URI>();
                                localVolumesMap.put(assocSystemURI, systemVolumes);
                            }
                            localVolumesMap.get(assocSystemURI).add(assocVolumeURI);
                        }
                    }
                }
            }
        }
        return localVolumesMap;
    }
    
    @Override
    public String addStepsForAddingVolumesToSRDFTargetCG(Workflow workflow, StorageSystem vplexSystem,
            List<URI> vplexVolumeURIs, String argWaitFor) {
        String waitFor = argWaitFor;    // to fix Sonar
        StringBuilder volumeList = new StringBuilder();
        for (URI vplexVolumeURI : vplexVolumeURIs) {
            Volume volume = dbClient.queryObject(Volume.class, vplexVolumeURI);
            if (volumeList.length() != 0) {
                volumeList.append(", ");
            }
            volumeList.append(volume.getLabel());
        }
        Workflow.Method executeMethod = addVplexVolumesToSRDFTagetCGMethod(vplexSystem.getId(), vplexVolumeURIs);
        Workflow.Method rollbackMethod = removeVplexVolumesFromSRDFTargetCGMethod(vplexSystem.getId(), vplexVolumeURIs);
        waitFor = workflow.createStep(null, 
                "Add VplexVolumes to Target CG: " + volumeList.toString(), waitFor, vplexSystem.getId(), 
                vplexSystem.getSystemType(), this.getClass(), executeMethod, rollbackMethod, null);
        
        // Add a step to set the read-only flag to true (on rollback, revert to false)
        waitFor = addStepForUpdateConsistencyGroupReadOnlyState(workflow, vplexVolumeURIs, true, 
                "Set Target CG read-only flag: " + volumeList.toString(), waitFor);
        return waitFor;
    }
    
    public Workflow.Method addVplexVolumesToSRDFTagetCGMethod(URI vplexURI, List<URI> vplexVolumeURIs) {
        return new Workflow.Method("addVplexVolumesToSRDFTargetCG", vplexURI, vplexVolumeURIs);
    }
    
    /**
     * This workflow step will add vplex volumes fronting an SRDF target volume to the SRDF target consistency group.
     * Note that the SRDF target consistency group may not be created until the SRDF Pairing operation has completed.
     * At this time the underlying SRDF Targets will be associated with the correct CG. For this reason, we don't know
     * what consistency group to associate the Vplex volumes with until the SRDF pairing operation has completed, and
     * the workflow is executing.
     * 
     * So here we find the appropriate SRDF target volume(s), determine the CG, and then set up code to
     * create the Vplex CG and add the volume to that.
     * 
     * @param vplexURI -- URI of VPlex system
     * @param vplexVolumeURIs -- List of VPLEX Volume URIs fronting SRDF Target volumes
     * @param stepId -- The step ID being processed
     * @throws WorkflowException
     */
    public void addVplexVolumesToSRDFTargetCG(URI vplexURI, List<URI> vplexVolumeURIs, String stepId)
            throws WorkflowException {
        try {
            // Make a map of the VPlex volume to corresponding SRDF volume
            Map<Volume, Volume> vplexToSrdfVolumeMap = VPlexSrdfUtil.makeVplexToSrdfVolumeMap(dbClient, vplexVolumeURIs);
            // Make sure that the SRDF volumes have a consistency group and it is the same.
            URI cgURI = null;
            for (Volume srdfVolume : vplexToSrdfVolumeMap.values()) {
                if (srdfVolume.getConsistencyGroup() != null) {
                    if (cgURI == null) {
                        cgURI = srdfVolume.getConsistencyGroup();
                    } else {
                        if (srdfVolume.getConsistencyGroup() != cgURI) {
                            log.info("Multiple CGs discovered: " + cgURI.toString() + " " + srdfVolume.getConsistencyGroup().toString());
                        }
                    }
                }
            }

            // If there is no consistency group, That is not an error.
            if (cgURI == null) {
                WorkflowStepCompleter.stepSucceded(stepId);
                return;
            }

            // Get the consistency group, and make sure it has requested type Vplex. Change the VPlex volumes to point to the CG.
            BlockConsistencyGroup consistencyGroup = dbClient.queryObject(BlockConsistencyGroup.class, cgURI);
            if (!consistencyGroup.getRequestedTypes().contains(Types.VPLEX)) {
                consistencyGroup.addRequestedTypes(Arrays.asList(Types.VPLEX.name()));
                dbClient.updateObject(consistencyGroup);
            }

            Volume protoVolume = null;
            for (Volume vplexVolume : vplexToSrdfVolumeMap.keySet()) {
                protoVolume = vplexVolume;
                break;
            }
            StorageSystem vplexSystem = dbClient.queryObject(StorageSystem.class, protoVolume.getStorageController());

            // Create the consistency group if it does not already exist. If there is an error, the step is completed
            // and we return.
            if (createVplexCG(vplexSystem, consistencyGroup, protoVolume, stepId) == false) {
                return;
            }

            // Add the Vplex volumes to the CG, and fire off the Step completer.
            addVolumesToCG(vplexSystem, consistencyGroup, vplexToSrdfVolumeMap.keySet(), stepId);
            
        } catch (Exception ex) {
            log.info("Exception adding Vplex volumes to SRDF Target CG: " + ex.getMessage(), ex);
            ServiceError svcError = VPlexApiException.errors.jobFailed(ex);
            WorkflowStepCompleter.stepFailed(stepId, svcError);
        }
    }

    /**
     * Adds steps for remove volumes from the SRDF Target CG.
     * @param workflow -- Workflow steps are to be added to
     * @param vplexSystem -- VPlex system being provisioned
     * @param vplexVolumeURIs -- List of volume URIs to be removed from CG
     * @param waitFor -- Step or step group identifier of previous step(s) to be waited on
     * @return waitFor -- step subsequent steps should wait on
     */
    public String addStepsForRemovingVolumesFromSRDFTargetCG(Workflow workflow, StorageSystem vplexSystem,
            List<URI> vplexVolumeURIs, String waitFor) {
        StringBuilder volumeList = new StringBuilder();
        if (vplexVolumeURIs.isEmpty()) {
            return waitFor;
        }
        for (URI vplexVolumeURI : vplexVolumeURIs) {
            Volume volume = dbClient.queryObject(Volume.class, vplexVolumeURI);
            if (volumeList.length() != 0) {
                volumeList.append(", ");
            }
            volumeList.append(volume.getLabel());
        }
        Workflow.Method executeMethod = removeVplexVolumesFromSRDFTargetCGMethod(vplexSystem.getId(), vplexVolumeURIs);
        Workflow.Method rollbackMethod = removeVplexVolumesFromSRDFTargetCGMethod(vplexSystem.getId(), vplexVolumeURIs);
        waitFor = workflow.createStep(null, 
                "Remove VplexVolumes from Target CG: " + volumeList.toString(), waitFor, vplexSystem.getId(), 
                vplexSystem.getSystemType(), this.getClass(), executeMethod, rollbackMethod, null);
        return waitFor;
    }
    
    /**
     * Returns a workflow method for removing Vplex Volumes form the SRDF Target CG.
     * This is used for rolling back addVplexVolumesToSRDFTargetCG.
     * @param vplexURI
     * @param vplexVolumeURIs
     * @return
     */
    Workflow.Method removeVplexVolumesFromSRDFTargetCGMethod(URI vplexURI, List<URI> vplexVolumeURIs) {
        return new Workflow.Method("removeVplexVolumesFromSRDFTargetCG", vplexURI, vplexVolumeURIs);
    }
    
    
    /**
     * Removes volumes from SRDF Target.
     * @param vplexURI
     * @param vplexVolumeURIs
     * @param stepId
     * @throws WorkflowException
     */
    public  void removeVplexVolumesFromSRDFTargetCG(URI vplexURI, List<URI> vplexVolumeURIs, String stepId)
            throws WorkflowException {
        try {
            WorkflowStepCompleter.stepExecuting(stepId);

            Volume protoVolume = dbClient.queryObject(Volume.class, vplexVolumeURIs.get(0));
            if (NullColumnValueGetter.isNullURI(protoVolume.getConsistencyGroup())) {
                WorkflowStepCompleter.stepSucceded(stepId);
                return;
            }
            BlockConsistencyGroup consistencyGroup = dbClient.queryObject(BlockConsistencyGroup.class, 
                    protoVolume.getConsistencyGroup());
            if (consistencyGroup == null) {
                WorkflowStepCompleter.stepSucceded(stepId);
                return;
            }
            
            // Remove the volumes from the ConsistencyGroup. Returns a codedError if failure.
            ServiceCoded codedError = removeVolumesFromCGInternal(vplexURI, protoVolume.getConsistencyGroup(), vplexVolumeURIs);
            if (codedError != null) {
                WorkflowStepCompleter.stepFailed(stepId, codedError);
                return;
            }

            // Determine if there are any remaining Vplex volumes in the consistency group.
            List<Volume> vplexVolumesInCG = 
                    BlockConsistencyGroupUtils.getActiveVplexVolumesInCG(consistencyGroup, dbClient, null);
            if (vplexVolumesInCG.isEmpty()) {
                ClusterConsistencyGroupWrapper clusterCGWrapper = 
                        getClusterConsistencyGroup(protoVolume, consistencyGroup);
                // No vplex volumes left, clean up the Vplex part of the consistency group.
                // deleteCG will call the step completer.
                deleteCG(vplexURI, consistencyGroup.getId(), clusterCGWrapper.getCgName(), 
                        clusterCGWrapper.getClusterName(), false, stepId);
            } else {
                // Vplex volumes left... we're finished.
                WorkflowStepCompleter.stepSucceded(stepId);
            }
        } catch (Exception ex) {
            log.info("Exception removing Vplex volumes from SRDF Target CG: " + ex.getMessage(), ex);
            ServiceError svcError = VPlexApiException.errors.jobFailed(ex);
            WorkflowStepCompleter.stepFailed(stepId, svcError);
        }
    }
    
    /**
     * Create a Vplex CG within a step. This routine does not complete the step unless there is an error (and it returns false).
     * @param vplexSystem -- StorageSystem of the VPLEX
     * @param cg -- BlockConsistencyGroup object
     * @param protoVolume -- A prototypical Vplex volume
     * @param stepId -- String stepId, completed only if error
     * @return true if no error, false if error
     */
    private boolean createVplexCG(StorageSystem vplexSystem, BlockConsistencyGroup cg, Volume protoVolume, String stepId) {
        try {
            VPlexApiClient client  = getVPlexAPIClient(vplexApiFactory, vplexSystem, dbClient);

            log.info("Got VPLEX API client.");

            // Check to see if it was created since we defined the workflow.
            if (cg.created(vplexSystem.getId())) {
                StringSet cgNames = cg.getSystemConsistencyGroups().get(vplexSystem.getId().toString());
                log.info("Consistency group(s) already created: " + cgNames.toString());
                return true;
            }

            // We need to know on what cluster to create the consistency group.
            // The cluster would be determined by the virtual array specified in
            // a volume creation request, which is the virtual array of the
            // passed virtual volumes. Get the virtual array for one of the
            // vplex volumes.
            // Lets determine the VPlex consistency group that need to be created for this volume.
            ClusterConsistencyGroupWrapper clusterConsistencyGroup =
                    getClusterConsistencyGroup(protoVolume, cg);

            String cgName = clusterConsistencyGroup.getCgName();
            String clusterName = clusterConsistencyGroup.getClusterName();
            boolean isDistributed = clusterConsistencyGroup.isDistributed();

            URI vaURI = protoVolume.getVirtualArray();
            log.info("Got virtual array for VPLEX volume.");

            // Now we can create the consistency group.
            client.createConsistencyGroup(cgName, clusterName, isDistributed);
            log.info("Created VPLEX consistency group.");

            // Now update the CG in the DB.
            cg.setVirtualArray(vaURI);
            cg.addSystemConsistencyGroup(vplexSystem.getId().toString(),
                    BlockConsistencyGroupUtils.buildClusterCgName(clusterName, cgName));
            cg.addConsistencyGroupTypes(Types.VPLEX.name());
            dbClient.updateObject(cg);
            log.info("Updated consistency group in DB.");
        } catch (VPlexApiException vex) {
            log.error("Exception creating consistency group: " + vex.getMessage(), vex);
            WorkflowStepCompleter.stepFailed(stepId, vex);
            return false;
        } catch (Exception ex) {
            log.error("Exception creating consistency group: " + ex.getMessage(), ex);
            String opName = ResourceOperationTypeEnum.CREATE_CONSISTENCY_GROUP.getName();
            ServiceError serviceError = VPlexApiException.errors.createConsistencyGroupFailed(opName, ex);
            WorkflowStepCompleter.stepFailed(stepId, serviceError);
            return false;
        } 
        return true;
    }
    
    /**
     * Adds volumes to a BlockConsistencyGroup.
     * @param vplexSystem - StorageSystem representing the Vplex
     * @param cg -- Block Consistency Group the volumes are to be added to
     * @param vplexVolumes -- Collection of Vplex volumes to be added
     * @param stepId -- String Stepid. WorkflowStepCompleter is called if successful or not.
     * @return true if successful, false if not
     */
    private boolean addVolumesToCG(StorageSystem vplexSystem, BlockConsistencyGroup cg, 
            Collection<Volume> vplexVolumes, String stepId) {
        try {
            VPlexApiClient client  = getVPlexAPIClient(vplexApiFactory, vplexSystem, dbClient);
            
            // Get the names of the volumes to be added.
            Volume protoVolume = null;
            List<String> vplexVolumeNames = new ArrayList<String>();
            for (Volume vplexVolume : vplexVolumes) {
                if (protoVolume == null) {
                    protoVolume = vplexVolume;
                }
                vplexVolumeNames.add(vplexVolume.getDeviceLabel());
                log.info("VPLEX volume:" + vplexVolume.getDeviceLabel());
            }
            log.info("Got VPLEX volume names.");
            String cgName = getVplexCgName(protoVolume, cg.getId());

            long startTime = System.currentTimeMillis();
            // Add the volumes to the CG.
            client.addVolumesToConsistencyGroup(cgName, vplexVolumeNames);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info(String.format("TIMER: Adding %s virtual volume(s) %s to the consistency group %s took %f seconds",
                    vplexVolumeNames.size(), vplexVolumeNames, cgName, (double) elapsed / (double) 1000));

            // Make sure the volumes are updated. Necessary when
            // adding volumes to a CG after volume creation.
            for (Volume vplexVolume : vplexVolumes) {
                vplexVolume.setConsistencyGroup(cg.getId());
                dbClient.updateObject(vplexVolume);
            }

            // Update workflow step state to success.
            WorkflowStepCompleter.stepSucceded(stepId);
            log.info("Updated workflow step state to success for add volumes to consistency group.");
            return true;
        } catch (VPlexApiException vae) {
            log.error("Exception adding volumes to consistency group: " + vae.getMessage(), vae);
            WorkflowStepCompleter.stepFailed(stepId, vae);
            return false;
        } catch (Exception ex) {
            log.error(
                    "Exception adding volumes to consistency group: " + ex.getMessage(), ex);
            ServiceError svcError = VPlexApiException.errors.jobFailed(ex);
            WorkflowStepCompleter.stepFailed(stepId, svcError);
            return false;
        }
    }
    
    /**
     * Adds a step to the passed workflow to set the consistency group read-only flag to the indicated state.
     * If the passed volumes have no consistency group, no step is generated.
     * @param workflow -- Workflow we're configuring
     * @param vplexVolumeURIs -- List of Vplex Volume URIs in the CG to be set
     * @param setToReadOnly -- if true set to read only, if false, clear read-only (so is read-write)
     * @param stepDescription -- Description of step, helpful if lists volumes
     * @param waitFor -- previous step in the workflow to wait for
     * @return waitFor -- step id of last step that was created
     */
    public String addStepForUpdateConsistencyGroupReadOnlyState(Workflow workflow, 
            List<URI> vplexVolumeURIs, boolean setToReadOnly,
            String stepDescription, String argWaitFor) {
        String waitFor = argWaitFor;    // to fix Sonar
        if (vplexVolumeURIs.isEmpty()) {
            return waitFor;
        }
        Volume vplexVolume = dbClient.queryObject(Volume.class, vplexVolumeURIs.get(0));
        // Note that volumes being added to SRDF target CGwill not have consistencyGroup attribute set
        // here because that CG will not have been created when the workflow is configured.
        // The CG is used here only for logging purposes.
        BlockConsistencyGroup cg = null;
        if (!NullColumnValueGetter.isNullURI(vplexVolume.getConsistencyGroup())) {
            cg = dbClient.queryObject(BlockConsistencyGroup.class, vplexVolume.getConsistencyGroup());
        }
        StorageSystem vplexSystem = dbClient.queryObject(StorageSystem.class, vplexVolume.getStorageController());
        Workflow.Method readOnlyExecuteMethod = updateConsistencyGroupReadOnlyStateMethod(vplexVolumeURIs, setToReadOnly);
        Workflow.Method readOnlyRollbackMethod = updateConsistencyGroupReadOnlyStateMethod(vplexVolumeURIs, !setToReadOnly);
        waitFor = workflow.createStep(null, 
                String.format("CG: %s: %s", (cg != null ? cg.getLabel() : ""), stepDescription), 
                waitFor, vplexSystem.getId(), vplexSystem.getSystemType(), this.getClass(), 
                readOnlyExecuteMethod, readOnlyRollbackMethod, null);
        return waitFor;
    }
    
    /**
     * Method to update ConsistencyGroup read-only state, must match args of updateConsistencyGroupReadOnlyState
     * (except stepId)
     * @param vplexVolumeURIs -- list of Vplex volume URIs
     * @param isReadOnly if true set to read-only, if false set to read-write
     * @return Workflow.Method for updateConsistencyGroupReadOnlyState
     */
    public Workflow.Method updateConsistencyGroupReadOnlyStateMethod(
            List<URI> vplexVolumeURIs, Boolean isReadOnly) {
        return new Workflow.Method("updateConsistencyGroupReadOnlyState", vplexVolumeURIs, isReadOnly);
    }
    
    /**
     * Step to call the VPLEX to update the read-only flag on a consistency group.
     * If the Vplex Api library detects the firmware does not properly handle the flag,
     * a warning message is put in the SUCCESS status.
     * @param vplexVolumeURIs -- List of at least some volumes in the consistency group.
     * @param isReadOnly - if true marks read-only, false read-write
     * @param stepId - Workflow step id
     */
    public void updateConsistencyGroupReadOnlyState(List<URI> vplexVolumeURIs, 
            Boolean isReadOnly, String stepId) {
        try {
            WorkflowStepCompleter.stepExecuting(stepId);
            // Get the first virtual volume, as all volumes should be in the same CG
            Volume vplexVolume = dbClient.queryObject(Volume.class, vplexVolumeURIs.get(0));
            // Get the storage system for the Vplex.
            StorageSystem vplexSystem = dbClient.queryObject(StorageSystem.class, vplexVolume.getStorageController());
            // And from that a handle to the VplexApiClient
            VPlexApiClient client  = getVPlexAPIClient(vplexApiFactory, vplexSystem, dbClient);
            
            // From that get the Consistency Group, if there is not one, just return success
            if (NullColumnValueGetter.isNullURI(vplexVolume.getConsistencyGroup())) {
                log.info("Volume has no ConsistencyGroup: " + vplexVolume.getLabel());
                WorkflowStepCompleter.stepSucceded(stepId);
                return;
            }
            BlockConsistencyGroup cg = dbClient.queryObject(BlockConsistencyGroup.class, 
                    vplexVolume.getConsistencyGroup());
            // Get the consistency group parameters.
            ClusterConsistencyGroupWrapper clusterConsistencyGroup =
                    getClusterConsistencyGroup(vplexVolume, cg);

            String cgName = clusterConsistencyGroup.getCgName();
            String clusterName = clusterConsistencyGroup.getClusterName();
            boolean isDistributed = clusterConsistencyGroup.isDistributed();
            
            // Make the call to update the consistency group read-only status
            
            client.updateConsistencyGroupReadOnly(cgName, clusterName, isDistributed, isReadOnly);
            
            // Complete the step
            WorkflowStepCompleter.stepSucceded(stepId);
        
        } catch (VPlexApiException ex) {
            if (ServiceCode.VPLEX_API_FIRMWARE_UPDATE_NEEDED.equals(ex.getServiceCode())) {
                // The firmware doesn't support read-only flag, inform the user, but do not fail.
                WorkflowStepCompleter.stepSucceeded(stepId, ex.getLocalizedMessage());
            } else {
                log.info("Exception setting Consistency Group read-only state: " + ex.getMessage());
                ServiceError svcError = VPlexApiException.errors.jobFailed(ex);
                WorkflowStepCompleter.stepFailed(stepId, svcError);
            }
        } catch (Exception ex) {
            log.info("Exception setting Consistency Group read-only state: " + ex.getMessage());
            ServiceError svcError = VPlexApiException.errors.jobFailed(ex);
            WorkflowStepCompleter.stepFailed(stepId, svcError);
        }
    }
}
