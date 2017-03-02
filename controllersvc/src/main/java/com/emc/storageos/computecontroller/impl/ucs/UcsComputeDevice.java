/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 *
 */
package com.emc.storageos.computecontroller.impl.ucs;

import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.xml.bind.JAXBElement;

import org.apache.commons.beanutils.BeanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.emc.cloud.platform.clientlib.ClientGeneralException;
import com.emc.cloud.platform.clientlib.ClientMessageKeys;
import com.emc.cloud.platform.ucs.out.model.ComputeBlade;
import com.emc.cloud.platform.ucs.out.model.LsRequirement;
import com.emc.cloud.platform.ucs.out.model.LsServer;
import com.emc.cloud.platform.ucs.out.model.VnicEther;
import com.emc.cloud.platform.ucs.out.model.VnicFc;
import com.emc.cloud.platform.ucs.out.model.VnicFcIf;
import com.emc.cloud.ucsm.service.LsServerOperStates;
import com.emc.cloud.ucsm.service.UCSMService;
import com.emc.storageos.computecontroller.ComputeDevice;
import com.emc.storageos.computesystemcontroller.exceptions.ComputeSystemControllerException;
import com.emc.storageos.computesystemcontroller.exceptions.ComputeSystemControllerTimeoutException;
import com.emc.storageos.computesystemcontroller.impl.ComputeSystemHelper;
import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.Cluster;
import com.emc.storageos.db.client.model.ComputeElement;
import com.emc.storageos.db.client.model.ComputeElementHBA;
import com.emc.storageos.db.client.model.ComputeSystem;
import com.emc.storageos.db.client.model.ComputeVirtualPool;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Host;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.Network;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Operation.Status;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.UCSServiceProfile;
import com.emc.storageos.db.client.model.UCSServiceProfileTemplate;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.client.util.StringSetUtil;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.networkcontroller.impl.NetworkAssociationHelper;
import com.emc.storageos.security.audit.AuditLogManager;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.util.ExportUtils;
import com.emc.storageos.util.InvokeTestFailure;
import com.emc.storageos.volumecontroller.BlockExportController;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;
import com.emc.storageos.volumecontroller.impl.monitoring.RecordableEventManager;
import com.emc.storageos.workflow.Workflow;
import com.emc.storageos.workflow.WorkflowService;
import com.emc.storageos.workflow.WorkflowStepCompleter;

/**
 * @author Prabhakara,Janardhan
 *
 */
public class UcsComputeDevice implements ComputeDevice {

    /**
     * ServiceProfileKeyConstants
     *
     * @author Prabhakara,Janardhan
     *
     */

    private CoordinatorClient _coordinator = null;

    public void setCoordinator(CoordinatorClient coordinator) {
        this._coordinator = coordinator;
    }

    private static final String ASSOCIATED_SERVER_POOL = "associatedServerPool";
    private static final String VHBA_COUNT = "vhbaCount";
    private static final String VNIC_COUNT = "vnicCount";

    private static final String EVENT_SERVICE_TYPE_CE = "ComputeElement";

    private static final String POWER_UP = "up";
    private static final String POWER_DOWN = "down";

    private static final String ASSOC_STATE_UNASSOCIATED = "unassociated";

    /**
     * 10 Mins is the default time out if one is not set...
     */
    private int deviceOperationTimeOut = 10;
    private int deviceOperationPollFrequency = 30;

    private WorkflowService workflowService;

    public void setWorkflowService(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    public int getDeviceOperationPollFrequency() {
        return deviceOperationPollFrequency;
    }

    public void setDeviceOperationPollFrequency(int deviceOperationPollFrequency) {
        this.deviceOperationPollFrequency = deviceOperationPollFrequency;
    }

    public int getDeviceOperationTimeOut() {
        return deviceOperationTimeOut;
    }

    public void setDeviceOperationTimeOut(int deviceOperationTimeOut) {
        this.deviceOperationTimeOut = deviceOperationTimeOut;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(UcsComputeDevice.class);
    private static final String CREATE_HOST_WORKFLOW = "CREATE_HOST_WF";
    private static final String CREATE_SP_FROM_SPT_STEP = "CREATE_SP_FROM_SPT";
    private static final String MODIFY_SP_BOOT_ORDER_STEP = "MODIFY_SP_BOOT_ORDER";
    private static final String BIND_SERVICE_PROFILE_TO_BLADE_STEP = "BIND_SERVICE_PROFILE_TO_BLADE";
    private static final String ADD_HOST_PORTS_TO_NETWORKS_STEP = "ADD_HOST_PORTS_TO_NETWORKS";
    private static final long TASK_STATUS_POLL_FREQUENCY = 30 * 1000;
    private static final String ADD_HOST_TO_SHARED_EXPORT_GROUPS = "ADD_HOST_TO_SHARED_EXPORT_GROUPS";

    private DbClient _dbClient;

    @Autowired
    UCSMService ucsmService;

    @Autowired
    private AuditLogManager _auditMgr;

    @Autowired
    private RecordableEventManager _eventManager;

    public void setDbClient(DbClient dbClient) {
        _dbClient = dbClient;
    }

    private BlockExportController blockExportController;

    public void setBlockExportController(BlockExportController blockExportController) {
        this.blockExportController = blockExportController;
    }

    @Override
    public void discoverComputeSystem(URI computeSystemId) throws InternalException {
        LOGGER.info("discoverComputeSystems");
        UcsDiscoveryWorker discoveryWorker = new UcsDiscoveryWorker(ucsmService, _dbClient);
        discoveryWorker.discoverComputeSystem(computeSystemId);
    }

    private UCSServiceProfile persistServiceProfileForHost(LsServer lsServer, ComputeSystem cs, URI hostId){
        Host host = _dbClient.queryObject(Host.class, hostId);
        if (host == null){
             LOGGER.error("Host not found for URI:"+ hostId.toString());
             throw ComputeSystemControllerException.exceptions.hostNotFound(hostId.toString());
        }
        UCSServiceProfile serviceProfile = new UCSServiceProfile();
        URI uri = URIUtil.createId(UCSServiceProfile.class);
        serviceProfile.setComputeSystem(cs.getId());
        serviceProfile.setInactive(false);
        serviceProfile.setId(uri);
        serviceProfile.setSystemType(cs.getSystemType());
        serviceProfile.setCreationTime(Calendar.getInstance());
        serviceProfile.setDn(lsServer.getDn());
        serviceProfile.setLabel(lsServer.getName());
        serviceProfile.setUuid(lsServer.getUuid());
        serviceProfile.setNativeGuid(NativeGUIDGenerator.generateNativeGuid(cs, serviceProfile));
        serviceProfile.setHost(hostId);
        _dbClient.createObject(serviceProfile);
         
        host.setServiceProfile(serviceProfile.getId());
        _dbClient.persistObject(host);

        return serviceProfile;
    }
    private void setComputeElementAttrFromBoundLsServer(DbClient dbClient, ComputeElement computeElement,
            LsServer lsServer, Host host, String systemType, boolean markUnregistered) {

        List<ComputeElementHBA> computeElementHBAs = new ArrayList<ComputeElementHBA>();

        computeElement.setUuid(lsServer.getUuid());
        computeElement.setDn(lsServer.getDn());
        String sptName = lsServer.getSrcTemplName();
        URIQueryResultList uris = new URIQueryResultList();
        _dbClient.queryByConstraint(ContainmentConstraint.Factory
                .getComputeSystemServiceProfileTemplateConstraint(computeElement.getComputeSystem()), uris);
        List<UCSServiceProfileTemplate> serviceTemplates = _dbClient.queryObject(UCSServiceProfileTemplate.class, uris, true);
        for (UCSServiceProfileTemplate serviceTemplate : serviceTemplates) {
            if (serviceTemplate.getLabel().equals(sptName)) {
                computeElement.setSptId(serviceTemplate.getId().toString());
            }
        }

        if (markUnregistered) {
            computeElement.setRegistrationStatus(DiscoveredDataObject.RegistrationStatus.UNREGISTERED.name());
        }
        computeElement.setAvailable(false);

        if (lsServer.getContent() != null && !lsServer.getContent().isEmpty()) {

            for (Serializable contentElement : lsServer.getContent()) {
                if (contentElement instanceof JAXBElement<?>) {
                    if (((JAXBElement) contentElement).getValue() instanceof VnicFc) {
                        VnicFc vnicFc = (VnicFc) ((JAXBElement) contentElement).getValue();
                        ComputeElementHBA computeElementHBA = new ComputeElementHBA();
                        computeElementHBA.setComputeElement(computeElement.getId());
                        computeElementHBA.setHost(host.getId());
                        computeElementHBA.setCreationTime(Calendar.getInstance());
                        computeElementHBA.setDn(vnicFc.getDn());
                        computeElementHBA.setId(URIUtil.createId(ComputeElementHBA.class));
                        computeElementHBA.setInactive(false);
                        computeElementHBA.setLabel(vnicFc.getName());
                        computeElementHBA.setProtocol(vnicFc.getType());
                        computeElementHBA.setNativeGuid(NativeGUIDGenerator.generateNativeGuid(computeElementHBA,
                                systemType));
                        computeElementHBA.setNode(vnicFc.getNodeAddr());
                        computeElementHBA.setPort(vnicFc.getAddr());
                        computeElementHBA.setVsanId(getVsanIdFromvnicFC(vnicFc));
                        computeElementHBAs.add(computeElementHBA);
                    }
                }
            }

        }

        if (!computeElementHBAs.isEmpty()) {
            dbClient.createObject(computeElementHBAs);
        }

        /**
         * For the case where the compute element exists, but we are updating
         * it:
         */
        if (dbClient.queryObject(ComputeElement.class, computeElement.getId()) != null) {
            dbClient.persistObject(computeElement);
        }
    }

    private String getVsanIdFromvnicFC(VnicFc vnicFc) {

        if (vnicFc == null) {
            return null;
        }

        if (vnicFc.getContent() != null && !vnicFc.getContent().isEmpty()) {
            for (Serializable contentElement : vnicFc.getContent()) {
                if (contentElement instanceof JAXBElement<?>) {
                    if (((JAXBElement) contentElement).getValue() instanceof VnicFcIf) {
                        VnicFcIf vnicFcIf = (VnicFcIf) ((JAXBElement) contentElement).getValue();
                        return vnicFcIf.getVnet();
                    }
                }

            }
        }

        return null;

    }

    private Map<String, Object> getServiceProfileTemplateDetails(LsServer spt) {

        Map<String, Object> serviceProfileTemplateDetails = new HashMap<String, Object>();

        int vhbaCount = 0;
        int vnicCount = 0;

        if (spt.getContent() != null && !spt.getContent().isEmpty()) {
            for (Serializable element : spt.getContent()) {
                if (element instanceof JAXBElement<?>) {
                    if (((JAXBElement) element).getValue() instanceof LsRequirement) {
                        LsRequirement lsRequirement = (LsRequirement) ((JAXBElement) element).getValue();
                        serviceProfileTemplateDetails.put(ASSOCIATED_SERVER_POOL, lsRequirement.getName());
                    } else if (((JAXBElement) element).getValue() instanceof VnicEther) {
                        vnicCount++;
                    } else if (((JAXBElement) element).getValue() instanceof VnicFc) {
                        vhbaCount++;
                    }
                }
            }
            serviceProfileTemplateDetails.put(VHBA_COUNT, vhbaCount);
            serviceProfileTemplateDetails.put(VNIC_COUNT, vnicCount);

        }
        return serviceProfileTemplateDetails;

    }

    private void changePowerState(URI csId, URI ceId, String state) throws DeviceControllerException {
        LOGGER.info("changePowerState");
        ComputeElement ce = _dbClient.queryObject(ComputeElement.class, ceId);
        ComputeSystem cs = _dbClient.queryObject(ComputeSystem.class, csId);

        OperationTypeEnum typeEnum = POWER_DOWN.equals(state) ? OperationTypeEnum.POWERDOWN_COMPUTE_ELEMENT
                : OperationTypeEnum.POWERUP_COMPUTE_ELEMENT;

        try {
            URL ucsmURL = getUcsmURL(cs);
            ucsmService.setLsServerPowerState(ucsmURL.toString(), cs.getUsername(), cs.getPassword(), ce.getDn(),
                    state);
            pullAndPollManagedObject(ucsmURL.toString(), cs.getUsername(), cs.getPassword(), ce.getLabel(),
                    ComputeBlade.class);
        } catch (ComputeSystemControllerTimeoutException cstoe) {
            LOGGER.error("Unable to change power state of compute element due to a device TimeOut", cstoe);
            throw cstoe;
        } catch (Exception e) {
            LOGGER.error("Unable to change power state of compute element due to a exception", e);
            throw ComputeSystemControllerException.exceptions.powerStateChangeFailed(state, ce != null ? ce.getId()
                    .toString() : null, e);
        }

        _auditMgr.recordAuditLog(null, null, EVENT_SERVICE_TYPE_CE, typeEnum, System.currentTimeMillis(),
                AuditLogManager.AUDITLOG_SUCCESS, AuditLogManager.AUDITOP_END, ce.getId().toString(), ce.getLabel(),
                ce.getNativeGuid(), ce.getUuid(), ce.getOriginalUuid());
    }

    @Override
    public void powerUpComputeElement(URI computeSystemId, URI computeElementId) throws InternalException {
        LOGGER.info("powerUpComputeElement");
        changePowerState(computeSystemId, computeElementId, POWER_UP);
    }

    @Override
    public void powerDownComputeElement(URI computeSystemId, URI computeElementId) throws InternalException {
        LOGGER.info("powerDownComputeElement");
        changePowerState(computeSystemId, computeElementId, POWER_DOWN);
    }

    @Override
    public String unbindHostFromTemplate(URI computeSystemId, URI hostId) throws InternalException {
        LOGGER.info("unbindHostFromTemplate");

        Host host = _dbClient.queryObject(Host.class, hostId);
        ComputeSystem cs = _dbClient.queryObject(ComputeSystem.class, computeSystemId);
        String sptDn = null;

        try {
            // Test mechanism to invoke a failure. No-op on production systems.
            InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_069);

            // VBDU TODO: COP-28452, Check for initiators in host as well
            if (host != null && !NullColumnValueGetter.isNullURI(host.getComputeElement()) && host.getUuid() != null) {
                ComputeElement ce = _dbClient.queryObject(ComputeElement.class, host.getComputeElement());
                URI sptId = URI.create(ce.getSptId());
                UCSServiceProfileTemplate template = _dbClient.queryObject(UCSServiceProfileTemplate.class, sptId);
                sptDn = template.getDn();
                LsServer sp = ucsmService.getLsServer(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(),
                        host.getUuid());
                if (sp != null) {
                    URL ucsmURL = getUcsmURL(cs);
                    if(null == ucsmService.unbindSPFromTemplate(ucsmURL.toString(), cs.getUsername(), cs.getPassword(),
                            sp.getDn())) {
                        LOGGER.error("Failed to unbind service profile from template due to error from UCSM Service.");
                        throw new RuntimeException(
                                "Failed to unbind service profile from template due to error from UCSM Service.");
                    }
                    LOGGER.info("Successfully unbound host {} from template {}", host.getLabel(), template.getLabel());
                } else {
                    LOGGER.error("Unable to unbind service profile to template.  LsServer is null");
                    throw new RuntimeException(
                            "Unable to unbind service profile to template.  LsServer is null");
                }
            } else {
                LOGGER.error(
                        "Unable to unbind service profile to template, due to insufficient host data.  Host or ComputeElement or host UUID is null");
                throw new RuntimeException(
                        "Unable to unbind service profile to template, due to insufficient host data.  Host or host's computeElement or host UUID is null");
            }
        } catch (Exception e) {
            LOGGER.error("Unable to unbind service profile from template due to a exception", e);
            throw ComputeSystemControllerException.exceptions.unbindHostFromTemplateFailed(host != null ? host.getId()
                    .toString() : null, e);
        }

        return sptDn;
    }

    @Override
    public void rebindHostToTemplate(URI computeSystemId, URI hostId) throws InternalException {
        LOGGER.info("rebindHostToTemplate");
        // re-bind host to SPT
        Host host = _dbClient.queryObject(Host.class, hostId);
        ComputeSystem cs = _dbClient.queryObject(ComputeSystem.class, computeSystemId);
        try {
            if (host != null && !NullColumnValueGetter.isNullURI(host.getComputeElement()) && host.getUuid() != null) {
                ComputeElement ce = _dbClient.queryObject(ComputeElement.class, host.getComputeElement());
                URI sptId = URI.create(ce.getSptId());
                UCSServiceProfileTemplate template = _dbClient.queryObject(UCSServiceProfileTemplate.class, sptId);
                LsServer sp = ucsmService.getLsServer(getUcsmURL(cs).toString(),
                        cs.getUsername(), cs.getPassword(), host.getUuid());

                if (sp != null && template.getLabel() != null) {
                    if (!sp.getSrcTemplName().equalsIgnoreCase(template.getLabel())) {
                        URL ucsmURL = getUcsmURL(cs);
                        if(null == ucsmService.bindSPToTemplate(ucsmURL.toString(), cs.getUsername(), cs.getPassword(), sp.getDn(),
                                template.getLabel())) {
                            throw new RuntimeException(
                                    "Failed to rebind service profile to template.");
                        }
                        LOGGER.info("Successfully rebound host {} to template {}", host.getLabel(), template.getLabel());
                    }else {
                        LOGGER.info("Host is already bound to template, nothing to do.  No OP.");
                    }
                } else {
                    LOGGER.error(
                            "Unable to bind service profile to template.  LsServer or UCSServiceProfileTemplate is null");
                    throw new RuntimeException(
                            "Unable to bind service profile to template.  LsServer or UCSServiceProfileTemplate is null");
                }
            } else {
                LOGGER.error(
                        "Unable to bind service profile to template, due to insufficient host data.  Host or ComputeElement or host UUID is null");
                throw new RuntimeException(
                        "Unable to bind service profile to template, due to insufficient host data.  Host or host's computeElement or host UUID is null");
            }
        } catch (Exception e) {
            LOGGER.error("Unable to bind service profile to template due to a exception", e);
            throw ComputeSystemControllerException.exceptions.bindHostToTemplateFailed(host != null ? host.getId()
                    .toString() : null, e);
        }
    }

    @Override
    public void createHost(ComputeSystem computeSystem, Host host, ComputeVirtualPool vcp, VirtualArray varray,
            TaskCompleter taskCompleter) throws InternalException {
        LOGGER.info("create Host : " + host.getLabel());
        LOGGER.info("Host ID: " + host.getId());
        try {
            Workflow workflow = workflowService.getNewWorkflow(this, CREATE_HOST_WORKFLOW, true,
                    taskCompleter.getOpId());

            String sptDn = getSptDNFromVCP(computeSystem, vcp);

            /**
             * This condition means that we were not able to find a suitable SPT
             * to do the provisioning with from the VCP! Hence abort...
             */

            if (sptDn == null) {
                throw ComputeSystemControllerException.exceptions.invalidComputeVirtualPool(host.getHostName(),
                        computeSystem.getNativeGuid(), vcp.getId().toString(),
                        "Unable to find a suitable ServiceProfileTemplate for provisioning host", null);
            }

            String createSpToken = workflow.createStepId();
            createSpToken = workflow.createStep(CREATE_SP_FROM_SPT_STEP,
                    "create a service profile from the ServiceProfile template selected in the VCP", null,
                    computeSystem.getId(), computeSystem.getSystemType(), this.getClass(), new Workflow.Method(
                            "createLsServer", computeSystem, sptDn, host),
                    new Workflow.Method(
                            "deleteLsServer", computeSystem, host.getId(), createSpToken),
                    createSpToken);

            String modifySpBootToken = workflow.createStepId();
            modifySpBootToken = workflow
                    .createStep(
                            MODIFY_SP_BOOT_ORDER_STEP,
                            "Modify the created service profile to have an Empty boot policy, if a boot policy was associated, it will be over-written!",
                            createSpToken, computeSystem.getId(), computeSystem.getSystemType(), this.getClass(),
                            new Workflow.Method("modifyLsServerNoBoot", computeSystem, createSpToken),
                            new Workflow.Method("deleteLsServer", computeSystem, host.getId(), createSpToken), modifySpBootToken);

            String bindSPStepId = workflow.createStepId();
            bindSPStepId = workflow.createStep(BIND_SERVICE_PROFILE_TO_BLADE_STEP,
                    "bind a service profile to the blade represented by the CE associated with the Host",
                    modifySpBootToken, computeSystem.getId(), computeSystem.getSystemType(), this.getClass(),
                    new Workflow.Method("bindServiceProfileToBlade", computeSystem, host.getId(),
                            createSpToken),
                    new Workflow.Method("unbindServiceProfile", computeSystem, createSpToken),
                    bindSPStepId);

            String addHostPortsToNetworkStepId = workflow.createStepId();
            addHostPortsToNetworkStepId = workflow.createStep(ADD_HOST_PORTS_TO_NETWORKS_STEP,
                    "Add host ports from service profile to the corresponding network in the vArray", bindSPStepId,
                    computeSystem.getId(), computeSystem.getSystemType(), this.getClass(), new Workflow.Method(
                            "addHostPortsToVArrayNetworks", varray, host),
                    new Workflow.Method(ROLLBACK_NOTHING_METHOD), addHostPortsToNetworkStepId);
            //forcefully skipping the sharedExport update step, due to concurrency issue
            // we will handle update of sharedExport to all hosts in bulk rather than one for each host.
            // Temporary workaround fix until the actual fix is delivered.
            boolean performStep = false;
            if (performStep) {
                String addHostToSharedExportGroupsStepId = workflow.createStepId();
                addHostToSharedExportGroupsStepId = workflow.createStep(ADD_HOST_TO_SHARED_EXPORT_GROUPS,
                        "Add host to shared export groups", addHostPortsToNetworkStepId, computeSystem.getId(),
                        computeSystem.getSystemType(), this.getClass(),
                        new Workflow.Method("addHostToSharedExportGroups", host), null,
                        addHostToSharedExportGroupsStepId);
            }
            workflow.executePlan(taskCompleter, "Successfully created host : " + host.getHostName());

            LOGGER.info("create Host : " + host.getLabel() + " Complete");
        } catch (InternalException e) {
            taskCompleter.error(_dbClient, e);
        } catch (Exception e) {
            taskCompleter.error(_dbClient, DeviceControllerException.errors.unforeseen());
        }
    }

    public void addHostToSharedExportGroups(Host host, String stepId) {

        LOGGER.info("addHostToSharedExportGroups : " + host.getHostName());

        try {

            WorkflowStepCompleter.stepExecuting(stepId);

            if (host.getCluster() != null) {
                List<ExportGroup> sharedExportGroups = CustomQueryUtility.queryActiveResourcesByConstraint(_dbClient,
                        ExportGroup.class, AlternateIdConstraint.Factory.getConstraint(ExportGroup.class, "clusters",
                                host.getCluster().toString()));

                for (ExportGroup exportGroup : sharedExportGroups) {

                    String task = UUID.randomUUID().toString();

                    Operation op = _dbClient.createTaskOpStatus(ExportGroup.class, exportGroup.getId(), task,
                            ResourceOperationTypeEnum.UPDATE_EXPORT_GROUP);
                    exportGroup.getOpStatus().put(task, op);

                    _dbClient.persistObject(exportGroup);

                    Map<URI, Integer> noUpdatesVolumeMap = new HashMap<URI, Integer>();

                    List<URI> existingInitiators = StringSetUtil.stringSetToUriList(exportGroup.getInitiators());
                    List<URI> existingHosts = StringSetUtil.stringSetToUriList(exportGroup.getHosts());
                    List<URI> existingClusters = StringSetUtil.stringSetToUriList(exportGroup.getClusters());

                    Set<URI> addedClusters = new HashSet<>();
                    Set<URI> removedClusters = new HashSet<>();
                    Set<URI> addedHosts = new HashSet<>();
                    Set<URI> removedHosts = new HashSet<>();
                    Set<URI> addedInitiators = new HashSet<>();
                    Set<URI> removedInitiators = new HashSet<>();

                    // add host reference to export group
                    if (!existingHosts.contains(host.getId())) {
                        addedHosts.add(host.getId());
                    }

                    List<Initiator> hostInitiators = ComputeSystemHelper.queryInitiators(_dbClient, host.getId());
                    List<Initiator> validInitiators = ComputeSystemHelper.validatePortConnectivity(_dbClient,
                            exportGroup, hostInitiators);
                    if (!validInitiators.isEmpty()) {
                        // if the initiators is not already in the list add
                        // it.
                        for (Initiator initiator : validInitiators) {
                            if (!existingInitiators.contains(initiator.getId())) {
                                addedInitiators.add(initiator.getId());
                            }
                        }
                    }

                    blockExportController.exportGroupUpdate(exportGroup.getId(),
                            noUpdatesVolumeMap, noUpdatesVolumeMap,
                            addedClusters, removedClusters, addedHosts, removedHosts, addedInitiators, removedInitiators, task);

                    while (true) {
                        Thread.sleep(TASK_STATUS_POLL_FREQUENCY);
                        exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroup.getId());

                        switch (Status.toStatus(exportGroup.getOpStatus().get(task).getStatus())) {
                        case ready:
                            WorkflowStepCompleter.stepSucceded(stepId);
                            break;
                        case error:
                            WorkflowStepCompleter.stepFailed(stepId, exportGroup.getOpStatus().get(task)
                                    .getServiceError());
                            break;
                        default:
                            break;

                        }
                    }

                }

            }
        }

        catch (Exception exception) {
            ServiceCoded serviceCoded = ComputeSystemControllerException.exceptions.unableToProvisionHost(
                    host != null ? host.getHostName() : host.getId().toString(), null, exception);
            WorkflowStepCompleter.stepFailed(stepId, serviceCoded);
        }

        WorkflowStepCompleter.stepSucceded(stepId);
    }

    public void unbindServiceProfile(ComputeSystem cs, String contextStepId, String stepId)
            throws ClientGeneralException {
        WorkflowStepCompleter.stepExecuting(stepId);
        String spDn = (String) workflowService.loadStepData(contextStepId);
        try {
            if (spDn != null) {
                LOGGER.info("Unbinding Service Profile : " + spDn + " from blade");
                // Test mechanism to invoke a failure. No-op on production systems.
                InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_067);
                if (null == ucsmService.unbindServiceProfile(getUcsmURL(cs).toString(), cs.getUsername(),
                        cs.getPassword(), spDn)) {
                    throw new RuntimeException("Failed to unbind service profile.");
                }
                LOGGER.info("Done Unbinding Service Profile : " + spDn + " from blade");
            } else {
                LOGGER.info("No OP");
            }
            WorkflowStepCompleter.stepSucceded(stepId);
        } catch (Exception e) {
            LOGGER.error("Unable to unbindServiceProfile...", e);
            WorkflowStepCompleter.stepFailed(stepId, ComputeSystemControllerException.exceptions.unableToProvisionHost(
                    spDn, cs.getNativeGuid(), e));
        }
    }

    private String getSptDNFromVCP(ComputeSystem computeSystem, ComputeVirtualPool virtualPool) {
        StringSet spts = virtualPool.getServiceProfileTemplates();

        if (spts == null) {
            return null;
        }

        for (String sptId : spts) {
            URI sptUri = URI.create(sptId);

            UCSServiceProfileTemplate serviceProfileTemplate = _dbClient.queryObject(UCSServiceProfileTemplate.class,
                    sptUri);

            if (serviceProfileTemplate != null && serviceProfileTemplate.getComputeSystem() != null
                    && serviceProfileTemplate.getComputeSystem().equals(computeSystem.getId())) {
                return serviceProfileTemplate.getDn();
            }
        }

        return null;

    }

    @Override
    public Map<String, Boolean> prepareOsInstallNetwork(URI computeSystemId, URI computeElementId)
            throws InternalException {
        LOGGER.info("prepareOsInstallNetwork");

        ComputeSystem cs = _dbClient.queryObject(ComputeSystem.class, computeSystemId);
        ComputeElement ce = _dbClient.queryObject(ComputeElement.class, computeElementId);

        String osInstallVlan = cs.getOsInstallNetwork();

        Map<String, Boolean> vlanMap = null;
        try {
            vlanMap = ucsmService.setOsInstallVlan(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(),
                    ce.getDn(), osInstallVlan);
        } catch (ClientGeneralException e) {
            LOGGER.error(
                    "Unable to set os install vlan: " + cs.getOsInstallNetwork() + " On computeElement : " + ce.getId(),
                    e);
            throw ComputeSystemControllerException.exceptions.unableToSetOsInstallNetwork(osInstallVlan, ce.getId()
                    .toString(), e);
        }

        return vlanMap;

    }

    @Override
    public void removeOsInstallNetwork(URI computeSystemId, URI computeElementId, Map<String, Boolean> vlanMap)
            throws InternalException {
        LOGGER.info("removeOsInstallNetwork");

        ComputeSystem cs = _dbClient.queryObject(ComputeSystem.class, computeSystemId);
        ComputeElement ce = _dbClient.queryObject(ComputeElement.class, computeElementId);

        String osInstallVlan = cs.getOsInstallNetwork();
        try {
            ucsmService.removeOsInstallVlan(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(), ce.getDn(),
                    osInstallVlan, vlanMap);
        } catch (ClientGeneralException e) {
            LOGGER.error(
                    "Unable to set os install vlan: " + cs.getOsInstallNetwork() + " On computeElement : " + ce.getId(),
                    e);
            throw ComputeSystemControllerException.exceptions.unableToRemoveOsInstallNetwork(osInstallVlan, ce.getId()
                    .toString(), e);
        }

    }

    private <T> T pullAndPollManagedObject(String url, String username, String password, String managedObjectDn,
            Class<T> returnType) throws ClientGeneralException, ComputeSystemControllerException {

        LOGGER.debug("Entering pullAndPollManagedObject for ManagedObject of Type : " + returnType + ":"
                + managedObjectDn);

        T pulledManagedObject = ucsmService.getManagedObject(url, username, password, managedObjectDn, false,
                returnType);

        if (pulledManagedObject == null) {
            throw new ClientGeneralException(ClientMessageKeys.NOT_FOUND, new String[] { managedObjectDn });
        }

        LOGGER.debug("Transient Initial OperState of the ManagedObject : "
                + callSimpleReadOnlyMethodOnMO(pulledManagedObject, "operState"));
        LOGGER.debug("Transient Initial AssocState of the ManagedObject : "
                + callSimpleReadOnlyMethodOnMO(pulledManagedObject, "assocState"));

        Calendar timeOutTime = Calendar.getInstance();
        timeOutTime.add(Calendar.SECOND, getDeviceOperationTimeOut());

        do {
            if (Calendar.getInstance().after(timeOutTime)) {

                LOGGER.warn("Time out occured waiting for operation to finish on the LsServer : " + managedObjectDn
                        + "Waited for " + getDeviceOperationTimeOut() + "seconds...");
                throw ComputeSystemControllerException.exceptions.timeoutWaitingForMOTerminalState(
                        callSimpleReadOnlyMethodOnMO(pulledManagedObject, "dn"),
                        callSimpleReadOnlyMethodOnMO(pulledManagedObject, "operState"), getDeviceOperationTimeOut());

            }

            try {
                Thread.sleep(getDeviceOperationPollFrequency() * 1000);
            } catch (InterruptedException e) {
                LOGGER.warn("Thread : " + Thread.currentThread().getName() + " interrupted...");
            }
            pulledManagedObject = ucsmService.getManagedObject(url, username, password, managedObjectDn, false,
                    returnType);

            LOGGER.debug("Current OperState of the ManagedObject : "
                    + callSimpleReadOnlyMethodOnMO(pulledManagedObject, "operState"));
            LOGGER.debug("Current AssocState of the ManagedObject : "
                    + callSimpleReadOnlyMethodOnMO(pulledManagedObject, "assocState"));

        } while (!LsServerOperStates.isTerminal(callSimpleReadOnlyMethodOnMO(pulledManagedObject, "operState")));

        LOGGER.debug("Terminal OperState of the ManagedObject : "
                + callSimpleReadOnlyMethodOnMO(pulledManagedObject, "operState"));
        LOGGER.debug("Terminal AssocState of the ManagedObject : "
                + callSimpleReadOnlyMethodOnMO(pulledManagedObject, "assocState"));

        return ucsmService.getManagedObject(url, username, password, managedObjectDn, true, returnType);
    }

    private String callSimpleReadOnlyMethodOnMO(Object obj, String property) {

        if (obj == null) {
            return null;
        }

        try {
            return BeanUtils.getSimpleProperty(obj, property);
        } catch (Exception e) {
            // Ignore exceptions here on purpose!
        }
        return null;

    }

    /**
     * Create a LsServer
     * @param cs ComputeSystem instance
     * @param sptDn serviceProfile template distinguished name (DN)
     * @param host Host being created
     * @param stepId Id of step being executed.
     * @return LsServer instance
     */
    public LsServer createLsServer(ComputeSystem cs, String sptDn, Host host, String stepId) {

        WorkflowStepCompleter.stepExecuting(stepId);
        LOGGER.info("Creating Service Profile : " + host.getHostName() + " from Service Profile Template : " + sptDn);
        LsServer lsServer = null;
        try {
            lsServer = ucsmService.createServiceProfileFromTemplate(getUcsmURL(cs).toString(), cs.getUsername(),
                    cs.getPassword(), sptDn, host.getHostName());

            // Test mechanism to invoke a failure. No-op on production systems.
            InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_073);
            if (lsServer == null) {
                throw new RuntimeException("UCS call to create service profile from template failed, null LsServer was returned.");
            }
            workflowService.storeStepData(stepId, lsServer.getDn());
            UCSServiceProfile serviceProfile = persistServiceProfileForHost(lsServer,cs, host.getId());
            validateNewServiceProfile(cs, serviceProfile, host);

            lsServer = pullAndPollManagedObject(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(),
                    lsServer.getDn(), LsServer.class);

            // Test mechanism to invoke a failure. No-op on production systems.
            InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_061);
            if (lsServer == null) {
                throw new RuntimeException("UCS call to poll for ManagedObject failed, null LsServer was returned.");
            }

        } catch (Exception e) {
            LOGGER.error("Unable to createLsServer...", e);
            WorkflowStepCompleter.stepFailed(stepId,
                    ComputeSystemControllerException.exceptions.unableToProvisionHost(host.getHostName(), cs.getNativeGuid(), e));
        }

        WorkflowStepCompleter.stepSucceded(stepId);
        LOGGER.info("Done Creating Service Profile : " + lsServer.getDn() + " from Service Profile Template : " + sptDn);
        return lsServer;
    }

    private void validateNewServiceProfile(ComputeSystem cs, UCSServiceProfile serviceProfile, Host newHost){
        Collection<URI> allHostUris = _dbClient.queryByType(Host.class, true);
        Collection<Host> hosts = _dbClient.queryObjectFields(Host.class,
                Arrays.asList("uuid", "computeElement", "registrationStatus", "inactive"), getFullyImplementedCollection(allHostUris));
        for (Host host: hosts) {
            if (host.getUuid()!=null && host.getUuid().equals(serviceProfile.getUuid()) && !host.getId().equals(newHost.getId()) && (host.getInactive()!=true)){
                LOGGER.error("Newly created service profile :"+ serviceProfile.getLabel() + " shares same uuid "+ serviceProfile.getUuid() +" as existing active host: " + host.getLabel());
                throw ComputeSystemControllerException.exceptions.newServiceProfileDuplicateUuid(serviceProfile.getLabel(),  serviceProfile.getUuid(), host.getLabel());
            }
        }


    }

    private static <T> Collection<T> getFullyImplementedCollection(Collection<T> collectionIn) {
        // Convert objects (like URIQueryResultList) that only implement iterator to
        // fully implemented Collection
        Collection<T> collectionOut = new ArrayList<>();
        Iterator<T> iter = collectionIn.iterator();
        while (iter.hasNext()) {
            collectionOut.add(iter.next());
        }
        return collectionOut;
    }
 

    /**
     * Modify the LsServer to have a no boot policy
     * @param cs ComputeSystem instance
     * @param contextStepId parent StepId
     * @param stepId Id of step being executed.
     * @return LsServer instance
     */
    public LsServer modifyLsServerNoBoot(ComputeSystem cs, String contextStepId, String stepId) {

        WorkflowStepCompleter.stepExecuting(stepId);
        String spDn = (String) workflowService.loadStepData(contextStepId);
        LOGGER.info("Modifying Service Profile to have no Boot Policy: " + spDn);
        LsServer lsServer = null;
        try {
            lsServer = ucsmService.setServiceProfileToNoBoot(getUcsmURL(cs).toString(), cs.getUsername(),
                    cs.getPassword(), spDn);

            // Test mechanism to invoke a failure. No-op on production systems.
            InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_062);
            if (null == lsServer) {
                throw new RuntimeException("UCS invocation to set serviceProfile to No Boot failed, null LsServer was returned.");
            }

            WorkflowStepCompleter.stepSucceded(stepId);
            LOGGER.info("Done updating Service Profile : " + spDn + " from Service Profile Template : " + spDn);
        } catch (Exception e) {
            LOGGER.error("Unable to modify LsServer...", e);
            WorkflowStepCompleter.stepFailed(stepId,
                    ComputeSystemControllerException.exceptions.unableToProvisionHost(spDn, cs.getNativeGuid(), e));
        }
        return lsServer;
    }

    public void deleteLsServer(ComputeSystem cs, URI hostURI, String createSpStepId, String stepId) throws ClientGeneralException {
        WorkflowStepCompleter.stepExecuting(stepId);
        String spDn = null;
        try {
            Host host = _dbClient.queryObject(Host.class, hostURI);
            UCSServiceProfile profile = null;
            if (host!=null){
                if (!NullColumnValueGetter.isNullURI(host.getServiceProfile())){
                   profile = _dbClient.queryObject(UCSServiceProfile.class, host.getServiceProfile());
                   if (profile == null){
                       throw ComputeSystemControllerException.exceptions.invalidServiceProfileReference(host.getServiceProfile().toString());
                   }else {
                       spDn = profile.getDn();
                       if (spDn != null) {
                           LOGGER.info("Deleting Service Profile : " + spDn);
                           // Test mechanism to invoke a failure. No-op on production systems.
                           InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_066);
                           ucsmService.deleteServiceProfile(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(), spDn);
                           LOGGER.info("Done Deleting Service Profile : " + spDn);
                           _dbClient.markForDeletion(profile);
                           host.setServiceProfile(NullColumnValueGetter.getNullURI());
                       }else {
                           throw ComputeSystemControllerException.exceptions.invalidServiceProfile(host.getServiceProfile().toString());
                       }   

                   }
                }

                if (!NullColumnValueGetter.isNullURI(host.getComputeElement())){
                     ComputeElement computeElement = _dbClient.queryObject(ComputeElement.class, host.getComputeElement());
                     if (computeElement!=null){
                         computeElement.setAvailable(true);
                         _dbClient.updateObject(computeElement);
                     }
                     host.setComputeElement(NullColumnValueGetter.getNullURI());
                }
                _dbClient.updateObject(host);
            }
   
            WorkflowStepCompleter.stepSucceded(stepId);
        } catch (Exception e) {
            LOGGER.error("Unable to deleteLsServer...", e);
            WorkflowStepCompleter.stepFailed(stepId, ComputeSystemControllerException.exceptions.unableToProvisionHost(
                    spDn, cs.getNativeGuid(), e));
        }
    }

    public void bindServiceProfileToBlade(ComputeSystem computeSystem, URI hostURI, String contextStepId,
            String stepId) {

        ComputeElement computeElement = null;
        LsServer serviceProfile = null;
        String spDn = null;
        try {
            WorkflowStepCompleter.stepExecuting(stepId);

            spDn = (String) workflowService.loadStepData(contextStepId);
            if (spDn == null) {
                throw new IllegalStateException(
                        "Invalid value for step data. Previous step didn't persist required data.");
            }

            Host host = _dbClient.queryObject(Host.class, hostURI);

            computeElement = _dbClient.queryObject(ComputeElement.class, host.getComputeElement());

            if (computeElement != null) {
                LOGGER.info("Binding Service Profile : " + spDn + " to blade : " + computeElement.getLabel());
                serviceProfile = ucsmService.bindSPToComputeElement(getUcsmURL(computeSystem).toString(),
                        computeSystem.getUsername(), computeSystem.getPassword(), spDn, computeElement.getLabel());

                serviceProfile = pullAndPollManagedObject(getUcsmURL(computeSystem).toString(),
                        computeSystem.getUsername(), computeSystem.getPassword(), spDn, LsServer.class);

                // Test mechanism to invoke a failure. No-op on production
                // systems.
                InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_063);
                if (serviceProfile == null || ASSOC_STATE_UNASSOCIATED.equals(serviceProfile.getAssocState())) {
                    LOGGER.info("SP {} AssocState is marked unassociated. Bind ServiceProfileToBlade failed", spDn);
                    throw new Exception(BIND_SERVICE_PROFILE_TO_BLADE_STEP + " failed.  ServiceProfile state is "
                            + serviceProfile == null ? "null" : serviceProfile.getAssocState());
                }

                // Test mechanism to invoke a failure. No-op on production
                // systems.
                InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_064);
                setComputeElementAttrFromBoundLsServer(_dbClient, computeElement, serviceProfile, host,
                        computeSystem.getSystemType(), false);
                LOGGER.info("Done binding Service Profile : " + spDn + " to blade : " + computeElement.getLabel());

                WorkflowStepCompleter.stepSucceded(stepId);
            } else {
                LOGGER.info("Unable to associate computeElement and LsServer/serviceProfile attribute.  ComputeElement is null.");
                throw new Exception(BIND_SERVICE_PROFILE_TO_BLADE_STEP + " failed.");
            }
        } catch (Exception e) {
            LOGGER.error("Step : " + BIND_SERVICE_PROFILE_TO_BLADE_STEP + " Failed...", e);
            WorkflowStepCompleter.stepFailed(
                    stepId,
                    ComputeSystemControllerException.exceptions.unableToProvisionHost(spDn,
                            computeSystem.getNativeGuid(), e));
        }
    }

    private URL getUcsmURL(ComputeSystem cs) {
        URL ucsmURL;
        try {
            if (cs.getSecure()) {
                ucsmURL = new URL("https", cs.getIpAddress(), cs.getPortNumber(), "/nuova");
            } else {
                ucsmURL = new URL("http", cs.getIpAddress(), cs.getPortNumber(), "/nuova");
            }
        } catch (MalformedURLException e) {
            LOGGER.error("Invalid IP Address / Hostname / Port for Compute System: " + cs.getId(), e);
            throw DeviceControllerException.exceptions.invalidURI(e);
        }
        return ucsmURL;
    }

    public void addHostPortsToVArrayNetworks(VirtualArray varray, Host host, String stepId) {

        LOGGER.info("Adding host ports to networks in Varray : " + varray.getLabel() + "for Host: "
                + host.getHostName());

        WorkflowStepCompleter.stepExecuting(stepId);
        try {

            if (!NullColumnValueGetter.isNullURI(host.getComputeElement())) {

                Map<Network, List<String>> networkToInitiatorMap = Collections
                        .synchronizedMap(new HashMap<Network, List<String>>());

                Map<String, Network> networkIdNetworkMapInVarray = getVarrayNetworkMap(_dbClient, varray.getId());

                URIQueryResultList ceHBAUriList = new URIQueryResultList();

                _dbClient.queryByConstraint(ContainmentConstraint.Factory.getHostComputeElemetHBAsConstraint(host
                        .getId()), ceHBAUriList);

                Iterator<URI> ceHBAUriListIterator = ceHBAUriList.iterator();

                Cluster cluster = null;
                if (host.getCluster() != null) {
                    cluster = _dbClient.queryObject(Cluster.class, host.getCluster());
                }

                while (ceHBAUriListIterator.hasNext()) {
                    URI ceHBAUri = ceHBAUriListIterator.next();

                    ComputeElementHBA computeElementHBA = _dbClient.queryObject(ComputeElementHBA.class, ceHBAUri);
                    Initiator initiator = new Initiator();
                    initiator.setHost(host.getId());
                    initiator.setHostName(host.getHostName());
                    initiator.setId(URIUtil.createId(Initiator.class));

                    if (cluster != null) {
                        initiator.setClusterName(cluster.getLabel());
                    }

                    initiator.setInitiatorNode(computeElementHBA.getNode());
                    initiator.setInitiatorPort(computeElementHBA.getPort());
                    initiator.setProtocol(computeElementHBA.getProtocol() != null ? computeElementHBA.getProtocol()
                            .toUpperCase() : null);

                    Network network = networkIdNetworkMapInVarray.get(computeElementHBA.getVsanId());

                    // Test mechanism to invoke a failure. No-op on production systems.
                    InvokeTestFailure.internalOnlyInvokeTestFailure(InvokeTestFailure.ARTIFICIAL_FAILURE_065);
                    if (network == null) {
                        LOGGER.error("No corresponding Network for HBA {} in vArray {}.  Network null from DB.",
                                computeElementHBA.getPort(), varray.getId());
                        throw new RuntimeException(
                                ComputeSystemControllerException.exceptions.noCorrespondingNetworkForHBAInVarray(
                                        computeElementHBA.getPort(), varray.getLabel(), null));

                    } else {

                        if (networkToInitiatorMap.get(network) == null) {
                            networkToInitiatorMap.put(network, new ArrayList<String>());
                        }

                        networkToInitiatorMap.get(network).add(computeElementHBA.getPort());
                    }

                    _dbClient.createObject(initiator);
                }

                /**
                 * Add all the newly added endpoints to their respective networks!
                 */
                for (Network network : networkToInitiatorMap.keySet()) {

                    network.addEndpoints(networkToInitiatorMap.get(network), false);

                    _dbClient.persistObject(network);

                    handleEndpointsAdded(network, networkToInitiatorMap.get(network), _dbClient, _coordinator);
                }
            }

            WorkflowStepCompleter.stepSucceded(stepId);
            LOGGER.info("Done adding host ports to networks in Varray : " + varray.getLabel() + "for Host: "
                    + host.getHostName());
        } catch (Exception ex) {
            LOGGER.error("Exception while adding host ports to vArray networks. {}", ex);
            WorkflowStepCompleter.stepFailed(stepId, ComputeSystemControllerException.exceptions
                    .unableToAddHostPortsToVArrayNetworks(varray.getLabel()
                            .toString(), ex));

        }
    }

    private void handleEndpointsAdded(Network network, Collection<String> endpoints, DbClient dbClient,
            CoordinatorClient coordinator) {
        // find if the endpoints exit in some old transport zone
        Map<String, Network> transportZoneMap = NetworkAssociationHelper.getNetworksMap(endpoints, dbClient);
        if (!transportZoneMap.isEmpty()) {
            LOGGER.info("Added endpoints {} to network {}", endpoints.toArray(), network.getLabel());
            // before we add the endpoints, they need to be removed from their
            // old transport zones
            NetworkAssociationHelper.handleRemoveFromOldNetworks(transportZoneMap, network, dbClient, coordinator);
        }
        // now, add the the endpoints
        NetworkAssociationHelper.handleEndpointsAdded(network, endpoints, dbClient, coordinator);
    }

    private Map<String, Network> getVarrayNetworkMap(DbClient dbClient, URI varray) {

        Map<String, Network> networkIdNetworkMapInVarray = new HashMap<String, Network>();

        List<Network> varrayAssociatedNetworks = CustomQueryUtility.queryActiveResourcesByRelation(dbClient, varray,
                Network.class, "connectedVirtualArrays");

        for (Network network : varrayAssociatedNetworks) {
            networkIdNetworkMapInVarray.put(network.getNativeId(), network);
        }
        return networkIdNetworkMapInVarray;

    }

    @Override
    public void setNoBoot(ComputeSystem cs, URI computeElementId, URI hostId,
            boolean waitForServerRestart) throws InternalException {

        ComputeElement computeElement = _dbClient.queryObject(ComputeElement.class, computeElementId);

        try {
            if (null != computeElement) {
                LsServer lsServer = ucsmService.setServiceProfileToNoBoot(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(),
                        computeElement.getDn());
                if (lsServer != null) {
                    if (waitForServerRestart) {
                        pullAndPollManagedObject(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(),
                                computeElement.getDn(), LsServer.class);
                    }
                }else {
                    throw new RuntimeException("Failed to set no boot target due to error from UCSM Service");
                }
            } else {
                throw new RuntimeException("ComputeElement object is null for id " + computeElementId);
            }
        } catch (Exception e) {
            throw ComputeSystemControllerException.exceptions.unableToSetNoBoot(
                    computeElement != null ? computeElement.getLabel() : computeElementId.toString(), e);
        }

    }


    @Override
    public void setLanBootTarget(ComputeSystem cs, URI computeElementId, URI hostId,
            boolean waitForServerRestart) throws InternalException {

        ComputeElement computeElement = _dbClient.queryObject(ComputeElement.class, computeElementId);

        try {
            if (null != computeElement) {
                LsServer lsServer = ucsmService.setServiceProfileToLanBoot(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(),
                        computeElement.getDn());
                if (lsServer != null) {
                    if (waitForServerRestart) {
                        pullAndPollManagedObject(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(),
                                computeElement.getDn(), LsServer.class);
                    }
                }else {
                    throw new RuntimeException("Failed to set LAN boot target due to error from UCSM Service");
                }
            } else {
                throw new RuntimeException("ComputeElement object is null for id " + computeElementId);
            }
        } catch (Exception e) {
            throw ComputeSystemControllerException.exceptions.unableToSetLanBoot(computeElementId.toString(), e);
        }

    }

    @Override
    public void setSanBootTarget(ComputeSystem cs, URI computeElementId, URI hostId, URI volumeId,
            boolean waitForServerRestart) throws InternalException {

        ComputeElement computeElement = _dbClient.queryObject(ComputeElement.class, computeElementId);

        Map<String, Map<String, Integer>> hbaToStoragePorts = getHBAToStoragePorts(volumeId, hostId);

        // VBDU TODO: COP-28455, HBAToStoragePorts are being constructed from the zoning map, for co-existence masks the
        // zoning map will be empty in older releases. Do we need to still continue this operation?
        try {
            if (null != computeElement) {
                LsServer lsServer = ucsmService.setServiceProfileToSanBoot(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(),
                        computeElement.getDn(), hbaToStoragePorts);
                if (lsServer != null) {
                    if (waitForServerRestart) {
                        pullAndPollManagedObject(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(),
                                computeElement.getDn(), LsServer.class);
                    }
                }else {
                    throw new RuntimeException("Failed to set SAN boot target due to error from UCSM Service");
                }
            } else {
                throw new RuntimeException("ComputeElement object is null for id " + computeElementId);
            }
        } catch (Exception e) {
            throw ComputeSystemControllerException.exceptions.unableToSetSanBootTarget(computeElementId.toString(), e);
        }

    }

    private Map<String, Map<String, Integer>> getHBAToStoragePorts(URI volumeId, URI hostId) {

        Host host = _dbClient.queryObject(Host.class, hostId);
        Map<String, String> initiatorToHBAMapping = getInitiatorToHBAMapping(host.getComputeElement());

        Volume volume = _dbClient.queryObject(Volume.class, volumeId);

        List<Initiator> initiators = CustomQueryUtility.queryActiveResourcesByRelation(_dbClient, hostId,
                Initiator.class, "host");

        Map<ExportMask, ExportGroup> exportMasks = ExportUtils.getExportMasks(volume, _dbClient);

        Map<String, Map<String, Integer>> hbaToStoragePortMapForBoot = new HashMap<String, Map<String, Integer>>();

        for (Initiator initiator : initiators) {
            for (ExportMask exportMask : exportMasks.keySet()) {
                List<URI> storagePorts = ExportUtils.getInitiatorPortsInMask(exportMask, initiator, _dbClient);
                if (storagePorts != null && !storagePorts.isEmpty()) {

                    Integer volumeHLU = Integer.valueOf(exportMask.getVolumes().get(volumeId.toString()));

                    for (URI storagePortUri : storagePorts) {
                        StoragePort port = _dbClient.queryObject(StoragePort.class, storagePortUri);

                        String hbaName = initiatorToHBAMapping.get(initiator.getInitiatorPort());

                        if (hbaName != null) {
                            if (hbaToStoragePortMapForBoot.get(hbaName) == null) {
                                hbaToStoragePortMapForBoot.put(hbaName, new HashMap<String, Integer>());
                            }
                            hbaToStoragePortMapForBoot.get(hbaName).put(port.getPortNetworkId(), volumeHLU);
                        }
                    }

                }

            }

        }

        return hbaToStoragePortMapForBoot;
    }

    private Map<String, String> getInitiatorToHBAMapping(URI computeElement) {

        Map<String, String> hostInitiatorToHBAMapping = new HashMap<String, String>();

        List<ComputeElementHBA> hbas = CustomQueryUtility.queryActiveResourcesByRelation(_dbClient, computeElement,
                ComputeElementHBA.class, "computeElement");

        for (ComputeElementHBA hba : hbas) {
            hostInitiatorToHBAMapping.put(hba.getPort(), hba.getLabel());
        }

        return hostInitiatorToHBAMapping;

    }

    @Override
    public void clearDeviceSession(URI computeSystemId) throws InternalException {
        try {
            ComputeSystem cs = _dbClient.queryObject(ComputeSystem.class, computeSystemId);
            URL ucsmURL = getUcsmURL(cs);
            ucsmService.clearDeviceSession(ucsmURL.toString(), cs.getUsername(), cs.getPassword());
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    @Override
    public void deactivateHost(ComputeSystem cs, Host host) throws ClientGeneralException {
       LOGGER.info("deactivateHost");
       try{
          unbindHostFromComputeElement(cs,host);
          deleteServiceProfile(cs,host);
       }catch (ClientGeneralException e){
           LOGGER.warn("Unable to deactivate host : ", e);
           throw e;
       }catch (Exception ex) {
            LOGGER.error("Error while deactivating host {} check stacktrace", host.getLabel(), ex);
            throw ex;
       }
    }

   /*
   * Unbinds the host's service profile from the associated blade.
   * Determines the service profile to unbind using host's serviceProfile association.
   * In case of host provisioned using pre-Anakin version of ViPR and no serviceProfile association yet set,
   * serviceprofile to unbind will be determined by trying to find a serviceProfile that matches
   * the computeElement's uuid. 
   */
    private void unbindHostFromComputeElement(ComputeSystem cs, Host host) throws ClientGeneralException{
        // VBDU TODO: COP-28452, Check initiators count, if empty do we still need to delete service profile?
        if (host != null && !NullColumnValueGetter.isNullURI(host.getComputeElement())) {
            ComputeElement computeElement = _dbClient.queryObject(ComputeElement.class, host.getComputeElement());
            if (computeElement == null){
                LOGGER.error("Host "+ host.getLabel()+ " has associated computeElementURI: "+ host.getComputeElement()+ " which is an invalid reference");
                LOGGER.info("Service profile unbind will not be triggered");
                return ;
            }
            String spDn = null;
            LOGGER.info("Host.uuid: "+host.getUuid() + " ComputeElement.uuid: "+  computeElement.getUuid());
            if (NullColumnValueGetter.isNullURI(host.getServiceProfile())){
                LOGGER.info("Host has no service profile asscoaition. trying to determine service profile to unbind based on compute element uuid: "+ computeElement.getUuid());
                if (computeElement.getUuid() != null ) {
                    LsServer sp = ucsmService.getLsServer(getUcsmURL(cs).toString(),
                            cs.getUsername(), cs.getPassword(), computeElement.getUuid());
                    if (sp!=null){
                        spDn = sp.getDn();
                        LOGGER.info("Found service profile {} matching uuid {}", spDn, computeElement.getUuid());
                    }else{
                        LOGGER.info("No service profile found with uuid {}. Nothing to unbind.", computeElement.getUuid());
                        return;
                    }
               }
            }else{
                UCSServiceProfile serviceProfile =  _dbClient.queryObject(UCSServiceProfile.class, host.getServiceProfile());
                if (serviceProfile == null){
                    LOGGER.error("Host "+ host.getLabel()+ " has associated serviceProfileURI: "+ host.getServiceProfile()+ " which is an invalid reference");
                    LOGGER.info("Service profile unbind will not be triggered");
                    return ;
                }else {
                    spDn = serviceProfile.getDn();
                    LOGGER.info("Host.uuid: "+host.getUuid() + " ComputeElement.uuid: "+  computeElement.getUuid() + "serviceProfile.uuid:" + serviceProfile.getUuid());
                }
            }
            if (spDn!=null){
                LOGGER.info("Unbinding service profile with dn: "+ spDn);
                LsServer unboundServiceProfile = ucsmService.unbindServiceProfile(getUcsmURL(cs).toString(),
                     cs.getUsername(), cs.getPassword(), spDn);
                LOGGER.debug("Operational state of Deleted Service Profile : " + unboundServiceProfile.getOperState());
                ComputeBlade computeBlade = pullAndPollManagedObject(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(),
                        computeElement.getLabel(), ComputeBlade.class);
                if (computeBlade == null){
                    LOGGER.info("ComputeBlade "+ computeElement.getLabel()+ " not found on UCS");
                } else {
                    // Release the computeElement back into the pool as soon as we have unbound it from the service profile
                    if (LsServerOperStates.UNASSOCIATED.equals(LsServerOperStates.fromString(computeBlade.getOperState()))) {
                         computeElement.setAvailable(true);
                         _dbClient.persistObject(computeElement);
                    }
                }
      
             }
        }else {
           LOGGER.info("NO OP. Host is null or has no asscoaited computeElement");
        }
    }

  /*
   * Deletes the host's service profile.
   * Determines the service profile to delete using host's serviceProfile association.
   * In case of host provisioned using pre-Anakin version of ViPR and no serviceProfile association yet set,
   * serviceprofile to delete will be determined by trying to find a serviceProfile that matches
   * the computeElement's uuid. 
   */

    private void deleteServiceProfile(ComputeSystem cs, Host host) throws ClientGeneralException {
        UCSServiceProfile serviceProfile = null;
        if (host!=null){
            String spDn = null;
            if (NullColumnValueGetter.isNullURI(host.getServiceProfile())){
                if (NullColumnValueGetter.isNullURI(host.getComputeElement())) {
                    LOGGER.info("Host has no associated service profile or compute element . cannot delete service profile.");
                   return;
                }
                ComputeElement computeElement = _dbClient.queryObject(ComputeElement.class, host.getComputeElement());
                if (computeElement != null){ 
                    LOGGER.info("Host has no associated service profile. Trying to determine service profile to delete based on associated computeElement's uuid {} ", computeElement.getUuid());
                    if (computeElement.getUuid() != null ) {
                        LsServer sp = ucsmService.getLsServer(getUcsmURL(cs).toString(),
                            cs.getUsername(), cs.getPassword(), computeElement.getUuid());
                        if (sp!=null){
                            spDn = sp.getDn();
                            LOGGER.info("Found service profile {} matching uuid {}", spDn, computeElement.getUuid());
                        }else{
                            LOGGER.info("No service profile found with uuid {}. Nothing to delete.", computeElement.getUuid());
                            return;
                        }
                   }
                } else {
                   LOGGER.info("Host has no associated service profile and no valid compute element association. cannot delete service profile.");
                   return;
                }
            } else {
                serviceProfile =  _dbClient.queryObject(UCSServiceProfile.class, host.getServiceProfile());
                if (serviceProfile == null){
                   LOGGER.error("Host "+ host.getLabel()+ " has associated serviceProfileURI: "+ host.getServiceProfile()+ " which is an invalid reference");
                   LOGGER.info("Service profile deletion will not be triggered");
                   return;
                }else {
                   spDn = serviceProfile.getDn();
                }
            }
            if (spDn!=null){
                LOGGER.info("Deleting serviceProfile " + spDn );
                ucsmService.deleteServiceProfile(getUcsmURL(cs).toString(), cs.getUsername(), cs.getPassword(),spDn);
                host.setServiceProfile(NullColumnValueGetter.getNullURI());
                _dbClient.persistObject(host);
                if (serviceProfile!=null){
                    _dbClient.markForDeletion(serviceProfile);
                }

            } else {
                LOGGER.info("No service profile to delete");
            }
            // On successful deletion of the service profile - get rid of the objects that represent objects from the service profile
            LOGGER.info("Removing host endpoints");
            removeHostInitiatorsFromNetworks(host);
       }else {
           LOGGER.info("host is null. NO OP");
       }
    }

    /**
     * Gets rid of the Initiators that were added to network. Also gets rid of
     * the ComputeElementHBAs that were created when the service profile was
     * bound to the host
     *
     * @param host
     */
    private void removeHostInitiatorsFromNetworks(Host host) {

        URIQueryResultList ceHBAUriList = new URIQueryResultList();

        _dbClient.queryByConstraint(
                ContainmentConstraint.Factory.getHostComputeElemetHBAsConstraint(host.getId()),
                ceHBAUriList);

        Set<Network> networks = new HashSet<Network>();

        List<String> endpoints = new ArrayList<String>();

        List<ComputeElementHBA> computeElementHBAs = _dbClient.queryObject(ComputeElementHBA.class, ceHBAUriList, true);

        for (ComputeElementHBA computeElementHBA : computeElementHBAs) {

            endpoints.add(computeElementHBA.getPort());

            networks.addAll(CustomQueryUtility.queryActiveResourcesByAltId(_dbClient, Network.class, "nativeId",
                    computeElementHBA.getVsanId()));

            _dbClient.markForDeletion(computeElementHBA);
        }

        for (Network network : networks) {
            Collection<String> removedEndpoints = network.removeEndpoints(endpoints);
            NetworkAssociationHelper.handleEndpointsRemoved(network, removedEndpoints, _dbClient, _coordinator);
        }
        _dbClient.persistObject(networks);

    }


    private static final String ROLLBACK_NOTHING_METHOD = "rollbackNothingMethod";
    /**
     * This is needed if any of the workflow steps do not have a real rollback method.
     *
     * @param stepId
     */
    public void rollbackNothingMethod(String stepId) {
        LOGGER.info("ROLLBACK_NOTHING_METHOD invoked nothing to be done, apart from success...");
        WorkflowStepCompleter.stepSucceded(stepId);
    }

}

