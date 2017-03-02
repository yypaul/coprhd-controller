/*
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource;

import static com.emc.storageos.api.mapper.DbObjectMapper.toNamedRelatedResource;
import static com.emc.storageos.api.mapper.TaskMapper.toTask;
import static com.emc.storageos.db.client.constraint.AlternateIdConstraint.Factory.getVolumesByAssociatedId;
import static com.emc.storageos.db.client.util.CommonTransformerFunctions.FCTN_STRING_TO_URI;
import static com.emc.storageos.db.client.util.CommonTransformerFunctions.FCTN_VPLEX_MIRROR_TO_URI;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Collections2.transform;
import static com.google.common.collect.Iterables.removeIf;
import static java.lang.String.format;

import java.net.URI;
import java.net.URISyntaxException;
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
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.emc.storageos.api.service.authorization.PermissionsHelper;
import com.emc.storageos.api.service.impl.placement.StorageScheduler;
import com.emc.storageos.api.service.impl.placement.VPlexScheduler;
import com.emc.storageos.api.service.impl.placement.VirtualPoolUtil;
import com.emc.storageos.api.service.impl.placement.VolumeRecommendation;
import com.emc.storageos.api.service.impl.placement.VolumeRecommendation.VolumeType;
import com.emc.storageos.api.service.impl.placement.VpoolUse;
import com.emc.storageos.api.service.impl.resource.fullcopy.BlockFullCopyManager;
import com.emc.storageos.api.service.impl.resource.fullcopy.BlockFullCopyUtils;
import com.emc.storageos.api.service.impl.resource.utils.BlockServiceUtils;
import com.emc.storageos.api.service.impl.resource.utils.VirtualPoolChangeAnalyzer;
import com.emc.storageos.api.service.impl.resource.utils.VolumeIngestionUtil;
import com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationController;
import com.emc.storageos.blockorchestrationcontroller.VolumeDescriptor;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.ContainmentPrefixConstraint;
import com.emc.storageos.db.client.constraint.PrefixConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockConsistencyGroup.Types;
import com.emc.storageos.db.client.model.BlockMirror;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.BlockSnapshotSession;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.DataObject.Flag;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.Migration;
import com.emc.storageos.db.client.model.NamedURI;
import com.emc.storageos.db.client.model.OpStatusMap;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.StorageProtocol;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.Task;
import com.emc.storageos.db.client.model.TenantOrg;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.Volume.PersonalityTypes;
import com.emc.storageos.db.client.model.VolumeGroup;
import com.emc.storageos.db.client.model.VplexMirror;
import com.emc.storageos.db.client.model.VpoolRemoteCopyProtectionSettings;
import com.emc.storageos.db.client.model.util.BlockConsistencyGroupUtils;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.client.util.ResourceOnlyNameGenerator;
import com.emc.storageos.db.client.util.SizeUtil;
import com.emc.storageos.db.client.util.StringSetUtil;
import com.emc.storageos.db.common.DependencyChecker;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.model.ResourceTypeEnum;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.TaskResourceRep;
import com.emc.storageos.model.application.VolumeGroupUpdateParam.VolumeGroupVolumeList;
import com.emc.storageos.model.block.NativeContinuousCopyCreate;
import com.emc.storageos.model.block.VirtualPoolChangeParam;
import com.emc.storageos.model.block.VolumeCreate;
import com.emc.storageos.model.block.VolumeDeleteTypeEnum;
import com.emc.storageos.model.project.ProjectElement;
import com.emc.storageos.model.project.ProjectParam;
import com.emc.storageos.model.systems.StorageSystemConnectivityList;
import com.emc.storageos.model.systems.StorageSystemConnectivityRestRep;
import com.emc.storageos.model.vpool.VirtualPoolChangeOperationEnum;
import com.emc.storageos.security.authorization.BasePermissionsHelper;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.svcs.errorhandling.resources.BadRequestException;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.svcs.errorhandling.resources.InternalServerErrorException;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCode;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCodeException;
import com.emc.storageos.util.ConnectivityUtil;
import com.emc.storageos.util.ExportUtils;
import com.emc.storageos.util.VPlexSrdfUtil;
import com.emc.storageos.util.VPlexUtil;
import com.emc.storageos.volumecontroller.ApplicationAddVolumeList;
import com.emc.storageos.volumecontroller.BlockController;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.Recommendation;
import com.emc.storageos.volumecontroller.SRDFCopyRecommendation;
import com.emc.storageos.volumecontroller.SRDFRecommendation;
import com.emc.storageos.volumecontroller.VPlexRecommendation;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.smis.SmisConstants;
import com.emc.storageos.volumecontroller.impl.utils.ControllerOperationValuesWrapper;
import com.emc.storageos.volumecontroller.impl.utils.ExportMaskUtils;
import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;
import com.emc.storageos.volumecontroller.impl.xtremio.prov.utils.XtremIOProvUtils;
import com.emc.storageos.vplexcontroller.VPlexController;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Table;

/**
 * Implementation of the {@link BlockServiceApi} when the VirtualPool specifies that created
 * volumes should have VPlex high availability.
 */
public class VPlexBlockServiceApiImpl extends AbstractBlockServiceApiImpl<VPlexScheduler> {
    public VPlexBlockServiceApiImpl() {
        super(DiscoveredDataObject.Type.vplex.name());
    }

    // Constants define the controller service path in Zoo Keeper.
    protected final static String CONTROLLER_SVC = "controllersvc";
    protected final static String CONTROLLER_SVC_VER = "1";
    private static final int MAX_VOLUMES_IN_CG = 1000;
    private static final String SRC_BACKEND_VOL_LABEL_SUFFIX = "-0";
    private static final String HA_BACKEND_VOL_LABEL_SUFFIX = "-1";
    private static final String MIGRATION_LABEL_SUFFIX = "m";

    private static final String VIRTUAL_ARRAYS_CONSTRAINT = "virtualArrays";

    // A logger reference.
    private static final Logger s_logger = LoggerFactory
            .getLogger(VPlexBlockServiceApiImpl.class);

    @Autowired
    private final PermissionsHelper _permissionsHelper = null;

    @Autowired
    private DependencyChecker _dependencyChecker;

    @Autowired
    private TenantsService _tenantsService;

    // The max number of volumes allowed in a CG for varray and vpool
    // changes resulting in backend data migrations. Set in the API
    // service configuration file.
    private int _maxCgVolumesForMigration;

    /**
     * Public setter for Spring configuration.
     *
     * @param maxCgVols
     *            Max number of volumes allowed in a CG for varray and
     *            vpool changes resulting in backend data migrations
     */
    public void setMaxCgVolumesForMigration(int maxCgVols) {
        _maxCgVolumesForMigration = maxCgVols;
    }

    /**
     * Generate a unique volume label based given prefix, its array and volume indices.
     *
     * @param baseVolumeLabel
     *            - volume prefix name
     * @param nhIndex
     *            - array index
     * @param volumeIndex
     *            - volume index
     * @param resourceCount
     *            - volume's virtual pool capacity resource count
     * @return
     */
    private String generateVolumeLabel(String baseVolumeLabel, int nhIndex, int volumeIndex, int resourceCount) {
        StringBuilder volumeLabelBuilder = new StringBuilder(baseVolumeLabel);
        volumeLabelBuilder.append("-").append(nhIndex);
        if (resourceCount > 1) {
            volumeLabelBuilder.append("-").append(volumeIndex + 1);
        }
        return volumeLabelBuilder.toString();
    }

    /**
     * Convenient method to generate volume labels and check for duplicate. If there is a duplicate, throw exception
     *
     * @param baseVolumeLabel
     *            - volume prefix
     * @param project
     *            - project volume creates within
     * @param vArray
     *            - virtual array where volume is create
     * @param vPool
     *            - volume's vpool
     * @param vPoolCapabilities
     *            - vpool capabilities
     * @param varrayRecomendationsMap
     *            - map of virtual array to its list of recommendation
     */
    private void validateVolumeLabels(String baseVolumeLabel, Project project,
            VirtualPoolCapabilityValuesWrapper vPoolCapabilities, Map<String, List<VPlexRecommendation>> varrayRecomendationsMap) {
        int varrayCount = 0;
        Iterator<String> varrayIter = varrayRecomendationsMap.keySet().iterator();
        while (varrayIter.hasNext()) {
            String varrayId = varrayIter.next();
            s_logger.info("Processing recommendations for virtual array {}", varrayId);
            int volumeCounter = 0;
            // Sum the resource counts from all recommendations.
            int totalResourceCount = 0;
            for (VPlexRecommendation recommendation : varrayRecomendationsMap.get(varrayId)) {
                totalResourceCount += recommendation.getResourceCount();
            }
            Iterator<VPlexRecommendation> recommendationsIter = varrayRecomendationsMap.get(varrayId).iterator();

            while (recommendationsIter.hasNext()) {
                VPlexRecommendation recommendation = recommendationsIter.next();
                URI storagePoolURI = recommendation.getSourceStoragePool();
                VirtualPool volumeVpool = recommendation.getVirtualPool();
                s_logger.info("Volume virtual pool is {}", volumeVpool.getId().toString());
                vPoolCapabilities.put(VirtualPoolCapabilityValuesWrapper.AUTO_TIER__POLICY_NAME, volumeVpool.getAutoTierPolicyName());
                s_logger.info("Recommendation is for {} resources in pool {}", recommendation.getResourceCount(),
                        storagePoolURI.toString());
                for (int i = 0; i < recommendation.getResourceCount(); i++) {
                    // Each volume has a unique label based off the passed
                    // value. Note that the way the storage system creates
                    // the actual volumes in a multi volume request, the
                    // names given the Bourne volumes here likely will not
                    // match the names given by the storage system. If desired,
                    // we will need to update the actual volumes after they
                    // are created to match the names given here. Currently,
                    // this is not implemented.
                    String volumeLabel = generateVolumeLabel(baseVolumeLabel, varrayCount, volumeCounter, totalResourceCount);

                    // throw exception of duplicate found
                    validateVolumeLabel(volumeLabel, project);
                    s_logger.info("Volume label is {}", volumeLabel);
                    volumeCounter++;
                }
            }
            varrayCount++;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws InternalException
     */
    @Override
    public TaskList createVolumes(VolumeCreate param, Project project,
            VirtualArray vArray, VirtualPool vPool, Map<VpoolUse, List<Recommendation>> recommendationMap, TaskList taskList,
            String task, VirtualPoolCapabilityValuesWrapper vPoolCapabilities) throws InternalException {
        List<Recommendation> volRecommendations = recommendationMap.get(VpoolUse.ROOT);
        List<Recommendation> srdfCopyRecommendations = recommendationMap.get(VpoolUse.SRDF_COPY);

        if (taskList == null) {
            taskList = new TaskList();
        }

        List<URI> allVolumes = new ArrayList<URI>();
        List<VolumeDescriptor> descriptors = createVPlexVolumeDescriptors(param, project, vArray, vPool,
                volRecommendations, task, vPoolCapabilities, vPoolCapabilities.getBlockConsistencyGroup(),
                taskList, allVolumes, true);
        for (VolumeDescriptor desc : descriptors) {
            s_logger.info("Vplex Root Descriptors: " + desc.toString());
        }

        if (srdfCopyRecommendations != null) {
            // We may have a Vplex protected SRDF copy- if so add those into the mix
            // This may be a Vplex volume or not
            for (Recommendation srdfCopyRecommendation : srdfCopyRecommendations) {
                vArray = _dbClient.queryObject(VirtualArray.class, srdfCopyRecommendation.getVirtualArray());
                vPool = srdfCopyRecommendation.getVirtualPool();
                List<VolumeDescriptor> srdfCopyDescriptors = new ArrayList<VolumeDescriptor>();
                List<Recommendation> copyRecommendations = new ArrayList<Recommendation>();
                copyRecommendations.add(srdfCopyRecommendation);
                if (srdfCopyRecommendation instanceof VPlexRecommendation) {
                    String name = param.getName();
                    // Do not pass in the consistency group for vplex volumes fronting targets
                    // as we will eventually put them in the target CG.
                    srdfCopyDescriptors = createVPlexVolumeDescriptors(param, project, vArray, vPool,
                            copyRecommendations, task, vPoolCapabilities, null,
                            taskList, allVolumes, true);
                    param.setName(name);
                } else {
                    srdfCopyDescriptors = super.createVolumesAndDescriptors(srdfCopyDescriptors,
                            param.getName() + "_srdf_copy", vPoolCapabilities.getSize(), project,
                            vArray, vPool, copyRecommendations,
                            taskList, task, vPoolCapabilities);
                }
                for (VolumeDescriptor desc : srdfCopyDescriptors) {
                    s_logger.info("SRDF Copy: " + desc.toString());
                }
                descriptors.addAll(srdfCopyDescriptors);
            }
        }

        // Log volume descriptor information
        logVolumeDescriptorPrecreateInfo(descriptors, task);

        // Now we get the Orchestration controller and use it to create the volumes of all types.
        try {
            BlockOrchestrationController controller = getController(BlockOrchestrationController.class,
                    BlockOrchestrationController.BLOCK_ORCHESTRATION_DEVICE);
            controller.createVolumes(descriptors, task);
        } catch (InternalException e) {
            if (s_logger.isErrorEnabled()) {
                s_logger.error("Controller error", e);
            }
            String errMsg = String.format("Controller error: %s", e.getMessage());
            Operation statusUpdate = new Operation(Operation.Status.error.name(), errMsg);
            for (URI volumeURI : allVolumes) {
                _dbClient.updateTaskOpStatus(Volume.class, volumeURI, task, statusUpdate);
            }
            for (TaskResourceRep volumeTask : taskList.getTaskList()) {
                volumeTask.setState(Operation.Status.error.name());
                volumeTask.setMessage(errMsg);
            }
            throw e;
        }
        return taskList;
    }

    @Override
    public List<VolumeDescriptor> createVolumesAndDescriptors(List<VolumeDescriptor> descriptors, String name, Long size, Project project,
            VirtualArray varray, VirtualPool vpool, List<Recommendation> recommendations, TaskList taskList, String task,
            VirtualPoolCapabilityValuesWrapper vpoolCapabilities) {
        // Not currently called from AbstractBlockServiceApiImpl.createVolumesAndDescriptors
        throw DeviceControllerException.exceptions.operationNotSupported();
    }

    /**
     * Create the necessary volume descriptors for Vplex volumes, including the backend
     * volume descriptors and the virtual volume descriptors.
     * 
     * @param param
     *            - the VolumeCreate parameters
     * @param project
     *            -- user's project
     * @param vArray
     *            -- virtual array volumes are created in
     * @param vPool
     *            -- virtual pool (ROOT) used to create the volumes
     * @param recommendations
     *            -- recommendations received from placement
     * @param task
     *            -- a task identifier
     * @param vPoolCapabilities
     *            - VirtualPoolCapabilitiesWrapper
     * @param blockConsistencyGroupURI
     *            - the block consistency group URI
     * @param taskList
     *            - OUTPUT list of tasks created
     * @param allVolumes
     *            - OUTPUT - list of volumes created
     * @param createTask
     *            -- boolean flag indicating to create tasks
     * @return
     */
    public List<VolumeDescriptor> createVPlexVolumeDescriptors(VolumeCreate param, Project project,
            final VirtualArray vArray, final VirtualPool vPool, List<Recommendation> recommendations,
            String task, VirtualPoolCapabilityValuesWrapper vPoolCapabilities,
            URI blockConsistencyGroupURI, TaskList taskList, List<URI> allVolumes, boolean createTask) {
        s_logger.info("Request to create {} VPlex virtual volume(s)",
                vPoolCapabilities.getResourceCount());

        // Determine if we're processing an SRDF copy so we can set appropriate name.
        boolean srdfCopy = false;
        if (recommendations.get(0).getRecommendation() != null
                && recommendations.get(0).getRecommendation() instanceof SRDFCopyRecommendation) {
            srdfCopy = true;
        }
        // Sort the recommendations by VirtualArray. There can be up to two
        // VirtualArrays, the requested VirtualArray and the HA VirtualArray
        // either passed or determined by the placement when HA virtual volumes
        // are being created. We also set the VPlex storage system, which
        // should be the same for all recommendations.
        URI vplexStorageSystemURI = null;
        URI[] vplexSystemURIOut = new URI[1];
        Map<String, List<VPlexRecommendation>> varrayRecommendationsMap = sortRecommendationsByVarray(recommendations, vplexSystemURIOut);
        vplexStorageSystemURI = vplexSystemURIOut[0];

        // check for potential duplicate volume labels before creating them, but not
        // for the srdf copies, since they are already pre-created.
        if (!srdfCopy) {
            validateVolumeLabels(param.getName(), project, vPoolCapabilities, varrayRecommendationsMap);
        }

        // Determine the project to be used for the VPlex's artifacts
        StorageSystem vplexStorageSystem = _dbClient.queryObject(StorageSystem.class,
                vplexStorageSystemURI);
        Project vplexProject = getVplexProject(vplexStorageSystem, _dbClient, _tenantsService);

        // The volume size.
        long size = SizeUtil.translateSize(param.getSize());

        // The consistency group or null when not specified.
        final BlockConsistencyGroup consistencyGroup = blockConsistencyGroupURI == null ? null
                : _dbClient.queryObject(BlockConsistencyGroup.class, blockConsistencyGroupURI);

        // Find all volumes assigned to the group
        boolean cgContainsVolumes = false;
        if (consistencyGroup != null) {
            final List<Volume> activeCGVolumes = getActiveCGVolumes(consistencyGroup);
            cgContainsVolumes = (activeCGVolumes != null && !activeCGVolumes.isEmpty());
        }

        // If the consistency group is created but does not specify the LOCAL
        // type, the CG must be a CG created prior to 2.2 or an ingested CG. In
        // this case, we don't want a volume creation to result in backend CGs.
        // The only exception is if the CG does not reference any volumes. In
        // this case, if the LOCAL type isn't specified, we can create backend
        // CGs.
        BlockConsistencyGroup backendCG = null;
        if (consistencyGroup != null && (!consistencyGroup.created() ||
                !cgContainsVolumes || consistencyGroup.getTypes().contains(Types.LOCAL.toString()))) {
            backendCG = consistencyGroup;
        }

        // Prepare Bourne volumes to represent the backend volumes for the
        // recommendations in each VirtualArray.
        int varrayCount = 0;
        String volumeLabel = param.getName();
        List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();

        URI[][] varrayVolumeURIs = new URI[2][vPoolCapabilities.getResourceCount()];
        Iterator<String> varrayIter = varrayRecommendationsMap.keySet().iterator();
        while (varrayIter.hasNext()) {
            String varrayId = varrayIter.next();
            s_logger.info("Processing backend recommendations for Virtual Array {}", varrayId);
            List<VPlexRecommendation> vplexRecommendations = varrayRecommendationsMap.get(varrayId);
            List<VolumeDescriptor> varrayDescriptors = makeBackendVolumeDescriptors(
                    vplexRecommendations, project, vplexProject, vPool, volumeLabel, varrayCount,
                    size, backendCG, vPoolCapabilities, createTask, task);
            descriptors.addAll(varrayDescriptors);
            List<URI> varrayURIs = VolumeDescriptor.getVolumeURIs(varrayDescriptors);
            allVolumes.addAll(varrayURIs);
            for (int i = 0; i < varrayURIs.size(); i++) {
                varrayVolumeURIs[varrayCount][i] = varrayURIs.get(i);
            }
            varrayCount++;
        }

        // Prepare Bourne volumes to represent the highly available virtual
        // volumes and associate the virtual volumes with their associated
        // backend volumes.
        s_logger.info("Preparing virtual volumes");
        List<URI> virtualVolumeURIs = new ArrayList<URI>();
        URI nullPoolURI = NullColumnValueGetter.getNullURI();
        vPoolCapabilities.put(VirtualPoolCapabilityValuesWrapper.AUTO_TIER__POLICY_NAME, null);
        for (int i = 0; i < vPoolCapabilities.getResourceCount(); i++) {
            // Compute the volume label based on the label of the underlying volume
            String volumeLabelBuilt = null;
            Volume associatedVolume = _dbClient.queryObject(Volume.class, varrayVolumeURIs[0][i]);
            // Get the virtual volume backing replication group instance name, if available.
            String backingReplicationGroupInstance = null;
            if (associatedVolume != null) {
                volumeLabelBuilt = generateLabelFromAssociatedVolume(volumeLabel, associatedVolume);
                backingReplicationGroupInstance = 
                    NullColumnValueGetter.isNotNullValue(associatedVolume.getReplicationGroupInstance()) ? 
                        associatedVolume.getReplicationGroupInstance() : NullColumnValueGetter.getNullStr();
            } else {
                volumeLabelBuilt = AbstractBlockServiceApiImpl.generateDefaultVolumeLabel(volumeLabel, i,
                        vPoolCapabilities.getResourceCount());
            }
            s_logger.info("Volume label is {}", volumeLabelBuilt);

            Volume volume = StorageScheduler.getPrecreatedVolume(_dbClient, taskList, volumeLabelBuilt);
            boolean volumePrecreated = false;
            if (volume != null) {
                volumePrecreated = true;
            }

            long thinVolumePreAllocationSize = 0;
            if (null != vPool.getThinVolumePreAllocationPercentage()) {
                thinVolumePreAllocationSize = VirtualPoolUtil
                        .getThinVolumePreAllocationSize(
                                vPool.getThinVolumePreAllocationPercentage(), size);
            }

            volume = prepareVolume(VolumeType.VPLEX_VIRTUAL_VOLUME, volume,
                    size, thinVolumePreAllocationSize, project, vArray,
                    vPool, vplexStorageSystemURI, nullPoolURI,
                    volumeLabelBuilt, consistencyGroup, vPoolCapabilities);

            StringSet associatedVolumes = new StringSet();
            associatedVolumes.add(varrayVolumeURIs[0][i].toString());
            s_logger.info("Associating volume {}", varrayVolumeURIs[0][i].toString());
            // If these are HA virtual volumes there are two volumes
            // associated with the virtual volume.
            if (varrayCount > 1) {
                associatedVolumes.add(varrayVolumeURIs[1][i].toString());
                s_logger.info("Associating volume {}", varrayVolumeURIs[1][i].toString());
            }
            volume.setAssociatedVolumes(associatedVolumes);
            if (null != backingReplicationGroupInstance) {
                s_logger.info("Setting virtual volume backingReplicationGroupInstance to {}", backingReplicationGroupInstance);
                volume.setBackingReplicationGroupInstance(backingReplicationGroupInstance);
            }
            _dbClient.updateObject(volume);
            URI volumeId = volume.getId();
            s_logger.info("Prepared virtual volume {}", volumeId);
            virtualVolumeURIs.add(volumeId);
            allVolumes.add(volumeId);
            if (createTask && !volumePrecreated) {
                Operation op = _dbClient.createTaskOpStatus(Volume.class, volume.getId(),
                        task, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME);
                TaskResourceRep volumeTask = toTask(volume, task, op);
                taskList.getTaskList().add(volumeTask);
            }
            VolumeDescriptor descriptor = new VolumeDescriptor(
                    VolumeDescriptor.Type.VPLEX_VIRT_VOLUME, vplexStorageSystemURI, volumeId,
                    null, consistencyGroup == null ? null : consistencyGroup.getId(),
                    vPoolCapabilities, volume.getCapacity());
            
            // Set the compute resource in the descriptor if the volume to be created will be exported

            // to a host/cluster after it has been created so that the compute resource name can be 
            // included in the volume name if the custom volume naming is so configured. Do not set the
            // compute resource if the descriptor is for an SRDF target as the target is not exported
            // to the compute resource.
            URI computeResourceURI = param.getComputeResource();
            if ((computeResourceURI != null) && (!srdfCopy)) {
                s_logger.info(String.format("Volume %s - will be exported to Host/Cluster: %s", volume.getLabel(),
                        computeResourceURI.toString()));
                descriptor.setComputeResource(computeResourceURI);
            }
            descriptors.add(descriptor);
        }

        return descriptors;
    }

    /**
     * {@inheritDoc}
     *
     * @throws InternalException
     */
    @Override
    public void deleteVolumes(final URI systemURI, final List<URI> volumeURIs,
            final String deletionType, final String task) throws InternalException {
        s_logger.info("Request to delete {} volume(s) with VPLEX high availability", volumeURIs.size());
        super.deleteVolumes(systemURI, volumeURIs, deletionType, task);
    }

    public void addDescriptorsForVplexMirrors(List<VolumeDescriptor> descriptors, Volume vplexVolume) {
        if (vplexVolume.getMirrors() != null && vplexVolume.getMirrors().isEmpty() == false) {
            for (String mirrorId : vplexVolume.getMirrors()) {
                VplexMirror mirror = _dbClient.queryObject(VplexMirror.class, URI.create(mirrorId));
                if (mirror != null && !mirror.getInactive()) {
                    if (null != mirror.getAssociatedVolumes()) {
                        for (String assocVolumeId : mirror.getAssociatedVolumes()) {
                            Volume volume = _dbClient.queryObject(Volume.class, URI.create(assocVolumeId));
                            if (volume != null && !volume.getInactive()) {
                                VolumeDescriptor volDesc = new VolumeDescriptor(VolumeDescriptor.Type.BLOCK_DATA,
                                        volume.getStorageController(), URI.create(assocVolumeId), null, null);
                                descriptors.add(volDesc);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends DataObject> String checkForDelete(T object, List<Class<? extends DataObject>> excludeTypes) {
        // VplexMirror is added as exclude types in dependencyChecker as there is code
        // that handles deletion of VPLEX Mirror along with VPLEX volume simultaneously.
        // Without this check user will be forced to delete continuous copies first and
        // then VPLEX volumes.
        List<Class<? extends DataObject>> allExcludeTypes = new ArrayList<Class<? extends DataObject>>();
        allExcludeTypes.add(VplexMirror.class);
        allExcludeTypes.add(Task.class);
        allExcludeTypes.add(Migration.class);
        if (excludeTypes != null) {
            allExcludeTypes.addAll(excludeTypes);
        }

        String depMsg = _dependencyChecker.checkDependencies(object.getId(), object.getClass(), true, allExcludeTypes);
        if (depMsg != null) {
            return depMsg;
        }

        // For VPLEX volumes, the snapshots are associated with the source
        // backend volume of the VPLEX volume and therefore any snapshot
        // dependencies will not be detected unless we check for them on
        // the source backend volume.
        if (object instanceof Volume) {
            Volume vplexVolume = (Volume) object;
            if (!vplexVolume.isIngestedVolumeWithoutBackend(_dbClient)) {
                Volume snapshotSourceVolume = VPlexUtil.getVPLEXBackendVolume(vplexVolume, true, _dbClient, false);
                if (snapshotSourceVolume != null) {
                    List<BlockSnapshot> snapshots = CustomQueryUtility.queryActiveResourcesByConstraint(
                            _dbClient, BlockSnapshot.class, ContainmentConstraint.Factory.getVolumeSnapshotConstraint(
                                    snapshotSourceVolume.getId()));
                    if (!snapshots.isEmpty()) {
                        return BlockSnapshot.class.getSimpleName();
                    }

                    // Also check for snapshot sessions.
                    List<BlockSnapshotSession> snapSessions = CustomQueryUtility.queryActiveResourcesByConstraint(_dbClient,
                            BlockSnapshotSession.class,
                            ContainmentConstraint.Factory.getParentSnapshotSessionConstraint(snapshotSourceVolume.getId()));
                    if (!snapSessions.isEmpty()) {
                        return BlockSnapshotSession.class.getSimpleName();
                    }
                }
            }
        }

        return object.canBeDeleted();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList deactivateMirror(StorageSystem vplexStorageSystem, URI mirrorURI, String taskId, String deleteType) {
        TaskList taskList = new TaskList();
        try {
            VplexMirror mirror = _dbClient.queryObject(VplexMirror.class, mirrorURI);
            Volume sourceVolume = _dbClient.queryObject(Volume.class, mirror.getSource().getURI());
            Operation op = _dbClient.createTaskOpStatus(Volume.class, sourceVolume.getId(), taskId,
                    ResourceOperationTypeEnum.DEACTIVATE_VOLUME_MIRROR, mirror.getId().toString());
            taskList.getTaskList().add(toTask(sourceVolume, Arrays.asList(mirror), taskId, op));

            if (VolumeDeleteTypeEnum.VIPR_ONLY.name().equals(deleteType)) {
                s_logger.info("Perform ViPR-only delete for VPLEX mirrors %s", mirrorURI);

                // Perform any database cleanup that is required.
                cleanupForViPROnlyMirrorDelete(Arrays.asList(mirrorURI));

                // Mark them inactive.
                _dbClient.markForDeletion(_dbClient.queryObject(VplexMirror.class, mirrorURI));

                // We must get the volume from the DB again, to properly update the status.
                sourceVolume = _dbClient.queryObject(Volume.class, mirror.getSource().getURI());
                op = sourceVolume.getOpStatus().get(taskId);
                op.ready("VPLEX continuous copy succesfully deleted from ViPR");
                sourceVolume.getOpStatus().updateTaskStatus(taskId, op);
                _dbClient.updateObject(sourceVolume);
            } else {
                List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();
                // Add a descriptor for each of the associated volumes.There will be only one associated volume
                if (mirror.getAssociatedVolumes() != null) {
                    for (String assocVolId : mirror.getAssociatedVolumes()) {
                        Volume assocVolume = _dbClient.queryObject(Volume.class, URI.create(assocVolId));
                        if (assocVolume != null && !assocVolume.getInactive() && assocVolume.getNativeId() != null) {
                            // In order to add descriptor for the the backend volumes that needs to be
                            // deleted we are checking for volume nativeId as well, because its possible
                            // that we were not able to create backend volume due to SMIS communication
                            // and rollback didn't clean up VplexMirror and its associated volumes in
                            // database. So in such a case nativeId will be null and we just want to skip
                            // sending this volume to SMIS, else it fails with null reference when user
                            // attempts to cleanup this failed mirror.
                            VolumeDescriptor assocDesc = new VolumeDescriptor(VolumeDescriptor.Type.BLOCK_DATA,
                                    assocVolume.getStorageController(), assocVolume.getId(), null, null);
                            descriptors.add(assocDesc);
                        }
                    }
                }

                VPlexController controller = getController();
                controller.deactivateMirror(vplexStorageSystem.getId(), mirror.getId(), descriptors, taskId);
            }
        } catch (ControllerException e) {
            String errorMsg = format("Failed to deactivate continuous copy %s: %s", mirrorURI.toString(), e.getMessage());
            s_logger.error(errorMsg, e);
            for (TaskResourceRep taskResourceRep : taskList.getTaskList()) {
                taskResourceRep.setState(Operation.Status.error.name());
                taskResourceRep.setMessage(errorMsg);
                _dbClient.error(Volume.class, taskResourceRep.getResource().getId(), taskId, e);
            }
        } catch (Exception e) {
            String errorMsg = format("Failed to deactivate continuous copy %s: %s", mirrorURI.toString(), e.getMessage());
            s_logger.error(errorMsg, e);
            ServiceCoded sc = APIException.internalServerErrors.genericApisvcError(errorMsg, e);
            for (TaskResourceRep taskResourceRep : taskList.getTaskList()) {
                taskResourceRep.setState(Operation.Status.error.name());
                taskResourceRep.setMessage(sc.getMessage());
                _dbClient.error(Volume.class, taskResourceRep.getResource().getId(), taskId, sc);
            }
        }

        return taskList;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void cleanupForViPROnlyMirrorDelete(List<URI> mirrorURIs) {
        // We need to mark the associated volume inactive.
        List<Volume> associatedVolumes = new ArrayList<Volume>();
        for (URI mirrorURI : mirrorURIs) {
            VplexMirror mirror = _dbClient.queryObject(VplexMirror.class, mirrorURI);
            if (mirror.getAssociatedVolumes() != null) {
                for (String assocVolumeId : mirror.getAssociatedVolumes()) {
                    URI assocVolumeURI = URI.create(assocVolumeId);

                    // Get the associated volume and add it to the list of associated
                    // volumes to be marked inactive.
                    Volume assocVolume = _dbClient.queryObject(Volume.class, assocVolumeURI);
                    associatedVolumes.add(assocVolume);

                    // Remove the associated volume form any export groups/masks.
                    ExportUtils.cleanBlockObjectFromExports(assocVolumeURI, true, _dbClient);
                }
            }
        }

        // Now mark all the associated volumes for deletion.
        _dbClient.markForDeletion(associatedVolumes);
    }

    /**
     * Prepare a new Bourne Volume.
     *
     * @param volume pre-created volume (optional)
     * @param size The volume capacity.
     * @param thinVolumePreAllocationSize preallocation size for thin provisioning or 0.
     * @param project A reference to the project.
     * @param vArray A reference to the virtual array
     * @param vPool The requested virtual pool.
     * @param storageSystemURI The URI of the storage system.
     * @param storagePoolURI The URI of the storage pool.
     * @param label The label for the new volume.
     * @param consistencyGroup The consistency group.
     * @param capabilities The virtual pool capabilities
     *
     * @return A reference to the newly persisted Volume.
     */
    private Volume prepareVolume(VolumeType volType, Volume volume,
            long size, long thinVolumePreAllocationSize, Project project,
            VirtualArray vArray, VirtualPool vPool, URI storageSystemURI,
            URI storagePoolURI, String label,
            BlockConsistencyGroup consistencyGroup, VirtualPoolCapabilityValuesWrapper capabilities) {

        // Encapsulate the storage system and storage pool in a
        // volume recommendation and use the default implementation.
        VolumeRecommendation volRecomendation = new VolumeRecommendation(volType, size,
                vPool, vArray.getId());
        volRecomendation.addStorageSystem(storageSystemURI);
        volRecomendation.addStoragePool(storagePoolURI);
        volume = StorageScheduler.prepareVolume(_dbClient,
                volume, size, thinVolumePreAllocationSize, project, vArray,
                vPool, volRecomendation, label, consistencyGroup, capabilities);

        // For VPLEX volumes, the protocol will not be set when the
        // storage scheduler is invoked to prepare the volume because
        // there is no storage pool associated with the volume. So, set
        // it to FC here.
        StringSet protocols = new StringSet();
        protocols.add(StorageProtocol.Block.FC.name());
        volume.setProtocol(protocols);
        _dbClient.updateObject(volume);

        return volume;
    }

    /**
     * Adds a VplexMirror structure for a Volume. It also calls addMirrorToVolume to
     * link the mirror into the volume's mirror set.
     *
     * @param vplexVolume The volume for which mirror needs to be created
     * @param vPool The virtual pool for the mirror
     * @param varray The virtual array for the mirror
     * @param mirrorLabel The label for the new vplex mirror
     * @param thinPreAllocationSize preallocation size for thin provisioning or 0.
     * @param dbClient
     */
    private static VplexMirror initializeMirror(Volume vplexVolume, VirtualPool vPool, VirtualArray varray,
            String mirrorLabel, long thinPreAllocationSize, DbClient dbClient) {

        // Check if there is already vplex mirror with the same name
        List<VplexMirror> mirrorList = CustomQueryUtility.queryActiveResourcesByConstraint(dbClient, VplexMirror.class,
                ContainmentPrefixConstraint.Factory.getFullMatchConstraint(VplexMirror.class, "project",
                        vplexVolume.getProject().getURI(), mirrorLabel));
        if (!mirrorList.isEmpty()) {
            throw APIException.badRequests.duplicateLabel(mirrorLabel);
        }

        VplexMirror createdMirror = new VplexMirror();
        createdMirror.setSource(new NamedURI(vplexVolume.getId(), vplexVolume.getLabel()));
        createdMirror.setId(URIUtil.createId(VplexMirror.class));
        createdMirror.setLabel(mirrorLabel);
        createdMirror.setStorageController(vplexVolume.getStorageController());
        createdMirror.setVirtualArray(varray.getId());
        createdMirror.setCapacity(vplexVolume.getCapacity());
        createdMirror.setProject(new NamedURI(vplexVolume.getProject().getURI(), createdMirror.getLabel()));
        createdMirror.setTenant(new NamedURI(vplexVolume.getTenant().getURI(), createdMirror.getLabel()));
        createdMirror.setVirtualPool(vPool.getId());
        createdMirror.setThinPreAllocationSize(thinPreAllocationSize);
        createdMirror.setThinlyProvisioned(VirtualPool.ProvisioningType.Thin.toString().equalsIgnoreCase(
                vPool.getSupportedProvisioningType()));
        dbClient.createObject(createdMirror);
        addVplexMirrorToVolume(vplexVolume, createdMirror, dbClient);
        return createdMirror;
    }

    /**
     * Adds a VplexMirror structure to a Volume's mirror set.
     */
    private static void addVplexMirrorToVolume(Volume volume, VplexMirror mirror, DbClient dbClient) {
        StringSet mirrors = volume.getMirrors();
        if (mirrors == null) {
            mirrors = new StringSet();
        }
        mirrors.add(mirror.getId().toString());
        volume.setMirrors(mirrors);
        // Persist changes
        dbClient.updateObject(volume);
    }

    private static Volume prepareVolume(VplexMirror mirror, Volume backendVolume, VirtualPool vPool,
            VirtualArray varray, URI storageSystemURI, URI recommendedPoolURI, String mirrorLabel,
            long thinVolumePreAllocationSize, VirtualPoolCapabilityValuesWrapper capabilities,
            DbClient dbClient) {

        // Encapsulate the storage system and storage pool in a volume recommendation and use the default implementation.
        VolumeRecommendation volRecomendation = new VolumeRecommendation(VolumeType.BLOCK_VOLUME, backendVolume.getProvisionedCapacity(),
                vPool, varray.getId());
        Project project = dbClient.queryObject(Project.class, backendVolume.getProject().getURI());
        volRecomendation.addStorageSystem(storageSystemURI);
        volRecomendation.addStoragePool(recommendedPoolURI);

        // Create volume object
        Volume volume = StorageScheduler.prepareVolume(dbClient, null,
                backendVolume.getProvisionedCapacity(), thinVolumePreAllocationSize, project, varray, vPool, volRecomendation, mirrorLabel,
                null, capabilities);

        // Add INTERNAL_OBJECT flag to the volume created
        volume.addInternalFlags(Flag.INTERNAL_OBJECT);
        dbClient.updateObject(volume);

        // Associate backend volume created to the mirror
        StringSet associatedVolumes = new StringSet();
        associatedVolumes.add(volume.getId().toString());
        mirror.setAssociatedVolumes(associatedVolumes);
        dbClient.updateObject(mirror);
        return volume;
    }

    /**
     * Get a reference to the VPlex controller
     *
     * @return A reference to a VPlex controller
     */
    public VPlexController getController() {
        return super.getController(VPlexController.class,
                DiscoveredDataObject.Type.vplex.toString());
    }

    /**
     * Returns the Project assigned to this VPlex for its artifacts.
     * If there is no existing Project, one is created.
     *
     * @param vplexSystem A StorageSystem instance representing a VPlex.
     * @param dbClient A reference to a database client.
     *
     * @return Project instance that was created for holding this VPlex's private volumes/export groups.
     */
    public static Project getVplexProject(StorageSystem vplexSystem, DbClient dbClient,
            TenantsService tenantsService) {
        BasePermissionsHelper helper = new BasePermissionsHelper(dbClient);
        TenantOrg rootTenant = helper.getRootTenant();
        PrefixConstraint constraint = PrefixConstraint.Factory.getLabelPrefixConstraint(Project.class, vplexSystem.getNativeGuid());
        URIQueryResultList result = new URIQueryResultList();
        dbClient.queryByConstraint(constraint, result);
        Iterator<URI> iter = result.iterator();
        while (iter.hasNext()) {
            Project project = dbClient.queryObject(Project.class, iter.next());
            if (project == null || project.getInactive() == true) {
                continue;
            }
            if (project.getLabel().equals(vplexSystem.getNativeGuid())
                    && project.getTenantOrg().getURI().toString().equals(rootTenant.getId().toString())) {
                return project;
            }
        }
        // Create the project
        ProjectParam projectParam = new ProjectParam(vplexSystem.getNativeGuid());
        ProjectElement projectElement = tenantsService.createProject(rootTenant.getId(), projectParam,
                TenantOrg.PROVIDER_TENANT_ORG, rootTenant.getId().toString());
        URI projectId = projectElement.getId();
        Project project = dbClient.queryObject(Project.class, projectId);
        project.addInternalFlags(DataObject.Flag.INTERNAL_OBJECT);
        dbClient.updateObject(project);
        return project;
    }

    public void setTenantsService(TenantsService tenantsService) {
        _tenantsService = tenantsService;
    }

    /**
     * Import an existing volume to a VPLEX to make it a Virtual Volume.
     * Outline: 1. Determine the VPLEX(s) that could be used. 2. If this is to
     * become a distributed virtual volume, get a Recommendation for the pool
     * for the haVirtualArray of the Virtual Volume. 3. Create a Virtual Volume
     * and link it to the existing Volume. 4. If this is a distributed virtual
     * volume, create a new Volume and link it to the virtual volume. 5. Format
     * the parameters and call the controller.
     *
     * @param arrayURI -- the URI of the Storage Array holding the existing
     *            Volume.
     * @param importVolume -- An existing Volume that has been provisioned.
     * @param vpool -- The vpool requested on the vpool change request.
     * @param taskId -- The taskId
     * @throws InternalException
     */
    public void importVirtualVolume(URI arrayURI, Volume importVolume, VirtualPool vpool,
            String taskId) throws InternalException {
        VirtualArray neighborhood = _dbClient.queryObject(VirtualArray.class,
                importVolume.getVirtualArray());
        Project project = _dbClient.queryObject(Project.class, importVolume.getProject());
        URI nullPoolURI = NullColumnValueGetter.getNullURI();
        BlockConsistencyGroup consistencyGroup = null;
        if (importVolume.getConsistencyGroup() != null) {
            consistencyGroup = _dbClient.queryObject(BlockConsistencyGroup.class, importVolume.getConsistencyGroup());
        }

        // Determine the VPLEX(s) that could be used.
        Set<URI> vplexes = ConnectivityUtil.getVPlexSystemsAssociatedWithArray(_dbClient, arrayURI);
        Iterator<URI> vplexIter = vplexes.iterator();
        while (vplexIter.hasNext()) {
            StorageSystem vplex = _dbClient.queryObject(StorageSystem.class, vplexIter.next());
            StringSet vplexVarrays = vplex.getVirtualArrays();
            if ((vplexVarrays == null) || (vplexVarrays.isEmpty()) ||
                    (!vplexVarrays.contains(neighborhood.getId().toString()))) {
                vplexIter.remove();
            }
        }

        if (vplexes.isEmpty()) {
            throw APIException.badRequests.noVPlexSystemsAssociatedWithStorageSystem(arrayURI);
        }

        // If distributed virtual volume, get a recommendation.
        // Then create the volume.
        List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();
        URI vplexURI = null;
        StorageSystem vplexSystem = null;
        Volume createVolume = null;
        Project vplexProject;
        if (vpool.getHighAvailability().equals(VirtualPool.HighAvailabilityType.vplex_distributed.name())) {
            // Determine if the user requested a specific HA VirtualArray and an associated HA VirtualPool.
            VirtualArray requestedHaVarray = null;
            VirtualPool requestedHaVirtualPool = vpool;
            try {
                if (vpool.getHaVarrayVpoolMap() != null && !vpool.getHaVarrayVpoolMap().isEmpty()) {
                    for (String haNH : vpool.getHaVarrayVpoolMap().keySet()) {
                        if (haNH.equals(NullColumnValueGetter.getNullURI().toString())) {
                            continue;
                        }
                        requestedHaVarray = _dbClient.queryObject(VirtualArray.class, new URI(haNH));
                        String haVirtualPool = vpool.getHaVarrayVpoolMap().get(haNH);
                        if (haVirtualPool.equals(NullColumnValueGetter.getNullURI().toString())) {
                            continue;
                        }
                        requestedHaVirtualPool = _dbClient.queryObject(VirtualPool.class, new URI(haVirtualPool));
                        break;
                    }
                }
            } catch (URISyntaxException ex) {
                s_logger.error("URISyntaxException", ex);
            }

            VirtualPoolCapabilityValuesWrapper cosCapabilities = new VirtualPoolCapabilityValuesWrapper();
            cosCapabilities.put(VirtualPoolCapabilityValuesWrapper.SIZE, getVolumeCapacity(importVolume));
            cosCapabilities.put(VirtualPoolCapabilityValuesWrapper.RESOURCE_COUNT, new Integer(1));
            cosCapabilities.put(VirtualPoolCapabilityValuesWrapper.THIN_PROVISIONING, importVolume.getThinlyProvisioned());

            // Get the recommendations and pick one.
            List<Recommendation> recommendations = getBlockScheduler().scheduleStorageForImport(
                    neighborhood, vplexes, requestedHaVarray, requestedHaVirtualPool, cosCapabilities);
            if (recommendations.isEmpty()) {
                throw APIException.badRequests.noStorageFoundForVolumeMigration(requestedHaVirtualPool.getLabel(),
                        requestedHaVarray.getLabel(), importVolume.getId());
            }

            Recommendation recommendation = recommendations.get(0);
            VPlexRecommendation vplexRecommendation = (VPlexRecommendation) recommendation;
            vplexURI = vplexRecommendation.getVPlexStorageSystem();
            vplexSystem = _dbClient.queryObject(StorageSystem.class, vplexURI);
            vplexProject = getVplexProject(vplexSystem, _dbClient, _tenantsService);

            // Prepare the created volume.
            VirtualArray haVirtualArray = _dbClient.queryObject(VirtualArray.class,
                    vplexRecommendation.getVirtualArray());
            createVolume = prepareVolumeForRequest(getVolumeCapacity(importVolume),
                    vplexProject, haVirtualArray, vpool, vplexRecommendation.getSourceStorageSystem(),
                    vplexRecommendation.getSourceStoragePool(), importVolume.getLabel() + "-1",
                    ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME, taskId, _dbClient);
            createVolume.addInternalFlags(Flag.INTERNAL_OBJECT);
            _dbClient.updateObject(createVolume);
            VolumeDescriptor desc = new VolumeDescriptor(VolumeDescriptor.Type.BLOCK_DATA,
                    createVolume.getStorageController(), createVolume.getId(),
                    createVolume.getPool(), cosCapabilities);
            descriptors.add(desc);

        } else {
            vplexURI = vplexes.toArray(new URI[0])[0];
            vplexSystem = _dbClient.queryObject(StorageSystem.class, vplexURI);
            vplexProject = getVplexProject(vplexSystem, _dbClient, _tenantsService);
        }

        // Prepare the VPLEX Virtual volume.
        Volume vplexVolume = prepareVolumeForRequest(getVolumeCapacity(importVolume), project,
                neighborhood, vpool, vplexURI, nullPoolURI, importVolume.getLabel(),
                ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME, taskId, _dbClient);
        vplexVolume.setAssociatedVolumes(new StringSet());
        vplexVolume.getAssociatedVolumes().add(importVolume.getId().toString());
        if (createVolume != null) {
            vplexVolume.getAssociatedVolumes().add(createVolume.getId().toString());
        }
        if (consistencyGroup != null) {
            // If the volume being converted to a virtual volume has a CG, make the virtual
            // volume a member of the CG.
            vplexVolume.setConsistencyGroup(consistencyGroup.getId());
            consistencyGroup.addRequestedTypes(Arrays.asList(BlockConsistencyGroup.Types.VPLEX.name()));
            _dbClient.updateObject(consistencyGroup);
        }
        vplexVolume.setVirtualPool(vpool.getId());
        _dbClient.updateObject(vplexVolume);
        // Add a descriptor for the VPLEX_VIRT_VOLUME
        VolumeDescriptor desc = new VolumeDescriptor(VolumeDescriptor.Type.VPLEX_VIRT_VOLUME,
                vplexURI, vplexVolume.getId(), null, null);
        descriptors.add(desc);
        // Add a descriptor for the import volume too!
        desc = new VolumeDescriptor(VolumeDescriptor.Type.VPLEX_IMPORT_VOLUME,
                importVolume.getStorageController(), importVolume.getId(), importVolume.getPool(), null);
        descriptors.add(desc);

        // Now send the command to the controller.
        try {
            s_logger.info("Calling VPlex controller.");
            VPlexController controller = getController();
            controller.importVolume(vplexURI, descriptors, vplexProject.getId(),
                    vplexProject.getTenantOrg().getURI(), vpool.getId(),
                    importVolume.getLabel() + SRC_BACKEND_VOL_LABEL_SUFFIX, null, Boolean.TRUE, taskId);
        } catch (InternalException ex) {
            s_logger.error("ControllerException on importVolume", ex);
            String errMsg = String.format("ControllerException: %s", ex.getMessage());
            Operation statusUpdate = new Operation(Operation.Status.error.name(), errMsg);
            _dbClient.updateTaskOpStatus(Volume.class, vplexVolume.getId(), taskId, statusUpdate);
            _dbClient.markForDeletion(vplexVolume);
            if (createVolume != null) {
                _dbClient.markForDeletion(createVolume);
            }
            throw ex;
        }
    }

    /**
     * Upgrade a local VPLEX volume to a distributed VPLEX volume.
     *
     * @param vplexURI -- VPLEX System URI
     * @param vplexVolume -- VPlex volume (existing).
     * @param vpool -- Requested vpool.
     * @param taskId
     * @throws InternalException
     */
    private void upgradeToDistributed(URI vplexURI, Volume vplexVolume, VirtualPool vpool, String transferSpeed,
            String taskId) throws InternalException {
        try {
            VirtualArray neighborhood = _dbClient.queryObject(VirtualArray.class,
                    vplexVolume.getVirtualArray());
            Set<URI> vplexes = new HashSet<URI>();
            vplexes.add(vplexURI);
            if (null == vplexVolume.getAssociatedVolumes() || vplexVolume.getAssociatedVolumes().isEmpty()) {
                s_logger.error("VPLEX volume {} has no backend volumes.", vplexVolume.forDisplay());
                throw InternalServerErrorException.
                    internalServerErrors.noAssociatedVolumesForVPLEXVolume(vplexVolume.forDisplay());
            }
            Iterator<String> assocIter = vplexVolume.getAssociatedVolumes().iterator();
            URI existingVolumeURI = new URI(assocIter.next());
            Volume existingVolume = _dbClient.queryObject(Volume.class, existingVolumeURI);
            if (existingVolume == null || existingVolume.getInactive() == true) {
                throw new ServiceCodeException(ServiceCode.UNFORSEEN_ERROR,
                        "Existing volume inactive", new Object[] {});
            }
            VirtualPoolCapabilityValuesWrapper cosCapabilities = new VirtualPoolCapabilityValuesWrapper();
            cosCapabilities.put(VirtualPoolCapabilityValuesWrapper.SIZE, getVolumeCapacity(existingVolume));
            cosCapabilities.put(VirtualPoolCapabilityValuesWrapper.RESOURCE_COUNT, new Integer(1));
            cosCapabilities.put(VirtualPoolCapabilityValuesWrapper.THIN_PROVISIONING, existingVolume.getThinlyProvisioned());

            // Get a recommendation.
            // Then create the volume.
            List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();
            Volume createVolume = null;
            // Determine if the user requested a specific HA VirtualArray and an associated HA VirtualPool.
            VirtualArray requestedHaVarray = null;
            VirtualPool requestedHaVirtualPool = vpool;
            if (vpool.getHaVarrayVpoolMap() != null && !vpool.getHaVarrayVpoolMap().isEmpty()) {
                for (String haNH : vpool.getHaVarrayVpoolMap().keySet()) {
                    if (haNH.equals(NullColumnValueGetter.getNullURI().toString())) {
                        continue;
                    }
                    requestedHaVarray = _dbClient.queryObject(VirtualArray.class, new URI(haNH));
                    String haVirtualPool = vpool.getHaVarrayVpoolMap().get(haNH);
                    if (haVirtualPool.equals(NullColumnValueGetter.getNullURI().toString())) {
                        continue;
                    }
                    requestedHaVirtualPool = _dbClient.queryObject(VirtualPool.class, new URI(haVirtualPool));
                    break;
                }
            }

            // Get the recommendations and pick one.
            List<Recommendation> recommendations = getBlockScheduler().scheduleStorageForImport(neighborhood, vplexes,
                    requestedHaVarray, requestedHaVirtualPool, cosCapabilities);

            if (recommendations.isEmpty()) {
                throw APIException.badRequests.noStorageFoundForVolumeMigration(requestedHaVirtualPool.getLabel(),
                        requestedHaVarray.getLabel(), existingVolume.getId());
            }

            Recommendation recommendation = recommendations.get(0);
            VPlexRecommendation vplexRecommendation = (VPlexRecommendation) recommendation;
            if (false == vplexURI.equals(vplexRecommendation.getVPlexStorageSystem())) {
                APIException.badRequests.vplexPlacementError(vplexVolume.getId());
            }
            StorageSystem vplexSystem = _dbClient.queryObject(StorageSystem.class, vplexURI);
            Project vplexProject = getVplexProject(vplexSystem, _dbClient, _tenantsService);

            // Prepare the created volume.
            VirtualArray haVirtualArray = _dbClient.queryObject(VirtualArray.class,
                    vplexRecommendation.getVirtualArray());
            createVolume = prepareVolumeForRequest(getVolumeCapacity(existingVolume), vplexProject,
                    haVirtualArray, requestedHaVirtualPool, vplexRecommendation.getSourceStorageSystem(),
                    vplexRecommendation.getSourceStoragePool(), vplexVolume.getLabel() + "-1",
                    ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME, taskId, _dbClient);
            createVolume.addInternalFlags(Flag.INTERNAL_OBJECT);
            _dbClient.updateObject(createVolume);
            VolumeDescriptor desc = new VolumeDescriptor(VolumeDescriptor.Type.BLOCK_DATA,
                    createVolume.getStorageController(), createVolume.getId(), createVolume.getPool(), cosCapabilities);
            descriptors.add(desc);
            // Add a descriptor for the VPlex Virtual Volume.
            desc = new VolumeDescriptor(VolumeDescriptor.Type.VPLEX_VIRT_VOLUME,
                    vplexVolume.getStorageController(), vplexVolume.getId(), vplexVolume.getPool(), cosCapabilities);
            descriptors.add(desc);

            // Now send the command to the controller.
            try {
                s_logger.info("Calling VPlex controller.");
                VPlexController controller = getController();
                controller.importVolume(vplexURI, descriptors, null, null, vpool.getId(), null, transferSpeed, Boolean.TRUE, taskId);
                // controller.importVolume(vplexURI, vpool.getId(),
                // null, null, /* no need to pass System Project/Tenant */
                // null, /* no import volume */
                // createVolume.getId(), vplexVolume.getId(), taskId);
            } catch (InternalException ex) {
                s_logger.error("ControllerException on upgradeToDistributed", ex);
                String errMsg = String.format("ControllerException: %s", ex.getMessage());
                Operation statusUpdate = new Operation(Operation.Status.error.name(), errMsg);
                _dbClient.updateTaskOpStatus(Volume.class, vplexVolume.getId(), taskId, statusUpdate);
                throw ex;
            }
        } catch (URISyntaxException ex) {
            s_logger.debug("URISyntaxException", ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected StoragePort.PortType getSystemConnectivityPortType() {
        return StoragePort.PortType.backend;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<VirtualPoolChangeOperationEnum> getVirtualPoolChangeAllowedOperations(Volume volume, VirtualPool currentVirtualPool,
            VirtualPool newVirtualPool, StringBuffer notSuppReasonBuff) {

        List<VirtualPoolChangeOperationEnum> allowedOperations = new ArrayList<VirtualPoolChangeOperationEnum>();

        // Get the varray for the volume.
        URI volumeVarrayURI = volume.getVirtualArray();
        StringSet newVirtualPoolVarrays = newVirtualPool.getVirtualArrays();
        if ((newVirtualPoolVarrays != null) && (!newVirtualPoolVarrays.isEmpty())
                && (!newVirtualPoolVarrays.contains(volumeVarrayURI.toString()))) {
            // The VirtualPool is not allowed because it is not available in the
            // volume varray.
            notSuppReasonBuff.append("The VirtualPool is not available to the volume's varray");
        } else if (VirtualPool.vPoolSpecifiesRPVPlex(newVirtualPool)) {
            if (VirtualPoolChangeAnalyzer.isSupportedAddRPProtectionVirtualPoolChange(volume, currentVirtualPool, newVirtualPool,
                    _dbClient, notSuppReasonBuff)) {
                if (VirtualPool.vPoolSpecifiesRPVPlex(currentVirtualPool)) {
                    allowedOperations.add(VirtualPoolChangeOperationEnum.RP_UPGRADE_TO_METROPOINT);
                } else {
                    allowedOperations.add(VirtualPoolChangeOperationEnum.RP_PROTECTED);
                }
            }
        } else if (VirtualPool.vPoolSpecifiesHighAvailability(newVirtualPool)) {
            VirtualPoolChangeOperationEnum allowedOperation = VirtualPoolChangeAnalyzer.getSupportedVPlexVolumeVirtualPoolChangeOperation(
                    volume, currentVirtualPool, newVirtualPool,
                    _dbClient, notSuppReasonBuff);
            if (allowedOperation != null) {
                allowedOperations.add(allowedOperation);
            }
        }

        return allowedOperations;
    }

    /**
     * {@inheritDoc}
     *
     * @throws InternalException
     */
    @Override
    public TaskList changeVolumeVirtualPool(URI systemURI, Volume volume, VirtualPool vpool,
            VirtualPoolChangeParam vpoolChangeParam, String taskId) throws InternalException {
        VirtualPool volumeVirtualPool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
        s_logger.info("Volume {} VirtualPool change.", volume.getId());
        TaskList taskList = createTasksForVolumes(vpool, Arrays.asList(volume), taskId);

        String transferSpeed = null;
        ArrayList<Volume> volumes = new ArrayList<Volume>();
        volumes.add(volume);

        if (checkCommonVpoolUpdates(volumes, vpool, taskId)) {
            return taskList;
        }

        // Get the storage system. This could be a vplex, vmax, or
        // vnxblock, or other block storage system.
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, systemURI);
        String systemType = storageSystem.getSystemType();

        if (!DiscoveredDataObject.Type.vplex.name().equals(systemType)) {
            // If it is not a VPLEX volume, then this must be an import to VPLEX.
            s_logger.info("High availability VirtualPool change for array volume, importing volume VPLEX: " + volume.getLabel());
            importVirtualVolume(systemURI, volume, vpool, taskId);
            // Check to see if the imported volume is an SRDF source volume.
            if (volume.getSrdfTargets() != null) {
                StringSet srdfTargets = volume.getSrdfTargets();
                for (String target : srdfTargets) {
                    Volume targetVolume = _dbClient.queryObject(Volume.class, URI.create(target));
                    URI targetVarray = targetVolume.getVirtualArray();
                    // Get the target virtual pool.
                    Map<URI, VpoolRemoteCopyProtectionSettings> protectionSettingsMap = VirtualPool.getRemoteProtectionSettings(vpool,
                            _dbClient);
                    VpoolRemoteCopyProtectionSettings settings = protectionSettingsMap.get(targetVarray);
                    if (settings != null) {
                        VirtualPool targetVpool = _dbClient.queryObject(VirtualPool.class, settings.getVirtualPool());
                        if (NullColumnValueGetter.isNotNullValue(targetVpool.getHighAvailability())
                                && targetVpool.getHighAvailability().equals(VirtualPool.HighAvailabilityType.vplex_local.name())) {
                            String subTaskId = UUID.randomUUID().toString();
                            s_logger.info("Importing SRDF target to VPLEX " + targetVolume.getLabel());
                            Operation op = new Operation();
                            op.setResourceType(ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VPOOL);
                            op.setDescription("Change vpool operation");
                            op = _dbClient.createTaskOpStatus(Volume.class, targetVolume.getId(), subTaskId, op);
                            taskList.addTask(toTask(targetVolume, subTaskId, op));
                            importVirtualVolume(targetVolume.getStorageController(), targetVolume, targetVpool, subTaskId);
                        }
                    }
                }
            }
        } else {
            if (VirtualPoolChangeAnalyzer.isVPlexConvertToDistributed(volumeVirtualPool, vpool,
                    new StringBuffer())) {
                if (!VirtualPoolUtil.checkMatchingRemoteCopyVarraysettings(volumeVirtualPool, vpool, _dbClient)) {
                    s_logger.info("Incompatible Remote Copy Varray Settings");
                    throw BadRequestException.badRequests.changeToVirtualPoolNotSupported(
                            volumeVirtualPool.getLabel(), "Incompatible Remote Copy Varray Settings");
                }
                if (vpoolChangeParam.getTransferSpeedParam() != null) {
                    transferSpeed = vpoolChangeParam.getTransferSpeedParam();
                    s_logger.info("Coversion of volume from vplex local to distributed will use the provided transfer speed {}",
                            transferSpeed);
                }

                // Convert vplex_local to vplex_distributed
                upgradeToDistributed(systemURI, volume, vpool, transferSpeed, taskId);
            } else if (!VirtualPool.vPoolSpecifiesMirrors(volumeVirtualPool, _dbClient)
                    && (VirtualPool.vPoolSpecifiesMirrors(vpool, _dbClient))
                    && VirtualPoolChangeAnalyzer.isSupportedAddMirrorsVirtualPoolChange(volume, volumeVirtualPool, vpool, _dbClient,
                            new StringBuffer())) {
                // Change Virtual pool to have continuous copies
                URI originalVirtualPool = volume.getVirtualPool();
                // Update the volume with the new virtual pool
                volume.setVirtualPool(vpool.getId());
                _dbClient.updateObject(volume);
                // Update the task
                String msg = format("VirtualPool changed from %s to %s for Volume %s",
                        originalVirtualPool, vpool.getId(), volume.getId());
                s_logger.info(msg);
                _dbClient.createTaskOpStatus(Volume.class, volume.getId(), taskId,
                        ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VPOOL);
                _dbClient.ready(Volume.class, volume.getId(), taskId, msg);
            } else {
                // Prepare for VPlex virtual volume VirtualPool change.
                // Get the varray for the virtual volume.
                s_logger.info("VirtualPool change for VPlex virtual volume.");
                ControllerOperationValuesWrapper operationsWrapper = new ControllerOperationValuesWrapper();
                operationsWrapper.put(ControllerOperationValuesWrapper.MIGRATION_SUSPEND_BEFORE_COMMIT,
                        vpoolChangeParam.getMigrationSuspendBeforeCommit());
                operationsWrapper.put(ControllerOperationValuesWrapper.MIGRATION_SUSPEND_BEFORE_DELETE_SOURCE,
                        vpoolChangeParam.getMigrationSuspendBeforeDeleteSource());
                List<VolumeDescriptor> descriptors = createChangeVirtualPoolDescriptors(storageSystem, volume, vpool,
                        taskId, null, null,
                        operationsWrapper);

                // Now we get the Orchestration controller and use it to change the virtual pool of the volumes.
                orchestrateVPoolChanges(Arrays.asList(volume), descriptors, taskId);
            }
        }

        return taskList;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList changeVolumeVirtualPool(List<Volume> volumes, VirtualPool vpool,
            VirtualPoolChangeParam vpoolChangeParam, String taskId) throws InternalException {
        TaskList taskList = new TaskList();

        StringBuffer notSuppReasonBuff = new StringBuffer();
        VirtualPool volumeVirtualPool = _dbClient.queryObject(VirtualPool.class, volumes.get(0).getVirtualPool());

        if (VirtualPoolChangeAnalyzer.isSupportedPathParamsChange(volumes.get(0), volumeVirtualPool, vpool,
                _dbClient, notSuppReasonBuff) ||
                VirtualPoolChangeAnalyzer.isSupportedAutoTieringPolicyAndLimitsChange(volumes.get(0), volumeVirtualPool, vpool,
                        _dbClient, notSuppReasonBuff)) {
            taskList = createTasksForVolumes(vpool, volumes, taskId);
            checkCommonVpoolUpdates(volumes, vpool, taskId);
            return taskList;
        }

        // Check if any of the volumes passed is a VPLEX volume
        // in a VPLEX CG with corresponding local consistency
        // group(s) for the backend volumes.
        BlockConsistencyGroup cg = isVPlexVolumeInCgWithLocalType(volumes);
        if (cg != null) {
            s_logger.info("Change vpool request for volume in VPLEX CG with backing local CGs");
            // If any of the volumes is in such a CG and if this is a data
            // migration of the volumes, then the volumes passed must be all
            // the volumes in the CG and only the volumes in the CG.
            Volume changeVPoolVolume = volumes.get(0);
            URI cguri = changeVPoolVolume.getConsistencyGroup();
            cg = _dbClient.queryObject(BlockConsistencyGroup.class, cguri);
            VirtualPool currentVPool = _dbClient.queryObject(VirtualPool.class, changeVPoolVolume.getVirtualPool());
            VirtualPoolChangeOperationEnum vpoolChange = VirtualPoolChangeAnalyzer
                    .getSupportedVPlexVolumeVirtualPoolChangeOperation(changeVPoolVolume, currentVPool, vpool,
                            _dbClient, new StringBuffer());
            if ((vpoolChange != null) && (vpoolChange == VirtualPoolChangeOperationEnum.VPLEX_DATA_MIGRATION)) {
                s_logger.info("Vpool change is a data migration");
                ControllerOperationValuesWrapper operationsWrapper = new ControllerOperationValuesWrapper();
                operationsWrapper.put(ControllerOperationValuesWrapper.MIGRATION_SUSPEND_BEFORE_COMMIT,
                        vpoolChangeParam.getMigrationSuspendBeforeCommit());
                operationsWrapper.put(ControllerOperationValuesWrapper.MIGRATION_SUSPEND_BEFORE_DELETE_SOURCE,
                        vpoolChangeParam.getMigrationSuspendBeforeDeleteSource());

                List<Volume> volumesNotInRG = new ArrayList<Volume>();
                taskList = migrateVolumesInReplicationGroup(volumes, vpool, volumesNotInRG, null, operationsWrapper, taskId);
                
                // Migrate volumes not in Replication Group as single volumes
                if (!volumesNotInRG.isEmpty()) {
                    for (Volume volume : volumesNotInRG) {
                        taskList.getTaskList().addAll(changeVolumeVirtualPool(volume.getStorageController(), 
                                volume, vpool, vpoolChangeParam, taskId).getTaskList());
                    }
                }
                
                return taskList;
            }
        }

        // Otherwise proceed as we normally would performing
        // individual vpool changes for each volume.
        for (Volume volume : volumes) {
            taskList.getTaskList().addAll(
                    changeVolumeVirtualPool(volume.getStorageController(), 
                            volume, vpool, vpoolChangeParam, taskId).getTaskList());
        }
        return taskList;
    }
    
    /**
     * Group all volumes in RGs and create a WF to migrate those volumes together.
     * 
     * @param volumes All volumes being considered for migration
     * @param vpool The vpool to migrate to
     * @param volumesNotInRG A container to store all volumes NOT in an RG
     * @param volumesInRG A container to store all the volumes in an RG
     * @param controllerOperationsWrapper values from controller called used to determine if
     *   we need to suspend on commit or deletion of source volumes
     * @param taskId The Task Id
     * @return taskList Tasks generated for RG migrations
     */
    protected TaskList migrateVolumesInReplicationGroup(List<Volume> volumes, VirtualPool vpool,   
            List<Volume> volumesNotInRG, List<Volume> volumesInRG, 
            ControllerOperationValuesWrapper controllerOperationValues, String taskId) {        
        TaskList taskList = new TaskList();
        // Group all volumes in the request by RG. If there are no volumes in the request
        // that are in an RG then the table will be empty.
        Table<URI, String, List<Volume>> groupVolumes = VPlexUtil.groupVPlexVolumesByRG(
                                                            volumes, volumesNotInRG, volumesInRG, _dbClient);
        for (Table.Cell<URI, String, List<Volume>> cell : groupVolumes.cellSet()) {
            // Get all the volumes in the request that have been grouped by RG
            List<Volume> volumesInRGRequest = cell.getValue();
            // Grab the first volume
            Volume firstVolume = volumesInRGRequest.get(0);
            // Get all the volumes from the RG
            List<Volume> rgVolumes = VPlexUtil.getVolumesInSameReplicationGroup(cell.getColumnKey(), cell.getRowKey(), firstVolume.getPersonality(), _dbClient);
            
            // If all the volumes in the request that have been grouped by RG are not all the volumes from 
            // the RG, throw an exception.
            // We need to migrate all the volumes from the RG together.
            if (volumesInRGRequest.size() != rgVolumes.size()) {
                throw APIException.badRequests.cantChangeVpoolNotAllCGVolumes();
            }
                        
            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, firstVolume.getConsistencyGroup());
            URI systemURI = firstVolume.getStorageController();

            // All volumes will be migrated in the same workflow.
            // If there are many volumes in the CG, the workflow
            // will have many steps. Worse, if an error occurs
            // additional workflow steps get added for rollback.
            // An issue can then arise trying to persist the
            // workflow to Zoo Keeper because the workflow
            // persistence is restricted to 250000 bytes. So
            // we add a restriction on the number of volumes in
            // the CG that will be allowed for a data migration
            // vpool change.
            if (volumesInRGRequest.size() > _maxCgVolumesForMigration) {
                throw APIException.badRequests
                        .cgContainsTooManyVolumesForVPoolChange(cg.getLabel(),
                                volumes.size(), _maxCgVolumesForMigration);
            }
                        
            // When migrating multiple volumes in the CG we
            // want to be sure the target vpool ensures the
            // migration targets will be placed on the same
            // storage system as the migration targets will
            // be placed in a CG on the target storage system.
            if (volumesInRGRequest.size() > 1) {
                s_logger.info("Multiple volume request, verifying target storage systems");
                verifyTargetSystemsForCGDataMigration(volumesInRGRequest, vpool, cg.getVirtualArray());
            }

            // Get all volume descriptors for all volumes to be migrated.
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, systemURI);
            List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();
            for (Volume volume : volumesInRGRequest) {
                descriptors.addAll(createChangeVirtualPoolDescriptors(storageSystem,
                        volume, vpool, taskId, null, null, controllerOperationValues));
            }
            
            // Create a task object associated with the CG
            taskList.getTaskList().add(createTaskForRG(vpool, rgVolumes, taskId));

            // Orchestrate the vpool changes of all volumes as a single request.
            orchestrateVPoolChanges(volumesInRGRequest, descriptors, taskId);
        }
        return taskList;
    }

    /**
     * Verifies if the valid storage pools for the target vpool specify a single
     * target storage system.
     *
     * @param currentVPool The source vpool for a vpool change
     * @param newVPool The target vpool for a vpool change.
     * @param srcVarrayURI The virtual array for the volumes being migrated.
     */
    private void verifyTargetSystemsForCGDataMigration(List<Volume> volumes,
            VirtualPool newVPool, URI srcVarrayURI) {
        // Determine if the vpool change requires a migration. If the
        // new vpool is null, then this is a varray migration and the
        // varray is changing. In either case, the valid storage pools
        // specified in the target vpool that are tagged to the passed
        // varray must specify the same system so that all volumes are
        // placed on the same array.
        URI tgtSystemURI = null;
        URI tgtHASystemURI = null;
        for (Volume volume : volumes) {
            VirtualPool currentVPool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
            if ((newVPool == null) || (VirtualPoolChangeAnalyzer.vpoolChangeRequiresMigration(currentVPool, newVPool))) {
                VirtualPool vPoolToCheck = (newVPool == null ? currentVPool : newVPool);
                List<StoragePool> pools = VirtualPool.getValidStoragePools(vPoolToCheck, _dbClient, true);
                for (StoragePool pool : pools) {
                    // We only need to check the storage pools in the target vpool that
                    // are tagged to the virtual array for the volumes being migrated.
                    if (!pool.getTaggedVirtualArrays().contains(srcVarrayURI.toString())) {
                        continue;
                    }

                    if (tgtSystemURI == null) {
                        tgtSystemURI = pool.getStorageDevice();
                    } else if (!tgtSystemURI.equals(pool.getStorageDevice())) {
                        throw APIException.badRequests.targetVPoolDoesNotSpecifyUniqueSystem();
                    }
                }
            }

            // The same restriction applies to the target HA virtual pool
            // when the HA side is being migrated.
            URI haVArrayURI = VirtualPoolChangeAnalyzer.getHaVarrayURI(currentVPool);
            if (!NullColumnValueGetter.isNullURI(haVArrayURI)) {
                // The HA varray is not null so must be distributed.
                VirtualPool currentHAVpool = VirtualPoolChangeAnalyzer.getHaVpool(currentVPool, _dbClient);
                VirtualPool newHAVpool = VirtualPoolChangeAnalyzer.getNewHaVpool(currentVPool, newVPool, _dbClient);
                if (VirtualPoolChangeAnalyzer.vpoolChangeRequiresMigration(currentHAVpool, newHAVpool)) {
                    List<StoragePool> haPools = VirtualPool.getValidStoragePools(newHAVpool, _dbClient, true);
                    for (StoragePool haPool : haPools) {
                        // We only need to check the storage pools in the target HA vpool
                        // tagged to the HA virtual array for the volumes being migrated.
                        if (!haPool.getTaggedVirtualArrays().contains(haVArrayURI.toString())) {
                            continue;
                        }

                        if (tgtHASystemURI == null) {
                            tgtHASystemURI = haPool.getStorageDevice();
                        } else if (!tgtHASystemURI.equals(haPool.getStorageDevice())) {
                            throw APIException.badRequests.targetHAVPoolDoesNotSpecifyUniqueSystem();
                        }
                    }
                }
            }
        }
    }

    /**
     * Invokes the block orchestrator for a vpool change operation.
     *
     * @param volumes The volumes undergoing the vpool change.
     * @param descriptors The prepared volume descriptors.
     * @param taskId The task identifier.
     */
    private void orchestrateVPoolChanges(List<Volume> volumes, List<VolumeDescriptor> descriptors, String taskId) {
        try {
            BlockOrchestrationController controller = getController(BlockOrchestrationController.class,
                    BlockOrchestrationController.BLOCK_ORCHESTRATION_DEVICE);
            controller.changeVirtualPool(descriptors, taskId);
        } catch (InternalException e) {
            if (s_logger.isErrorEnabled()) {
                s_logger.error("Controller error", e);
            }
            String errMsg = String.format("Controller error on changeVolumeVirtualPool: %s", e.getMessage());
            Operation statusUpdate = new Operation(Operation.Status.error.name(), errMsg);
            for (Volume volume : volumes) {
                _dbClient.updateTaskOpStatus(Volume.class, volume.getId(), taskId, statusUpdate);
            }
            throw e;
        }
    }

    /**
     * Change the VirtualPool for the passed virtual volume on the passed VPlex
     * storage system.
     *
     * @param vplexSystem A reference to the VPlex storage system.
     * @param volume A reference to the virtual volume.
     * @param newVpool The desired VirtualPool.
     * @param taskId The task identifier.
     * @param operationsWrapper a wrapper of various controller options
     * @throws InternalException
     */
    protected List<VolumeDescriptor> createChangeVirtualPoolDescriptors(StorageSystem vplexSystem, Volume volume,
            VirtualPool newVpool, String taskId, List<Recommendation> recommendations,
            VirtualPoolCapabilityValuesWrapper capabilities,
            ControllerOperationValuesWrapper operationsWrapper) throws InternalException {
        // Get the varray and current vpool for the virtual volume.
        URI volumeVarrayURI = volume.getVirtualArray();
        VirtualArray volumeVarray = _dbClient.queryObject(VirtualArray.class, volumeVarrayURI);
        s_logger.info("Virtual volume varray is {}", volumeVarrayURI);
        URI volumeVpoolURI = volume.getVirtualPool();
        VirtualPool currentVpool = _dbClient.queryObject(VirtualPool.class, volumeVpoolURI);

        List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();

        // Add the VPLEX Virtual Volume Descriptor for change vpool
        VolumeDescriptor vplexVirtualVolumeDesc = new VolumeDescriptor(VolumeDescriptor.Type.VPLEX_VIRT_VOLUME,
                volume.getStorageController(),
                volume.getId(),
                volume.getPool(), null);

        Map<String, Object> volumeParams = new HashMap<String, Object>();
        volumeParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_EXISTING_VOLUME_ID, volume.getId());
        volumeParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_NEW_VPOOL_ID, newVpool.getId());
        volumeParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_OLD_VPOOL_ID, volume.getVirtualPool());
        if (operationsWrapper != null) {
            if (operationsWrapper.getMigrationSuspendBeforeCommit() != null) {
                volumeParams.put(VolumeDescriptor.PARAM_MIGRATION_SUSPEND_BEFORE_COMMIT,
                        operationsWrapper.getMigrationSuspendBeforeCommit());
            }
            if (operationsWrapper.getMigrationSuspendBeforeDeleteSource() != null) {
                volumeParams.put(VolumeDescriptor.PARAM_MIGRATION_SUSPEND_BEFORE_DELETE_SOURCE,
                        operationsWrapper.getMigrationSuspendBeforeDeleteSource());
            }
        }
        vplexVirtualVolumeDesc.setParameters(volumeParams);
        descriptors.add(vplexVirtualVolumeDesc);

        // A VirtualPool change on a VPlex virtual volume requires
        // a migration of the data on the backend volume(s) used by
        // the virtual volume to new volumes that satisfy the new
        // new VirtualPool. So we need to get the placement
        // recommendations for the new volumes to which the data
        // will be migrated and prepare the volume(s). First
        // determine if the backend volume on the source side,
        // i.e., the backend volume in the same varray as the
        // vplex volume. Recall for ingested volumes, we know
        // nothing about the backend volumes.
        if (VirtualPoolChangeAnalyzer.vpoolChangeRequiresMigration(currentVpool, newVpool)) {
            Volume migSrcVolume = getAssociatedVolumeInVArray(volume, volumeVarrayURI);
            descriptors.addAll(createBackendVolumeMigrationDescriptors(vplexSystem, volume,
                    migSrcVolume, volumeVarray, newVpool, getVolumeCapacity(migSrcVolume != null ? migSrcVolume : volume),
                    taskId, recommendations, false, capabilities));
        }

        // Now determine if the backend volume in the HA varray
        // needs to be migrated.
        URI haVarrayURI = VirtualPoolChangeAnalyzer.getHaVarrayURI(currentVpool);
        if (haVarrayURI != null) {
            VirtualArray haVarray = _dbClient.queryObject(VirtualArray.class, haVarrayURI);
            VirtualPool currentHaVpool = VirtualPoolChangeAnalyzer.getHaVpool(currentVpool, _dbClient);
            VirtualPool newHaVpool = VirtualPoolChangeAnalyzer.getNewHaVpool(currentVpool, newVpool, _dbClient);

            if (VirtualPoolChangeAnalyzer.vpoolChangeRequiresMigration(currentHaVpool, newHaVpool)) {
                Volume migSrcVolume = getAssociatedVolumeInVArray(volume, haVarrayURI);
                descriptors.addAll(createBackendVolumeMigrationDescriptors(vplexSystem, volume,
                        migSrcVolume, haVarray, newHaVpool, getVolumeCapacity(migSrcVolume != null ? migSrcVolume : volume),
                        taskId, recommendations, true, capabilities));
            }
        }

        return descriptors;
    }

    /**
     * Returns the backend volume of the passed VPLEX volume in the passed
     * virtual array.
     *
     * @param vplexVolume A reference to the VPLEX volume.
     * @param varrayURI The URI of the virtual array.
     *
     * @return A reference to the backend volume for the passed VPLEX volume in
     *         the passed virtual array, or null if the backend volumes are not
     *         know, as in the case of an ingested VPLEX volume.
     */
    private Volume getAssociatedVolumeInVArray(Volume vplexVolume, URI varrayURI) {
        StringSet associatedVolumeIds = vplexVolume.getAssociatedVolumes();
        if (associatedVolumeIds != null) {
            for (String associatedVolumeId : associatedVolumeIds) {
                Volume associatedVolume = _dbClient.queryObject(Volume.class,
                        URI.create(associatedVolumeId));
                if (associatedVolume.getVirtualArray().equals(varrayURI)) {
                    return associatedVolume;
                }
            }
        }
        return null;
    }

    /**
     * Deprecated, need to start using createBackendVolumeMigrationDescriptors for use in
     * BlockOrchestration.
     *
     * Does the work necessary to prepare the passed backend volume for the
     * passed virtual volume to be migrated to a new volume with a new VirtualPool.
     *
     * @param vplexSystem A reference to the Vplex storage system
     * @param virtualVolume A reference to the virtual volume.
     * @param sourceVolume A reference to the backend volume to be migrated.
     * @param nh A reference to the varray for the backend volume.
     * @param vpool A reference to the VirtualPool for the new volume.
     * @param capacity The capacity for the migration target.
     * @param taskId The task identifier.
     * @param newVolumes An OUT parameter to which the new volume is added.
     * @param migrationMap A OUT parameter to which the new migration is added.
     * @param poolVolumeMap An OUT parameter associating the new Volume to the
     *            storage pool in which it will be created.
     */
    @Deprecated
    private void prepareBackendVolumeForMigration(StorageSystem vplexSystem,
            Volume virtualVolume, Volume sourceVolume, VirtualArray varray, VirtualPool vpool,
            Long capacity, String taskId, List<URI> newVolumes, Map<URI, URI> migrationMap,
            Map<URI, URI> poolVolumeMap) {

        URI sourceVolumeURI = null;
        Project targetProject = null;
        String targetLabel = null;
        if (sourceVolume != null) {
            // Since we know the source volume, this is not an ingested
            // VPLEX volume that is being migrated. Ideally we would just
            // give the new backend volume the same name as the current
            // i.e., source. However, this is a problem if the migration
            // is on the same array. We can't have two volumes with the
            // same name. Eventually the source will go away, but not until
            // after the migration is completed. The backend volume names
            // are basically irrelevant, but we still want them tied to
            // the VPLEX volume name.
            //
            // When initially created, the names are <vvolname>-0 or
            // <vvolname>-1, depending upon if it is the source side
            // backend volume or HA side backend volume. The volume may
            // also have an additional suffix of "-<1...N>" if the
            // VPLEX volume was created as part of a multi-volume creation
            // request, where N was the number of volumes requested. When
            // a volume is first migrated, we will append a "m" to the current
            // source volume name to ensure name uniqueness. If the volume
            // happens to be migrated again, we'll remove the extra character.
            // We'll go back forth in this manner for each migration of that
            // backend volume.
            sourceVolumeURI = sourceVolume.getId();
            targetProject = _dbClient.queryObject(Project.class, sourceVolume.getProject().getURI());
            targetLabel = sourceVolume.getLabel();
            if (!targetLabel.endsWith(MIGRATION_LABEL_SUFFIX)) {
                targetLabel += MIGRATION_LABEL_SUFFIX;
            } else {
                targetLabel = targetLabel.substring(0, targetLabel.length() - 1);
            }
        } else {
            // The VPLEX volume must be ingested and now the backend
            // volume(s) are being migrated. We have no idea what the
            // source volume name is. Therefore, we can just give
            // them initial extensions. It is highly unlikely that
            // they will have the same naming conventions for their
            // backend volumes.
            targetProject = getVplexProject(vplexSystem, _dbClient, _tenantsService);
            targetLabel = virtualVolume.getLabel();
            if (virtualVolume.getVirtualArray().equals(varray.getId())) {
                targetLabel += SRC_BACKEND_VOL_LABEL_SUFFIX;
            } else {
                targetLabel += HA_BACKEND_VOL_LABEL_SUFFIX;
            }
        }

        // Get the recommendation for this volume placement.
        URI cgURI = null;
        if (!NullColumnValueGetter.isNullURI(sourceVolume.getConsistencyGroup())) {
            cgURI = sourceVolume.getConsistencyGroup();
        }
        Set<URI> requestedVPlexSystems = new HashSet<URI>();
        requestedVPlexSystems.add(vplexSystem.getId());
        VirtualPoolCapabilityValuesWrapper cosWrapper = new VirtualPoolCapabilityValuesWrapper();
        cosWrapper.put(VirtualPoolCapabilityValuesWrapper.SIZE, capacity);
        cosWrapper.put(VirtualPoolCapabilityValuesWrapper.RESOURCE_COUNT, new Integer(1));
        if (cgURI != null) {
            cosWrapper.put(VirtualPoolCapabilityValuesWrapper.BLOCK_CONSISTENCY_GROUP, cgURI);
        }
        List<Recommendation> recommendations = getBlockScheduler().scheduleStorage(
                varray, requestedVPlexSystems, null, vpool, false, null, null,
                cosWrapper, targetProject, VpoolUse.ROOT, new HashMap<VpoolUse, List<Recommendation>>());
        if (recommendations.isEmpty()) {
            throw APIException.badRequests.noStorageFoundForVolumeMigration(vpool.getLabel(), varray.getLabel(), sourceVolumeURI);
        }
        s_logger.info("Got recommendation");

        // Create a volume for the new backend volume to which
        // data will be migrated.
        URI targetStorageSystem = recommendations.get(0).getSourceStorageSystem();
        URI targetStoragePool = recommendations.get(0).getSourceStoragePool();
        Volume targetVolume = prepareVolumeForRequest(capacity,
                targetProject, varray, vpool, targetStorageSystem, targetStoragePool,
                targetLabel, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME,
                taskId, _dbClient);
        if (cgURI != null) {
            targetVolume.setConsistencyGroup(cgURI);
        }
        targetVolume.addInternalFlags(Flag.INTERNAL_OBJECT);
        _dbClient.updateObject(targetVolume);

        s_logger.info("Prepared volume {}", targetVolume.getId());

        // Add the volume to the passed new volumes list and pool
        // volume map.
        URI targetVolumeURI = targetVolume.getId();
        newVolumes.add(targetVolumeURI);
        poolVolumeMap.put(targetStoragePool, targetVolumeURI);

        // Create a migration to represent the migration of data
        // from the backend volume to the new backend volume for the
        // passed virtual volume and add the migration to the passed
        // migrations list.
        Migration migration = prepareMigration(virtualVolume.getId(),
                sourceVolumeURI, targetVolumeURI, taskId);
        migrationMap.put(targetVolumeURI, migration.getId());
        s_logger.info("Prepared migration {}.", migration.getId());
    }

    /**
     * Does the work necessary to prepare the passed backend volume for the
     * passed virtual volume to be migrated to a new volume with a new VirtualPool.
     *
     * @param vplexSystem A reference to the Vplex storage system
     * @param virtualVolume A reference to the virtual volume.
     * @param sourceVolume A reference to the backend volume to be migrated.
     * @param nh A reference to the varray for the backend volume.
     * @param vpool A reference to the VirtualPool for the new volume.
     * @param capacity The capacity for the migration target.
     * @param taskId The task identifier.
     * @param newVolumes An OUT parameter to which the new volume is added.
     * @param migrationMap A OUT parameter to which the new migration is added.
     * @param poolVolumeMap An OUT parameter associating the new Volume to the
     *            storage pool in which it will be created.
     */
    private List<VolumeDescriptor> createBackendVolumeMigrationDescriptors(StorageSystem vplexSystem,
            Volume virtualVolume, Volume sourceVolume, VirtualArray varray, VirtualPool vpool,
            Long capacity, String taskId, List<Recommendation> recommendations, boolean isHA,
            VirtualPoolCapabilityValuesWrapper capabilities) {

        // If we know the backend source volume, the new backend volume
        // will have the same label and project. Otherwise, the volume
        // must be ingested and we know nothing about the backend volume.
        // Therefore, we create the label based on the name of the VPLEX
        // volume and determine the project in a manner similar to a
        // volume creation.
        URI sourceVolumeURI = null;
        Project targetProject = null;
        String targetLabel = null;
        if (sourceVolume != null) {
            // Since we know the source volume, this is not an ingested
            // VPLEX volume that is being migrated. Ideally we would just
            // give the new backend volume the same name as the current
            // i.e., source. However, this is a problem if the migration
            // is on the same array. We can't have two volumes with the
            // same name. Eventually the source will go away, but not until
            // after the migration is completed. The backend volume names
            // are basically irrelevant, but we still want them tied to
            // the VPLEX volume name.
            //
            // When initially created, the names are <vvolname>-0 or
            // <vvolname>-1, depending upon if it is the source side
            // backend volume or HA side backend volume. The volume may
            // also have an additional suffix of "-<1...N>" if the
            // VPLEX volume was created as part of a multi-volume creation
            // request, where N was the number of volumes requested. When
            // a volume is first migrated, we will append a "m" to the current
            // source volume name to ensure name uniqueness. If the volume
            // happens to be migrated again, we'll remove the extra character.
            // We'll go back forth in this manner for each migration of that
            // backend volume.
            sourceVolumeURI = sourceVolume.getId();
            targetProject = _dbClient.queryObject(Project.class, sourceVolume.getProject().getURI());
            targetLabel = sourceVolume.getLabel();
            if (!targetLabel.endsWith(MIGRATION_LABEL_SUFFIX)) {
                targetLabel += MIGRATION_LABEL_SUFFIX;
            } else {
                targetLabel = targetLabel.substring(0, targetLabel.length() - 1);
            }
        } else {
            targetProject = getVplexProject(vplexSystem, _dbClient, _tenantsService);
            targetLabel = virtualVolume.getLabel();
            if (virtualVolume.getVirtualArray().equals(varray.getId())) {
                targetLabel += SRC_BACKEND_VOL_LABEL_SUFFIX;
            } else {
                targetLabel += HA_BACKEND_VOL_LABEL_SUFFIX;
            }
        }

        // Get the recommendation for this volume placement.
        Set<URI> requestedVPlexSystems = new HashSet<URI>();
        requestedVPlexSystems.add(vplexSystem.getId());

        URI cgURI = null;
        // Check to see if the VirtualPoolCapabilityValuesWrapper have been passed in, if not, create a new one.
        if (capabilities != null) {
            // The consistency group or null when not specified.
            final BlockConsistencyGroup consistencyGroup = capabilities.getBlockConsistencyGroup() == null ? null : _dbClient
                    .queryObject(BlockConsistencyGroup.class, capabilities.getBlockConsistencyGroup());

            // If the consistency group is created but does not specify the LOCAL
            // type, the CG must be a CG created prior to 2.2 or an ingested CG. In
            // this case, we don't want a volume creation to result in backend CGs
            if ((consistencyGroup != null) && ((!consistencyGroup.created()) ||
                    (consistencyGroup.getTypes().contains(Types.LOCAL.toString())))) {
                cgURI = consistencyGroup.getId();
            }
        } else {
            capabilities = new VirtualPoolCapabilityValuesWrapper();
            capabilities.put(VirtualPoolCapabilityValuesWrapper.SIZE, capacity);
            capabilities.put(VirtualPoolCapabilityValuesWrapper.RESOURCE_COUNT, new Integer(1));
        }

        boolean premadeRecs = false;

        if (recommendations == null || recommendations.isEmpty()) {
            recommendations = getBlockScheduler().scheduleStorage(
                    varray, requestedVPlexSystems, null, vpool, false, null, null, capabilities,
                    targetProject, VpoolUse.ROOT, new HashMap<VpoolUse, List<Recommendation>>());
            if (recommendations.isEmpty()) {
                throw APIException.badRequests.noStorageFoundForVolumeMigration(vpool.getLabel(), varray.getLabel(), sourceVolumeURI);
            }
            s_logger.info("Got recommendation");
        } else {
            premadeRecs = true;
        }

        // If we have premade recommendations passed in and this is trying to create descriptors for HA
        // then the HA rec will be at index 1 instead of index 0. Default case is index 0.
        int recIndex = (premadeRecs && isHA) ? 1 : 0;

        // Create a volume for the new backend volume to which
        // data will be migrated.
        URI targetStorageSystem = recommendations.get(recIndex).getSourceStorageSystem();
        URI targetStoragePool = recommendations.get(recIndex).getSourceStoragePool();
        Volume targetVolume = prepareVolumeForRequest(capacity,
                targetProject, varray, vpool, targetStorageSystem, targetStoragePool,
                targetLabel, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME,
                taskId, _dbClient);

        // If the cgURI is null, try and get it from the source volume.
        if (cgURI == null) {
            if ((sourceVolume != null) && (!NullColumnValueGetter.isNullURI(sourceVolume.getConsistencyGroup()))) {
                cgURI = sourceVolume.getConsistencyGroup();
                targetVolume.setConsistencyGroup(cgURI);
            }
        }

        if ((sourceVolume != null) && NullColumnValueGetter.isNotNullValue(sourceVolume.getReplicationGroupInstance())) {
            targetVolume.setReplicationGroupInstance(sourceVolume.getReplicationGroupInstance());
        }

        // Retain any previous RP fields on the new target volumes
        if ((sourceVolume != null) && NullColumnValueGetter.isNotNullValue(sourceVolume.getRpCopyName())) {
            targetVolume.setRpCopyName(sourceVolume.getRpCopyName());
        }

        if ((sourceVolume != null) && NullColumnValueGetter.isNotNullValue(sourceVolume.getInternalSiteName())) {
            targetVolume.setInternalSiteName(sourceVolume.getInternalSiteName());
        }
        targetVolume.addInternalFlags(Flag.INTERNAL_OBJECT);
        _dbClient.updateObject(targetVolume);

        s_logger.info("Prepared volume {}", targetVolume.getId());

        // Add the volume to the passed new volumes list and pool
        // volume map.
        URI targetVolumeURI = targetVolume.getId();

        List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();

        descriptors.add(new VolumeDescriptor(VolumeDescriptor.Type.BLOCK_DATA,
                targetStorageSystem,
                targetVolumeURI,
                targetStoragePool,
                cgURI,
                capabilities,
                capacity));

        // Create a migration to represent the migration of data
        // from the backend volume to the new backend volume for the
        // passed virtual volume and add the migration to the passed
        // migrations list.
        Migration migration = prepareMigration(virtualVolume.getId(),
                sourceVolumeURI, targetVolumeURI, taskId);

        descriptors.add(new VolumeDescriptor(VolumeDescriptor.Type.VPLEX_MIGRATE_VOLUME,
                targetStorageSystem,
                targetVolumeURI,
                targetStoragePool,
                cgURI,
                migration.getId(),
                capabilities));

        s_logger.info("Prepared migration {}.", migration.getId());

        return descriptors;
    }

    /**
     * Prepare a new Bourne volume.
     *
     * TODO: Use existing function (prepareVolume) when VirtualPool capabilities change
     * completed by Stalin. Just pass size instead of getting from VolumeCreate
     * parameter.
     *
     * @param size The volume size.
     * @param project A reference to the volume's Project.
     * @param neighborhood A reference to the volume's varray.
     * @param vpool A reference to the volume's VirtualPool.
     * @param storageSystemURI The URI of the volume's storage system.
     * @param storagePoolURI The URI of the volume's storage pool.
     * @param label The volume label.
     * @param token The task id for volume creation.
     * @param dbClient A reference to a database client.
     *
     * @return A reference to the new volume.
     */
    public static Volume prepareVolumeForRequest(Long size, Project project,
            VirtualArray neighborhood, VirtualPool vpool, URI storageSystemURI,
            URI storagePoolURI, String label, ResourceOperationTypeEnum opType,
            String token, DbClient dbClient) {
        Volume volume = new Volume();
        volume.setId(URIUtil.createId(Volume.class));
        volume.setLabel(label);
        volume.setCapacity(size);
        volume.setThinlyProvisioned(VirtualPool.ProvisioningType.Thin.toString()
                .equalsIgnoreCase(vpool.getSupportedProvisioningType()));
        volume.setVirtualPool(vpool.getId());
        volume.setProject(new NamedURI(project.getId(), volume.getLabel()));
        volume.setTenant(new NamedURI(project.getTenantOrg().getURI(), volume.getLabel()));
        volume.setVirtualArray(neighborhood.getId());
        StoragePool storagePool = null;
        if (!NullColumnValueGetter.getNullURI().toString().equals(storagePoolURI.toString())) {
            storagePool = dbClient.queryObject(StoragePool.class, storagePoolURI);
            if (null != storagePool) {
                volume.setProtocol(new StringSet());
                volume.getProtocol().addAll(
                        VirtualPoolUtil.getMatchingProtocols(vpool.getProtocols(), storagePool.getProtocols()));
            }
        } else {
            // Must be preparing a VPLEX volume which does not
            // have a storage pool so a null URI is passed. Set
            // the volume protocols to FC.
            StringSet protocols = new StringSet();
            protocols.add(StorageProtocol.Block.FC.name());
            volume.setProtocol(protocols);
        }
        volume.setStorageController(storageSystemURI);
        StorageSystem storageSystem = dbClient.queryObject(StorageSystem.class, storageSystemURI);
        String systemType = storageSystem.checkIfVmax3() ? 
                DiscoveredDataObject.Type.vmax3.name() : storageSystem.getSystemType();
        volume.setSystemType(systemType);
        volume.setPool(storagePoolURI);
        volume.setOpStatus(new OpStatusMap());

        // Set the auto tiering policy.
        if (null != vpool.getAutoTierPolicyName()) {
            URI autoTierPolicyUri = StorageScheduler.getAutoTierPolicy(storagePoolURI,
                    vpool.getAutoTierPolicyName(), dbClient);
            if (null != autoTierPolicyUri) {
                volume.setAutoTieringPolicyUri(autoTierPolicyUri);
            }
        }

        if (opType != null) {
            Operation op = new Operation();
            op.setResourceType(opType);
            volume.getOpStatus().createTaskStatus(token, op);
        }

        dbClient.createObject(volume);

        return volume;
    }

    /**
     * Prepares a migration for the passed virtual volume specifying the source
     * and target volumes for the migration.
     *
     * @param virtualVolumeURI The URI of the virtual volume.
     * @param sourceURI The URI of the source volume for the migration.
     * @param targetURI The URI of the target volume for the migration.
     * @param token The task identifier.
     *
     * @return A reference to a newly created Migration.
     */
    public Migration prepareMigration(URI virtualVolumeURI, URI sourceURI, URI targetURI, String token) {
        Migration migration = new Migration();
        migration.setId(URIUtil.createId(Migration.class));
        migration.setVolume(virtualVolumeURI);
        migration.setSource(sourceURI);
        migration.setTarget(targetURI);
        _dbClient.createObject(migration);
        migration.setOpStatus(new OpStatusMap());
        Operation op = _dbClient.createTaskOpStatus(Migration.class, migration.getId(),
                token, ResourceOperationTypeEnum.MIGRATE_BLOCK_VOLUME);
        migration.getOpStatus().put(token, op);
        _dbClient.updateObject(migration);

        return migration;
    }

    @Override
    public StorageSystemConnectivityList getStorageSystemConnectivity(
            StorageSystem storageSystem) {
        // Connectivity list to return
        StorageSystemConnectivityList connectivityList = new StorageSystemConnectivityList();
        // Set used to ensure unique values are added to the connectivity list
        Set<String> existing = new HashSet<String>();
        // List to store all vplexes found
        List<StorageSystem> vplexes = new ArrayList<StorageSystem>();

        // First determine whether or not this Storage System is a VPLEX
        if (ConnectivityUtil.isAVPlex(storageSystem)) {
            vplexes.add(storageSystem);
        } else {
            // If it's not a VPLEX find any associated VPLEXs for this Storage System
            Set<URI> vplexSystems = ConnectivityUtil.getVPlexSystemsAssociatedWithArray(_dbClient, storageSystem.getId());
            for (URI uri : vplexSystems) {
                StorageSystem vplexSystem = _dbClient.queryObject(StorageSystem.class, uri);
                vplexes.add(vplexSystem);
            }
        }

        // For every VPLEX System, find all the associated Storage Systems and add them to the
        // result list.
        for (StorageSystem vplexSystem : vplexes) {
            // Loop through the associated Storage Systems to build the connectivity response list.
            // Only store unique responses.
            Set<URI> associatedSystemURIs = ConnectivityUtil
                    .getStorageSystemAssociationsByNetwork(_dbClient, vplexSystem.getId(),
                            StoragePort.PortType.backend);
            for (URI associatedStorageSystemURI : associatedSystemURIs) {
                StorageSystem associatedStorageSystem = _dbClient.queryObject(
                        StorageSystem.class, associatedStorageSystemURI);

                if (associatedStorageSystem == null
                        || associatedStorageSystem.getInactive()
                        || ConnectivityUtil.isAVPlex(associatedStorageSystem)
                        || storageSystem.getId().equals(associatedStorageSystemURI)) {
                    continue;
                }

                StorageSystemConnectivityRestRep connection = new StorageSystemConnectivityRestRep();
                connection.getConnectionTypes().add(DiscoveredDataObject.Type.vplex.toString());
                connection.setProtectionSystem(toNamedRelatedResource(ResourceTypeEnum.PROTECTION_SYSTEM,
                        vplexSystem.getId(),
                        vplexSystem.getNativeGuid()));
                connection.setStorageSystem(toNamedRelatedResource(ResourceTypeEnum.STORAGE_SYSTEM,
                        associatedStorageSystem.getId(),
                        associatedStorageSystem.getNativeGuid()));

                // The key is a transient unique ID, since none of the actual fields guarantee uniqueness.
                // We use this to make sure we don't add the same storage system more than once for the same
                // protection system and connection type.
                String key = connection.getProtectionSystem().toString() + connection.getConnectionTypes()
                        + connection.getStorageSystem().toString();
                if (!existing.contains(key)) {
                    existing.add(key);
                    connectivityList.getConnections().add(connection);
                }
            }
        }

        return connectivityList;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void verifyVarrayChangeSupportedForVolumeAndVarray(Volume volume,
            VirtualArray newVarray) throws APIException {

        // We will not allow virtual array change for a VPLEX volume that was
        // created using the target volume of a block snapshot.
        if (VPlexUtil.isVolumeBuiltOnBlockSnapshot(_dbClient, volume)) {
            throw APIException.badRequests.varrayChangeNotAllowedVolumeIsExposedSnapshot(volume.getId().toString());
        }

        // The volume virtual pool must specify vplex local high availability.
        VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
        String highAvailability = vpool.getHighAvailability();
        if (!VirtualPool.HighAvailabilityType.vplex_local.name().equals(highAvailability)) {
            s_logger.info("The volume is not a VPlex local virtual volume.");
            throw APIException.badRequests.changesNotSupportedFor("VirtualArray", "distributed VPlex volumes");
        }

        // Verify that the VPLEX has connectivity to the new virtual array.
        // Note that we know at this point that the current and new varrays
        // are not the same.
        URI systemURI = volume.getStorageController();
        StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
        List<URI> vplexSystemVarrays = ConnectivityUtil.getVPlexSystemVarrays(
                _dbClient, system.getId());
        if (!vplexSystemVarrays.contains(newVarray.getId())) {
            throw APIException.badRequests.invalidVarrayForVplex(system.getLabel(), newVarray.getLabel());
        }

        // If the volume is exported, all storage ports that the volume is exported through should be in the target
        // virtual array
        // The hosts that volume is exported to should be in the target virtual array too.
        URIQueryResultList exportGroupURIs = new URIQueryResultList();
        _dbClient.queryByConstraint(ContainmentConstraint.Factory.getBlockObjectExportGroupConstraint(volume.getId()), exportGroupURIs);
        Iterator<URI> it = exportGroupURIs.iterator();
        Set<URI> storagePorts = new HashSet<URI>();
        Set<URI> initiators = new HashSet<URI>();
        while (it.hasNext()) {
            // exported
            URI egUri = it.next();
            ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, egUri);
            List<URI> inits = StringSetUtil.stringSetToUriList(exportGroup.getInitiators());
            initiators.addAll(inits);
            List<ExportMask> exportMasks = ExportMaskUtils.getExportMasks(_dbClient, exportGroup);
            for (ExportMask exportMask : exportMasks) {
                storagePorts.addAll(StringSetUtil.stringSetToUriList(exportMask.getStoragePorts()));
            }
        }
        if (!storagePorts.isEmpty()) {
            String newVarrayId = newVarray.getId().toString();
            Set<URI> newVarrayStoragePorts = new HashSet<URI>();
            URIQueryResultList queryResults = new URIQueryResultList();
            _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                    .getAssignedVirtualArrayStoragePortsConstraint(newVarrayId), queryResults);
            Iterator<URI> resultsIter = queryResults.iterator();
            while (resultsIter.hasNext()) {
                newVarrayStoragePorts.add(resultsIter.next());
            }

            URIQueryResultList connectedResults = new URIQueryResultList();
            _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                    .getImplicitVirtualArrayStoragePortsConstraint(newVarrayId), connectedResults);
            Iterator<URI> iter = connectedResults.iterator();
            while (iter.hasNext()) {
                newVarrayStoragePorts.add(iter.next());
            }
            if (!newVarrayStoragePorts.containsAll(storagePorts)) {
                s_logger.info("The volume is exported, but the exported target storage ports are not all in the target virtual array");
                throw APIException.badRequests.changesNotSupportedFor("VirtualArray",
                        "exported volumes, and the target storage ports exported through"
                                + " are not all in the target virtual array");
            }
            for (URI init : initiators) {
                Initiator initiator = _dbClient.queryObject(Initiator.class, init);
                if (initiator != null && !initiator.getInactive()) {
                    String pwwn = initiator.getInitiatorPort();
                    Set<String> varrayIds = ConnectivityUtil.getInitiatorVarrays(pwwn, _dbClient);
                    if (!varrayIds.contains(newVarrayId)) {
                        s_logger.info("The volume is exported, the exported hosts initiators are not all in the target virtual array");
                        throw APIException.badRequests.changesNotSupportedFor("VirtualArray",
                                "exported volumes, and the exported host initiators"
                                        + " are not all in the target virtual array");
                    }
                }
            }

        }

        // If the vpool has assigned varrays, the vpool must be assigned
        // to the new varray.
        StringSet vpoolVarrayIds = vpool.getVirtualArrays();
        if ((vpoolVarrayIds == null) || (!vpoolVarrayIds.contains(newVarray.getId().toString()))) {
            throw APIException.badRequests.vpoolNotAssignedToVarrayForVarrayChange(
                    vpool.getLabel(), volume.getLabel());
        }

        // The volume must be detached from all full copies.
        if (BlockFullCopyUtils.volumeHasFullCopySession(volume, _dbClient)) {
            throw APIException.badRequests.volumeForVarrayChangeHasFullCopies(volume.getLabel());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void changeVirtualArrayForVolumes(List<Volume> volumes,
            BlockConsistencyGroup cg, List<Volume> cgVolumes, VirtualArray newVirtualArray, String taskId)
                    throws InternalException {
        // Since the backend volume would change and VPLEX snapshots are just
        // snapshots of the backend volume, the user would lose all snapshots
        // if we allowed the varray change. Therefore, we don't allow the varray
        // change if the volume has snapshots. The user would have to explicitly
        // go and delete their snapshots first.
        //
        // Note: We make this validation here instead of in the verification
        // method "verifyVarrayChangeSupportedForVolumeAndVarray" because the
        // verification method is called from not only the API to change the
        // volume virtual array, but also the API that determines the volumes
        // that would be eligible for a proposed varray change. The latter API
        // is used by the UI to populate the list of volumes. We want volumes
        // with snaps to appear in the list, so that the user will know that
        // if they remove the snapshots, they can perform the varray change.
        for (Volume volume : volumes) {
            List<BlockSnapshot> snapshots = getSnapshots(volume);
            if (!snapshots.isEmpty()) {
                for (BlockSnapshot snapshot : snapshots) {
                    if (!snapshot.getInactive()) {
                        throw APIException.badRequests.volumeForVarrayChangeHasSnaps(volume.getId().toString());
                    }
                }
            }
            // If the volume has mirrors then varray change will not
            // be allowed. User needs to explicitly delete mirrors first.
            // This is applicable for both Local and Distributed volumes.
            // For distributed volume getMirrors will get mirror if any
            // on source or HA side.
            StringSet mirrorURIs = volume.getMirrors();
            if (mirrorURIs != null && !mirrorURIs.isEmpty()) {
                List<VplexMirror> mirrors = _dbClient.queryObject(VplexMirror.class, StringSetUtil.stringSetToUriList(mirrorURIs));
                if (mirrors != null && !mirrors.isEmpty()) {
                    throw APIException.badRequests
                            .volumeForVarrayChangeHasMirrors(volume.getId().toString(), volume.getLabel());
                }
            }
        }

        // All volumes will be migrated in the same workflow.
        // If there are many volumes in the CG, the workflow
        // will have many steps. Worse, if an error occurs
        // additional workflow steps get added for rollback.
        // An issue can then arise trying to persist the
        // workflow to Zoo Keeper because the workflow
        // persistence is restricted to 250000 bytes. So
        // we add a restriction on the number of volumes in
        // the CG that will be allowed for a data migration
        // vpool change.
        if ((cg != null) && (volumes.size() > _maxCgVolumesForMigration)) {
            throw APIException.badRequests
                    .cgContainsTooManyVolumesForVArrayChange(cg.getLabel(),
                            volumes.size(), _maxCgVolumesForMigration);
        }

        // Varray changes for volumes in CGs will always migrate all
        // volumes in the CG. If there are multiple volumes in the CG
        // and the CG has associated backend CGs then we need to ensure
        // that when the migration targets are created for these volumes
        // they are placed on the same backend storage system in the new
        // varray. Therefore, we ensure this is the case by verifying that
        // the valid storage pools for the volume's vpool that are tagged
        // to the new varray resolve to a single storage system. If not
        // we don't allow the varray change.
        if ((cg != null) && (cg.checkForType(Types.LOCAL)) && (cgVolumes.size() > 1)) {
            verifyTargetSystemsForCGDataMigration(volumes, null, newVirtualArray.getId());
        }

        // Create the volume descriptors for the virtual array change.
        List<VolumeDescriptor> descriptors = createVolumeDescriptorsForVarrayChange(
                volumes, newVirtualArray, taskId);

        try {
            // Orchestrate the virtual array change.
            BlockOrchestrationController controller = getController(
                    BlockOrchestrationController.class,
                    BlockOrchestrationController.BLOCK_ORCHESTRATION_DEVICE);
            controller.changeVirtualArray(descriptors, taskId);
            s_logger.info("Successfully invoked block orchestrator.");
        } catch (InternalException e) {
            s_logger.error("Controller error", e);
            for (VolumeDescriptor descriptor : descriptors) {
                // Make sure to clean up the tasks associated with the
                // migration targets and migrations.
                if (VolumeDescriptor.Type.VPLEX_MIGRATE_VOLUME.equals(descriptor.getType())) {
                    _dbClient.error(Volume.class, descriptor.getVolumeURI(), taskId, e);
                    _dbClient.error(Migration.class, descriptor.getMigrationId(), taskId, e);
                }
            }
            throw e;
        }
    }

    /**
     * Creates the volumes descriptors for a varray change for the passed
     * list of VPLEX volumes.
     *
     * @param volumes The VPLEX volumes being moved
     * @param newVarray The target virtual array
     * @param taskId The task identifier
     *
     * @return A list of volume descriptors
     */
    private List<VolumeDescriptor> createVolumeDescriptorsForVarrayChange(List<Volume> volumes,
            VirtualArray newVarray, String taskId) {

        // The list of descriptors for the virtual array change.
        List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();

        // The VPLEX system.
        StorageSystem vplexSystem = _dbClient.queryObject(StorageSystem.class,
                volumes.get(0).getStorageController());

        // Create a descriptor for each vplex volume.
        for (Volume vplexVolume : volumes) {
            VolumeDescriptor descriptor = new VolumeDescriptor(
                    VolumeDescriptor.Type.VPLEX_VIRT_VOLUME, vplexVolume.getStorageController(),
                    vplexVolume.getId(), null, null);
            Map<String, Object> descrParams = new HashMap<String, Object>();
            descrParams.put(VolumeDescriptor.PARAM_VARRAY_CHANGE_NEW_VAARAY_ID, newVarray.getId());
            descriptor.setParameters(descrParams);
            descriptors.add(descriptor);

            // We'll need to prepare a target volume and create a
            // descriptor for each backend volume being migrated.
            StringSet assocVolumes = vplexVolume.getAssociatedVolumes();
            if (null == assocVolumes) {
                s_logger.warn("VPLEX volume {} has no backend volumes. It was possibly ingested 'Virtual Volume Only'.", 
                        vplexVolume.forDisplay());
            } else {
                String assocVolumeId = assocVolumes.iterator().next();
                URI assocVolumeURI = URI.create(assocVolumeId);
                Volume assocVolume = _dbClient.queryObject(Volume.class, assocVolumeURI);
                VirtualPool assocVolumeVPool = _dbClient.queryObject(VirtualPool.class,
                        assocVolume.getVirtualPool());
                descriptors.addAll(createBackendVolumeMigrationDescriptors(vplexSystem,
                        vplexVolume, assocVolume, newVarray, assocVolumeVPool,
                        getVolumeCapacity(assocVolume), taskId, null, false, null));
            }
        }

        return descriptors;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void verifyVolumeExpansionRequest(Volume vplexVolume, long newSize) {
        s_logger.info("Verify if VPlex volume {} can be expanded", vplexVolume.getId());
        // We try and expand the VPlex volume by natively expanding the
        // backend volumes. However, if native expansion is not supported
        // for the backend volumes, we can always try to expand the VPlex
        // volume by migrating the the backend volumes to new target volumes
        // of the expanded size. However, we will not allow expansion of a
        // VPLEX volume that was created using the target volume of a
        // block snapshot.
        if (VPlexUtil.isVolumeBuiltOnBlockSnapshot(_dbClient, vplexVolume)) {
            throw APIException.badRequests.expansionNotAllowedVolumeIsExposedSnapshot(vplexVolume.getId().toString());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expandVolume(Volume vplexVolume, long newSize, String taskId)
            throws InternalException {
        URI vplexURI = vplexVolume.getStorageController();

        if (isNativeVolumeExpansionSupported(vplexVolume, newSize)) {
            // Expand the passed VPlex virtual volume by natively
            // expanding the backend volume(s).
            // TODO: At the moment, native expansion go via block orchestration controller. JIRA CTRL-5336 filed for this.
            // Expand via migration still follows the old way of doing things and this needs to be changed.
            List<VolumeDescriptor> volumeDescriptors = createVolumeDescriptorsForNativeExpansion(Arrays.asList(vplexVolume.getId()));
            BlockOrchestrationController controller = getController(BlockOrchestrationController.class,
                    BlockOrchestrationController.BLOCK_ORCHESTRATION_DEVICE);
            for (VolumeDescriptor volDesc : volumeDescriptors) {
                volDesc.setVolumeSize(newSize);
            }
            controller.expandVolume(volumeDescriptors, taskId);
        } else {
            // A list of the volumes satisfying the new size to
            // which the data on the current backend volumes
            // will be migrated.
            List<URI> newVolumes = new ArrayList<URI>();

            // A Map containing a migration for each new backend
            // volume
            Map<URI, URI> migrationMap = new HashMap<URI, URI>();

            // A map that specifies the storage pool in which
            // each new volume should be created.
            Map<URI, URI> poolVolumeMap = new HashMap<URI, URI>();

            // Get the VPlex system.
            StorageSystem vplexSystem = _permissionsHelper.getObjectById(
                    vplexURI, StorageSystem.class);

            // Prepare the backend volume(s) for migration.
            StringSet assocVolumeIds = vplexVolume.getAssociatedVolumes();
            if (null == assocVolumeIds || assocVolumeIds.isEmpty()) {
                s_logger.error("VPLEX volume {} has no backend volumes.", vplexVolume.forDisplay());
                throw InternalServerErrorException.
                    internalServerErrors.noAssociatedVolumesForVPLEXVolume(vplexVolume.forDisplay());
            }
            for (String assocVolumeId : assocVolumeIds) {
                Volume assocVolume = _permissionsHelper.getObjectById(
                        URI.create(assocVolumeId), Volume.class);
                VirtualArray assocVolumeNH = _permissionsHelper.getObjectById(
                        assocVolume.getVirtualArray(), VirtualArray.class);
                VirtualPool assocVolumeCos = _permissionsHelper.getObjectById(
                        assocVolume.getVirtualPool(), VirtualPool.class);
                prepareBackendVolumeForMigration(vplexSystem, vplexVolume, assocVolume,
                        assocVolumeNH, assocVolumeCos, newSize, taskId, newVolumes,
                        migrationMap, poolVolumeMap);
            }

            // Use the VPlex controller to expand the passed VPlex virtual
            // volume by migrating the backend volume(s) to the migration
            // target(s) of the new size.
            VPlexController controller = getController();
            controller.expandVolumeUsingMigration(vplexURI, vplexVolume.getId(),
                    newVolumes, migrationMap, poolVolumeMap, newSize, taskId);
        }
    }

    /**
     * Determines if the VPlex volume can be expanded by natively expanding
     * the backend volumes.
     *
     * @param vplexVolume A reference to the VPlex volume.
     * @param newSize The new desired size.
     *
     * @return true if the volume can be expanded natively, false otherwise.
     */
    private boolean isNativeVolumeExpansionSupported(Volume vplexVolume, Long newSize) {
        // Determine if native volume expansion should be used or VPlex
        // migration to a larger target volume(s).
        boolean useNativeVolumeExpansion = true;
        StringSet assocVolumeIds = vplexVolume.getAssociatedVolumes();
        if (null == assocVolumeIds) {
            s_logger.warn("VPLEX volume {} has no backend volumes. It was probably ingested 'Virtual Volume Only'.", 
                    vplexVolume.forDisplay());
            useNativeVolumeExpansion = false;
        } else {
            for (String assocVolumeId : assocVolumeIds) {
                Volume assocVolume = _permissionsHelper.getObjectById(
                        URI.create(assocVolumeId), Volume.class);
                // If any backend volume does not support native expansion, then
                // we use migration to expand the VPlex volume.
                try {
                    super.verifyVolumeExpansionRequest(assocVolume, newSize);
                } catch (Exception e) {
                    useNativeVolumeExpansion = false;
                    break;
                }
            }
        }

        return useNativeVolumeExpansion;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskResourceRep deleteConsistencyGroup(StorageSystem device,
            BlockConsistencyGroup consistencyGroup, String task) throws ControllerException {

        Operation op = _dbClient.createTaskOpStatus(BlockConsistencyGroup.class, consistencyGroup.getId(),
                task, ResourceOperationTypeEnum.DELETE_CONSISTENCY_GROUP);

        // Get VPlex controller and delete the consistency group.
        VPlexController controller = getController();
        controller.deleteConsistencyGroup(device.getId(), consistencyGroup.getId(), task);

        return toTask(consistencyGroup, task, op);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskResourceRep updateConsistencyGroup(StorageSystem cgStorageSystem,
            List<Volume> cgVolumes, BlockConsistencyGroup consistencyGroup,
            List<URI> addVolumesList, List<URI> removeVolumesList, String taskId)
                    throws ControllerException {

        // addVolumesList could be volumes, or full copies, or snapshots or mirrors.
        List<URI> addVolumes = new ArrayList<URI>();
        List<URI> addSnapshots = new ArrayList<URI>();
        List<URI> addFullcopies = new ArrayList<URI>();
        for (URI volumeURI : addVolumesList) {
            BlockObject blockObject = BlockObject.fetch(_dbClient, volumeURI);
            if (blockObject instanceof BlockMirror) {
                throw APIException.badRequests.actionNotApplicableForVplexVolumeMirrors(
                        ResourceOperationTypeEnum.UPDATE_CONSISTENCY_GROUP.name());
            } else if (blockObject instanceof BlockSnapshot) {
                addSnapshots.add(volumeURI);
            } else if (blockObject instanceof Volume) {
                boolean isFullCopy = ControllerUtils.isVolumeFullCopy((Volume) blockObject, _dbClient);
                if (isFullCopy) {
                    addFullcopies.add(volumeURI);
                } else {
                    addVolumes.add(volumeURI);
                }
            }
        }
        if ((!addVolumes.isEmpty() && (!addSnapshots.isEmpty() || !addFullcopies.isEmpty())) ||
                (!addSnapshots.isEmpty() && !addFullcopies.isEmpty())) {
            throw APIException.badRequests.cantUpdateCGWithMixedBlockObjects(consistencyGroup.getLabel());
        }

        // When adding volumes to a VPLEX CG, the volumes must be of the same
        // type and have the same virtual array as those already in the consistency
        // group.
        if (!addVolumes.isEmpty()) {
            Iterator<Volume> cgVolumesIter = cgVolumes.iterator();
            if (cgVolumesIter.hasNext()) {
                Volume cgVolume = cgVolumesIter.next();
                VirtualPool cgVPool = _permissionsHelper.getObjectById(
                        cgVolume.getVirtualPool(), VirtualPool.class);
                URI cgVArrayURI = cgVolume.getVirtualArray();
                String cgHAType = cgVPool.getHighAvailability();
                for (URI volumeURI : addVolumes) {
                    Volume addVolume = _permissionsHelper.getObjectById(volumeURI, Volume.class);
                    VirtualPool addVolumeVPool = _permissionsHelper.getObjectById(
                            addVolume.getVirtualPool(), VirtualPool.class);
                    if (!addVolumeVPool.getHighAvailability().equals(cgHAType)) {
                        throw APIException.badRequests.invalidParameterConsistencyGroupVolumeHasIncorrectHighAvailability(cgVolume.getId(),
                                cgHAType);
                    } else if (!cgVArrayURI.equals(addVolume.getVirtualArray())) {
                        throw APIException.badRequests.invalidParameterConsistencyGroupVolumeHasIncorrectVArray(cgVolume.getId(),
                                cgVArrayURI);
                    }
                }
            }

            // Check if the volumes have been in the CG, and not ingestion case
            if (consistencyGroup.getTypes().contains(Types.LOCAL.toString()) && !cgVolumes.isEmpty()) {
                Set<String> cgVolumesURISet = new HashSet<String>();
                for (Volume cgVolume : cgVolumes) {
                    cgVolumesURISet.add(cgVolume.getId().toString());
                }
                Iterator<URI> iter = addVolumes.iterator();
                while (iter.hasNext()) {
                    if (cgVolumesURISet.contains(iter.next().toString())) {
                        iter.remove();
                    }
                }

                if (addVolumes.isEmpty()) {
                    // All volumes in the addVolumes list have been in the CG. return success
                    s_logger.info("The volumes have been added to the CG");
                    Operation op = new Operation();
                    op.setResourceType(ResourceOperationTypeEnum.UPDATE_CONSISTENCY_GROUP);
                    op.ready("Volumes have been added to the consistency group");
                    _dbClient.createTaskOpStatus(BlockConsistencyGroup.class, consistencyGroup.getId(), taskId, op);
                    return toTask(consistencyGroup, taskId, op);
                }

            }
        }

        // Only add snapshot or full copies to CG if backend volumes are from the same storage system.
        if (!addSnapshots.isEmpty() || !addFullcopies.isEmpty()) {
            if (!VPlexUtil.isVPLEXCGBackendVolumesInSameStorage(cgVolumes, _dbClient)) {
                throw APIException.badRequests.cantUpdateCGWithReplicaFromMultipleSystems(consistencyGroup.getLabel());
            }
        }

        Operation op = _dbClient.createTaskOpStatus(BlockConsistencyGroup.class, consistencyGroup.getId(),
                taskId, ResourceOperationTypeEnum.UPDATE_CONSISTENCY_GROUP);

        // When adding snapshots to CG, just call block implementation.
        if (!addSnapshots.isEmpty()) {
            BlockSnapshot snapshot = _permissionsHelper.getObjectById(addSnapshots.get(0), BlockSnapshot.class);
            URI systemURI = snapshot.getStorageController();
            StorageSystem system = _permissionsHelper.getObjectById(systemURI, StorageSystem.class);
            BlockController controller = getController(BlockController.class, system.getSystemType());
            controller.updateConsistencyGroup(system.getId(), consistencyGroup.getId(),
                    addVolumesList, removeVolumesList, taskId);
            return toTask(consistencyGroup, taskId, op);

        }

        // If the CG is ingested, and we would like to add back end CGs for those virtual volumes in the CG,
        // all the virtual volumes in the CG have to be selected.
        if (!addVolumes.isEmpty()) {
            verifyAddVolumesToIngestedCG(consistencyGroup, addVolumes);
        }

        if (!addFullcopies.isEmpty()) {
            addVolumes.addAll(addFullcopies);
        }

        // Get VPlex controller
        VPlexController controller = getController();
        controller.updateConsistencyGroup(cgStorageSystem.getId(),
                consistencyGroup.getId(), addVolumes, removeVolumesList, taskId);

        return toTask(consistencyGroup, taskId, op);

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList startNativeContinuousCopies(StorageSystem vplexStorageSystem, Volume vplexVolume,
            VirtualPool sourceVirtualPool, VirtualPoolCapabilityValuesWrapper capabilities,
            NativeContinuousCopyCreate param, String taskId)
                    throws ControllerException {

        // We will not allow continuous copies for a VPLEX volume that was created
        // using the target volume of a block snapshot.
        if (VPlexUtil.isVolumeBuiltOnBlockSnapshot(_dbClient, vplexVolume)) {
            throw APIException.badRequests.mirrorNotAllowedVolumeIsExposedSnapshot(vplexVolume.getId().toString());
        }

        validateNotAConsistencyGroupVolume(vplexVolume, sourceVirtualPool);

        TaskList taskList = new TaskList();

        // Currently, For Vplex Local Volume this will create a single mirror and add it
        // to the vplex volume. For Vplex Distributed Volume this will create single mirror
        // on source and/or HA side and add it to the vplex volume. Two steps: first place
        // the mirror and then prepare the mirror.
        URI vplexStorageSystemURI = vplexVolume.getStorageController();

        // For VPLEX Local volume there will be only one associated volume entry in this set.
        StringSet associatedVolumeIds = vplexVolume.getAssociatedVolumes();
        if (associatedVolumeIds == null) {
            throw InternalServerErrorException.internalServerErrors
                    .noAssociatedVolumesForVPLEXVolume(vplexVolume.forDisplay());
        }

        VirtualPool sourceMirrorVPool = null;
        // Set source mirror vpool
        if (!isNullOrEmpty(sourceVirtualPool.getMirrorVirtualPool())
                && !NullColumnValueGetter.isNullURI(URI.create(sourceVirtualPool.getMirrorVirtualPool()))) {
            sourceMirrorVPool = _dbClient.queryObject(VirtualPool.class, URI.create(sourceVirtualPool.getMirrorVirtualPool()));
        }

        // Check if volume is distributed and if HA Mirror Vpool is also set
        VirtualPool haMirrorVPool = VPlexUtil.getHAMirrorVpool(sourceVirtualPool, associatedVolumeIds, _dbClient);

        // Map of backend volume and the mirror pool to target backend volume for the mirror
        Map<Volume, VirtualPool> backendVolumeToMirrorVpoolMap = new HashMap<Volume, VirtualPool>();

        if (associatedVolumeIds.size() > 1) {
            // If associatedVolumeIds size is greater than 1 then its a VPLEX Distributed Volume
            updateBackendVolumeToMirrorVpoolMap(vplexVolume, associatedVolumeIds, sourceVirtualPool, sourceMirrorVPool, haMirrorVPool,
                    backendVolumeToMirrorVpoolMap);
        } else {
            // If we are here that means we need to create mirror for the VPLEX local volume
            for (String associatedVolumeId : associatedVolumeIds) {
                Volume associatedVolume = _dbClient.queryObject(Volume.class, URI.create(associatedVolumeId));
                if (associatedVolume != null) {
                    backendVolumeToMirrorVpoolMap.put(associatedVolume, sourceMirrorVPool);
                }
            }
        }

        // Project is not passed in continuous copies call.
        // Implicit assumption to use same project as the source volume.
        Project project = _permissionsHelper.getObjectById(vplexVolume.getProject(), Project.class);

        Map<Volume, List<Recommendation>> backendvolumeToMirrorRecommendationMap = new HashMap<Volume, List<Recommendation>>();
        Map<Volume, VirtualArray> backendvolumeToMirrorVarrayMap = new HashMap<Volume, VirtualArray>();

        for (Volume backendVolume : backendVolumeToMirrorVpoolMap.keySet()) {
            URI backendVolumeVarrayURI = backendVolume.getVirtualArray();
            // Get the VPLEX cluster value from the varray
            String cluster = ConnectivityUtil.getVplexClusterForVarray(backendVolumeVarrayURI, vplexStorageSystemURI, _dbClient);
            if (cluster.equals(ConnectivityUtil.CLUSTER_UNKNOWN)) {
                throw InternalServerErrorException.internalServerErrors
                        .noVplexClusterInfoForVarray(backendVolumeVarrayURI.toString(), vplexStorageSystemURI.toString());
            }

            VirtualPool backendVolumeVpool = _dbClient.queryObject(VirtualPool.class, backendVolume.getVirtualPool());
            VirtualPool mirrorVpool = backendVolumeToMirrorVpoolMap.get(backendVolume);
            // Get recommendations for the mirror placement
            List<Recommendation> volumeRecommendations = null;
            VirtualArray varray = null;
            if (mirrorVpool != null) {
                // If mirror vpool is provided try to get recommendations using the provided mirror vpool
                // Check if any of the varray for mirror vpool is same as that of the source volume varray.
                // If yes then get recommendations using that varray.
                StringSet mirrorVPoolVarrays = mirrorVpool.getVirtualArrays();
                boolean foundMatch = false;
                for (String mirrorVPoolVarrayId : mirrorVPoolVarrays) {
                    if (mirrorVPoolVarrayId.equals(backendVolumeVarrayURI.toString())) {
                        varray = _dbClient.queryObject(VirtualArray.class, backendVolumeVarrayURI);
                        volumeRecommendations = _scheduler.getRecommendationsForMirrors(varray, project, backendVolumeVpool, mirrorVpool,
                                capabilities, vplexStorageSystemURI, backendVolume.getStorageController(), cluster);
                        foundMatch = true;
                        break;
                    }
                }

                if (!foundMatch) {
                    s_logger.info("Mirror Vpool varray is different than the source vpool varray");
                    // If mirror vpool selected belongs to a different varray than the source volume varray,
                    // In that case iterate through all the varrays to check if anyone of them is associated
                    // with the source volume VPLEX system.
                    for (String mirrorVPoolVarrayId : mirrorVPoolVarrays) {
                        if (VPlexUtil.checkIfVarrayContainsSpecifiedVplexSystem(mirrorVPoolVarrayId, cluster, vplexStorageSystemURI,
                                _dbClient)) {
                            varray = _dbClient.queryObject(VirtualArray.class, URI.create(mirrorVPoolVarrayId));
                            volumeRecommendations = _scheduler.getRecommendationsForMirrors(varray, project, backendVolumeVpool,
                                    mirrorVpool, capabilities, vplexStorageSystemURI, backendVolume.getStorageController(), cluster);
                            if (!volumeRecommendations.isEmpty()) {
                                foundMatch = true;
                                break;
                            } else {
                                s_logger.info("Tried to get recommemdations using varray {} {}. ", varray.getId(), varray.getLabel());
                            }
                        }
                    }
                }
            } else {
                if (sourceVirtualPool.getHighAvailability().equals(VirtualPool.HighAvailabilityType.vplex_local.name())) {
                    s_logger.info("Mirror vpool is not specified, use the source volume virtual pool and virtual array");
                    // In case of Vplex local if mirror pool is not provided then we can use source vpool as mirror vpool.
                    sourceMirrorVPool = backendVolumeVpool;
                    mirrorVpool = backendVolumeVpool;
                    backendVolumeToMirrorVpoolMap.put(backendVolume, sourceMirrorVPool);
                    // Separate Mirror vpool is not provided so use the source volume vpool and varray for
                    // getting recommendations.Here sourceVirtualPool and mirrorVPool will be same.
                    varray = _dbClient.queryObject(VirtualArray.class, backendVolumeVarrayURI);
                    volumeRecommendations = _scheduler.getRecommendationsForMirrors(varray, project, backendVolumeVpool, mirrorVpool,
                            capabilities, vplexStorageSystemURI, backendVolume.getStorageController(), cluster);
                }
            }

            if (mirrorVpool == null) {
                throw APIException.badRequests.noMirrorVpoolForVplexVolume(vplexVolume.getLabel());
            }

            if (varray == null) {
                throw APIException.badRequests.noVarrayForMirrorVpoolWithExpectedVplex(mirrorVpool.getLabel(),
                        vplexStorageSystem.getLabel(), cluster);
            }

            if (volumeRecommendations == null || volumeRecommendations.isEmpty()) {
                if (volumeRecommendations.isEmpty()) {
                    StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, backendVolume.getStorageController());
                    throw APIException.badRequests.noMatchingStoragePoolsForContinuousCopiesVpoolForVplex(varray.getLabel(),
                            sourceMirrorVPool.getLabel(), storageSystem.getLabel());
                }
            }

            // Add mirror recommendations for the backend volume to the map
            backendvolumeToMirrorRecommendationMap.put(backendVolume, volumeRecommendations);
            backendvolumeToMirrorVarrayMap.put(backendVolume, varray);
        }

        // Prepare mirror.
        int varrayCount = 0;
        int volumeCounter = 1;
        int volumeCount = capabilities.getResourceCount(); // volumeCount will be always 1 for now
        String volumeLabel = param.getName();

        List<URI> allVolumes = new ArrayList<URI>();
        List<URI> allMirrors = new ArrayList<URI>();
        List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();
        // Currently only one local mirror is supported for the VPLEX local virtual volume
        List<VplexMirror> preparedMirrors = new ArrayList<VplexMirror>();

        for (Volume backendVolume : backendvolumeToMirrorRecommendationMap.keySet()) {
            List<Recommendation> volumeRecommendations = backendvolumeToMirrorRecommendationMap.get(backendVolume);
            VirtualArray varray = backendvolumeToMirrorVarrayMap.get(backendVolume);
            VirtualPool mirrorVpool = backendVolumeToMirrorVpoolMap.get(backendVolume);
            long thinVolumePreAllocationSize = 0;
            if (null != mirrorVpool.getThinVolumePreAllocationPercentage()) {
                thinVolumePreAllocationSize = VirtualPoolUtil
                        .getThinVolumePreAllocationSize(
                                mirrorVpool.getThinVolumePreAllocationPercentage(), vplexVolume.getCapacity());
            }

            for (Recommendation volumeRecommendation : volumeRecommendations) {
                VPlexRecommendation vplexRecommendation = (VPlexRecommendation) volumeRecommendation;
                StringBuilder mirrorLabelBuilder = new StringBuilder(volumeLabel);
                if (backendVolume.getVirtualArray().equals(vplexVolume.getVirtualArray())) {
                    varrayCount = 0;
                } else {
                    varrayCount = 1;
                }
                mirrorLabelBuilder.append('-').append(varrayCount);
                if (volumeCount > 1) {
                    mirrorLabelBuilder.append('-').append(volumeCounter++);
                }

                // Create mirror object
                VplexMirror createdMirror = initializeMirror(vplexVolume, mirrorVpool, varray, mirrorLabelBuilder.toString(),
                        thinVolumePreAllocationSize, _dbClient);
                preparedMirrors.add(createdMirror);
                Operation op = _dbClient.createTaskOpStatus(VplexMirror.class, createdMirror.getId(), taskId,
                        ResourceOperationTypeEnum.ATTACH_VPLEX_LOCAL_MIRROR);
                s_logger.info("Prepared mirror {}", createdMirror.getId());
                allMirrors.add(createdMirror.getId());

                // Add descriptor for the mirror.
                VolumeDescriptor descriptor = new VolumeDescriptor(
                        VolumeDescriptor.Type.VPLEX_LOCAL_MIRROR,
                        vplexStorageSystemURI, createdMirror.getId(), null, capabilities);
                descriptors.add(descriptor);

                // Create backend volume object and add it to the VplexMirror created above.
                Volume volume = prepareVolume(createdMirror, backendVolume, mirrorVpool, varray,
                        vplexRecommendation.getSourceStorageSystem(),
                        vplexRecommendation.getSourceStoragePool(), mirrorLabelBuilder.toString(), thinVolumePreAllocationSize,
                        capabilities, _dbClient);
                op = new Operation();
                op.setResourceType(ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME);
                _dbClient.createTaskOpStatus(Volume.class, volume.getId(), taskId, op);
                URI volumeId = volume.getId();
                allVolumes.add(volumeId);
                s_logger.info("Prepared volume {}", volumeId);

                // Add descriptor for the backend volume
                descriptor = new VolumeDescriptor(
                        VolumeDescriptor.Type.BLOCK_DATA,
                        vplexRecommendation.getSourceStorageSystem(), volumeId, vplexRecommendation.getSourceStoragePool(), capabilities);
                descriptors.add(descriptor);

            }
        }

        Collection<URI> mirrorTargetIds = Collections2.transform(preparedMirrors, FCTN_VPLEX_MIRROR_TO_URI);
        String mirrorTargetCommaDelimList = Joiner.on(',').join(mirrorTargetIds);
        Operation op = _dbClient.createTaskOpStatus(Volume.class, vplexVolume.getId(), taskId,
                ResourceOperationTypeEnum.ATTACH_VPLEX_LOCAL_MIRROR, mirrorTargetCommaDelimList);
        taskList.getTaskList().add(toTask(vplexVolume, preparedMirrors, taskId, op));

        try {
            VPlexController controller = getController();
            controller.attachContinuousCopies(vplexStorageSystem.getId(), descriptors, vplexVolume.getId(), taskId);
        } catch (InternalException e) {
            if (s_logger.isErrorEnabled()) {
                s_logger.error("Controller error", e);
            }
            String errMsg = String.format("Controller error: %s", e.getMessage());
            for (URI volumeURI : allVolumes) {
                _dbClient.error(Volume.class, volumeURI, taskId, e);
            }
            for (URI mirrorURI : allMirrors) {
                _dbClient.error(VplexMirror.class, mirrorURI, taskId, e);
            }
            for (TaskResourceRep volumeTask : taskList.getTaskList()) {
                volumeTask.setState(Operation.Status.error.name());
                volumeTask.setMessage(errMsg);
            }
            throw e;
        }

        return taskList;
    }

    /**
     * Convenience method to update backendVolumeToMirrorVpoolMap for the VPLEX Distributed volume.
     * In case if there is already mirror for the vplex volume, this method ensures
     * to add entry to the map only if there isn't already mirror on either leg.
     *
     * @param vplexVolume The reference to vplex distributed volume
     * @param associatedVolumeIds URIs of the associated volumes
     * @param sourceVirtualPool The reference to virtual pool to which vplex volume is associated with
     * @param sourceMirrorVPool The reference to virtual pool for the mirror on the source side
     * @param haMirrorVPool The reference to virtual pool for the mirror on the HA side
     * @param backendVolumeToMirrorVpoolMap OUT param containing map of backend volume to mirror vpool
     */
    private void updateBackendVolumeToMirrorVpoolMap(Volume vplexVolume, StringSet associatedVolumeIds, VirtualPool sourceVirtualPool,
            VirtualPool sourceMirrorVPool, VirtualPool haMirrorVPool, Map<Volume, VirtualPool> backendVolumeToMirrorVpoolMap) {

        Set<String> vplexClusterWithMirrorForVolume = new HashSet<String>();
        // Set vplexClusterWithMirrorForVolume contains Vplex Cluster on which mirror already exist for the vplex volume
        if (vplexVolume.getMirrors() != null && !vplexVolume.getMirrors().isEmpty()) {
            StringSet existingMirrors = vplexVolume.getMirrors();
            for (String existingMirrorURI : existingMirrors) {
                VplexMirror existingMirror = _dbClient.queryObject(VplexMirror.class, URI.create(existingMirrorURI));
                if (existingMirror != null && !existingMirror.getInactive()) {
                    String cluster = ConnectivityUtil.getVplexClusterForVarray(existingMirror.getVirtualArray(),
                            vplexVolume.getStorageController(), _dbClient);
                    checkIfClusterIsUnknown(cluster, existingMirror.getVirtualArray().toString(), vplexVolume.getStorageController()
                            .toString());
                    vplexClusterWithMirrorForVolume.add(cluster);
                }
            }
            s_logger.info("Vplex Mirror(s) already exists for Vplex volume" + vplexVolume.getLabel() + " "
                    + vplexVolume.getId() + " on cluster " + vplexClusterWithMirrorForVolume);
        }

        for (String associatedVolumeId : associatedVolumeIds) {
            if (sourceMirrorVPool != null && sourceVirtualPool.getMaxNativeContinuousCopies() > 0) {
                // Get the source backend volume
                Volume associatedVolume = VPlexUtil.getVPLEXBackendVolume(vplexVolume, true, _dbClient);
                if (associatedVolume != null && associatedVolume.getId().toString().equals(associatedVolumeId)) {
                    if (!vplexClusterWithMirrorForVolume.isEmpty()) {
                        // Get the vplex cluster for the source varray
                        String cluster = ConnectivityUtil.getVplexClusterForVarray(vplexVolume.getVirtualArray(),
                                vplexVolume.getStorageController(), _dbClient);
                        checkIfClusterIsUnknown(cluster, vplexVolume.getVirtualArray().toString(), vplexVolume.getStorageController()
                                .toString());
                        // If there isn't already mirror on the source side then add entry to backendVolumeToMirrorVpoolMap
                        if (!vplexClusterWithMirrorForVolume.contains(cluster)) {
                            backendVolumeToMirrorVpoolMap.put(associatedVolume, sourceMirrorVPool);
                        }
                    } else {
                        backendVolumeToMirrorVpoolMap.put(associatedVolume, sourceMirrorVPool);
                    }
                }
            } else {
                s_logger.info("The max native continuous copies for the source Vpool {} is {} ",
                        sourceVirtualPool.getLabel(), sourceVirtualPool.getMaxNativeContinuousCopies());
                if (sourceMirrorVPool == null) {
                    s_logger.info("The mirror will not be created on the source side as the source mirror pool is not provided "
                            + "in the virtual pool {} {}", sourceVirtualPool.getLabel(), sourceVirtualPool.getId());
                }
            }

            VirtualPool haVPool = VirtualPool.getHAVPool(sourceVirtualPool, _dbClient);
            if (haMirrorVPool != null && haVPool != null && haVPool.getMaxNativeContinuousCopies() > 0) {
                // Get the HA backend volume
                Volume associatedVolume = VPlexUtil.getVPLEXBackendVolume(vplexVolume, false, _dbClient);
                if (associatedVolume != null && associatedVolume.getId().toString().equals(associatedVolumeId)) {
                    if (!vplexClusterWithMirrorForVolume.isEmpty()) {
                        // Get HA varray
                        URI haVarrayURI = VPlexUtil.getHAVarray(sourceVirtualPool);
                        if (haVarrayURI != null) {
                            // Get the vplex cluster for the HA varray
                            String cluster = ConnectivityUtil.getVplexClusterForVarray(haVarrayURI, vplexVolume.getStorageController(),
                                    _dbClient);
                            checkIfClusterIsUnknown(cluster, haVarrayURI.toString(), vplexVolume.getStorageController().toString());
                            // If there isn't already mirror on the HA side then add entry to backendVolumeToMirrorVpoolMap
                            if (!vplexClusterWithMirrorForVolume.contains(cluster)) {
                                backendVolumeToMirrorVpoolMap.put(associatedVolume, haMirrorVPool);
                            }
                        }
                    } else {
                        backendVolumeToMirrorVpoolMap.put(associatedVolume, haMirrorVPool);
                    }
                }

            } else {
                if (haVPool != null) {
                    s_logger.info("The max native continuous copies for the HA Vpool {} is {} ", haVPool.getLabel(),
                            haVPool.getMaxNativeContinuousCopies());
                    if (haMirrorVPool == null) {
                        s_logger.info("The mirror will not be created on the HA side as the HA mirror pool is not provided "
                                + "in the virtual pool {} {}", haVPool.getLabel(), haVPool.getId());
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList stopNativeContinuousCopies(StorageSystem vplexStorageSystem,
            Volume sourceVolume, List<URI> mirrors, String taskId)
                    throws ControllerException {

        TaskList taskList = new TaskList();
        List<VplexMirror> vplexMirrors = null;
        if (mirrors != null) {
            vplexMirrors = new ArrayList<VplexMirror>();
            for (URI mirrorURI : mirrors) {
                VplexMirror vplexMirror = _dbClient.queryObject(VplexMirror.class, mirrorURI);
                if (vplexMirror.getDeviceLabel() == null) {
                    s_logger.error("This Vplex Mirror {} was not created successfully.", vplexMirror.getId());
                    throw APIException.badRequests.invalidVplexMirror(vplexMirror.getLabel(), vplexMirror.getId().toString());
                }
                vplexMirrors.add(vplexMirror);
            }
        }

        // If mirrors is null then this will return all mirrors for the source volume.
        // This will be mostly case through python script that will just make a call
        // to stop mirrors without specifying mirror ID.
        // Note : There will be only one mirror for the vplex volume though
        List<URI> copiesToStop = getVplexCopiesToStop(vplexMirrors, sourceVolume);

        // Removes inactive mirrors
        removeIf(copiesToStop, isVplexMirrorInactivePredicate());

        String mirrorTargetCommaDelimList = Joiner.on(',').join(copiesToStop);
        Operation op = _dbClient.createTaskOpStatus(Volume.class, sourceVolume.getId(), taskId,
                ResourceOperationTypeEnum.DETACH_VPLEX_LOCAL_MIRROR, mirrorTargetCommaDelimList);

        List<VplexMirror> copies = _dbClient.queryObject(VplexMirror.class, copiesToStop);

        // Stopped copies will be promoted to vplex volumes.
        // Creates volume objects for the mirrors that will be promoted
        List<URI> promotees = preparePromotedVolumes(copies, taskList, taskId);

        // Add task to the task list
        taskList.getTaskList().add(toTask(sourceVolume, copies, taskId, op));

        try {
            VPlexController controller = getController();
            controller.detachContinuousCopies(vplexStorageSystem.getId(), sourceVolume.getId(), copiesToStop, promotees, taskId);
        } catch (InternalException e) {

            String errorMsg = format("Failed to stop continuous copies for volume %s: %s",
                    sourceVolume.getId(), e.getMessage());

            for (TaskResourceRep taskResourceRep : taskList.getTaskList()) {
                taskResourceRep.setState(Operation.Status.error.name());
                taskResourceRep.setMessage(errorMsg);
                _dbClient.error(Volume.class, taskResourceRep.getResource().getId(), taskId, e);
            }
            throw e;
        }

        return taskList;
    }

    /**
     * @param vplexMirrors The vplex mirrors for the source volume.
     * @param sourceVolume The vplex virtual volume.
     */
    private List<URI> getVplexCopiesToStop(List<VplexMirror> vplexMirrors, Volume sourceVolume) {
        List<URI> copiesToStop = new ArrayList<URI>();
        if (vplexMirrors == null || vplexMirrors.isEmpty()) {
            copiesToStop.addAll(transform(sourceVolume.getMirrors(), FCTN_STRING_TO_URI));
        } else {
            copiesToStop.addAll(transform(vplexMirrors, FCTN_VPLEX_MIRROR_TO_URI));
        }
        return copiesToStop;
    }

    private Predicate<URI> isVplexMirrorInactivePredicate() {
        return new Predicate<URI>() {

            @Override
            public boolean apply(URI uri) {
                VplexMirror mirror = _dbClient.queryObject(VplexMirror.class, uri);
                return mirror == null || mirror.getInactive();
            }
        };
    }

    /**
     * This method creates volume objects for the copiesToStop which will used as
     * independent virtual volumes if detach mirror action completes successfully.
     */
    private List<URI> preparePromotedVolumes(List<VplexMirror> copiesToStop, TaskList taskList, String opId) {
        List<URI> promotedVolumes = new ArrayList<URI>();
        for (VplexMirror copy : copiesToStop) {
            Volume v = new Volume();
            v.setId(URIUtil.createId(Volume.class));
            Volume sourceVplexVolume = _dbClient.queryObject(Volume.class, copy.getSource());
            String promotedLabel = String.format("%s-%s", sourceVplexVolume.getLabel(), copy.getLabel());
            v.setProject(new NamedURI(copy.getProject().getURI(), promotedLabel));
            StringSet protocols = new StringSet();
            protocols.add(StorageProtocol.Block.FC.name());
            v.setProtocol(protocols);
            v.setTenant(new NamedURI(copy.getTenant().getURI(), promotedLabel));
            _dbClient.createObject(v);
            Operation op = _dbClient.createTaskOpStatus(Volume.class, v.getId(), opId,
                    ResourceOperationTypeEnum.PROMOTE_COPY_TO_VPLEX, copy.getId().toString());
            taskList.getTaskList().add(toTask(v, Arrays.asList(copy), opId, op));
            promotedVolumes.add(v.getId());
        }
        return promotedVolumes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList pauseNativeContinuousCopies(StorageSystem storageSystem,
            Volume sourceVolume, List<BlockMirror> blockMirrors, Boolean sync, String taskId)
                    throws ControllerException {
        throw APIException.methodNotAllowed.notSupportedForVplexVolumes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList resumeNativeContinuousCopies(StorageSystem storageSystem,
            Volume sourceVolume, List<BlockMirror> blockMirrors, String taskId)
                    throws ControllerException {
        throw APIException.methodNotAllowed.notSupportedForVplexVolumes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskResourceRep establishVolumeAndNativeContinuousCopyGroupRelation(
            StorageSystem storageSystem, Volume sourceVolume,
            BlockMirror blockMirror, String taskId) throws ControllerException {
        throw APIException.methodNotAllowed.notSupportedForVplexVolumes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskResourceRep establishVolumeAndSnapshotGroupRelation(
            StorageSystem storageSystem, Volume sourceVolume,
            BlockSnapshot snapshot, String taskId) throws ControllerException {
        throw APIException.methodNotAllowed.notSupportedForVplexVolumes();
    }

    @Override
    protected Set<URI> getConnectedVarrays(URI varrayUID) {

        Set<URI> varrays = new HashSet<URI>();

        List<StorageSystem> storageSystems = CustomQueryUtility
                .queryActiveResourcesByConstraint(_dbClient, StorageSystem.class,
                        AlternateIdConstraint.Factory.getConstraint(StorageSystem.class,
                                VIRTUAL_ARRAYS_CONSTRAINT, varrayUID.toString()));

        // Create and return the result.
        for (StorageSystem storageSystem : storageSystems) {
            if (storageSystem.getVirtualArrays() != null) {
                for (String varrayURIStr : storageSystem.getVirtualArrays()) {
                    varrays.add(URI.create(varrayURIStr));
                }
            }
        }

        return varrays;
    }

    /**
     *
     * @param volume
     */
    public Long getVolumeCapacity(Volume volume) {
        Long userRequestedCapacity = volume.getCapacity();
        Long provisionedCapacity = volume.getProvisionedCapacity();
        if (provisionedCapacity > userRequestedCapacity) {
            return provisionedCapacity;
        }

        return userRequestedCapacity;
    }

    /**
     * Creates and returns a new ViPR BlockSnapshot instance with the passed
     * name for the passed volume.
     *
     * @param volume The volume for which the snapshot is being created.
     * @param snapsetLabel The snapset label for grouping this snapshot.
     * @param label The label for the new snapshot.
     *
     * @return A reference to the new BlockSnapshot instance.
     */
    @Override
    protected BlockSnapshot prepareSnapshotFromVolume(Volume vplexVolume, String snapsetLabel, String label) {

        // When creating a snapshot for a VPLEX volume, we create a
        // native snapshot of the source backend volume for the
        // VPLEX volume. The source backend volume is the associated
        // volume in the same virtual arrays as the VPLEX volume.
        Volume nativeSnapshotSourceVolume = getVPLEXSnapshotSourceVolume(vplexVolume);

        // Note that when creating the ViPR snapshot, some of the properties
        // of the snapshot come from the VPLEX volume, while others come
        // from the source backend volume.
        BlockSnapshot snapshot = new BlockSnapshot();
        snapshot.setId(URIUtil.createId(BlockSnapshot.class));
        snapshot.setSourceNativeId(nativeSnapshotSourceVolume.getNativeId());
        snapshot.setParent(new NamedURI(nativeSnapshotSourceVolume.getId(), nativeSnapshotSourceVolume.getLabel()));
        snapshot.setLabel(label);
        snapshot.setStorageController(nativeSnapshotSourceVolume.getStorageController());
        snapshot.setSystemType(nativeSnapshotSourceVolume.getSystemType());
        snapshot.setVirtualArray(nativeSnapshotSourceVolume.getVirtualArray());
        snapshot.setProtocol(new StringSet());
        snapshot.getProtocol().addAll(nativeSnapshotSourceVolume.getProtocol());
        snapshot.setProject(new NamedURI(vplexVolume.getProject().getURI(), vplexVolume.getProject().getName()));
        snapshot.setSnapsetLabel(ResourceOnlyNameGenerator.removeSpecialCharsForName(
                snapsetLabel, SmisConstants.MAX_SNAPSHOT_NAME_LENGTH));

        // Set the CG. We could really use either the VPLEX volume or the
        // backend volume. They would have the same CG.
        URI cgUri = nativeSnapshotSourceVolume.getConsistencyGroup();
        if (cgUri != null) {
            snapshot.setConsistencyGroup(cgUri);
        }

        return snapshot;
    }

    /**
     * Returns the backend volume of the passed VPLEX volume to be used as the
     * source volume for a snapshot of the VPLEX volume.
     *
     * @param vplexVolume A reference to the VPLEX volume.
     *
     * @return A reference to the backend volume to serve as the snapshot
     *         source.
     */
    public Volume getVPLEXSnapshotSourceVolume(Volume vplexVolume) {
        StringSet associatedVolumeIds = vplexVolume.getAssociatedVolumes();
        if (associatedVolumeIds == null) {
            throw InternalServerErrorException.internalServerErrors
                    .noAssociatedVolumesForVPLEXVolume(vplexVolume.forDisplay());
        }

        // Get the backend volume that will serve as the source volume
        // for a native snapshot.
        Volume snapshotSourceVolume = VPlexUtil.getVPLEXBackendVolume(vplexVolume, true, _dbClient);
        if (snapshotSourceVolume == null) {
            throw InternalServerErrorException.internalServerErrors
                    .noSourceVolumeForVPLEXVolumeSnapshot(vplexVolume.forDisplay());
        }

        return snapshotSourceVolume;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Volume> getVolumesToSnap(Volume reqVolume, String snapshotType) {
        // By default, if the passed volume is in a consistency group
        // all volumes in the consistency group should be snapped.
        List<Volume> volumesToSnap = new ArrayList<Volume>();
        URI cgURI = reqVolume.getConsistencyGroup();
        if (!NullColumnValueGetter.isNullURI(cgURI)) {
            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, cgURI);
            // If there is no corresponding native CG for the VPLEX
            // CG, then this is a CG created prior to 2.2 and in this
            // case we maintain pre-2.2 behavior by only snapping the
            // requested volume.
            if (!cg.checkForType(Types.LOCAL)) {
                volumesToSnap.add(reqVolume);
            } else {
                volumesToSnap = getActiveCGVolumes(cg);
            }
        } else {
            volumesToSnap.add(reqVolume);
        }
        return volumesToSnap;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Volume> getActiveCGVolumes(BlockConsistencyGroup cg) {
        return BlockConsistencyGroupUtils.getActiveVplexVolumesInCG(cg, _dbClient, null);
    }

    /**
     * Uses the appropriate controller to create the snapshots.
     *
     * @param reqVolume The volume from the snapshot request.
     * @param snapshotURIs The URIs of the prepared snapshots
     * @param snapshotType The snapshot technology type.
     * @param createInactive true if the snapshots should be created but not
     *            activated, false otherwise.
     * @param readOnly true if the snapshot should be read only, false otherwise
     * @param taskId The unique task identifier.
     */
    @Override
    public void createSnapshot(Volume reqVolume, List<URI> snapshotURIs,
            String snapshotType, Boolean createInactive, Boolean readOnly, String taskId) {

        Volume snapshotSourceVolume = getVPLEXSnapshotSourceVolume(reqVolume);
        super.createSnapshot(snapshotSourceVolume, snapshotURIs, snapshotType,
                createInactive, readOnly, taskId);
    }

    /**
     * Counts and returns the number of snapshots on a VPLEX volume.
     *
     * NOTE: We use the VPLEX volume vpool max snapshots value. The source
     * backend volume, which is actually snapped natively, should have the
     * same vpool and hence same max snapshots. However, this could be an
     * issue for distributed volumes if we snap both sides.
     *
     * @param vplexVolume A reference to a VPLEX volume.
     *
     * @return The number of snapshots on a VPLEX volume.
     */
    @Override
    protected int getNumNativeSnapshots(Volume vplexVolume) {
        Volume snapshotSourceVolume = getVPLEXSnapshotSourceVolume(vplexVolume);
        return super.getNumNativeSnapshots(snapshotSourceVolume);
    }

    /**
     * Check if a snapshot with the same name exists for the passed volume.
     *
     * @param name The name to verify.
     * @param vplexVolume The VPLEX volume to check.
     */
    @Override
    protected void checkForDuplicatSnapshotName(String name, Volume vplexVolume) {
        Volume snapshotSourceVolume = getVPLEXSnapshotSourceVolume(vplexVolume);
        super.checkForDuplicatSnapshotName(name, snapshotSourceVolume);
    }

    /**
     * Get the snapshots for the passed VPLEX volume.
     *
     * @param vplexVolume A reference to a VPLEX volume.
     *
     * @return The snapshots for the passed volume.
     */
    @Override
    public List<BlockSnapshot> getSnapshots(Volume vplexVolume) {
        if (!vplexVolume.isIngestedVolumeWithoutBackend(_dbClient)) {
            Volume snapshotSourceVolume = null;
            try {
                snapshotSourceVolume = getVPLEXSnapshotSourceVolume(vplexVolume);
            } catch (Exception e) {
                // Just log a warning and return the empty list.
                s_logger.warn("Cound not find source side backend volume for VPLEX volume {}", vplexVolume.getId());
                return new ArrayList<BlockSnapshot>();
            }
            if (snapshotSourceVolume != null) {
                return super.getSnapshots(snapshotSourceVolume);
            }
        }

        return new ArrayList<BlockSnapshot>();
    }

    /**
     * Validates a restore snapshot request.
     *
     * @param snapshot The snapshot to restore.
     * @param parent The parent of the snapshot
     */
    @Override
    public void validateRestoreSnapshot(BlockSnapshot snapshot, Volume parentVolume) {
        if (!snapshot.getIsSyncActive()) {
            throw APIException.badRequests.snapshotNotActivated(snapshot.getLabel());
        }

        URI parentVolumeURI = parentVolume.getId();
        URI cgURI = snapshot.getConsistencyGroup();
        // Note: In 2.2 We do not allow creating mirror for the volume
        // in the consistency group so we don't need to do
        // validation for the volumes which are in a consistency group
        // In pre 2.2 snapshot were not placed in consistency group
        // only vplex volume were in consistency group.
        // So this check should work for vplex volumes created in
        // consistency group pre 2.2.
        if (NullColumnValueGetter.isNullURI(cgURI)) {
            // If the snapshot is not in a CG, the only VPLEX
            // volume to restore is the VPLEX volume using the
            // snapshot parent.
            // Get the VLPEX volume for this backend volume.
            URIQueryResultList queryResults = new URIQueryResultList();
            _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                    .getVolumeByAssociatedVolumesConstraint(parentVolumeURI.toString()),
                    queryResults);
            URI vplexVolumeURI = queryResults.iterator().next();
            Volume vplexVolume = _dbClient.queryObject(Volume.class, vplexVolumeURI);
            // If the volume has mirrors then restore snapshot will not
            // be allowed. User needs to explicitly delete mirrors first.
            // This is applicable for both Local and Distributed volumes.
            // For distributed volume getMirrors will get mirror if any
            // on source or HA side.
            List<URI> activeMirrors = getActiveMirrorsForVolume(vplexVolume);
            if (!activeMirrors.isEmpty()) {
                throw APIException.badRequests
                        .snapshotParentForVPlexHasActiveMirrors(snapshot.getLabel(), vplexVolume.getLabel(),
                                vplexVolume.getId().toString());
            }
        }
    }

    /**
     * Return a list of active VplexMirror URI's that are known to be active.
     *
     * @param volume Volume to check for mirrors against
     * @return List of active VplexMirror URI's
     */
    @Override
    protected List<URI> getActiveMirrorsForVolume(Volume volume) {
        return BlockServiceUtils.getActiveMirrorsForVplexVolume(volume, _dbClient);
    }

    /**
     * Restore the passed parent volume from the passed snapshot of that parent
     * volume.
     *
     * NOTE: When restoring a snap of a backend volume of a VPLEX volume,
     * the parent volume will be the backend volume. This implementation class
     * should be invoked by the BlockSnapshotService because the vpool for the
     * backend volume should specify VPLEX HA. This could be a problem if we
     * snap both sides of a distributed volume because the HA side vpool might
     * not specify VPLEX HA. Right now we always snap the source side backend
     * volume, which should always specify VPLEX HA.
     *
     * @param snapshot The snapshot to restore
     * @param parentVolume The volume to be restored.
     * @param taskId The unique task identifier.
     */
    @Override
    public void restoreSnapshot(BlockSnapshot snapshot, Volume parentVolume, String syncDirection, String taskId) {
        s_logger.info(String.format("Request to restore VPlex volume %s from snapshot %s.",
                parentVolume.getId().toString(), snapshot.getId().toString()));
        super.restoreSnapshot(snapshot, parentVolume, syncDirection, taskId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<VolumeDescriptor> getDescriptorsForVolumesToBeDeleted(URI systemURI,
            List<URI> volumeURIs, String deletionType) {
        List<VolumeDescriptor> volumeDescriptors = new ArrayList<VolumeDescriptor>();
        for (URI volumeURI : volumeURIs) {
            Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
            if (!volume.isVPlexVolume(_dbClient)) {
                // We can get invoked on a vplex backend volume (because of the vpool) when
                // trying to clean up failed deletions.
                BlockServiceApi apiImpl = volume.checkForSRDF() ? 
                        BlockService.getBlockServiceImpl("srdf") : BlockService.getBlockServiceImpl("default");
                List<URI> nonVplexVolumeIds = new ArrayList<URI>();
                nonVplexVolumeIds.add(volume.getId());
                List<VolumeDescriptor> nonVplexDescriptors = apiImpl.getDescriptorsForVolumesToBeDeleted(
                        volume.getStorageController(), nonVplexVolumeIds, deletionType);
                volumeDescriptors.addAll(nonVplexDescriptors);
                continue;
            }
            // At this point we know we are a VPLEX volume
            VolumeDescriptor descriptor = new VolumeDescriptor(VolumeDescriptor.Type.VPLEX_VIRT_VOLUME,
                    systemURI, volumeURI, null, null);
            volumeDescriptors.add(descriptor);
            // Add a descriptor for each of the associated volumes.
            if (!volume.isIngestedVolumeWithoutBackend(_dbClient) && (null != volume.getAssociatedVolumes())) {
                for (String assocVolId : volume.getAssociatedVolumes()) {
                    Volume assocVolume = _dbClient.queryObject(Volume.class, URI.create(assocVolId));
                    if (null != assocVolume && !assocVolume.getInactive() && assocVolume.getNativeId() != null) {
                        if (assocVolume.isSRDFSource()) {
                            // Loop through each of the targets adding them. If they are fronted by a Vplex vol,
                            // add that volume also.
                            for (String target : assocVolume.getSrdfTargets()) {
                                Volume targetVolume = _dbClient.queryObject(Volume.class, URI.create(target));
                                if (null != targetVolume) {
                                    // Look for a Vplex volume fronting the target. If so add it.
                                    List<Volume> vplexTargetVolumes = CustomQueryUtility
                                            .queryActiveResourcesByConstraint(_dbClient, Volume.class,
                                                    getVolumesByAssociatedId(targetVolume.getId().toString()));
                                    for (Volume vplexTargetVolume : vplexTargetVolumes) {
                                        // There should only be one volume in list, ie. target only associated with
                                        // one Vplex volume
                                        VolumeDescriptor vplexTargetDescriptor = new VolumeDescriptor(
                                                VolumeDescriptor.Type.VPLEX_VIRT_VOLUME,
                                                vplexTargetVolume.getStorageController(), vplexTargetVolume.getId(),
                                                null, null);
                                        volumeDescriptors.add(vplexTargetDescriptor);
                                    }
                                }
                            }
                            // Let the SRDF code fill in its own set of volume descriptors
                            List<URI> srdfVolumeIds = new ArrayList<URI>();
                            srdfVolumeIds.add(assocVolume.getId());
                            SRDFBlockServiceApiImpl srdfApi = (SRDFBlockServiceApiImpl) BlockService.getBlockServiceImpl("srdf");
                            List<VolumeDescriptor> srdfDescriptors = srdfApi.getDescriptorsForVolumesToBeDeleted(
                                    assocVolume.getStorageController(), srdfVolumeIds, deletionType);
                            volumeDescriptors.addAll(srdfDescriptors);
                        } else if (!NullColumnValueGetter.isNullNamedURI(assocVolume.getSrdfParent())) {
                            // SRDF target, caller needs to specify source
                            throw BadRequestException.badRequests.cannotDeleteSRDFTargetVolume(assocVolume.getLabel());
                        } else {
                            VolumeDescriptor assocDesc = new VolumeDescriptor(VolumeDescriptor.Type.BLOCK_DATA,
                                    assocVolume.getStorageController(), assocVolume.getId(), null, null);
                            volumeDescriptors.add(assocDesc);
                        }
                    }
                }
                // If there were any Vplex Mirrors, add a descriptors for them.
                addDescriptorsForVplexMirrors(volumeDescriptors, volume);
            }
        }
        return volumeDescriptors;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void cleanupForViPROnlyDelete(List<VolumeDescriptor> volumeDescriptors) {
        // Call super first.
        super.cleanupForViPROnlyDelete(volumeDescriptors);

        // Clean up the relationship between vplex volumes that are full
        // copies and and their source vplex volumes.
        List<VolumeDescriptor> vplexVolumeDescriptors = VolumeDescriptor
                .getDescriptors(volumeDescriptors, VolumeDescriptor.Type.VPLEX_VIRT_VOLUME);
        BlockFullCopyManager.cleanUpFullCopyAssociations(vplexVolumeDescriptors, _dbClient);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMaxVolumesForConsistencyGroup(BlockConsistencyGroup consistencyGroup) {
        return MAX_VOLUMES_IN_CG;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateConsistencyGroupName(BlockConsistencyGroup consistencyGroup) {
        // VPLEX CG names cannot start with a number only "_" or a letter.
        // Note that when a CG is created in ViPR there are already restrictions
        // in place that prevent CG names from containing anything other than
        // letters and numbers, so we don;t have to worry about the "_". Also,
        // when creating a ViPR the name has a min length of 2 and max of 8,
        // so we don't have to concern ourselves with length issues.
        String cgName = consistencyGroup.getLabel();
        if (!Pattern.matches("^[_a-zA-Z].*", cgName)) {
            throw APIException.badRequests.invalidVplexCgName(cgName);
        }
    }

    /**
     * Prep work before the call to orchestrator to create the volume descriptors for volume expand operation
     * 
     * @param volumeURIs vplex volumes already prepared
     * @return list of volume descriptors
     */
    private List<VolumeDescriptor> createVolumeDescriptorsForNativeExpansion(List<URI> volumeURIs) {

        List<Volume> preparedVolumes = _dbClient.queryObject(Volume.class, volumeURIs);
        List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();

        // Package up the Volume descriptors
        for (Volume volume : preparedVolumes) {
            if (null == volume.getAssociatedVolumes() || volume.getAssociatedVolumes().isEmpty()) {
                s_logger.error("VPLEX volume {} has no backend volumes.", volume.forDisplay());
                throw InternalServerErrorException.
                    internalServerErrors.noAssociatedVolumesForVPLEXVolume(volume.forDisplay());
            }
            for (String associatedVolumeStr : volume.getAssociatedVolumes()) {
                Volume associatedVolume = _dbClient.queryObject(Volume.class, URI.create(associatedVolumeStr));

                if (associatedVolume.isSRDFSource()) { // SRDF Source Volume
                    SRDFBlockServiceApiImpl srdfApi = (SRDFBlockServiceApiImpl) BlockService.getBlockServiceImpl("srdf");
                    List<VolumeDescriptor> srdfDescriptors = srdfApi.getVolumeDescriptorsForExpandVolume(
                            associatedVolume, associatedVolume.getProvisionedCapacity());
                    descriptors.addAll(srdfDescriptors);
                    // Check to see if any of the SRDF targets are VPlex protected, and if so add them.
                    List<VolumeDescriptor> srdfTargetDescriptors = VolumeDescriptor.filterByType(
                            srdfDescriptors, VolumeDescriptor.Type.SRDF_TARGET);
                    for (VolumeDescriptor targetDescriptor : srdfTargetDescriptors) {
                        Volume targetVolume = _dbClient.queryObject(Volume.class, targetDescriptor.getVolumeURI());
                        Volume vplexTargetVolume = VPlexSrdfUtil.getVplexVolumeFromSrdfVolume(_dbClient, targetVolume);
                        if (vplexTargetVolume != null) {
                            // Recursively add what is needed for the target vplex encapsulation.
                            List<VolumeDescriptor> vplexTargetDescriptors = createVolumeDescriptorsForNativeExpansion(
                                    Arrays.asList(vplexTargetVolume.getId()));
                            descriptors.addAll(vplexTargetDescriptors);
                        }
                    }
                } else if (!NullColumnValueGetter.isNullNamedURI(associatedVolume.getSrdfParent())) {
                    s_logger.info("Ignoring associated volume that is SRDF target: " + associatedVolume.getLabel());
                } else { // A nice, plain, simple backing volume
                    VolumeDescriptor descriptor = new VolumeDescriptor(
                            VolumeDescriptor.Type.BLOCK_DATA,
                            associatedVolume.getStorageController(), associatedVolume.getId(), associatedVolume.getPool(), null, null,
                            associatedVolume.getCapacity());
                    descriptors.add(descriptor);
                }
            }

            // A descriptor for the top level Vplex virtual volume
            VolumeDescriptor desc = new VolumeDescriptor(
                    VolumeDescriptor.Type.VPLEX_VIRT_VOLUME, volume.getStorageController(), volume.getId(), volume.getPool(), null, null,
                    volume.getCapacity());
            descriptors.add(desc);
        }
        return descriptors;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void verifyRemoveVolumeFromCG(Volume volume, List<Volume> cgVolumes) {
        super.verifyRemoveVolumeFromCG(volume, cgVolumes);

        // Don't allow ingested VPLEX volumes to be removed from
        // their ingested consistency group. The only operation
        // that currently should be supported for an ingested VPLEX
        // volume is to migrate it to known, supported backend
        // storage.
        VolumeIngestionUtil.checkOperationSupportedOnIngestedVolume(volume,
                ResourceOperationTypeEnum.UPDATE_CONSISTENCY_GROUP, _dbClient);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void verifyAddVolumeToCG(Volume volume, BlockConsistencyGroup cg,
            List<Volume> cgVolumes, StorageSystem cgStorageSystem) {
        super.verifyAddVolumeToCG(volume, cg, cgVolumes, cgStorageSystem);

        // We will not allow a VPLEX volume that was created using the target
        // volume of a block snapshot to be added to a consistency group.
        if (VPlexUtil.isVolumeBuiltOnBlockSnapshot(_dbClient, volume)) {
            throw APIException.badRequests.cgNotAllowedVolumeIsExposedSnapshot(volume.getId().toString());
        }

        // Don't allow ingested VPLEX volumes to be added to a consistency group.
        // They must first be migrated to known, supported backend storage.
        VolumeIngestionUtil.checkOperationSupportedOnIngestedVolume(volume,
                ResourceOperationTypeEnum.UPDATE_CONSISTENCY_GROUP, _dbClient);

        // Can't add a volume into a CG with ingested volumes.
        for (Volume cgVolume : cgVolumes) {
            if (cgVolume.isIngestedVolumeWithoutBackend(_dbClient)) {
                throw APIException.badRequests.notAllowedAddVolumeToCGWithIngestedVolumes();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void verifyReplicaCount(List<Volume> volumes, List<Volume> cgVolumes, boolean volsAlreadyInCG) {
        // Get all backend volumes
        List<Volume> backendVolumes = new ArrayList<Volume>();
        for (Volume volume : volumes) {
            backendVolumes.add(VPlexUtil.getVPLEXBackendVolume(volume, true, _dbClient));
        }

        // Verify replica count
        super.verifyReplicaCount(backendVolumes, cgVolumes, volsAlreadyInCG);
    }

    private void checkIfClusterIsUnknown(String cluster, String varrayURI, String vplexStorageSystemURI) {
        if (cluster.equals(ConnectivityUtil.CLUSTER_UNKNOWN)) {
            throw InternalServerErrorException.internalServerErrors
                    .noVplexClusterInfoForVarray(varrayURI, vplexStorageSystemURI);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateCreateSnapshot(Volume reqVolume, List<Volume> volumesToSnap,
            String snapshotType, String snapshotName, BlockFullCopyManager fcManager) {
        super.validateCreateSnapshot(getVPLEXSnapshotSourceVolume(reqVolume), volumesToSnap, snapshotType, snapshotName, fcManager);

        // If the volume is a VPLEX volume created on a block snapshot,
        // we don't support creation of a snapshot. In this case the
        // requested volume will not be in a CG, so volumes to snaps will
        // just be the requested volume.
        if (VPlexUtil.isVolumeBuiltOnBlockSnapshot(_dbClient, reqVolume)) {
            throw APIException.badRequests.snapshotNotAllowedVolumeIsExposedSnapshot(reqVolume.getId().toString());
        }

        // If there are more than one volume in the consistency group, and they are on different backend storage system,
        // return error.
        if (volumesToSnap.size() > 1 && !VPlexUtil.isVPLEXCGBackendVolumesInSameStorage(volumesToSnap, _dbClient)) {
            throw APIException.badRequests.snapshotNotAllowedWhenCGAcrossMultipleSystems();
        }

        // if the backend cg volume in the consistency group does not have back end cg on array, return error
        if (VPlexUtil.isBackendVolumesNotHavingBackendCG(volumesToSnap, _dbClient)) {
            throw APIException.badRequests.snapshotNotAllowedWhenBackendVolumeDoestHavingCG();
        }

        // Check if the source volume is an ingested CG, without any back end CGs yet. if yes, throw error
        if (VPlexUtil.isVolumeInIngestedCG(reqVolume, _dbClient)) {
            throw APIException.badRequests.cannotCreateSnapshotOfVplexCG();
        }
    }

    /**
     * Verify that the adding volumes are valid for ingested consistency group. For ingested consistency group,
     * the valid adding volumes are either all virtual volumes in the CG, or a new virtual volume not in the CG.
     *
     * @param consistencyGroup
     * @param addVolumesList
     */
    private void verifyAddVolumesToIngestedCG(BlockConsistencyGroup cg, List<URI> addVolumesList) {
        if (cg.getTypes().contains(Types.LOCAL.toString())) {
            // Not ingested CG
            return;
        }
        List<Volume> cgVolumes = BlockConsistencyGroupUtils.getActiveVplexVolumesInCG(cg, _dbClient, null);
        Set<String> cgVolumeURIs = new HashSet<String>();
        for (Volume cgVolume : cgVolumes) {
            cgVolumeURIs.add(cgVolume.getId().toString());
        }
        boolean isExistingVolume = false;
        boolean hasNewVolume = false;
        for (URI addVolume : addVolumesList) {
            if (cgVolumeURIs.contains(addVolume.toString())) {
                isExistingVolume = true;
            } else {
                hasNewVolume = true;
            }
        }
        if (isExistingVolume && hasNewVolume) {
            throw APIException.badRequests.cantAddMixVolumesToIngestedCG(cg.getLabel());
        } else if (isExistingVolume && (cgVolumes.size() != addVolumesList.size())) {
            // Not all virtual volumes are selected
            throw APIException.badRequests.notAllVolumesAddedToIngestedCG(cg.getLabel());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteSnapshot(BlockSnapshot requestedSnapshot, List<BlockSnapshot> allSnapshots, String taskId, String deleteType) {
        if (!VolumeDeleteTypeEnum.VIPR_ONLY.name().equals(deleteType)) {
            for (BlockSnapshot snapshot : allSnapshots) {
                String snapshotNativeGuid = snapshot.getNativeGuid();
                List<Volume> volumesWithSameNativeGuid = CustomQueryUtility.getActiveVolumeByNativeGuid(_dbClient, snapshotNativeGuid);
                if (!volumesWithSameNativeGuid.isEmpty()) {
                    // There should only be one and it should be a backend volume for
                    // a VPLEX volume.
                    List<Volume> vplexVolumes = CustomQueryUtility.queryActiveResourcesByConstraint(
                            _dbClient, Volume.class, AlternateIdConstraint.Factory.getVolumeByAssociatedVolumesConstraint(
			    volumesWithSameNativeGuid.get(0).getId().toString()));
                    String volumeLabel = !vplexVolumes.isEmpty() ? 
                            vplexVolumes.get(0).getLabel() : volumesWithSameNativeGuid.get(0).getId().toString();
                    throw APIException.badRequests
                            .cantDeleteSnapshotExposedByVolume(snapshot.forDisplay(), volumeLabel);
                }
            }
        }
        super.deleteSnapshot(requestedSnapshot, allSnapshots, taskId, deleteType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resynchronizeSnapshot(BlockSnapshot snapshot, Volume parentVolume, String taskId) {
        VPlexController controller = getController();
        Volume vplexVolume = Volume.fetchVplexVolume(_dbClient, parentVolume);
        StorageSystem vplexSystem = _dbClient.queryObject(StorageSystem.class, vplexVolume.getStorageController());
        controller.resyncSnapshot(vplexSystem.getId(), snapshot.getId(), taskId);
    }
    
    /**
     * Sort the recommendations by VirtualArray. There can be up to two
     * VirtualArrays, the requested VirtualArray and the HA VirtualArray
     * either passed or determined by the placement when HA virtual volumes
     * are being created. We also set the VPlex storage system, which
     * should be the same for all recommendations.
     * 
     * @param recommendations -- list of Recommendations
     * @param vplexSystemURIOut -- Output parameter the Vplex system URI
     * @return
     */
    private Map<String, List<VPlexRecommendation>> sortRecommendationsByVarray(
            List<Recommendation> recommendations, URI[] vplexSystemURIOut) {
        URI vplexStorageSystemURI = null;
        Map<String, List<VPlexRecommendation>> varrayRecommendationsMap =
                new HashMap<String, List<VPlexRecommendation>>();
        for (Recommendation recommendation : recommendations) {
            VPlexRecommendation vplexRecommendation = (VPlexRecommendation) recommendation;
            String varrayId = vplexRecommendation.getVirtualArray().toString();
            if (vplexStorageSystemURI == null) {
                vplexStorageSystemURI = vplexRecommendation.getVPlexStorageSystem();
                vplexSystemURIOut[0] = vplexStorageSystemURI;
            }
            if (!varrayRecommendationsMap.containsKey(varrayId)) {
                List<VPlexRecommendation> varrayRecommendations = new ArrayList<VPlexRecommendation>();
                varrayRecommendations.add(vplexRecommendation);
                varrayRecommendationsMap.put(varrayId, varrayRecommendations);
            } else {
                List<VPlexRecommendation> varrayRecommendations = varrayRecommendationsMap.get(varrayId);
                varrayRecommendations.add(vplexRecommendation);
            }
        }
        return varrayRecommendationsMap;
    }
    /**
     * Takes a list of recommendations and makes the backend volumes and volume descriptors needed to 
     * provision. When possible (e.g. for SRDF and Block), All recommendations must be in single varray.
     * calls the underlying storage routine createVolumesAndDescriptors().
     * @param recommendations -- a VPlex recommendation list
     * @param project - Project containing the Vplex volumes
     * @param vplexProject -- private project of the Vplex
     * @param rootVpool -- top level Virtual Pool (VpoolUse.ROOT)
     * @param varrayCount -- instance count of the varray being provisioned
     * @param size -- size of each volume
     * @param backendCG -- the CG to be used on the backend Storage Systems
     * @param vPoolCapabilities - a VirtualPoolCapabilityValuesWrapper containing provisioning arguments
     * @param createTask -- boolean if true creates a task
     * @param task -- Overall task id
     * @return -- list of VolumeDescriptors to be provisioned
     */
    private List<VolumeDescriptor> makeBackendVolumeDescriptors(
            List<VPlexRecommendation> recommendations, 
            Project project, Project vplexProject, VirtualPool rootVpool,
            String volumeLabel, int varrayCount, long size, 
            BlockConsistencyGroup backendCG, VirtualPoolCapabilityValuesWrapper vPoolCapabilities,
            boolean createTask, String task) {
        VPlexRecommendation firstRecommendation = recommendations.get(0);
        List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();
        URI varrayId = firstRecommendation.getVirtualArray();
        VirtualPool vpool = firstRecommendation.getVirtualPool();
        
        s_logger.info("Generated backend descriptors for {} recommendations varray {}", 
                recommendations.size(), varrayCount);
        
        vPoolCapabilities.put(VirtualPoolCapabilityValuesWrapper.AUTO_TIER__POLICY_NAME,
                vpool.getAutoTierPolicyName());
        
        if (firstRecommendation.getRecommendation() != null) {
            // If these recommendations have lower level recommendation, process them.
            // This path is used for the source side of Distributed Volumes and for Local volumes
            // where we support building on top of SRDF or the BlockStorage as appropriate.
            List<Recommendation> childRecommendations = new ArrayList<Recommendation>();
            Recommendation childRecommendation = null;
            for (VPlexRecommendation recommendation : recommendations) {
                childRecommendation = recommendation.getRecommendation();
                childRecommendations.add(childRecommendation);
            }
            VirtualArray varray = _dbClient.queryObject(VirtualArray.class, varrayId);
            
            String newVolumeLabel = generateVolumeLabel(volumeLabel, varrayCount, 0, 0);
            boolean srdfTarget = (childRecommendation instanceof SRDFCopyRecommendation);
            boolean srdfSource = (childRecommendation instanceof SRDFRecommendation);
            if (srdfTarget) {
                newVolumeLabel = newVolumeLabel+"-target";
            } else if (srdfSource) {
            } else {
                // nothing special about these volumes, hide them in the vplex project
                // We can't use the vplexProject for SRDF volumes as they determine their RDF group
                // grom the project.
                project = vplexProject;
            }
            
            TaskList taskList = new TaskList();
            descriptors = 
                    super.createVolumesAndDescriptors(descriptors, newVolumeLabel, size, project, 
                            varray, vpool, childRecommendations, taskList, task, vPoolCapabilities);
            VolumeDescriptor.Type[] types;
            if (srdfTarget) {
                types =  new VolumeDescriptor.Type[] { 
                        VolumeDescriptor.Type.SRDF_TARGET};
            } else {
                types =  new VolumeDescriptor.Type[] { 
                                VolumeDescriptor.Type.BLOCK_DATA, 
                                VolumeDescriptor.Type.SRDF_SOURCE, 
                                VolumeDescriptor.Type.SRDF_EXISTING_SOURCE};
            }
            descriptors = VolumeDescriptor.filterByType(descriptors, types);
            for (VolumeDescriptor descriptor : descriptors) {
                Volume volume = _dbClient.queryObject(Volume.class, descriptor.getVolumeURI());
                s_logger.info(String.format("Received prepared volume %s (%s, args) type %s",
                        volume.getLabel(), volume.getId(), descriptor.getType().name()));
                volume.addInternalFlags(DataObject.Flag.INTERNAL_OBJECT);
                configureCGAndReplicationGroup(rootVpool, vPoolCapabilities, backendCG, volume);
                _dbClient.updateObject(volume);
            }
            return descriptors;
        }
        
        // Sum resourceCount across all recommendations
        int totalResourceCount = 0;
        for (VPlexRecommendation recommendation: recommendations) {
            totalResourceCount += recommendation.getResourceCount();
        }
        // The code below is used for the HA side of distributed volumes.
        // The HA side does not currently call the lower level schedulers to get descriptors.
        s_logger.info("Processing recommendations for Virtual Array {}", varrayId);
        int volumeCounter = 0;
        
        for (VPlexRecommendation recommendation : recommendations) {
            for (int i = 0; i < recommendation.getResourceCount(); i++) {
                vpool = recommendation.getVirtualPool();
                URI storageDeviceURI = recommendation.getSourceStorageSystem();
                URI storagePoolURI = recommendation.getSourceStoragePool();
                
                String newVolumeLabel = generateVolumeLabel(
                        volumeLabel, varrayCount, volumeCounter, totalResourceCount);
                validateVolumeLabel(newVolumeLabel, project);

                s_logger.info("Volume label is {}", newVolumeLabel);
                VirtualArray varray = _dbClient.queryObject(VirtualArray.class,varrayId);

                // This is also handled in StorageScheduler.prepareRecomendedVolumes
                long thinVolumePreAllocationSize = 0;
                if (null != vpool.getThinVolumePreAllocationPercentage()) {
                    thinVolumePreAllocationSize = VirtualPoolUtil
                            .getThinVolumePreAllocationSize(
                                    vpool.getThinVolumePreAllocationPercentage(), size);
                }

                Volume volume = prepareVolume(VolumeType.BLOCK_VOLUME, null,
                        size, thinVolumePreAllocationSize, vplexProject,
                        varray, vpool, storageDeviceURI,
                        storagePoolURI, newVolumeLabel, backendCG, vPoolCapabilities);
                configureCGAndReplicationGroup(rootVpool, vPoolCapabilities, backendCG, volume);
                volume.addInternalFlags(Flag.INTERNAL_OBJECT);
                _dbClient.persistObject(volume);

                if (createTask) {
                    _dbClient.createTaskOpStatus(Volume.class, volume.getId(),
                            task, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME);
                }

                s_logger.info("Prepared volume {} ({}) ", volume.getLabel(), volume.getId());
                VolumeDescriptor descriptor = new VolumeDescriptor(
                        VolumeDescriptor.Type.BLOCK_DATA, storageDeviceURI, volume.getId(),
                        storagePoolURI, backendCG == null ? null : backendCG.getId(),
                                vPoolCapabilities, size);
                descriptors.add(descriptor);
                volumeCounter++;
            } 
        }
        return descriptors;
    }
    
    /**
     * Configures the consistency group(s) and sets the volume replicationGroupInstance.
     * @param rootVpool - root VirtualPool
     * @param vPoolCapabilities -- a VirtualPoolCapabilitiesWrapper
     * @param backendCG -- the consistency group for the backend array
     * @param volume -- volume being configured
     */
    private void configureCGAndReplicationGroup(VirtualPool rootVpool, VirtualPoolCapabilityValuesWrapper vPoolCapabilities,
            BlockConsistencyGroup backendCG, Volume volume) {
        // Don't process CGs / replication groups on SRDF volumes.
        if (volume.checkForSRDF()) {
            return;
        }
        // If this is the HA side of a VPLEX-SRDF virtual volume, then don't allow a consistency group.
        // This is necessary so that we want try to set up snapshot sessions or replicationGroupInstances on the HA volume.
        if (VirtualPool.vPoolSpecifiesHighAvailabilityDistributed(rootVpool) 
                && !VirtualPool.getRemoteProtectionSettings(rootVpool, _dbClient).isEmpty()
                && !volume.checkForSRDF()) {
            if (volume.getConsistencyGroup() != null) {
                volume.setConsistencyGroup(NullColumnValueGetter.getNullURI());
            }
            return;
        }
        // The consistency group or null when not specified.
        final BlockConsistencyGroup consistencyGroup = vPoolCapabilities.getBlockConsistencyGroup() == null ? null : _dbClient
                .queryObject(BlockConsistencyGroup.class, vPoolCapabilities.getBlockConsistencyGroup());
        // Check if it is RP target or journal volumes
        // This is not handled in StorageScheduler.
        String rpPersonality = vPoolCapabilities.getPersonality();
        boolean isRPTargetOrJournal = false;
        if (rpPersonality != null && (rpPersonality.equals(PersonalityTypes.TARGET.name()) 
                || rpPersonality.equals(PersonalityTypes.METADATA.name()))) {
            s_logger.info("{} {} is RP target or journal volume", volume.getLabel(), volume.getId());
            isRPTargetOrJournal = true;
        }

        // Do not set the replicationGroupInstance if the backend volume is on XIO 3.x system which doesn't support CGs
        StorageSystem backendSystem = _dbClient.queryObject(StorageSystem.class, volume.getStorageController());
        boolean isXIO3xVersion = StorageSystem.Type.xtremio.name().equalsIgnoreCase(backendSystem.getSystemType())
                && !XtremIOProvUtils.is4xXtremIOModel(backendSystem.getFirmwareVersion());

        // Set replicationGroupInstance if CG's arrayConsistency is true
        // This is also done in StorageScheduler.prepareVolume
        if (backendCG != null && backendCG.getArrayConsistency() && !isRPTargetOrJournal && !isXIO3xVersion) {
            String repGroupInstance = consistencyGroup.getCgNameOnStorageSystem(volume.getStorageController());
            if (NullColumnValueGetter.isNullValue(repGroupInstance)) {
                repGroupInstance = consistencyGroup.getLabel();
            }
            volume.setReplicationGroupInstance(repGroupInstance);
        }
        
        // Set the volume's consistency group
        if (consistencyGroup != null) {
            volume.setConsistencyGroup(consistencyGroup.getId());
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void updateVolumesInVolumeGroup(VolumeGroupVolumeList addVolumes, List<Volume> removeVolumes, URI volumeGroupId, String taskId) {

        ApplicationAddVolumeList addVols = new ApplicationAddVolumeList();
        VolumeGroup volumeGroup = _dbClient.queryObject(VolumeGroup.class, volumeGroupId);
        URI systemURI = getVolumesToAddToApplication(addVols, addVolumes, volumeGroup, taskId);
        List<URI> removeVolIds = new ArrayList<URI>();

        URI removeSystemURI = getVolumesToRemoveFromApplication(removeVolIds, removeVolumes);
        if (systemURI == null) {
            systemURI = removeSystemURI;
        }

        if (systemURI != null) {
            VPlexController controller = getController();
            controller.updateVolumeGroup(systemURI, addVols, removeVolIds, volumeGroup.getId(), taskId);
        } else {
            // No need to call to controller. update the application task
            Operation op = volumeGroup.getOpStatus().get(taskId);
            op.ready();
            volumeGroup.getOpStatus().updateTaskStatus(taskId, op);
            _dbClient.updateObject(volumeGroup);
        }
    }

    /**
     * get backing volumes to be removed from the application
     *
     * @param removeVolIds output list of volume ids
     * @param removeVolumes input list of volumes
     * @return URI of the storage system the backing volumes are in (this will need to change)
     */
    private URI getVolumesToRemoveFromApplication(List<URI> removeVolIds, List<Volume> removeVolumes) {
        URI systemURI = null;

        if (removeVolumes != null && !removeVolumes.isEmpty()) {
            for (Volume removeVol : removeVolumes) {
                if (systemURI == null) {
                    systemURI = removeVol.getStorageController();
                }
                StringSet backingVolumes = removeVol.getAssociatedVolumes();
                if (backingVolumes != null ) {
                    for (String backingVolId : backingVolumes) {
                        URI backingVolUri = URI.create(backingVolId);
                        Volume backingVol = _dbClient.queryObject(Volume.class, backingVolUri);
                        if (backingVol != null &&
                                !BlockServiceUtils.checkUnityVolumeCanBeAddedOrRemovedToCG(null, backingVol, _dbClient, false)) {
                            throw APIException.badRequests.volumeCantBeRemovedFromVolumeGroup(removeVol.getLabel(),
                                "the Unity subgroup has snapshot");
                        }
                    }
                }
                removeVolIds.add(removeVol.getId());
            }
        }

        return systemURI;
    }

    /**
     * get backing volumes to be added the the application
     *
     * @param addVols output list of volumes to be added after validation
     * @param addVolumes input list of volumes to add
     * @param volumeGroup application to add to
     * @param taskId task id used if some volumes are already in a backend array CG
     * @return URI of the storage system the backing volumes are in (this will need to change)
     */
    public URI getVolumesToAddToApplication(ApplicationAddVolumeList addVols, VolumeGroupVolumeList addVolumes, VolumeGroup volumeGroup,
            String taskId) {
        URI systemURI = null;
        if (addVolumes != null && addVolumes.getVolumes() != null && !addVolumes.getVolumes().isEmpty()) {
            Set<URI> allVolumes = new HashSet<URI>(addVolumes.getVolumes());
            Map<String, Boolean> checkedRGMap = new HashMap<String, Boolean>();

            // add the backing volumes to the volume group list
            for (URI volUri : addVolumes.getVolumes()) {

                // query the vplex virtual volume
                Volume addVol = _dbClient.queryObject(Volume.class, volUri);
                if (addVol == null || addVol.getInactive()) {
                    throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volUri.toString(),
                            "the VPLEX virtual volume has been deleted");
                }

                if (systemURI == null) {
                    systemURI = addVol.getStorageController();
                }

                // get the backing volumes
                StringSet backingVolumes = addVol.getAssociatedVolumes();
                if (backingVolumes == null || backingVolumes.isEmpty()) {
                    throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(addVol.getLabel(),
                            "the VPLEX virtual volume does not have any backing volumes");
                }

                for (String backingVolId : backingVolumes) {
                    URI backingVolUri = URI.create(backingVolId);
                    Volume backingVol = _dbClient.queryObject(Volume.class, backingVolUri);
                    if (backingVol == null || backingVol.getInactive()) {
                        String error = String.format("the backing volume %s for the VPLEX virtual volume has been deleted", backingVolId);
                        throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(addVol.getLabel(), error);
                    }
                    
                    if (backingVol.checkForSRDF()) {
                    	throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(addVol.getLabel(), 
                    			"Vplex volumes with SRDF protection are not supported with applications");
                    }

                    String rgName = backingVol.getReplicationGroupInstance();
                    if (NullColumnValueGetter.isNotNullValue(rgName) && rgName.equals(addVolumes.getReplicationGroupName())) {
                        if (!checkAllVPlexVolsInRequest(backingVol, allVolumes, checkedRGMap)) {
                            throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(backingVol.getLabel(),
                                    "Volume has to be added to a different replication group than it is currently in");
                        }
                    }
                    if (!BlockServiceUtils.checkUnityVolumeCanBeAddedOrRemovedToCG(addVolumes.getReplicationGroupName(), backingVol, _dbClient, true)) {
                        throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(backingVol.getLabel(),
                                "the Unity subgroup has snapshot.");
                    }
                }
            }

            addVols.setVolumes(addVolumes.getVolumes());
            addVols.setReplicationGroupName(addVolumes.getReplicationGroupName());
            addVols.setConsistencyGroup(addVolumes.getConsistencyGroup());
        }

        return systemURI;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.emc.storageos.api.service.impl.resource.BlockServiceApi#getReplicationGroupNames(com.emc.storageos.db.client.model.VolumeGroup)
     */
    @Override
    public Collection<? extends String> getReplicationGroupNames(VolumeGroup group) {
        Set<String> groupNames = new HashSet<String>();
        final List<Volume> volumes = CustomQueryUtility
                .queryActiveResourcesByConstraint(_dbClient, Volume.class,
                        AlternateIdConstraint.Factory.getVolumesByVolumeGroupId(group.getId().toString()));
        for (Volume volume : volumes) {
            StringSet backingVolumes = volume.getAssociatedVolumes();
            if (backingVolumes != null) {
                for (String backingVolId : backingVolumes) {
                    Volume backingVol = _dbClient.queryObject(Volume.class, URI.create(backingVolId));
                    if (backingVol != null && !backingVol.getInactive()
                            && NullColumnValueGetter.isNotNullValue(backingVol.getReplicationGroupInstance())) {
                        groupNames.add(backingVol.getReplicationGroupInstance());
                    }
                }
            }
        }
        return groupNames;
    }

    /**
     * Check if the volumes are in a CG and in the same backend CG
     *
     * @param volumes
     *            The Vplex volumes
     * @return true or false
     */
    private boolean allVolumesInSameBackendCG(List<Volume> volumes) {
        boolean result = true;
        String replicationGroup = null;
        int count = 0;
        URI storageUri = null;
        for (Volume volume : volumes) {
            URI cgURI = volume.getConsistencyGroup();
            if (NullColumnValueGetter.isNullURI(cgURI)) {
                result = false;
                break;
            }
            Volume srcVol = VPlexUtil.getVPLEXBackendVolume(volume, true, _dbClient, false);
            if (srcVol == null) {
                result = false;
                break;
            }
            URI storage = volume.getStorageController();
            String rgName = srcVol.getReplicationGroupInstance();
            if (count == 0) {
                replicationGroup = rgName;
                storageUri = storage;
            }
            if (replicationGroup == null || replicationGroup.isEmpty()) {
                result = false;
                break;
            }
            if (rgName == null || !replicationGroup.equals(rgName)) {
                result = false;
                break;
            }
            if (!storageUri.equals(storage)) {
                result = false;
                break;
            }
            count++;
        }
        return result;
    }

    /**
     * Check if all VPlex volumes whose backend volume RG that the backendVol belongs to are in the allVolumes set
     *
     * @param backnedVol Backend volume to get the RG name
     * @param allVolumes All volumes to compare against
     * @param checkedRGMap a map contained RG that has been checked
     * @return boolean True is all the volumes are in the same request, false otherwise.
     */
    public boolean checkAllVPlexVolsInRequest(Volume backendVol, Set<URI> allVolumes, Map<String, Boolean> checkedRGMap) {
        String rgName = backendVol.getReplicationGroupInstance();
        URI storageSystemUri = backendVol.getStorageController();
        String key = storageSystemUri.toString() + rgName;

        if (checkedRGMap.containsKey(key)) {
            return checkedRGMap.get(key);
        } else {
            boolean containAll = true;
            Volume firstVolume = _dbClient.queryObject(Volume.class, allVolumes.iterator().next());
            List<Volume> rgVolumes = VPlexUtil.getVolumesInSameReplicationGroup(rgName, storageSystemUri, firstVolume.getPersonality(), _dbClient);
            for (Volume vol : rgVolumes) {
                if (vol != null && !vol.getInactive()) {
                    if (!allVolumes.contains(vol.getId())) {
                        containAll = false;
                        break;
                    }
                }
            }

            checkedRGMap.put(key, containAll);
            return containAll;
        }
    }

    /**
     * Determines in any of the passed volumes is A VPLEX volume in a VPLEX
     * consistency group with corresponding consistency group(s) for the backend
     * storage.
     *
     * @param volumes The list of volumes to check
     *
     * @return A reference to the CG if any of the passed volumes is A VPLEX
     *         volume in a VPLEX consistency group with corresponding
     *         consistency group(s) for the backend storage, null otherwise.
     */
    private BlockConsistencyGroup isVPlexVolumeInCgWithLocalType(List<Volume> volumes) {
        BlockConsistencyGroup cg = null;
        for (Volume volume : volumes) {
            URI systemURI = volume.getStorageController();
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, systemURI);
            String systemType = storageSystem.getSystemType();
            if (DiscoveredDataObject.Type.vplex.name().equals(systemType)) {
                URI cgURI = volume.getConsistencyGroup();
                if (!NullColumnValueGetter.isNullURI(cgURI)) {
                    BlockConsistencyGroup volCG = _dbClient.queryObject(BlockConsistencyGroup.class, cgURI);
                    if (volCG.checkForType(Types.LOCAL)) {
                        cg = volCG;
                        break;
                    }
                }
            }
        }

        return cg;
    }
    
    /**
     * The underlying layers may generate volume names in a different ordering than we do so 
     * make sure that the volume names match the underlying volume names.
     * @param assocVolume
     * @return
     */
    private String generateLabelFromAssociatedVolume(String baseName, Volume assocVolume) {
        final int baseNameLen = baseName.length();
        // The associated volume will have -0 or -1 after the basename which should be skipped
        String suffix = assocVolume.getLabel().substring(baseNameLen + 2);
        StringBuilder builder = new StringBuilder();
        builder.append(baseName);
        // Add on appropriate suffix
        suffix = suffix.replaceAll("-source", "");
        builder.append(suffix);
        s_logger.info("generateLabelFromAssociatedVolume: " + builder.toString());
        return builder.toString();
    }
    
}
