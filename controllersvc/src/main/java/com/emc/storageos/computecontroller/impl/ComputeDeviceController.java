/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.computecontroller.impl;

import java.net.URI;
import java.util.List;

import com.emc.storageos.Controller;
import com.emc.storageos.blockorchestrationcontroller.VolumeDescriptor;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.workflow.Workflow;

public interface ComputeDeviceController extends Controller {

    /**
     * Discover compute system
     *
     * @param csId
     *            {@link URI} computeSystem id
     */
    public void discoverComputeSystem(URI csId) throws InternalException;

    /**
     * Create host using the specified params
     *
     * @param csId
     *            {@link URI} computesystem Id
     * @param vcpoolId
     *            {@link URI} vcpoolId
     * @param varray {@link URI} varray id
     * @param hostId
     *            {@link URI} host Id
     * @param opId
     *            (@link String} operation Id
     */
    public void createHost(URI csId, URI vcpoolId, URI varray, URI hostId, String opId) throws InternalException;

    /**
     * Create/Add Pre-OS install steps to the workflow.
     *
     * @param workflow
     *            {@link Workflow} instance
     * @param waitFor
     *            If non-null, the step will not be queued for execution in the
     *            Dispatcher until the Step or StepGroup indicated by the
     *            waitFor has completed. The waitFor may either be a string
     *            representation of a Step UUID, or the name of a StepGroup.
     * @param computeSystemId
     *            {@link URI} computeSystem Id
     * @param hostId
     *            {@link URI} host Id
     * @param prepStepId
     *            {@link String} step Id
     * @return waitFor step name
     */
    public String addStepsPreOsInstall(Workflow workflow, String waitFor, URI computeSystemId, URI hostId,
            String prepStepId);

    /**
     * Create/Add PostOsInstall steps to the workflow.
     *
     * @param workflow
     *            {@link Workflow} instance
     * @param waitFor
     *            If non-null, the step will not be queued for execution in the
     *            Dispatcher until the Step or StepGroup indicated by the
     *            waitFor has completed. The waitFor may either be a string
     *            representation of a Step UUID, or the name of a StepGroup.
     * @param computeSystemId
     *            {@link URI} computeSystem Id
     * @param computeElementId
     *            {@link URI} computeElement Id
     * @param hostId
     *            {@link URI} host Id
     * @param contextStepId
     *            {@link String} step Id
     * @param volumeId
     *            {@link URI} bootvolume Id
     * @return waitFor step name
     */
    public String addStepsPostOsInstall(Workflow workflow, String waitFor, URI computeSystemId, URI computeElementId,
            URI hostId, String contextStepId, URI volumeId);

    /**
     * Method to add required steps to deactivate a host
     *
     * @param workflow
     *            {@link Workflow} instance
     * @param waitFor
     *            {@link String} If non-null, the step will not be queued for
     *            execution in the Dispatcher until the Step or StepGroup
     *            indicated by the waitFor has completed. The waitFor may either
     *            be a string representation of a Step UUID, or the name of a
     *            StepGroup.
     * @param hostId
     *            {@link URI} host URI
     * @param deactivateBootVolume
     *            boolean indicating if boot volume has to be deleted.
     * @return waitFor step name
     */
    public String addStepsDeactivateHost(Workflow workflow, String waitFor, URI hostId,
            boolean deactivateBootVolume, List<VolumeDescriptor> volumeDescriptors)
            throws InternalException;

    /**
     * A cluster could have only discovered hosts, only provisioned hosts, or
     * mixed. If cluster has only provisioned hosts, then the hosts will be
     * deleted from vCenter. If cluster has only discovered hosts, then the
     * hosts will not be deleted from vCenter. If cluster is mixed, then the
     * hosts will not be deleted from the vCenter; however, the provisioned
     * hosts will still be decommissioned, and their state in vCenter will be
     * "disconnected". If a cluster is provisioned or mixed, then check VMs step
     * will be executed since hosts with running VMs may endup decommissioned.
     * @param workflow
     *            {@link Workflow} instance
     * @param waitFor
     *            {@link String} If non-null, the step will not be queued for
     *            execution in the Dispatcher until the Step or StepGroup
     *            indicated by the waitFor has completed. The waitFor may either
     *            be a string representation of a Step UUID, or the name of a
     *            StepGroup.
     * @param clusterId
     *            {@link URI} cluster URI
     * @param deactivateCluster
     *            if true, cluster is being deactivated
     * @return waitFor step name
     */
    public String addStepsVcenterClusterCleanup(Workflow workflow, String waitFor, URI clusterId, boolean deactivateCluster)
            throws InternalException;

    /**
     * Method to add steps to perform host cleanup operations on the vcenter
     *
     * @param workflow
     *            {@link Workflow} instance
     * @param waitFor
     *            {@link String} If non-null, the step will not be queued for
     *            execution in the Dispatcher until the Step or StepGroup
     *            indicated by the waitFor has completed. The waitFor may either
     *            be a string representation of a Step UUID, or the name of a
     *            StepGroup.
     * @param hostId
     *            {@link URI} hostId URI
     * @return waitFor step name
     */
    public String addStepsVcenterHostCleanup(Workflow workflow, String waitFor, URI hostId) throws InternalException;

    /**
     * Method is responsible for setting boot from SAN
     *
     * @param computeSystemId
     *            {@link URI} computeSystem Id
     * @param computeElementId
     *            {@link URI} computeElement Id
     * @param hostId
     *            {@link URI} host Id
     * @param volumeId
     *            {@link URI} boot volume id
     * @param waitForServerRestart
     */
    public void setSanBootTarget(URI computeSystemId, URI computeElementId, URI hostId, URI volumeId,
            boolean waitForServerRestart) throws InternalException;
    
    /**
     * Validates that the specified boot volume is exported to the host and there are array portsmapped to the host's initiators in the export masks
     * @param hostId the host URI
     * @param volumeId the volumeId
     * @returns boolean true if the export is valid
     */
     public boolean validateBootVolumeExport(URI hostId, URI volumeId) throws InternalException;
}
