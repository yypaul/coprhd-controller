/*
 * Copyright (c) 2008-2013 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.api.service.impl.resource;

import static com.emc.storageos.api.mapper.BlockMapper.map;
import static com.emc.storageos.api.mapper.DbObjectMapper.toNamedRelatedResource;
import static com.emc.storageos.api.mapper.ProtectionMapper.map;
import static com.emc.storageos.api.mapper.TaskMapper.toTask;
import static com.emc.storageos.db.client.constraint.ContainmentConstraint.Factory.getBlockSnapshotByConsistencyGroup;
import static com.emc.storageos.db.client.constraint.ContainmentConstraint.Factory.getVolumesByConsistencyGroup;
import static com.emc.storageos.db.client.util.CommonTransformerFunctions.fctnDataObjectToID;
import static com.emc.storageos.db.client.util.NullColumnValueGetter.isNullURI;
import static com.emc.storageos.model.block.Copy.SyncDirection.SOURCE_TO_TARGET;
import static com.google.common.collect.Collections2.transform;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.parseBoolean;

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

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.mapper.functions.MapVolume;
import com.emc.storageos.api.service.authorization.PermissionsHelper;
import com.emc.storageos.api.service.impl.placement.PlacementManager;
import com.emc.storageos.api.service.impl.placement.StorageScheduler;
import com.emc.storageos.api.service.impl.placement.VirtualPoolUtil;
import com.emc.storageos.api.service.impl.resource.fullcopy.BlockFullCopyManager;
import com.emc.storageos.api.service.impl.resource.fullcopy.BlockFullCopyUtils;
import com.emc.storageos.api.service.impl.resource.snapshot.BlockSnapshotSessionManager;
import com.emc.storageos.api.service.impl.resource.snapshot.BlockSnapshotSessionUtils;
import com.emc.storageos.api.service.impl.resource.utils.AsyncTaskExecutorIntf;
import com.emc.storageos.api.service.impl.resource.utils.BlockServiceUtils;
import com.emc.storageos.api.service.impl.resource.utils.CapacityUtils;
import com.emc.storageos.api.service.impl.resource.utils.ExportUtils;
import com.emc.storageos.api.service.impl.resource.utils.VirtualPoolChangeAnalyzer;
import com.emc.storageos.api.service.impl.resource.utils.VolumeIngestionUtil;
import com.emc.storageos.api.service.impl.response.BulkList;
import com.emc.storageos.api.service.impl.response.FilterIterator;
import com.emc.storageos.api.service.impl.response.ProjOwnedResRepFilter;
import com.emc.storageos.api.service.impl.response.ResRepFilter;
import com.emc.storageos.api.service.impl.response.RestLinkFactory;
import com.emc.storageos.api.service.impl.response.SearchedResRepList;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.Constraint;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.ContainmentPrefixConstraint;
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
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.ExportPathParams;
import com.emc.storageos.db.client.model.Migration;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.ProtectionSet;
import com.emc.storageos.db.client.model.ProtectionSystem;
import com.emc.storageos.db.client.model.RemoteDirectorGroup;
import com.emc.storageos.db.client.model.RemoteDirectorGroup.SupportedCopyModes;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.TenantOrg;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.VirtualPool.RPCopyMode;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.Volume.PersonalityTypes;
import com.emc.storageos.db.client.model.VolumeGroup;
import com.emc.storageos.db.client.model.VplexMirror;
import com.emc.storageos.db.client.model.VpoolRemoteCopyProtectionSettings;
import com.emc.storageos.db.client.model.util.BlockConsistencyGroupUtils;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.client.util.SizeUtil;
import com.emc.storageos.db.client.util.StringSetUtil;
import com.emc.storageos.model.BulkIdParam;
import com.emc.storageos.model.BulkRestRep;
import com.emc.storageos.model.NamedRelatedResourceRep;
import com.emc.storageos.model.RelatedResourceRep;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.model.ResourceTypeEnum;
import com.emc.storageos.model.RestLinkRep;
import com.emc.storageos.model.SnapshotList;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.TaskResourceRep;
import com.emc.storageos.model.block.BlockMirrorRestRep;
import com.emc.storageos.model.block.BlockSnapshotSessionList;
import com.emc.storageos.model.block.BulkDeleteParam;
import com.emc.storageos.model.block.CopiesParam;
import com.emc.storageos.model.block.Copy;
import com.emc.storageos.model.block.MigrationList;
import com.emc.storageos.model.block.MirrorList;
import com.emc.storageos.model.block.NamedVolumesList;
import com.emc.storageos.model.block.NativeContinuousCopyCreate;
import com.emc.storageos.model.block.RelatedStoragePool;
import com.emc.storageos.model.block.SnapshotSessionCreateParam;
import com.emc.storageos.model.block.VirtualArrayChangeParam;
import com.emc.storageos.model.block.VirtualPoolChangeParam;
import com.emc.storageos.model.block.VolumeBulkRep;
import com.emc.storageos.model.block.VolumeCreate;
import com.emc.storageos.model.block.VolumeDeleteTypeEnum;
import com.emc.storageos.model.block.VolumeExpandParam;
import com.emc.storageos.model.block.VolumeFullCopyCreateParam;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.storageos.model.block.VolumeSnapshotParam;
import com.emc.storageos.model.block.VolumeVirtualArrayChangeParam;
import com.emc.storageos.model.block.VolumeVirtualPoolChangeParam;
import com.emc.storageos.model.block.export.ITLBulkRep;
import com.emc.storageos.model.block.export.ITLRestRepList;
import com.emc.storageos.model.protection.ProtectionSetRestRep;
import com.emc.storageos.model.search.SearchResultResourceRep;
import com.emc.storageos.model.search.SearchResults;
import com.emc.storageos.model.vpool.VirtualPoolChangeList;
import com.emc.storageos.model.vpool.VirtualPoolChangeOperationEnum;
import com.emc.storageos.protectioncontroller.ProtectionController;
import com.emc.storageos.protectioncontroller.RPController;
import com.emc.storageos.protectionorchestrationcontroller.ProtectionOrchestrationController;
import com.emc.storageos.security.audit.AuditLogManager;
import com.emc.storageos.security.authentication.StorageOSUser;
import com.emc.storageos.security.authorization.ACL;
import com.emc.storageos.security.authorization.CheckPermission;
import com.emc.storageos.security.authorization.DefaultPermissions;
import com.emc.storageos.security.authorization.Role;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.services.util.TimeUtils;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.svcs.errorhandling.resources.BadRequestException;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.svcs.errorhandling.resources.InternalServerErrorException;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCode;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCodeException;
import com.emc.storageos.util.VPlexSrdfUtil;
import com.emc.storageos.util.VPlexUtil;
import com.emc.storageos.volumecontroller.AsyncTask;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.smis.SRDFOperations.Mode;
import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;
import com.emc.storageos.volumecontroller.placement.ExportPathUpdater;
import com.emc.storageos.vplexcontroller.VPlexDeviceController;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;

@Path("/block/volumes")
@DefaultPermissions(readRoles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, readAcls = { ACL.OWN, ACL.ALL }, writeRoles = {
        Role.TENANT_ADMIN }, writeAcls = { ACL.OWN, ACL.ALL })
@SuppressWarnings({ "unchecked", "deprecation", "rawtypes" })
public class BlockService extends TaskResourceService {
    private static final String SEARCH_VARRAY = "virtual_array";
    private static final String SEARCH_WWN = "wwn";
    private static final String SEARCH_PERSONALITY = "personality";
    private static final String SEARCH_PROTECTION = "protection";

    private static final String SRDF = "srdf";
    private static final String RP = "rp";
    private static final String VPLEX = "vplex";
    private static final String HA = "ha";
    private static final String TRUE_STR = "true";
    private static final String FALSE_STR = "false";
    private static final String MIRRORS = "Mirrors";

    private static final String SIZE = "size";

    // Protection operations that are allowed with /block/volumes/{id}/protection/continuous-copies/
    public static enum ProtectionOp {
        FAILOVER("failover", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_FAILOVER),
        FAILOVER_TEST("failover-test", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_FAILOVER_TEST),
        FAILOVER_TEST_CANCEL("failover-test-cancel", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_FAILOVER_TEST_CANCEL),
        FAILOVER_CANCEL("failover-cancel", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_FAILOVER_CANCEL),
        SWAP("swap", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_SWAP),
        SYNC("sync", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_SYNC),
        START("start", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_START),
        STOP("stop", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_STOP),
        PAUSE("pause", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_PAUSE),
        SUSPEND("suspend", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_SUSPEND),
        RESUME("resume", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_RESUME),
        CHANGE_COPY_MODE("change-copy-mode", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_CHANGE_COPY_MODE),
        CHANGE_ACCESS_MODE("change-access-mode", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION_CHANGE_ACCESS_MODE),
        UNKNOWN("unknown", ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION);

        private final String op;
        private final ResourceOperationTypeEnum resourceType;

        ProtectionOp(String op, ResourceOperationTypeEnum resourceType) {
            this.op = op;
            this.resourceType = resourceType;
        }

        // The rest URI operation
        public String getRestOp() {
            return op;
        }

        // The resource type, which contains a good name and description
        public ResourceOperationTypeEnum getResourceType() {
            return resourceType;
        }

        private static final ProtectionOp[] copyOfValues = values();

        public static String getProtectionOpDisplayName(String op) {
            for (ProtectionOp opValue : copyOfValues) {
                if (opValue.getRestOp().contains(op)) {
                    return opValue.getResourceType().getName();
                }
            }
            return ProtectionOp.UNKNOWN.name();
        }

        public static ResourceOperationTypeEnum getResourceOperationTypeEnum(String restOp) {
            for (ProtectionOp opValue : copyOfValues) {
                if (opValue.getRestOp().contains(restOp)) {
                    return opValue.getResourceType();
                }
            }
            return ResourceOperationTypeEnum.PERFORM_PROTECTION_ACTION;
        }
    }

    @SuppressWarnings("unused")
    private static class DiscoverProtectionSetJobExec implements AsyncTaskExecutorIntf {

        private final ProtectionController _controller;

        DiscoverProtectionSetJobExec(ProtectionController controller) {
            _controller = controller;
        }

        @Override
        public void executeTasks(AsyncTask[] tasks) throws ControllerException {
            _controller.discover(tasks);
        }

        @Override
        public ResourceOperationTypeEnum getOperation() {
            return ResourceOperationTypeEnum.DISCOVER_STORAGE_SYSTEM;
        }
    }

    static final Logger _log = LoggerFactory.getLogger(BlockService.class);

    public static final String EVENT_SERVICE_TYPE = "block";

    private static final int GB = 1024 * 1024 * 1024;

    private static final int MAX_VOLUME_COUNT = 100;

    private TenantsService _tenantsService;

    PlacementManager _placementManager;

    public void setPlacementManager(PlacementManager placementManager) {
        _placementManager = placementManager;
    }

    public void setTenantsService(TenantsService tenantsService) {
        _tenantsService = tenantsService;
    }

    @Override
    public String getServiceType() {
        return EVENT_SERVICE_TYPE;
    }

    private static double THIN_VOLUME_MAX_LIMIT = 240;

    // Block service implementations
    static volatile private Map<String, BlockServiceApi> _blockServiceApis;

    static public void setBlockServiceApis(Map<String, BlockServiceApi> serviceInterfaces) {
        _blockServiceApis = serviceInterfaces;
    }

    static public BlockServiceApi getBlockServiceImpl(String type) {
        return _blockServiceApis.get(type);
    }

    @Override
    public Class<Volume> getResourceClass() {
        return Volume.class;
    }

    /**
     * Retrieve volume representations based on input ids.
     *
     * @return list of volume representations.
     */
    @Override
    public VolumeBulkRep queryBulkResourceReps(List<URI> ids) {

        Iterator<Volume> _dbIterator = _dbClient.queryIterativeObjects(getResourceClass(), ids);
        return new VolumeBulkRep(BulkList.wrapping(_dbIterator, MapVolume.getInstance(_dbClient)));
    }

    @Override
    protected BulkRestRep queryFilteredBulkResourceReps(
            List<URI> ids) {

        Iterator<Volume> _dbIterator = _dbClient.queryIterativeObjects(getResourceClass(), ids);
        BulkList.ResourceFilter<Volume> filter = new BulkList.ProjectResourceFilter<Volume>(
                getUserFromContext(), _permissionsHelper);
        return new VolumeBulkRep(BulkList.wrapping(_dbIterator, MapVolume.getInstance(_dbClient), filter));
    }

    /**
     * Start continuous copies. Continuous copies will be created when <i>NATIVE</i> type is specified and
     * <i>copyID</i> fields are omitted.
     *
     * @prereq none
     *
     * @param id URN of a ViPR Source volume
     * @param param List of copies to start or create.
     *
     * @brief Start or create continuous copies.
     *
     * @return TaskList
     * @throws ControllerException
     *
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/start")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList startContinuousCopies(@PathParam("id") URI id, CopiesParam param)
            throws ControllerException {

        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();

        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");

        Volume volume = _dbClient.queryObject(Volume.class, id);

        // Make sure that we don't have some pending
        // operation against the volume
        checkForPendingTasks(Arrays.asList(volume.getTenant().getURI()), Arrays.asList(volume));

        // Don't operate on ingested volumes.
        VolumeIngestionUtil.checkOperationSupportedOnIngestedVolume(volume,
                ResourceOperationTypeEnum.CREATE_VOLUME_MIRROR, _dbClient);

        Volume sourceVolume = queryVolumeResource(id);

        validateSourceVolumeHasExported(sourceVolume);

        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");

        // Verify that the copy IDs are either all specified or none are specified
        // for a particular protection type. Combinations are not allowed
        verifyCopyIDs(param);

        // Process the list of copies
        for (Copy copy : param.getCopies()) {

            // Validate a copy type was passed
            ArgValidator.checkFieldNotEmpty(copy.getType(), "type");

            URI copyID = copy.getCopyID();
            // If copyID is null all copies are started
            if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
                // If copyID is not set all copies are started
                if (!URIUtil.isValid(copyID)) {
                    copyID = null;
                }
                taskResp = performProtectionAction(id, copy, ProtectionOp.START.getRestOp());
                taskList.getTaskList().add(taskResp);
                // If copyID is null, we have already started all copies
                if (copyID == null) {
                    return taskList;
                }
            } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
                id = VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, id);
                copy.setCopyID(VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, copy.getCopyID()));
                taskResp = performSRDFProtectionAction(id, copy, ProtectionOp.START.getRestOp());
                taskList.getTaskList().add(taskResp);
            } else if (copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
                if (URIUtil.isValid(copyID) && URIUtil.isType(copyID, BlockMirror.class)) {
                    /*
                     * To establish group relationship between volume group and mirror group
                     */
                    taskResp = establishVolumeMirrorGroupRelation(id, copy, ProtectionOp.START.getRestOp());
                    taskList.getTaskList().add(taskResp);
                } else {
                    NativeContinuousCopyCreate mirror = new NativeContinuousCopyCreate(
                            copy.getName(), copy.getCount());
                    taskList = startMirrors(id, mirror);
                }
            } else {
                throw APIException.badRequests.invalidCopyType(copy.getType());
            }
        }

        return taskList;
    }

    /**
     * Stop continuous copies.
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param param
     *            List of copies to stop
     *
     * @brief Stop continuous copies.
     * @return TaskList
     *
     * @throws ControllerException
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/stop")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList stopContinuousCopies(@PathParam("id") URI id, CopiesParam param)
            throws ControllerException {

        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();

        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");

        Volume volume = _dbClient.queryObject(Volume.class, id);

        // Make sure that we don't have some pending
        // operation against the volume
        checkForPendingTasks(Arrays.asList(volume.getTenant().getURI()), Arrays.asList(volume));

        boolean vplexVolume = checkIfVolumeIsForVplex(id);

        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");

        // Verify that the copy IDs are either all specified or none are specified
        // for a particular protection type. Combinations are not allowed
        verifyCopyIDs(param);
        // Validate for SRDF Stop operation
        validateSRDFStopOperation(id, param);

        // Process the list of copies
        for (Copy copy : param.getCopies()) {

            // If copyID is not set all copies are stopped
            URI copyID = copy.getCopyID();
            if (!URIUtil.isValid(copyID)) {
                copyID = null;
            }

            // Validate a copy type was passed
            ArgValidator.checkFieldNotEmpty(copy.getType(), "type");

            // If copyID is null all copies are stopped
            if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
                taskResp = performProtectionAction(id, copy, ProtectionOp.STOP.getRestOp());
                taskList.getTaskList().add(taskResp);
            } else if (!vplexVolume && copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
                taskList = stopMirrors(id, copyID);
            } else if (vplexVolume && copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
                taskList = stopVplexMirrors(id, copyID);
            } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
                id = VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, id);
                copy.setCopyID(VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, copy.getCopyID()));
                taskResp = performSRDFProtectionAction(id, copy, ProtectionOp.STOP.getRestOp());
                taskList.getTaskList().add(taskResp);
            } else {
                throw APIException.badRequests.invalidCopyType(copy.getType());
            }

            // If copyID is null, we have already stopped all copies
            if (copyID == null) {
                return taskList;
            }
        }

        return taskList;
    }

    /**
     * Cant Perform SRDF STOP operation Sync/Async with CG if it has active snap or clone.
     *
     * @param id
     * @param param
     */
    private void validateSRDFStopOperation(URI id, CopiesParam param) {
        List<URI> srdfVolumeURIList = new ArrayList<URI>();
        Volume srdfSourceVolume = _dbClient.queryObject(Volume.class, id);
        if (srdfSourceVolume.checkForSRDF() && srdfSourceVolume.hasConsistencyGroup()) {
            srdfVolumeURIList.add(id);
            for (Copy copy : param.getCopies()) {
                URI copyID = copy.getCopyID();
                if (URIUtil.isType(copyID, Volume.class) && URIUtil.isValid(copyID)) {
                    srdfVolumeURIList.add(copyID);
                    break;
                }
            }
            for (URI srdfVolURI : srdfVolumeURIList) {
                Volume volume = _dbClient.queryObject(Volume.class, srdfVolURI);

                URIQueryResultList list = new URIQueryResultList();
                Constraint constraint = ContainmentConstraint.Factory
                        .getVolumeSnapshotConstraint(srdfVolURI);
                _dbClient.queryByConstraint(constraint, list);
                Iterator<URI> it = list.iterator();
                while (it.hasNext()) {
                    URI snapshotID = it.next();
                    BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
                    if (snapshot != null && !snapshot.getInactive()) {
                        throw APIException.badRequests.cannotStopSRDFBlockSnapShotExists(volume
                                .getLabel());
                    }
                }

                // Also check for snapshot sessions.
                List<BlockSnapshotSession> snapSessions = CustomQueryUtility.queryActiveResourcesByConstraint(_dbClient,
                        BlockSnapshotSession.class, ContainmentConstraint.Factory.getParentSnapshotSessionConstraint(srdfVolURI));
                if (!snapSessions.isEmpty()) {
                    throw APIException.badRequests.cannotStopSRDFBlockSnapShotExists(volume
                            .getLabel());
                }

                // For a volume that is a full copy or is the source volume for
                // full copies deleting the volume may not be allowed.
                if (!getFullCopyManager().volumeCanBeDeleted(volume)) {
                    throw APIException.badRequests.cantStopSRDFFullCopyNotDetached(volume
                            .getLabel());
                }
            }
        }
    }

    /**
     * Create a full copy of the specified volume.
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param param
     *            POST data containing full copy creation information
     *
     * @brief Create full copies
     * @return TaskList
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList createFullCopy(@PathParam("id") URI id,
            VolumeFullCopyCreateParam param) throws InternalException {
        return getFullCopyManager().createFullCopy(id, param);
    }

    /**
     * Activate a full copy.
     * <p>
     * This method is deprecated. Use /block/full-copies/{id}/activate instead with {id} representing full copy URI id
     *
     * @prereq Create full copy as inactive
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param fullCopyId
     *            Full copy URI
     *
     * @brief Activate full copy. This method is deprecated. Use /block/full-copies/{id}/activate instead with {id}
     *        representing full copy
     *        URI id
     *
     * @return TaskResourceRep
     */
    @Deprecated
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies/{pid}/activate")
    public TaskResourceRep activateFullCopy(@PathParam("id") URI id,
            @PathParam("pid") URI fullCopyId) throws InternalException {
        return getFullCopyManager().activateFullCopy(id, fullCopyId).getTaskList().get(0);
    }

    /**
     * Show synchronization progress for a full copy.
     *
     * <p>
     * This method is deprecated. Use /block/full-copies/{id}/check-progress instead with {id} representing full copy URI id
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param fullCopyId
     *            Full copy URI
     *
     * @brief Show full copy synchronization progress
     *
     * @return VolumeRestRep
     */
    @Deprecated
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies/{pid}/check-progress")
    public VolumeRestRep getFullCopyProgressCheck(@PathParam("id") URI id,
            @PathParam("pid") URI fullCopyId) throws InternalException {
        return getFullCopyManager().checkFullCopyProgress(id, fullCopyId);
    }

    /**
     * Detach a full copy from its source volume.
     *
     * <p>
     * This method is deprecated. Use /block/full-copies/{id}/detach instead with {id} representing full copy URI id
     *
     * @prereq Create full copy as inactive
     * @prereq Activate full copy
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param id
     *            the URN of Full copy volume
     *
     * @brief Detach full copy
     *
     * @return TaskResourceRep
     */
    @Deprecated
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies/{pid}/detach")
    public TaskResourceRep detachFullCopy(@PathParam("id") URI id,
            @PathParam("pid") URI fullCopyId) throws InternalException {
        return getFullCopyManager().detachFullCopy(id, fullCopyId).getTaskList().get(0);
    }

    /**
     * Creates and returns an instance of the block full copy manager to handle
     * a full copy request.
     *
     * @return BlockFullCopyManager
     */
    private BlockFullCopyManager getFullCopyManager() {
        BlockFullCopyManager fcManager = new BlockFullCopyManager(_dbClient,
                _permissionsHelper, _auditMgr, _coordinator, _placementManager, sc, uriInfo,
                _request, _tenantsService);
        return fcManager;
    }

    /**
     * Creates and returns an instance of the block snapshot session manager to handle
     * a snapshot session creation request.
     *
     * @return BlockSnapshotSessionManager
     */
    private BlockSnapshotSessionManager getSnapshotSessionManager() {
        BlockSnapshotSessionManager snapshotSessionManager = new BlockSnapshotSessionManager(_dbClient,
                _permissionsHelper, _auditMgr, _coordinator, sc, uriInfo, _request);
        return snapshotSessionManager;
    }

    /**
     * The fundamental abstraction in the Block Store is a
     * volume. A volume is a unit of block storage capacity that has been
     * allocated by a consumer to a project. This API allows the user to
     * create one or more volumes. The volumes are created in the same
     * storage pool.
     *
     * NOTE: This is an asynchronous operation.
     *
     *
     * @prereq none
     *
     * @param param
     *            POST data containing the volume creation information.
     *
     * @brief Create volume
     * @return A reference to a BlockTaskList containing a list of
     *         TaskResourceRep references specifying the task data for the
     *         volume creation tasks.
     * @throws InternalException
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public TaskList createVolume(VolumeCreate param) throws InternalException {
        ArgValidator.checkFieldNotNull(param, "volume_create");

        // CQECC00604134
        ArgValidator.checkFieldUriType(param.getProject(), Project.class, "project");

        // Get and validate the project.
        Project project = _permissionsHelper.getObjectById(param.getProject(), Project.class);
        ArgValidator.checkEntity(project, param.getProject(), isIdEmbeddedInURL(param.getProject()));

        // Verify the user is authorized.
        BlockServiceUtils.verifyUserIsAuthorizedForRequest(project, getUserFromContext(), _permissionsHelper);

        // Get and validate the varray
        ArgValidator.checkFieldUriType(param.getVarray(), VirtualArray.class, "varray");
        VirtualArray varray = BlockServiceUtils.verifyVirtualArrayForRequest(project,
                param.getVarray(), uriInfo, _permissionsHelper, _dbClient);
        ArgValidator.checkEntity(varray, param.getVarray(), isIdEmbeddedInURL(param.getVarray()));

        // Get and validate the VirtualPool.
        VirtualPool vpool = getVirtualPoolForVolumeCreateRequest(project, param);

        VirtualPoolCapabilityValuesWrapper capabilities = new VirtualPoolCapabilityValuesWrapper();
        // Get the count indicating the number of volumes to create. If not
        // passed
        // assume 1. Then get the volume placement recommendations.
        Integer volumeCount = 1;
        Long volumeSize = 0L;
        if (param.getCount() != null) {
            if (param.getCount() <= 0) {
                throw APIException.badRequests.parameterMustBeGreaterThan("count", 0);
            }
            if (param.getCount() > MAX_VOLUME_COUNT) {
                throw APIException.badRequests.exceedingLimit("count", MAX_VOLUME_COUNT);
            }
            volumeCount = param.getCount();
            capabilities.put(VirtualPoolCapabilityValuesWrapper.RESOURCE_COUNT, volumeCount);
        }

        if (param.getSize() != null) {
            // Validate the requested volume size is greater then 0.
            volumeSize = SizeUtil.translateSize(param.getSize());
            if (volumeSize <= 0) {
                throw APIException.badRequests.parameterMustBeGreaterThan(SIZE, 0);
            }
            capabilities.put(VirtualPoolCapabilityValuesWrapper.SIZE, volumeSize);
        }

        if (null != vpool.getThinVolumePreAllocationPercentage()
                && 0 < vpool.getThinVolumePreAllocationPercentage()) {
            capabilities.put(VirtualPoolCapabilityValuesWrapper.THIN_VOLUME_PRE_ALLOCATE_SIZE, VirtualPoolUtil
                    .getThinVolumePreAllocationSize(vpool.getThinVolumePreAllocationPercentage(), volumeSize));
        }

        if (VirtualPool.ProvisioningType.Thin.toString().equalsIgnoreCase(
                vpool.getSupportedProvisioningType())) {
            capabilities.put(VirtualPoolCapabilityValuesWrapper.THIN_PROVISIONING, Boolean.TRUE);
        }

        // Does vpool supports dedup
        if (null != vpool.getDedupCapable() && vpool.getDedupCapable()) {
            capabilities.put(VirtualPoolCapabilityValuesWrapper.DEDUP, Boolean.TRUE);
        }

        // Find the implementation that services this vpool and volume request
        BlockServiceApi blockServiceImpl = getBlockServiceImpl(vpool, _dbClient);

        BlockConsistencyGroup consistencyGroup = null;
        final Boolean isMultiVolumeConsistencyOn = vpool.getMultivolumeConsistency() == null ? FALSE
                : vpool.getMultivolumeConsistency();

        /*
         * Validate Consistency Group:
         * 1. CG should be active in the database
         * 2. CG project and Volume project should match
         * 3. The storage system that the CG is bonded to is associated to the
         * request virtual array
         */
        ArrayList<String> requestedTypes = new ArrayList<String>();
        final URI actualId = project.getId();
        if (param.getConsistencyGroup() != null) {
            // Get and validate consistency group
            consistencyGroup = queryConsistencyGroup(param.getConsistencyGroup());

            // Check that the Volume project and the CG project are the same
            final URI expectedId = consistencyGroup.getProject().getURI();
            checkProjectsMatch(expectedId, actualId);

            // If the Consistency Group was provided, MultiVolumeConsistency
            // attribute should be true
            if (!isMultiVolumeConsistencyOn) {
                throw APIException.badRequests.invalidParameterConsistencyGroupProvidedButVirtualPoolHasNoMultiVolumeConsistency(
                        param.getConsistencyGroup(), param.getVpool());
            }

            // Find all volumes assigned to the group
            final List<Volume> activeCGVolumes = blockServiceImpl.getActiveCGVolumes(consistencyGroup);

            // Validate that the number of volumes in the group plus the number
            // to be added by this request does not exceed the maximum volumes
            // in a CG.
            int cgMaxVolCount = blockServiceImpl
                    .getMaxVolumesForConsistencyGroup(consistencyGroup);
            if ((activeCGVolumes.size() + volumeCount.intValue()) > cgMaxVolCount) {
                throw APIException.badRequests.requestedVolumeCountExceedsLimitsForCG(
                        volumeCount.intValue(), cgMaxVolCount, consistencyGroup.getLabel());
            }

            // If the consistency group is not yet created, verify the name is OK.
            if (!consistencyGroup.created()) {
                blockServiceImpl.validateConsistencyGroupName(consistencyGroup);
            }

            // Consistency Group is already a Target, hence cannot be used to create source volume
            if (consistencyGroup.srdfTarget()) {
                throw APIException.badRequests.consistencyGroupBelongsToTarget(consistencyGroup.getId());
            }

            if (VirtualPool.vPoolSpecifiesSRDF(vpool)
                    && (consistencyGroup.getLabel().length() > 8 || !isAlphaNumeric(consistencyGroup.getLabel()))) {
                throw APIException.badRequests.groupNameCannotExceedEightCharactersoronlyAlphaNumericAllowed();
            }

            if (!VirtualPool.vPoolSpecifiesSRDF(vpool) && consistencyGroup.checkForType(Types.SRDF)) {
                throw APIException.badRequests.nonSRDFVolumeCannotbeAddedToSRDFCG();
            }

            if (VirtualPool.vPoolSpecifiesSRDF(vpool)) {
                List<Volume> nativeVolumesInCG = BlockConsistencyGroupUtils.getActiveNativeVolumesInCG(consistencyGroup, _dbClient);
                for (Volume nativeVolume : nativeVolumesInCG) {
                    // Cannot add volumes if in swapped state. This is a limitation that will eventually be removed.
                    if (Volume.LinkStatus.SWAPPED.name().equals(nativeVolume.getLinkStatus())) {
                        throw BadRequestException.badRequests.cannotAddVolumesToSwappedCG(consistencyGroup.getLabel());
                    }
                }
            }

            // check if CG's storage system is associated to the requested virtual array
            validateCGValidWithVirtualArray(consistencyGroup, varray);

            requestedTypes = getRequestedTypes(vpool);

            // Validate the CG type. We want to make sure the volume create request is appropriate
            // the CG's previously requested types.
            if (consistencyGroup.creationInitiated()) {
                if (!consistencyGroup.getRequestedTypes().containsAll(requestedTypes)) {
                    throw APIException.badRequests.consistencyGroupIsNotCompatibleWithRequest(
                            consistencyGroup.getId(), consistencyGroup.getRequestedTypes().toString(), requestedTypes.toString());
                }
            }

            Volume existingRpSourceVolume = null;

            // RP consistency group validation
            if (VirtualPool.vPoolSpecifiesProtection(vpool)) {
                // If an RP protected vpool is specified, ensure that the CG selected is empty or contains only RP
                // volumes.
                if (activeCGVolumes != null && !activeCGVolumes.isEmpty()
                        && !consistencyGroup.getTypes().contains(BlockConsistencyGroup.Types.RP.toString())) {
                    throw APIException.badRequests.consistencyGroupMustBeEmptyOrContainRpVolumes(consistencyGroup.getId());
                }

                if (!activeCGVolumes.isEmpty()) {
                    // Find the first existing source volume for source/target varray comparison.
                    for (Volume cgVolume : activeCGVolumes) {
                        if (cgVolume.getPersonality() != null &&
                                cgVolume.getPersonality().equals(Volume.PersonalityTypes.SOURCE.toString())) {
                            existingRpSourceVolume = cgVolume;
                            break;
                        }
                    }

                    if (existingRpSourceVolume != null) {
                        VirtualPool existingVpool = _dbClient.queryObject(
                                VirtualPool.class, existingRpSourceVolume.getVirtualPool());
                        VirtualPool requestedVpool = _dbClient.queryObject(
                                VirtualPool.class, param.getVpool());

                        // The source virtual arrays must much
                        if (existingVpool.getVirtualArrays().size() != requestedVpool.getVirtualArrays().size()
                                || !existingVpool.getVirtualArrays().containsAll(requestedVpool.getVirtualArrays())) {
                            // The source virtual arrays are not compatible with the CG
                            throw APIException.badRequests.vPoolSourceVarraysNotCompatibleForCG(consistencyGroup.getLabel());
                        }

                        // Validate that the request is not attempting to mix VPlex Metro volumes and
                        // MetroPoint volumes in the same CG.
                        if (VirtualPool.vPoolSpecifiesHighAvailability(existingVpool) &&
                                VirtualPool.vPoolSpecifiesHighAvailability(requestedVpool)) {
                            // If the requested and CG assigned virtual pools both specify VPlex, ensure
                            // that we are not trying to mix MetroPoint volumes with Metro volumes.
                            if ((!VirtualPool.vPoolSpecifiesMetroPoint(requestedVpool) &&
                                    VirtualPool.vPoolSpecifiesMetroPoint(existingVpool)) ||
                                    (VirtualPool.vPoolSpecifiesMetroPoint(requestedVpool) &&
                                    !VirtualPool.vPoolSpecifiesMetroPoint(existingVpool))) {
                                throw APIException.badRequests.cannotMixMetroPointAndNonMetroPointVolumes(consistencyGroup.getLabel());
                            }
                        }

                        // Check the target virtual arrays
                        StringMap existingProtectionVarraySettings = existingVpool.getProtectionVarraySettings();

                        if (existingProtectionVarraySettings == null) {
                            // The existing CG source volume's protection settings are null. This can only happen when a
                            // swap is performed on the CG. This would have been a former target volume so its virtual
                            // pool references a target virtual pool, which has no protection settings defined. We do
                            // not support adding a volume to an existing CG whose volumes have been swapped.
                            // NOTE: This will be supported in the future through Jira CTRL-10129
                            throw APIException.badRequests.cannotAddVolumesToSwappedCG(consistencyGroup.getLabel());
                        }

                        StringMap requestedProtectionVarraySettings = requestedVpool.getProtectionVarraySettings();

                        if (existingProtectionVarraySettings.size() != requestedProtectionVarraySettings.size()) {
                            // The target virtual arrays are not compatible with the CG
                            throw APIException.badRequests.vPoolTargetVarraysNotCompatibleForCG(consistencyGroup.getLabel());
                        }

                        for (String targetVarray : requestedProtectionVarraySettings.keySet()) {
                            if (!existingProtectionVarraySettings.containsKey(targetVarray)) {
                                // The target virtual arrays are not compatible with the CG
                                throw APIException.badRequests.vPoolTargetVarraysNotCompatibleForCG(consistencyGroup.getLabel());
                            }
                        }

                        // Ensure the replication mode is logically equivalent
                        String requestedRpCopyMode = NullColumnValueGetter.isNullValue(requestedVpool.getRpCopyMode())
                                ? RPCopyMode.ASYNCHRONOUS.name() : requestedVpool.getRpCopyMode();
                        String existingRpCopyMode = NullColumnValueGetter.isNullValue(existingVpool.getRpCopyMode())
                                ? RPCopyMode.ASYNCHRONOUS.name() : existingVpool.getRpCopyMode();
                        if (!requestedRpCopyMode.equalsIgnoreCase(existingRpCopyMode)) {
                            throw APIException.badRequests.vPoolRPCopyModeNotCompatibleForCG(consistencyGroup.getLabel());
                        }
                    }
                }
            }

            // Creating new volumes in a consistency group is
            // not supported when the consistency group has
            // volumes with full copies to which they are still
            // attached or has volumes that are full copies that
            // are still attached to their source volumes.
            if (!activeCGVolumes.isEmpty()) {
                // Pass in an active CG volume for validation. If we are dealing with a RecoverPoint
                // consistency group, we need to use an RP source volume. Otherwise we can use any arbitrary
                // CG volume.
                Volume activeCGVolume = existingRpSourceVolume == null ? activeCGVolumes.get(0) : existingRpSourceVolume;

                if (!BlockServiceUtils.checkCGVolumeCanBeAddedOrRemoved(consistencyGroup, activeCGVolume, _dbClient)) {
                    checkCGForMirrors(consistencyGroup, activeCGVolumes);
                    checkCGForSnapshots(consistencyGroup);
                    getFullCopyManager().verifyNewVolumesCanBeCreatedInConsistencyGroup(consistencyGroup,
                            activeCGVolumes);
                }
            }

            capabilities.put(VirtualPoolCapabilityValuesWrapper.BLOCK_CONSISTENCY_GROUP,
                    param.getConsistencyGroup());
        } else if (VirtualPool.vPoolSpecifiesProtection(vpool)) {
            // The consistency group param is null and RP protection has been specified. When RP
            // protection is specified, a consistency group must be selected.
            throw APIException.badRequests.consistencyGroupMissingForRpProtection();
        }

        // verify quota
        long size = volumeCount * SizeUtil.translateSize(param.getSize());
        TenantOrg tenant = _dbClient.queryObject(TenantOrg.class, project.getTenantOrg().getURI());
        ArgValidator.checkEntity(tenant, project.getTenantOrg().getURI(), false);
        CapacityUtils.validateQuotasForProvisioning(_dbClient, vpool, project, tenant, size, "volume");

        // set compute param
        URI computeURI = param.getComputeResource();
        if (!NullColumnValueGetter.isNullURI(computeURI)) {
            capabilities.put(VirtualPoolCapabilityValuesWrapper.COMPUTE, computeURI.toString());
        }

        // COP-14028
        // Changing the return of a TaskList to return immediately while the underlying tasks are
        // being built up. Steps:
        // 1. Create a task object ahead of time and persist it for each requested volume.
        // 2. Fire off a thread that does the placement and preparation of the volumes, which will use the pre-created
        // task/volume objects during their source volume creations.
        // 3. Return to the caller the new Task objects that is in the pending state.
        String task = UUID.randomUUID().toString();
        TaskList taskList = createVolumeTaskList(param.getSize(), project, varray, vpool, param.getName(), task, volumeCount);

        // This is causing exceptions when run in the thread.
        auditOp(OperationTypeEnum.CREATE_BLOCK_VOLUME, true, AuditLogManager.AUDITOP_BEGIN,
                param.getName(), volumeCount, varray.getId().toString(), actualId.toString());

        // call thread that does the work.
        CreateVolumeSchedulingThread.executeApiTask(this, _asyncTaskService.getExecutorService(), _dbClient, varray,
                project, vpool, capabilities, taskList, task,
                consistencyGroup, requestedTypes, param, blockServiceImpl);

        _log.info("Kicked off thread to perform placement and scheduling.  Returning " + taskList.getTaskList().size() + " tasks");
        return taskList;
    }

    /**
     * returns the types (RP, VPLEX, SRDF or LOCAL) that will be created based on the vpool
     *
     * @param vpool
     * @param requestedTypes
     */
    private ArrayList<String> getRequestedTypes(VirtualPool vpool) {

        ArrayList<String> requestedTypes = new ArrayList<String>();

        if (VirtualPool.vPoolSpecifiesProtection(vpool)) {
            requestedTypes.add(Types.RP.name());
        }

        // Note that for ingested VPLEX CGs or CGs created in releases
        // prior to 2.2, there will be no corresponding native
        // consistency group. We don't necessarily want to fail
        // volume creations in these CGs, so we don't require the
        // LOCAL type.
        if (VirtualPool.vPoolSpecifiesHighAvailability(vpool)) {
            requestedTypes.add(Types.VPLEX.name());
        }

        if (VirtualPool.vPoolSpecifiesSRDF(vpool)) {
            requestedTypes.add(Types.SRDF.name());
        }

        if (!VirtualPool.vPoolSpecifiesProtection(vpool)
                && !VirtualPool.vPoolSpecifiesHighAvailability(vpool)
                && !VirtualPool.vPoolSpecifiesSRDF(vpool)
                && vpool.getMultivolumeConsistency()) {
            requestedTypes.add(Types.LOCAL.name());
        }

        return requestedTypes;
    }

    /**
     * A method that pre-creates task and volume objects to return to the caller of the API.
     *
     * @param size
     *            size of the volume
     * @param project
     *            project of the volume
     * @param varray
     *            virtual array of the volume
     * @param vpool
     *            virtual pool of the volume
     * @param label
     *            label
     * @param task
     *            task string
     * @param volumeCount
     *            number of volumes requested
     * @return a list of tasks associated with this request
     */
    private TaskList createVolumeTaskList(String size, Project project, VirtualArray varray, VirtualPool vpool, String label, String task,
            Integer volumeCount) {
        TaskList taskList = new TaskList();

        try {
            // For each volume requested, pre-create a volume object/task object
            long lsize = SizeUtil.translateSize(size);
            for (int i = 0; i < volumeCount; i++) {
                Volume volume = StorageScheduler.prepareEmptyVolume(_dbClient, lsize, project, varray, vpool, label, i, volumeCount);
                Operation op = _dbClient.createTaskOpStatus(Volume.class, volume.getId(),
                        task, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME);
                volume.getOpStatus().put(task, op);
                TaskResourceRep volumeTask = toTask(volume, task, op);
                taskList.getTaskList().add(volumeTask);
                _log.info(String.format("Volume and Task Pre-creation Objects [Init]--  Source Volume: %s, Task: %s, Op: %s",
                        volume.getId(), volumeTask.getId(), task));
            }
        } catch (APIException ex) {
            // Mark the dummy objects inactive
            String errMsg = "Caught Exception while creating Volume and Task objects. Marking pre-created Objects inactive";
            _log.error(errMsg, ex);
            for (TaskResourceRep taskObj : taskList.getTaskList()) {
                taskObj.setMessage(String.format("%s. %s", errMsg, ex.getMessage()));
                taskObj.setState(Operation.Status.error.name());
                URI volumeURI = taskObj.getResource().getId();
                _dbClient.error(Volume.class, volumeURI, task, ex);
                // Set the volumes to inactive
                Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
                volume.setInactive(true);
                _dbClient.updateObject(volume);
            }
            // throw the Exception to the caller
            throw ex;
        }

        return taskList;
    }

    private boolean isAlphaNumeric(String consistencyGroupName) {
        String pattern = "^[a-zA-Z0-9]*$";
        if (consistencyGroupName.matches(pattern)) {
            return true;
        }
        return false;
    }

    /**
     * Checks existing CG for non-RP snapshots. If non-RP snapshots exist,
     * we cannot create/add a volume to the CG.
     *
     * @param consistencyGroup
     *            the consistency group to validate.
     */
    private void checkCGForSnapshots(BlockConsistencyGroup consistencyGroup) {
        // If the Consistency Group has Snapshot(s), then Volume can not be created.
        final URIQueryResultList cgSnapshotsResults = new URIQueryResultList();
        _dbClient.queryByConstraint(getBlockSnapshotByConsistencyGroup(consistencyGroup.getId()),
                cgSnapshotsResults);
        Iterator<BlockSnapshot> blockSnapshotIterator = _dbClient.queryIterativeObjects(BlockSnapshot.class, cgSnapshotsResults);

        while (blockSnapshotIterator.hasNext()) {
            BlockSnapshot next = blockSnapshotIterator.next();
            // RP BlockSnapshots do not prevent other volumes from being created/added to the
            // consistency group.
            if (!next.getTechnologyType().equalsIgnoreCase(TechnologyType.RP.name())) {
                throw APIException.badRequests.cannotCreateVolumeAsConsistencyGroupHasSnapshots(consistencyGroup.getLabel(),
                        consistencyGroup.getId());
            }
        }

    }

    /**
     * Verify that new volumes can be created in the passed consistency group.
     *
     * @param consistencyGroup
     *            A reference to the consistency group.
     * @param cgVolumes
     *            The volumes in the consistency group.
     */
    private void checkCGForMirrors(BlockConsistencyGroup consistencyGroup, List<Volume> cgVolumes) {
        // If volumes in CG have mirrors, then new volume cannot be created.
        for (Volume volume : cgVolumes) {
            StringSet mirrors = volume.getMirrors();
            if (mirrors != null && !mirrors.isEmpty()) {
                throw APIException.badRequests.cannotCreateVolumeAsConsistencyGroupHasMirrors(consistencyGroup.getLabel(),
                        consistencyGroup.getId());
            }
        }
    }

    private void checkProjectsMatch(final URI expectedId, final URI actualId) {
        final boolean condition = actualId.equals(expectedId);
        if (!condition) {
            throw APIException.badRequests.invalidProjectConflict(expectedId);
        }
    }

    /**
     * Returns the bean responsible for servicing the request
     *
     * @param vpool
     *            Virtual Pool
     * @return block service implementation object
     */
    private static BlockServiceApi getBlockServiceImpl(VirtualPool vpool, DbClient dbClient) {
        // Mutually exclusive logic that selects an implementation of the block service
        if (VirtualPool.vPoolSpecifiesProtection(vpool)) {
            return getBlockServiceImpl(DiscoveredDataObject.Type.rp.name());
        } else if (VirtualPool.vPoolSpecifiesHighAvailability(vpool)) {
            return getBlockServiceImpl(DiscoveredDataObject.Type.vplex.name());
        } else if (VirtualPool.vPoolSpecifiesSRDF(vpool)) {
            return getBlockServiceImpl(DiscoveredDataObject.Type.srdf.name());
        } else if (VirtualPool.vPoolSpecifiesMirrors(vpool, dbClient)) {
            return getBlockServiceImpl("mirror");
        } else if (vpool.getMultivolumeConsistency() != null && vpool.getMultivolumeConsistency()) {
            return getBlockServiceImpl("group");
        }

        return getBlockServiceImpl("default");
    }

    /**
     * Returns the bean responsible for servicing the request
     *
     * @param volume
     *            block volume
     * @return block service implementation object
     */
    private BlockServiceApi getBlockServiceImpl(Volume volume) {
        return getBlockServiceImpl(volume, _dbClient);
    }

    /**
     * Returns the bean responsible for servicing the request
     *
     * @param volume
     *            block volume
     * @return block service implementation object
     */
    public static BlockServiceApi getBlockServiceImpl(Volume volume, DbClient dbClient) {
        // RP volumes may not be in an RP CoS (like after failover), so look to the volume properties
        if (!isNullURI(volume.getProtectionController())
                && volume.checkForRp()) {
            return getBlockServiceImpl(DiscoveredDataObject.Type.rp.name());
        }

        if (Volume.checkForSRDF(dbClient, volume.getId())) {
            return getBlockServiceImpl(DiscoveredDataObject.Type.srdf.name());
        }

        // Otherwise the volume sent in is assigned to a virtual pool that tells us what block service to return
        VirtualPool vPool = dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
        // Mutually exclusive logic that selects an implementation of the block service
        if (VirtualPool.vPoolSpecifiesHighAvailability(vPool)) {
            return getBlockServiceImpl(DiscoveredDataObject.Type.vplex.name());
        } else if (VirtualPool.vPoolSpecifiesSRDF(vPool)) {
            return getBlockServiceImpl(DiscoveredDataObject.Type.srdf.name());
        } else if (VirtualPool.vPoolSpecifiesMirrors(vPool, dbClient)) {
            return getBlockServiceImpl("mirror");
        } else if (vPool.getMultivolumeConsistency() != null && vPool.getMultivolumeConsistency()) {
            return getBlockServiceImpl("group");
        }

        return getBlockServiceImpl("default");
    }

    /**
     * Gets and verifies the VirtualPool passed in the request.
     *
     * @param project
     *            A reference to the project.
     * @param param
     *            The volume create post data.
     *
     * @return A reference to the VirtualPool.
     */
    private VirtualPool getVirtualPoolForVolumeCreateRequest(Project project, VolumeCreate param) {
        ArgValidator.checkUri(param.getVpool());
        VirtualPool cos = _dbClient.queryObject(VirtualPool.class, param.getVpool());
        ArgValidator.checkEntity(cos, param.getVpool(), false);
        if (!VirtualPool.Type.block.name().equals(cos.getType())) {
            throw APIException.badRequests.virtualPoolNotForFileBlockStorage(VirtualPool.Type.block.name());
        }

        _permissionsHelper.checkTenantHasAccessToVirtualPool(project.getTenantOrg().getURI(), cos);
        return cos;
    }

    /**
     * Gets and verifies the consistency group passed in the request.
     *
     * @param consistencyGroupUri
     *            URI of the Consistency Group
     *
     * @return A reference to the BlockConsistencyGroup.
     */
    private BlockConsistencyGroup queryConsistencyGroup(final URI consistencyGroupUri) {
        ArgValidator.checkFieldNotNull(consistencyGroupUri, "consistency_group");
        ArgValidator.checkUri(consistencyGroupUri);

        final BlockConsistencyGroup consistencyGroup = _dbClient.queryObject(BlockConsistencyGroup.class, consistencyGroupUri);

        ArgValidator.checkEntity(consistencyGroup, consistencyGroupUri, isIdEmbeddedInURL(consistencyGroupUri));

        return consistencyGroup;
    }

    /**
     * Request to expand volume capacity to the specified size.
     *
     * NOTE: This is an asynchronous operation.
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR storage volume.
     * @param param
     *            Specifies requested size for volume expansion.
     *
     * @brief Expand volume capacity
     * @return Task resource representation
     *
     * @throws InternalException
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/expand")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskResourceRep expandVolume(@PathParam("id") URI id, VolumeExpandParam param) throws InternalException {

        // Get the volume.
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        Volume volume = queryVolumeResource(id);

        // Verify that the volume is 'expandable'
        VirtualPool virtualPool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
        if (!virtualPool.getExpandable()) {
            throw APIException.badRequests.volumeNotExpandable(volume.getLabel());
        }

        // Don't operate on VPLEX backend or RP Journal volumes.
        BlockServiceUtils.validateNotAnInternalBlockObject(volume, false);

        // Don't operate on ingested volumes
        VolumeIngestionUtil.checkOperationSupportedOnIngestedVolume(volume,
                ResourceOperationTypeEnum.EXPAND_BLOCK_VOLUME, _dbClient);

        // Verify if there are any restrictions on volume expansion for
        // full copy volumes or full copy source volumes.
        if (((BlockFullCopyUtils.isVolumeFullCopy(volume, _dbClient)) ||
                (BlockFullCopyUtils.isVolumeFullCopySource(volume, _dbClient))) &&
                (!getFullCopyManager().volumeCanBeExpanded(volume))) {
            throw APIException.badRequests.fullCopyExpansionNotAllowed(volume.getLabel());
        }

        // Check for an SRDF volume with snapshots which cannot be expanded.
        if (VirtualPool.vPoolSpecifiesSRDF(virtualPool)) {
            validateExpandingSrdfVolume(volume);
        }

        // Make sure that we don't have some pending
        // operation against the volume
        checkForPendingTasks(Arrays.asList(volume.getTenant().getURI()), Arrays.asList(volume));

        // Get the new size.
        Long newSize = SizeUtil.translateSize(param.getNewSize());
        // When newSize is the same as current size of the volume, this can be recovery attempt from failing previous
        // expand to cleanup
        // dangling meta members created for failed expansion
        if (newSize.equals(volume.getCapacity()) && volume.getMetaVolumeMembers() != null && !(volume.getMetaVolumeMembers().isEmpty())) {
            _log.info(String
                    .format(
                            "expandVolume --- Zero capacity expansion: allowed as a recovery to cleanup dangling members from previous expand failure.\n"
                                    +
                                    "VolumeId id: %s, Current size: %d, New size: %d, Dangling volumes: %s ",
                            id, volume.getCapacity(), newSize, volume.getMetaVolumeMembers()));
        } else if (newSize <= volume.getCapacity()) {
            _log.info(String.format(
                    "expandVolume: VolumeId id: %s, Current size: %d, New size: %d ", id, volume.getCapacity(), newSize));
            throw APIException.badRequests.newSizeShouldBeLargerThanOldSize("volume");
        }

        _log.info(String.format(
                "expandVolume --- VolumeId id: %s, Current size: %d, New size: %d", id,
                volume.getCapacity(), newSize));

        // Get the Block service implementation for this volume.
        BlockServiceApi blockServiceApi = getBlockServiceImpl(volume);

        // Verify that the volume can be expanded.
        blockServiceApi.verifyVolumeExpansionRequest(volume, newSize);

        // verify quota
        if (newSize > volume.getProvisionedCapacity()) {
            long size = newSize - volume.getProvisionedCapacity();
            TenantOrg tenant = _dbClient.queryObject(TenantOrg.class, volume.getTenant().getURI());
            ArgValidator.checkEntity(tenant, volume.getTenant().getURI(), false);
            Project project = _dbClient.queryObject(Project.class, volume.getProject().getURI());
            ArgValidator.checkEntity(project, volume.getProject().getURI(), false);
            VirtualPool cos = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
            ArgValidator.checkEntity(cos, volume.getVirtualPool(), false);
            CapacityUtils.validateQuotasForProvisioning(_dbClient, cos, project, tenant, size, "volume");
        }

        // Create a task for the volume expansion.
        String taskId = UUID.randomUUID().toString();
        Operation op = _dbClient.createTaskOpStatus(Volume.class, volume.getId(),
                taskId, ResourceOperationTypeEnum.EXPAND_BLOCK_VOLUME);

        // Try and expand the volume.
        try {
            blockServiceApi.expandVolume(volume, newSize, taskId);
        } catch (final ControllerException e) {
            _log.error("Controller Error", e);
            String errMsg = String.format("Controller Error: %s", e.getMessage());
            op = new Operation(Operation.Status.error.name(), errMsg);
            _dbClient.updateTaskOpStatus(Volume.class, id, taskId, op);
            throw e;
        }

        auditOp(OperationTypeEnum.EXPAND_BLOCK_VOLUME, true, AuditLogManager.AUDITOP_BEGIN,
                volume.getId().toString(), volume.getCapacity(), newSize);

        return toTask(volume, taskId, op);
    }

    /**
     * Request to test failover of the protection link associated with the param.copyID.
     *
     * NOTE: This is an asynchronous operation.
     *
     * If volume is srdf protected, then invoking failover-test ends in no-op.
     * failoverTest is being replaced by failover
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param param
     *            Copy to test failover on
     *
     * @brief Test volume protection link failover
     * @return TaskList
     *
     * @throws ControllerException
     *
     * @deprecated failoverTest is being replaced by failover.
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/failover-test")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    @Deprecated
    public TaskList failoverTest(@PathParam("id") URI id, CopiesParam param) throws ControllerException {
        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();
        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        boolean vplexVolume = checkIfVolumeIsForVplex(id);

        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");
        List<Copy> copies = param.getCopies();
        if (copies.size() != 1) {
            throw APIException.badRequests.failoverCopiesParamCanOnlyBeOne();
        }

        Copy copy = copies.get(0);
        if (vplexVolume && copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
            throw APIException.badRequests.actionNotApplicableForVplexVolumeMirrors(ProtectionOp.FAILOVER_TEST.getRestOp());
        }
        ArgValidator.checkFieldUriType(copy.getCopyID(), Volume.class, "id");
        ArgValidator.checkFieldNotEmpty(copy.getType(), "type");

        if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
            taskResp = performProtectionAction(id, copy, ProtectionOp.FAILOVER_TEST.getRestOp());
            taskList.getTaskList().add(taskResp);
        } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
            id = VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, id);
            copy.setCopyID(VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, copy.getCopyID()));
            taskResp = performSRDFProtectionAction(id, copy, ProtectionOp.FAILOVER_TEST.getRestOp());
            taskList.getTaskList().add(taskResp);
        } else {
            throw APIException.badRequests.invalidCopyType(copy.getType());
        }
        return taskList;
    }

    /**
     * Request to reverse the replication direction, i.e. R1 and R2 are interchanged..
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param param
     *            Copy to swap
     *
     * @brief reversing roles of source and target
     * @return TaskList
     *
     * @throws ControllerException
     *
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/swap")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList swap(@PathParam("id") URI id, CopiesParam param) throws ControllerException {
        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();
        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");
        List<Copy> copies = param.getCopies();
        if (copies.size() > 1) {
            throw APIException.badRequests.swapCopiesParamCanOnlyBeOne();
        }
        Copy copy = copies.get(0);
        ArgValidator.checkFieldUriType(copy.getCopyID(), Volume.class, "id");
        ArgValidator.checkFieldNotEmpty(copy.getType(), "type");

        if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
            taskResp = performProtectionAction(id, copy, ProtectionOp.SWAP.getRestOp());
            taskList.getTaskList().add(taskResp);
        } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
            id = VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, id);
            copy.setCopyID(VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, copy.getCopyID()));
            taskResp = performSRDFProtectionAction(id, copy, ProtectionOp.SWAP.getRestOp());
            taskList.getTaskList().add(taskResp);
        } else {
            throw APIException.badRequests.invalidCopyType(copy.getType());
        }
        return taskList;
    }

    /**
     * Request to cancel fail over on already failed over volumes.
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param param
     *            Copy to fail back
     *
     * @brief fail back to source again
     * @return TaskList
     *
     * @throws ControllerException
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/failover-cancel")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList failoverCancel(@PathParam("id") URI id, CopiesParam param) throws ControllerException {
        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();
        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");
        List<Copy> copies = param.getCopies();
        if (copies.size() > 1) {
            throw APIException.badRequests.failOverCancelCopiesParamCanOnlyBeOne();
        }
        Copy copy = copies.get(0);
        ArgValidator.checkFieldUriType(copy.getCopyID(), Volume.class, "id");
        ArgValidator.checkFieldNotEmpty(copy.getType(), "type");

        if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
            taskResp = performProtectionAction(id, copy, ProtectionOp.FAILOVER_CANCEL.getRestOp());
            taskList.getTaskList().add(taskResp);
        } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
            id = VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, id);
            copy.setCopyID(VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, copy.getCopyID()));
            taskResp = performSRDFProtectionAction(id, copy, ProtectionOp.FAILOVER_CANCEL.getRestOp());
            taskList.getTaskList().add(taskResp);
        } else {
            throw APIException.badRequests.invalidCopyType(copy.getType());
        }
        return taskList;
    }

    /**
     * Request to cancel a prior test failover of the protection link associated with the param.copyID.
     *
     * NOTE: This is an asynchronous operation.
     *
     * If volume is srdf protected, then its a no-op
     * <p>
     * This method is deprecated. Use /block/volumes/{id}/protection/continuous-copies/failover-cancel instead.
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param param
     *            Copy to cancel the failover to
     *
     * @brief Cancel volume protection link failover test
     * @return TaskList
     *
     * @throws ControllerException
     *
     * @deprecated failoverTestCancel is being replaced by failover-cancel.
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/failover-test-cancel")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    @Deprecated
    public TaskList failoverTestCancel(@PathParam("id") URI id, CopiesParam param) throws ControllerException {
        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();
        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        boolean vplexVolume = checkIfVolumeIsForVplex(id);

        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");
        List<Copy> copies = param.getCopies();
        if (copies.size() != 1) {
            throw APIException.badRequests.failoverCopiesParamCanOnlyBeOne();
        }
        Copy copy = copies.get(0);
        if (vplexVolume && copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
            throw APIException.badRequests.actionNotApplicableForVplexVolumeMirrors(ProtectionOp.FAILOVER_TEST_CANCEL.getRestOp());
        }

        ArgValidator.checkFieldUriType(copy.getCopyID(), Volume.class, "id");
        ArgValidator.checkFieldNotEmpty(copy.getType(), "type");
        if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
            taskResp = performProtectionAction(id, copy, ProtectionOp.FAILOVER_TEST_CANCEL.getRestOp());
            taskList.getTaskList().add(taskResp);
        } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
            id = VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, id);
            copy.setCopyID(VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, copy.getCopyID()));
            taskResp = performSRDFProtectionAction(id, copy, ProtectionOp.FAILOVER_TEST_CANCEL.getRestOp());
            taskList.getTaskList().add(taskResp);
        } else {
            throw APIException.badRequests.invalidCopyType(copy.getType());
        }
        return taskList;
    }

    /**
     *
     * Request to failover the protection link associated with the param.copyID.
     *
     * NOTE: This is an asynchronous operation.
     *
     * If volume is srdf protected, then invoking failover internally triggers
     * SRDF SWAP on volume pairs.
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param param
     *            Copy to failover to
     *
     * @brief Failover the volume protection link
     * @return TaskList
     *
     * @throws ControllerException
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/failover")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList failoverProtection(@PathParam("id") URI id, CopiesParam param) throws ControllerException {

        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();

        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        boolean vplexVolume = checkIfVolumeIsForVplex(id);

        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");

        List<Copy> copies = param.getCopies();

        if (copies.size() != 1) {
            throw APIException.badRequests.failoverCopiesParamCanOnlyBeOne();
        }

        Copy copy = copies.get(0);
        if (vplexVolume && copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
            throw APIException.badRequests.actionNotApplicableForVplexVolumeMirrors(ProtectionOp.FAILOVER.getRestOp());
        }

        ArgValidator.checkFieldNotEmpty(copy.getType(), "type");
        if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
            taskResp = performProtectionAction(id, copy, ProtectionOp.FAILOVER.getRestOp());
            taskList.getTaskList().add(taskResp);
        } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
            id = VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, id);
            copy.setCopyID(VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, copy.getCopyID()));
            taskResp = performSRDFProtectionAction(id, copy, ProtectionOp.FAILOVER.getRestOp());
            taskList.getTaskList().add(taskResp);
        } else {
            throw APIException.badRequests.invalidCopyType(copy.getType());
        }

        return taskList;
    }

    /**
     *
     * Sync continuous copies.
     *
     *
     * @prereq none
     *
     * @param id
     *            the URI of a ViPR Source volume
     * @param param
     *            List of copies to sync
     *
     * @brief Sync continuous copies.
     * @return TaskList
     *
     * @throws ControllerException
     *
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/sync")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList syncContinuousCopies(@PathParam("id") URI id, CopiesParam param)
            throws ControllerException {

        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();

        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");

        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");

        // Verify that the copy IDs are either all specified or none are specified
        // for a particular protection type. Combinations are not allowed
        verifyCopyIDs(param);

        // Process the list of copies
        for (Copy copy : param.getCopies()) {

            // Validate a copy type was passed
            ArgValidator.checkFieldNotEmpty(copy.getType(), "type");

            // If copyID is null all copies are paused
            if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
                // If copyID is not set all copies are sync'd
                URI copyID = copy.getCopyID();
                if (!URIUtil.isValid(copyID)) {
                    copyID = null;
                }
                taskResp = performProtectionAction(id, copy, ProtectionOp.SYNC.getRestOp());
                taskList.getTaskList().add(taskResp);
                // If copyID is null, we have already synced all copies
                if (copyID == null) {
                    return taskList;
                }
            } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
                id = VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, id);
                copy.setCopyID(VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, copy.getCopyID()));
                taskResp = performSRDFProtectionAction(id, copy, ProtectionOp.SYNC.getRestOp());
                taskList.getTaskList().add(taskResp);
            } else {
                throw APIException.badRequests.invalidCopyType(copy.getType());
            }
        }

        return taskList;
    }

    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/copymode")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList changeCopyMode(@PathParam("id") URI id, CopiesParam param)
            throws ControllerException {

        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();

        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");

        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");

        // Verify that the copy IDs are either all specified or none are specified
        // for a particular protection type. Combinations are not allowed
        verifyCopyIDs(param);

        // Process the list of copies
        for (Copy copy : param.getCopies()) {

            // Validate a copy type was passed
            ArgValidator.checkFieldNotEmpty(copy.getType(), "type");

            String copyMode = copy.getCopyMode();
            // Validate a copy mode was passed
            ArgValidator.checkFieldNotEmpty(copyMode, "copyMode");
            Volume volume = queryVolumeResource(id);
            ArgValidator.checkEntity(volume, id, true);

            if (volume.hasConsistencyGroup()) {
                if (TechnologyType.SRDF.name().equalsIgnoreCase(copy.getType())) {
                    id = VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, id);
                    copy.setCopyID(VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, copy.getCopyID()));

                    if (RemoteDirectorGroup.SupportedCopyModes.ASYNCHRONOUS.name().equalsIgnoreCase(copyMode)
                            || RemoteDirectorGroup.SupportedCopyModes.SYNCHRONOUS.name().equalsIgnoreCase(copyMode)
                            || RemoteDirectorGroup.SupportedCopyModes.ADAPTIVECOPY.name().equalsIgnoreCase(copyMode)) {
                        taskResp = performSRDFProtectionAction(id, copy, ProtectionOp.CHANGE_COPY_MODE.getRestOp());
                        taskList.getTaskList().add(taskResp);
                    } else {
                        throw APIException.badRequests.invalidSRDFCopyMode(copy.getType());
                    }

                } else {
                    throw APIException.badRequests.invalidCopyType(copy.getType());
                }
            } else {
                /**
                 * As of now ViPR supports change copy mode operations only for volumes with CG.
                 */
                throw APIException.badRequests.invalidSRDFCopyMode(volume.getNativeId());
            }

        }

        return taskList;
    }

    /**
     * Request to change the access mode on the provided copy.
     *
     * NOTE: This is an asynchronous operation.
     *
     * Currently only supported for RecoverPoint protected volumes. If volume is SRDF protected,
     * then we do nothing and return the task.
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param param
     *            Copy to change access mode on
     *
     * @brief Changes the access mode for a copy.
     * @return TaskList
     *
     * @throws ControllerException
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/accessmode")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList changeAccessMode(@PathParam("id") URI id, CopiesParam param) throws ControllerException {

        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();

        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        boolean vplexVolume = checkIfVolumeIsForVplex(id);

        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");

        List<Copy> copies = param.getCopies();

        if (copies.size() != 1) {
            // Change access mode operations can only be performed on a single copy
            throw APIException.badRequests.changeAccessCopiesParamCanOnlyBeOne();
        }

        Copy copy = copies.get(0);
        if (vplexVolume && copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
            throw APIException.badRequests.actionNotApplicableForVplexVolumeMirrors(ProtectionOp.CHANGE_ACCESS_MODE.getRestOp());
        }

        ArgValidator.checkFieldNotEmpty(copy.getType(), "type");
        ArgValidator.checkFieldNotEmpty(copy.getAccessMode(), "accessMode");
        if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
            taskResp = performProtectionAction(id, copy, ProtectionOp.CHANGE_ACCESS_MODE.getRestOp());
            taskList.getTaskList().add(taskResp);
        } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
            _log.warn("Changing access mode is currently not supported for SRDF.  Returning empty task list (no-op).");
            return taskList;
        } else {
            throw APIException.badRequests.invalidCopyType(copy.getType());
        }

        return taskList;
    }

    /**
     * Get the details of a specific volume
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR volume to query
     *
     * @brief Show volume
     * @return Volume details
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public VolumeRestRep getVolume(@PathParam("id") URI id) {
        // TODO queryResource permits queries for Volume and BlockMirror types - is this validation too restrictive?
        Class<? extends DataObject> type = Volume.class;
        if (URIUtil.isType(id, BlockMirror.class)) {
            type = BlockMirror.class;
        }
        ArgValidator.checkFieldUriType(id, type, "id");
        Volume volume = queryVolumeResource(id);
        return map(_dbClient, volume);
    }

    /**
     * Get the storage pool of a specific volume
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR volume to query
     *
     * @brief Show volume storage pool
     * @return Storage pool details
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/storage-pool")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.SYSTEM_ADMIN })
    public RelatedStoragePool getStoragePool(@PathParam("id") URI id) {
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        Volume volume = queryVolumeResource(id);
        StoragePool pool = _dbClient.queryObject(StoragePool.class, volume.getPool());
        if (pool != null) {
            return new RelatedStoragePool(
                    toNamedRelatedResource(ResourceTypeEnum.STORAGE_POOL,
                            volume.getPool(), pool.getPoolName()));
        } else {
            // the related storage pool may be null in the case of a virtual volume
            // so, return an empty related resource
            return RelatedStoragePool.EMPTY;
        }
    }

    @Override
    protected DataObject queryResource(URI id) {
        ArgValidator.checkUri(id);
        Class<? extends DataObject> blockClazz = Volume.class;

        if (URIUtil.isType(id, BlockMirror.class)) {
            blockClazz = BlockMirror.class;
        }
        if (URIUtil.isType(id, VplexMirror.class)) {
            blockClazz = VplexMirror.class;
        }
        DataObject dataObject = _permissionsHelper.getObjectById(id, blockClazz);
        ArgValidator.checkEntityNotNull(dataObject, id, isIdEmbeddedInURL(id));
        return dataObject;
    }

    private Volume queryVolumeResource(URI id) {
        return (Volume) queryResource(id);
    }

    @Override
    protected URI getTenantOwner(URI id) {
        Volume volume = (Volume) queryResource(id);
        return volume.getTenant().getURI();
    }

    /**
     * Return all the export information related to this volume.
     * This will be in the form of a list of initiator / target pairs
     * for all the initiators that have been paired with a target
     * storage port.
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Volume
     *
     * @brief Show export information for volume
     * @return List of exports
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/exports")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public ITLRestRepList getVolumeExports(@PathParam("id") URI id) {
        queryResource(id);
        return ExportUtils.getBlockObjectInitiatorTargets(id, _dbClient, isIdEmbeddedInURL(id));
    }

    /**
     * Deactivate a volume, this will move the volume to a "marked-for-delete"
     * state after the deletion happens on the array side. The volume will be
     * deleted from the database when all references to this volume of type
     * BlockSnapshot and ExportGroup are deleted.
     *
     * If "?force=true" is added to the path, it will force the delete of internal
     * volumes that have the SUPPORTS_FORCE flag.
     *
     * NOTE: This is an asynchronous operation.
     *
     *
     * @prereq Dependent volume resources such as snapshots and export groups must be deleted
     *
     * @param id
     *            the URN of a ViPR volume to delete
     *
     * @brief Delete volume
     * @return Volume information
     *
     * @throws InternalException
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/deactivate")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskResourceRep deleteVolume(@PathParam("id") URI id,
            @DefaultValue("false") @QueryParam("force") boolean force,
            @DefaultValue("FULL") @QueryParam("type") String type)
            throws InternalException {
        // Reuse implementation for deleting multiple volumes.
        BulkDeleteParam deleteParam = new BulkDeleteParam();
        deleteParam.setIds(Lists.newArrayList(id));
        TaskList taskList = deleteVolumes(deleteParam, force, type);
        return taskList.getTaskList().get(0);
    }

    /**
     * This API allows the user to deactivate multiple volumes in a single request.
     * There is no restriction on the the volumes specified in the request. The volumes
     * can reside in multiple storage pools on multiple storage systems. The
     * response will contain a task resource for each volume to be
     * deactivated. The volumes will be deleted from the database when
     * all references to the volumes of type BlockSnapshot and
     * ExportGroup are deleted.
     *
     * If "?force=true" is added to the path, it will force the delete of internal
     * volumes that have the SUPPORTS_FORCE flag.
     *
     * NOTE: This is an asynchronous operation.
     *
     *
     * @prereq Dependent volume resources such as snapshots and export groups must be deleted
     *
     * @param volumeURIs
     *            The POST data specifying the ids of the volume(s) to be
     *            deleted.
     *
     * @brief Delete multiple volumes
     * @return A reference to a BlockTaskList containing a list of
     *         TaskResourceRep instances specifying the task data for each
     *         volume delete task.
     * @throws InternalException
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/deactivate")
    public TaskList deleteVolumes(BulkDeleteParam volumeURIs,
            @DefaultValue("false") @QueryParam("force") boolean force,
            @DefaultValue("FULL") @QueryParam("type") String type) throws InternalException {
        // Verify the some volumes were passed in the request.
        BlockService.checkVolumesParameter(volumeURIs);

        // For volume operations, user need to has TENANT_ADMIN role or proper ACLs etc.
        StorageOSUser user = getUserFromContext();
        Iterator<Volume> dbVolumeIter = _dbClient.queryIterativeObjects(getResourceClass(), volumeURIs.getIds());
        Set<URI> tenantSet = new HashSet<>();
        List<Volume> volumes = new ArrayList<Volume>();
        while (dbVolumeIter.hasNext()) {
            Volume vol = dbVolumeIter.next();

            // Don't operate on VPLEX backend or RP Journal volumes (unless forced to).
            BlockServiceUtils.validateNotAnInternalBlockObject(vol, force);

            if (!_permissionsHelper.userHasGivenRole(user, vol.getTenant().getURI(), Role.TENANT_ADMIN)
                    && !_permissionsHelper.userHasGivenACL(user, vol.getProject().getURI(), ACL.OWN, ACL.ALL)) {
                throw APIException.forbidden.insufficientPermissionsForUser(user.getName());
            }

            tenantSet.add(vol.getTenant().getURI());
            volumes.add(vol);
        }

        // Make sure that we don't have some pending
        // operation against the volume
        if (!force) {
            checkForPendingTasks(tenantSet, volumes);
        }

        // Volumes on different storage systems need to be deleted with
        // separate, individual calls to the controller. Therefore, we
        // need to map the volumes passed to the storage systems on
        // which they reside.
        Map<URI, List<URI>> systemVolumesMap = new HashMap<URI, List<URI>>();

        // We will create a task resource response for each volume to
        // be deleted and initialize it to the pending state. If there
        // is a controller error deleting the volumes on a given storage
        // system, we need to update the responses associated with these
        // volumes to specify an error state.
        Map<URI, List<TaskResourceRep>> systemTaskResourceRepsMap = new HashMap<URI, List<TaskResourceRep>>();

        // We create a global task list containing the task resource response
        // for all volumes, which will be returned as the request response.
        TaskList taskList = new TaskList();

        // CTRL-8839
        // Initial validations for the volumes. We want to make sure the basic validations
        // are done before we start creating any Tasks. Otherwise, we will get Tasks that
        // seem to be "stuck".
        for (Volume volume : volumes) {
            URI volumeURI = volume.getId();

            ArgValidator.checkEntity(volume, volumeURI, isIdEmbeddedInURL(volumeURI));
            BlockServiceApi blockServiceApi = getBlockServiceImpl(volume);

            /**
             * Delete volume api call will delete the replica objects as part of volume delete call for vmax using SMI
             * 8.0.3.
             * Hence we don't require reference check for vmax.
             */
            if (!volume.isInCG() || !BlockServiceUtils.checkCGVolumeCanBeAddedOrRemoved(null, volume, _dbClient)) {
                List<Class<? extends DataObject>> excludeTypes = null;
                if (VolumeDeleteTypeEnum.VIPR_ONLY.name().equals(type)) {
                    // For ViPR-only delete of exported volumes, We will clean up any
                    // export groups/masks if the snapshot is exported.
                    excludeTypes = new ArrayList<>();
                    excludeTypes.add(ExportGroup.class);
                    excludeTypes.add(ExportMask.class);
                }
                ArgValidator.checkReference(Volume.class, volumeURI, blockServiceApi.checkForDelete(volume, excludeTypes));
            }

            // For a volume that is a full copy or is the source volume for
            // full copies deleting the volume may not be allowed.
            if ((!VolumeDeleteTypeEnum.VIPR_ONLY.name().equals(type)) && (!getFullCopyManager().volumeCanBeDeleted(volume))) {
                throw APIException.badRequests.cantDeleteFullCopyNotDetached(volume.getLabel());
            }
        }

        // since we issue one controller request per storage system, we must give each storage system
        // a separate task id. Otherwise, we will create multiple workflows with the same task id
        // which is not allowed.
        // this maps task ids to their storage systems
        Map<URI, String> systemURITaskIdMap = new HashMap<URI, String>();

        // Now loop over the volumes, initializing the above constructs.
        for (Volume volume : volumes) {
            URI volumeURI = volume.getId();

            // If the volume has active associated volumes, try to deactivate regardless
            // of native ID or inactive state. This basically means it's a VPLEX volume.
            boolean forceDeactivate = checkIfVplexVolumeHasActiveAssociatedVolumes(volume);

            if (forceDeactivate || (!Strings.isNullOrEmpty(volume.getNativeId()) && !volume.getInactive())) {

                URI systemURI = null;
                if (!isNullURI(volume.getProtectionController())) {
                    systemURI = volume.getProtectionController();
                } else {
                    systemURI = volume.getStorageController();
                }

                if (systemURITaskIdMap.get(systemURI) == null) {
                    systemURITaskIdMap.put(systemURI, UUID.randomUUID().toString());
                }
                String task = systemURITaskIdMap.get(systemURI);

                // Create a task resource response for this volume and
                // set the initial task state to pending.
                // Initialize volume delete task status.
                Operation op = _dbClient.createTaskOpStatus(Volume.class, volume.getId(),
                        task, ResourceOperationTypeEnum.DELETE_BLOCK_VOLUME);
                TaskResourceRep volumeTaskResourceRep = toTask(volume, task, op);

                List<URI> systemVolumeURIs = systemVolumesMap.get(systemURI);
                if (systemVolumeURIs == null) {
                    // Create a list to hold the volumes for the
                    // system, add the volume to the list, and put
                    // the list in the system volumes map.
                    systemVolumeURIs = new ArrayList<URI>();
                    systemVolumeURIs.add(volumeURI);
                    systemVolumesMap.put(systemURI, systemVolumeURIs);

                    // Build a list to hold the task resource responses for
                    // the system. Create a task resource response for
                    // this volume and add it to the list for the system.
                    // Put the list for the system into the map.
                    List<TaskResourceRep> systemTaskResourceReps = new ArrayList<TaskResourceRep>();
                    systemTaskResourceReps.add(volumeTaskResourceRep);
                    systemTaskResourceRepsMap.put(systemURI, systemTaskResourceReps);
                } else if (!systemVolumeURIs.contains(volumeURI)) {
                    // Add the volume to the system's volume list if it has
                    // not already been added. Duplicates are just ignored.
                    systemVolumeURIs.add(volumeURI);
                    List<TaskResourceRep> systemTaskResourceReps = systemTaskResourceRepsMap
                            .get(systemURI);
                    systemTaskResourceReps.add(volumeTaskResourceRep);
                }

                // Add the task resource response for the volume to the global list
                // to be returned.
                taskList.getTaskList().add(volumeTaskResourceRep);
            } else if (!volume.getInactive()) {
                // somehow no nativeId is set on volume, but it was active. Set it to not active
                volume.setInactive(true);
                _dbClient.persistObject(volume);
            }

        }

        // Try and delete the volumes on each system.
        Iterator<URI> systemsURIIter = systemVolumesMap.keySet().iterator();
        while (systemsURIIter.hasNext()) {
            URI systemURI = systemsURIIter.next();
            String task = systemURITaskIdMap.get(systemURI);
            try {
                List<URI> systemVolumes = systemVolumesMap.get(systemURI);
                BlockServiceApi blockServiceApi = getBlockServiceImpl(queryVolumeResource(systemVolumes.get(0)));
                blockServiceApi.deleteVolumes(systemURI, systemVolumes, type, task);
            } catch (APIException | InternalException e) {
                if (_log.isErrorEnabled()) {
                    _log.error("Delete error", e);
                }

                List<TaskResourceRep> systemTaskResourceReps = systemTaskResourceRepsMap.get(systemURI);
                for (TaskResourceRep volumeTask : systemTaskResourceReps) {
                    volumeTask.setState(Operation.Status.error.name());
                    volumeTask.setMessage(e.getMessage());
                    _dbClient.updateTaskOpStatus(Volume.class, volumeTask
                            .getResource().getId(), task, new Operation(
                            Operation.Status.error.name(), e.getMessage()));
                }
            }
        }

        auditOp(OperationTypeEnum.DELETE_BLOCK_VOLUME, true, AuditLogManager.AUDITOP_MULTI_BEGIN);

        return taskList;
    }

    /**
     * This method is used during delete volume to check if a volume has active associated
     * volumes with nativeId.
     *
     * TODO : This method can be moved to some utility class post 2.0, once we figure out
     * which class is suitable for this.
     *
     * @param volume
     *            A reference to the volume.
     * @return true if volume has active associated volumes with nativeId else returns false
     */
    private boolean checkIfVplexVolumeHasActiveAssociatedVolumes(Volume volume) {
        boolean activeAssociatedVolumes = false;
        if (volume != null && volume.getAssociatedVolumes() != null) {
            for (String associatedVolumeUri : volume.getAssociatedVolumes()) {
                Volume associatedVolume = _dbClient.queryObject(Volume.class, URI.create(associatedVolumeUri));
                if (associatedVolume != null && !associatedVolume.getInactive()) {
                    _log.warn("volume {} has active associated volume {}",
                            volume.getLabel(), associatedVolume.getLabel());
                    if (associatedVolume.getNativeId() == null) {
                        _log.warn("associated volume with Id {} has no native Id. Native Id is {}. So mark this volume for deletion. ",
                                associatedVolume.getId(), associatedVolume.getNativeId());
                        _dbClient.markForDeletion(associatedVolume);
                    } else {
                        activeAssociatedVolumes = true;
                    }
                }
            }
        }
        return activeAssociatedVolumes;
    }

    /**
     * A snapshot is a point-in-time copy of a volume. Snapshots are intended for short-term
     * operational recovery and are typically implemented using lightweight, fast capabilities
     * native to the underlying storage platforms.
     * Like a volume, a snapshot can be exported to initiators, and you can delete it.
     * A snapshots lifetime is tied to the original volume. When the original volume is deleted
     * all of its snapshots will also be deleted.
     * A snapshot is associated with the same project as the original volume.
     * A volume may be restored in place based on a snapshot. The snapshot must have come from the volume.
     * A new volume may be created using a snapshot as a template.
     *
     * See multi-volume consistent snapshots for a description of an advanced feature to snapshot multiple volumes at
     * once.
     *
     * NOTE: This is an asynchronous operation.
     *
     *
     * @prereq Virtual pool must specify non-zero value for max_snapshots
     *
     * @param id
     *            the URN of a ViPR Volume to snapshot
     * @param param
     *            Volume snapshot parameters
     *
     * @brief Create volume snapshot
     * @return List of snapshots information
     *
     * @throws InternalException
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshots")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public TaskList createSnapshot(@PathParam("id") URI id, VolumeSnapshotParam param) throws InternalException {

        // Validate and get the volume being snapped.
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        Volume requestedVolume = queryVolumeResource(id);

        VolumeIngestionUtil.checkOperationSupportedOnIngestedVolume(requestedVolume,
                ResourceOperationTypeEnum.CREATE_VOLUME_SNAPSHOT, _dbClient);

        // Don't operate on VPLEX backend volumes or RP journal volumes.
        BlockServiceUtils.validateNotAnInternalBlockObject(requestedVolume, false);

        // Set default type, if not set at all.
        if (param.getType() == null) {
            param.setType(TechnologyType.NATIVE.toString());
        }
        String snapshotType = param.getType();

        validateSourceVolumeHasExported(requestedVolume);

        // Make sure that we don't have some pending
        // operation against the volume
        checkForPendingTasks(Arrays.asList(requestedVolume.getTenant().getURI()), Arrays.asList(requestedVolume));

        // validate the volume is not part of a RP or VPlex CG that is part of an application
        validateCGIsNotInApplication(requestedVolume, snapshotType);

        // Set whether or not the snapshot be activated when created.
        Boolean createInactive = Boolean.FALSE;
        if (param.getCreateInactive() != null) {
            createInactive = param.getCreateInactive();
        }

        // Set whether the snapshot should be read only
        Boolean readOnly = Boolean.FALSE;
        if (param.getReadOnly() != null) {
            readOnly = param.getReadOnly();
        }
        // Get the block service implementation for the volume. The manner
        // in which snapshots are created an initialized can be different
        // based on the volume being snapped.
        BlockServiceApi blockServiceApiImpl = getBlockServiceImpl(requestedVolume);

        // Determine the list of volumes to be snapped. If the volume
        // is in a consistency group, a snapshot will be created for
        // each volume in the CG.
        List<Volume> volumesToSnap = new ArrayList<Volume>();
        volumesToSnap.addAll(blockServiceApiImpl.getVolumesToSnap(requestedVolume, snapshotType));

        // Validate the snapshot creation request parameters for the volume(s)
        // to be snapped.
        String snapshotNamePattern = param.getName();
        String snapshotName = TimeUtils.formatDateForCurrent(snapshotNamePattern);
        blockServiceApiImpl.validateCreateSnapshot(requestedVolume, volumesToSnap,
                snapshotType, snapshotName, getFullCopyManager());

        // Create the snapshots for the volume(s) being snapped and
        // initialize the task list.
        String taskId = UUID.randomUUID().toString();
        List<URI> snapshotURIs = new ArrayList<URI>();

        List<BlockSnapshot> snapshots = blockServiceApiImpl.prepareSnapshots(
                volumesToSnap, snapshotType, snapshotName, snapshotURIs, taskId);

        TaskList response = new TaskList();
        for (BlockSnapshot snapshot : snapshots) {
            response.getTaskList().add(toTask(snapshot, taskId));
        }

        // Update the task status for the volumes task.
        _dbClient.createTaskOpStatus(Volume.class, requestedVolume.getId(),
                taskId, ResourceOperationTypeEnum.CREATE_VOLUME_SNAPSHOT);

        // Invoke the block service API implementation to create the snapshot
        blockServiceApiImpl.createSnapshot(requestedVolume, snapshotURIs, snapshotType,
                createInactive, readOnly, taskId);

        // Record a message in the audit log.
        auditOp(OperationTypeEnum.CREATE_VOLUME_SNAPSHOT, true,
                AuditLogManager.AUDITOP_BEGIN, snapshotName, requestedVolume.getId()
                        .toString());

        return response;
    }

    /**
     * validates that the volume is not part of a RP or VPlex CG that is part of an application
     *
     * @param requestedVolume
     * @param snapshotType
     *            indicates if this is an array snapshot or RP bookmark request
     */
    private void validateCGIsNotInApplication(Volume requestedVolume, String snapshotType) {
        // validation should only apply to non-RP snapshots
        if (TechnologyType.RP.toString().equalsIgnoreCase(snapshotType)) {
            return;
        }
        if (NullColumnValueGetter.isNotNullValue(requestedVolume.getReplicationGroupInstance())
                && (VPlexUtil.isVplexVolume(requestedVolume, _dbClient)
                || NullColumnValueGetter.isNullURI(requestedVolume.getProtectionController()))) {
            VolumeGroup application = requestedVolume.getApplication(_dbClient);
            if (application != null) {
                throw APIException.badRequests.cannotCreateSnapshotCgPartOfApplication(application.getLabel());
            }
        }
    }

    /**
     * Create an array snapshot of the volume with the passed Id. Creating a
     * snapshot session simply creates and array snapshot point-in-time copy
     * of the volume. It does not automatically create a single target volume
     * and link it to the array snapshot as is done with the existing create
     * snapshot API. It allows array snapshots to be created with out any linked
     * target volumes, or multiple linked target volumes depending on the
     * data passed in the request. This API is only supported on a limited
     * number of platforms that support this capability.
     *
     * @brief Create volume snapshot session
     *
     * @prereq Virtual pool for the volume must specify non-zero value for max_snapshots
     *
     * @param id
     *            The URI of a ViPR Volume.
     * @param param
     *            Volume snapshot parameters
     *
     * @return TaskList
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshot-sessions")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public TaskList createSnapshotSession(@PathParam("id") URI id, SnapshotSessionCreateParam param) {
        return getSnapshotSessionManager().createSnapshotSession(id, param, getFullCopyManager());
    }

    /**
     * List volume snapshots
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Volume to list snapshots
     *
     * @brief List volume snapshots
     * @return Volume snapshot response containing list of snapshot identifiers
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshots")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public SnapshotList getSnapshots(@PathParam("id") URI id) {
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        Volume requestedVolume = queryVolumeResource(id);

        // Get the block service implementation for the volume.
        BlockServiceApi blockServiceApiImpl = getBlockServiceImpl(requestedVolume);

        // Get and return the snapshots.
        List<BlockSnapshot> snapList = blockServiceApiImpl.getSnapshots(requestedVolume);
        SnapshotList list = new SnapshotList();
        list.setSnapList(new ArrayList<NamedRelatedResourceRep>());
        List<NamedRelatedResourceRep> activeSnaps = new ArrayList<NamedRelatedResourceRep>();
        List<NamedRelatedResourceRep> inactiveSnaps = new ArrayList<NamedRelatedResourceRep>();
        for (BlockSnapshot snap : snapList) {
            if (snap.getInactive()) {
                inactiveSnaps.add(toNamedRelatedResource(snap));
            } else {
                activeSnaps.add(toNamedRelatedResource(snap));
            }
        }
        list.getSnapList().addAll(activeSnaps);
        list.getSnapList().addAll(inactiveSnaps);
        return list;
    }

    /**
     * List volume snapshot sessions.
     *
     * @brief List volume snapshot sessions.
     *
     * @prereq none
     *
     * @param id
     *            The URI of a ViPR Volume.
     *
     * @return Volume snapshot response containing list of snapshot sessions
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshot-sessions")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public BlockSnapshotSessionList getSnapshotSessions(@PathParam("id") URI id) {
        return getSnapshotSessionManager().getSnapshotSessionsForSource(id);
    }

    /**
     * List volume mirrors
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Volume to list mirrors
     *
     * @brief List volume mirrors
     * @return Volume mirror response containing a list of mirror identifiers
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public MirrorList getNativeContinuousCopies(@PathParam("id") URI id) {
        MirrorList list = new MirrorList();
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        Volume sourceVolume = queryVolumeResource(id);
        boolean vplexVolume = checkIfVolumeIsForVplex(id);

        StringSet sourceVolumeMirrors = sourceVolume.getMirrors();

        if (sourceVolumeMirrors == null || sourceVolumeMirrors.isEmpty()) {
            return list;
        }

        for (String uriStr : sourceVolumeMirrors) {
            if (vplexVolume) {
                VplexMirror vplexMirror = _dbClient.queryObject(VplexMirror.class, URI.create(uriStr));

                if (vplexMirror == null || vplexMirror.getInactive()) {
                    _log.warn("Stale mirror {} found for volume {}", uriStr, sourceVolume.getId());
                    continue;
                }
                list.getMirrorList().add(toNamedRelatedResource(vplexMirror));
            } else {
                BlockMirror blockMirror = _dbClient.queryObject(BlockMirror.class, URI.create(uriStr));

                if (blockMirror == null || blockMirror.getInactive()) {
                    _log.warn("Stale mirror {} found for volume {}", uriStr, sourceVolume.getId());
                    continue;
                }
                list.getMirrorList().add(toNamedRelatedResource(blockMirror));
            }
        }

        return list;
    }

    /**
     * Show details for a specific continuous copy
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param mid
     *            Continuous copy URI
     *
     * @brief Show continuous copy
     * @return BlockMirrorRestRep
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/{mid}")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public BlockMirrorRestRep getMirror(@PathParam("id") URI id, @PathParam("mid") URI mid) {
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        boolean vplexVolume = checkIfVolumeIsForVplex(id);
        BlockMirrorRestRep mirrorRestRep = null;
        if (vplexVolume) {
            ArgValidator.checkFieldUriType(mid, VplexMirror.class, "mid");
            VplexMirror mirror = queryVplexMirror(mid);
            if (!mirror.getSource().getURI().equals(id)) {
                throw APIException.badRequests.invalidParameterVolumeMirrorMismatch(mid, id);
            }
            mirrorRestRep = map(mirror);
        } else {
            queryResource(id);
            ArgValidator.checkFieldUriType(mid, BlockMirror.class, "mid");
            BlockMirror mirror = queryMirror(mid);

            if (!mirror.getSource().getURI().equals(id)) {
                throw APIException.badRequests.invalidParameterVolumeMirrorMismatch(mid, id);
            }
            mirrorRestRep = map(_dbClient, mirror);
        }
        return mirrorRestRep;
    }

    /**
     * Returns a list of the full copy volume references associated with a given volume.
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR source volume from which to retrieve associated full copies
     *
     * @brief List full copies
     * @return full copy volume response containing a list of full copy identifiers
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public NamedVolumesList getFullCopies(@PathParam("id") URI id) {
        return getFullCopyManager().getFullCopiesForSource(id);
    }

    /**
     * Pause continuous copies for given source volume
     *
     * NOTE: This is an asynchronous operation.
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param param
     *            List of copies to pause
     *
     * @brief Pause continuous copies
     * @return TaskList
     *
     * @throws ControllerException
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/pause")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList pauseContinuousCopies(@PathParam("id") URI id, CopiesParam param) throws ControllerException {

        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();

        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        boolean vplexVolume = checkIfVolumeIsForVplex(id);

        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");

        // Verify that the copy IDs are either all specified or none are specified
        // for a particular protection type. Combinations are not allowed
        verifyCopyIDs(param);

        // Process the list of copies
        for (Copy copy : param.getCopies()) {

            // If copyID is not set all copies are paused
            URI copyID = copy.getCopyID();
            if (!URIUtil.isValid(copyID)) {
                copyID = null;
            }

            // Validate a copy type was passed
            ArgValidator.checkFieldNotEmpty(copy.getType(), "type");

            if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
                taskResp = performProtectionAction(id, copy, ProtectionOp.PAUSE.getRestOp());
                taskList.getTaskList().add(taskResp);
            } else if (!vplexVolume && copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
                TaskList pauseTaskList = pauseMirrors(id, copy.getSync(), copyID);
                taskList.getTaskList().addAll(pauseTaskList.getTaskList());
            } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
                id = VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, id);
                copy.setCopyID(VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, copy.getCopyID()));
                taskResp = performSRDFProtectionAction(id, copy, ProtectionOp.PAUSE.getRestOp());
                taskList.getTaskList().add(taskResp);
            } else if (vplexVolume && copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
                throw APIException.badRequests.actionNotApplicableForVplexVolumeMirrors(ProtectionOp.PAUSE.getRestOp());
            } else {
                throw APIException.badRequests.invalidCopyType(copy.getType());
            }

            // If copyID is null, we have already paused all copies
            if (copyID == null) {
                return taskList;
            }
        }

        return taskList;
    }

    /**
     * Resume continuous copies for given source volume
     *
     * NOTE: This is an asynchronous operation.
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param param
     *            List of copies to resume
     *
     * @brief Resume continuous copies
     * @return TaskList
     *
     * @throws ControllerException
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/resume")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList resumeContinuousCopies(@PathParam("id") URI id, CopiesParam param)
            throws ControllerException {

        TaskResourceRep taskResp = null;
        TaskList taskList = new TaskList();

        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        boolean vplexVolume = checkIfVolumeIsForVplex(id);

        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");

        // Verify that the copy IDs are either all specified or none are specified
        // for a particular protection type. Combinations are not allowed
        verifyCopyIDs(param);

        // Process the list of copies
        for (Copy copy : param.getCopies()) {

            // If copyID is not set all copies are resumed
            URI copyID = copy.getCopyID();
            if (!URIUtil.isValid(copyID)) {
                copyID = null;
            }

            // Validate a copy type was passed
            ArgValidator.checkFieldNotEmpty(copy.getType(), "type");

            // If copyID is null all copies are paused
            if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
                taskResp = performProtectionAction(id, copy, ProtectionOp.RESUME.getRestOp());
                taskList.getTaskList().add(taskResp);
            } else if (!vplexVolume && copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
                TaskList resumeTaskList = resumeMirrors(id, copyID);
                taskList.getTaskList().addAll(resumeTaskList.getTaskList());
            } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
                id = VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, id);
                copy.setCopyID(VPlexSrdfUtil.getSrdfIdFromVolumeId(_dbClient, copy.getCopyID()));
                taskResp = performSRDFProtectionAction(id, copy, ProtectionOp.RESUME.getRestOp());
                taskList.getTaskList().add(taskResp);
            } else if (vplexVolume && copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
                throw APIException.badRequests.actionNotApplicableForVplexVolumeMirrors(ProtectionOp.RESUME.getRestOp());
            } else {
                throw APIException.badRequests.invalidCopyType(copy.getType());
            }

            // If copyID is null, we have already resumed all copies
            if (copyID == null) {
                return taskList;
            }
        }

        return taskList;
    }

    /**
     * Deactivate continuous copies for given source volume
     *
     * NOTE: This is an asynchronous operation.
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param param
     *            List of copies to deactivate
     * @param deleteType
     *            the type of deletion
     *
     * @brief Delete continuous copies
     * @return TaskList
     *
     * @throws ControllerException
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/continuous-copies/deactivate")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList deactivateMirror(@PathParam("id") URI id, CopiesParam param,
            @DefaultValue("FULL") @QueryParam("type") String deleteType) throws ControllerException {

        TaskList taskList = new TaskList();

        // Validate the source volume URI
        ArgValidator.checkFieldUriType(id, Volume.class, "id");

        Volume volume = _dbClient.queryObject(Volume.class, id);

        // Make sure that we don't have some pending
        // operation against the volume
        checkForPendingTasks(Arrays.asList(volume.getTenant().getURI()), Arrays.asList(volume));

        // Validate the list of copies
        ArgValidator.checkFieldNotEmpty(param.getCopies(), "copies");

        boolean vplexVolume = checkIfVolumeIsForVplex(id);

        // Process the list of copies
        for (Copy copy : param.getCopies()) {

            // Validate the copy ID
            URI copyID = copy.getCopyID();
            ArgValidator.checkUri(copyID);

            // Validate a copy type was passed
            ArgValidator.checkFieldNotEmpty(copy.getType(), "type");

            if (TechnologyType.NATIVE.toString().equalsIgnoreCase(copy.getType())) {

                String task = UUID.randomUUID().toString();

                StorageSystem device;
                String mirrorLabel;
                URI mirrorURI;
                BlockServiceApi blockServiceApi;
                if (vplexVolume) {
                    VplexMirror mirror = queryVplexMirror(copyID);
                    ArgValidator.checkEntity(mirror, mirror.getId(), isIdEmbeddedInURL(copyID));
                    if (!mirror.getSource().getURI().equals(id)) {
                        throw APIException.badRequests.mirrorDoesNotBelongToVolume(copyID, id);
                    }
                    mirrorLabel = mirror.getLabel();
                    mirrorURI = mirror.getId();
                    device = _dbClient.queryObject(StorageSystem.class, mirror.getStorageController());
                    blockServiceApi = getBlockServiceImpl(DiscoveredDataObject.Type.vplex.name());
                } else {
                    BlockMirror mirror = queryMirror(copyID);
                    ArgValidator.checkEntity(mirror, mirror.getId(), isIdEmbeddedInURL(copyID));
                    if (!mirror.getSource().getURI().equals(id)) {
                        throw APIException.badRequests.mirrorDoesNotBelongToVolume(copyID, id);
                    }
                    mirrorLabel = mirror.getLabel();
                    mirrorURI = mirror.getId();
                    device = _dbClient.queryObject(StorageSystem.class, mirror.getStorageController());
                    blockServiceApi = getBlockServiceImpl("mirror");
                }

                // Deactivate the mirror
                TaskList deactivateTaskList = blockServiceApi.deactivateMirror(device, mirrorURI, task, deleteType);

                // Create the audit log message
                String opStage = VolumeDeleteTypeEnum.VIPR_ONLY.name().equals(deleteType) ? null : AuditLogManager.AUDITOP_BEGIN;
                boolean opStatus = true;
                for (TaskResourceRep resultTask : deactivateTaskList.getTaskList()) {
                    if (Operation.Status.error.name().equals(resultTask.getState())) {
                        opStatus = false;
                        break;
                    }
                }
                auditOp(OperationTypeEnum.DEACTIVATE_VOLUME_MIRROR, opStatus, opStage, copyID.toString(), mirrorLabel);

                // Add tasks for this copy
                taskList.getTaskList().addAll(deactivateTaskList.getTaskList());

            } else {
                throw APIException.badRequests.invalidCopyType(copy.getType());
            }
        }

        return taskList;
    }

    /**
     * perform SRDF Protection APIs
     *
     * @param id
     *            the URN of a ViPR volume associated
     * @param copy
     * @param op
     * @return
     * @throws InternalException
     */
    private TaskResourceRep performSRDFProtectionAction(URI id, Copy copy, String op)
            throws InternalException {
        URI copyID = copy.getCopyID();
        ArgValidator.checkFieldUriType(copyID, Volume.class, "copyID");
        // Get the volume associated with the URI
        Volume volume = queryVolumeResource(id);
        Volume copyVolume = null;
        if (null == copyID) {
            copyVolume = volume;
        } else {
            copyVolume = queryVolumeResource(copyID);
        }

        ArgValidator.checkEntity(volume, id, true);
        ArgValidator.checkEntity(copyVolume, copyID, true);

        // check if the passed in target volume is indeed the target of the
        // passed in source volume
        if (!copyVolume.getSrdfParent().getURI().equals(id)
                && !copyVolume.getId().equals(id)) {
            throw APIException.badRequests.protectionVolumeInvalidTargetOfVolume(copyID, id);
        }

        // Make sure that we don't have some pending
        // operation against the volume
        checkForPendingTasks(Arrays.asList(volume.getTenant().getURI()), Arrays.asList(volume));

        String task = UUID.randomUUID().toString();
        Operation status = new Operation();
        status.setResourceType(ProtectionOp.getResourceOperationTypeEnum(op));
        _dbClient.createTaskOpStatus(Volume.class, volume.getId(), task, status);

        if (Volume.isSRDFProtectedVolume(copyVolume)) {

            if (op.equalsIgnoreCase(ProtectionOp.FAILOVER_TEST_CANCEL.getRestOp()) ||
                    op.equalsIgnoreCase(ProtectionOp.FAILOVER_TEST.getRestOp())) {
                _dbClient.ready(Volume.class, volume.getId(), task);
                return toTask(volume, task, status);
            }

            if (PersonalityTypes.SOURCE.name().equalsIgnoreCase(
                    copyVolume.getPersonality())) {
                if (op.equalsIgnoreCase(ProtectionOp.FAILOVER_CANCEL.getRestOp()) || op.equalsIgnoreCase(ProtectionOp.FAILOVER.getRestOp())
                        || op.equalsIgnoreCase(ProtectionOp.SWAP.getRestOp())) {
                    throw new ServiceCodeException(
                            ServiceCode.IO_ERROR,
                            "Expected SRDF Target R2 volume, instead R1 {0} is being passed, hence cannot proceed with failover or failback.",
                            new Object[] { copyVolume.getNativeGuid() });
                } else if (copyVolume.getSrdfTargets() == null
                        || copyVolume.getSrdfTargets().isEmpty()) {
                    throw new ServiceCodeException(
                            ServiceCode.IO_ERROR,
                            "Target Volume Empty for a given source R1 {0}, hence cannot proceed with failover or failback.",
                            new Object[] { copyVolume.getNativeGuid() });
                } else if (PersonalityTypes.TARGET.name().equalsIgnoreCase(copyVolume.getPersonality()) &&
                        RemoteDirectorGroup.SupportedCopyModes.ADAPTIVECOPY.name().equalsIgnoreCase(copyVolume.getSrdfCopyMode())) {

                    if (ProtectionOp.CHANGE_COPY_MODE.getRestOp().equalsIgnoreCase(op)) {
                        validateVpoolCopyModeSetting(volume, copy.getCopyMode());
                    }
                }
            }

            /*
             * CTRL-6972: In the absence of a /restore API, we re-use /sync with a syncDirection parameter for
             * specifying either SMI-S Resume or Restore:
             * SOURCE_TO_TARGET -> ViPR Resume -> SMI-S Resume -> SRDF Incremental Establish (R1 overwrites R2)
             * TARGET_TO_SOURCE -> ViPR Sync -> SMI-S Restore -> SRDF Full Restore (R2 overwrites R1)
             */
            if (op.equalsIgnoreCase(ProtectionOp.SYNC.getRestOp()) &&
                    SOURCE_TO_TARGET.toString().equalsIgnoreCase(copy.getSyncDirection())) {
                op = ProtectionOp.RESUME.getRestOp();
            } else if (isSuspendCopyRequest(op, copy)) {
                op = ProtectionOp.SUSPEND.getRestOp();
            }
            ProtectionOrchestrationController protectionController = getController(ProtectionOrchestrationController.class,
                    ProtectionOrchestrationController.PROTECTION_ORCHESTRATION_DEVICE);
            StorageSystem system = _dbClient.queryObject(StorageSystem.class,
                    copyVolume.getStorageController());
            protectionController.performSRDFProtectionOperation(system.getId(), copy, op, task);
        } else {
            throw new ServiceCodeException(ServiceCode.IO_ERROR,
                    "Volume {0} is not SRDF protected",
                    new Object[] { copyVolume.getNativeGuid() });
        }
        return toTask(volume, task, status);
    }

    private void validateVpoolCopyModeSetting(Volume srcVolume, String newCopyMode) {
        if (srcVolume != null) {
            URI virtualPoolURI = srcVolume.getVirtualPool();
            VirtualPool virtualPool = _dbClient.queryObject(VirtualPool.class, virtualPoolURI);
            if (virtualPool != null) {
                StringMap remoteCopySettingsMap = virtualPool.getProtectionRemoteCopySettings();
                if (remoteCopySettingsMap != null) {
                    for (Map.Entry<URI, VpoolRemoteCopyProtectionSettings> entry : VirtualPool.getRemoteProtectionSettings(virtualPool,
                            _dbClient).entrySet()) {
                        VpoolRemoteCopyProtectionSettings copySetting = entry.getValue();
                        if (!newCopyMode.equalsIgnoreCase(copySetting.getCopyMode())) {
                            throw APIException.badRequests.invalidCopyModeOp(newCopyMode, copySetting.getCopyMode());
                        }
                    }
                }
            }
        }
    }

    private TaskResourceRep establishVolumeMirrorGroupRelation(URI id, Copy copy, String op)
            throws InternalException {
        URI copyID = copy.getCopyID();
        ArgValidator.checkFieldUriType(copyID, BlockMirror.class, "copyID");
        // Get the volume associated with the URI
        Volume volume = queryVolumeResource(id);
        BlockMirror mirror = queryMirror(copyID);

        ArgValidator.checkEntity(volume, id, true);
        ArgValidator.checkEntity(mirror, copyID, true);

        StringSet mirrors = volume.getMirrors();
        if (mirrors == null || mirrors.isEmpty()) {
            throw APIException.badRequests.invalidParameterVolumeHasNoContinuousCopies(id);
        }

        if (!mirror.getSource().getURI().equals(id)) {
            throw APIException.badRequests.invalidParameterBlockCopyDoesNotBelongToVolume(copyID, id);
        }

        if (!volume.hasConsistencyGroup() ||
                !mirror.hasConsistencyGroup()) {
            throw APIException.badRequests.blockObjectHasNoConsistencyGroup();
        }

        String task = UUID.randomUUID().toString();
        Operation status = new Operation();
        status.setResourceType(ProtectionOp.getResourceOperationTypeEnum(op));
        _dbClient.createTaskOpStatus(Volume.class, volume.getId(), task, status);

        auditOp(OperationTypeEnum.ESTABLISH_VOLUME_MIRROR, true, AuditLogManager.AUDITOP_BEGIN,
                mirrors);

        StorageSystem system = _dbClient.queryObject(StorageSystem.class,
                volume.getStorageController());
        BlockServiceApi blockServiceApi = getBlockServiceImpl("mirror");
        return blockServiceApi.establishVolumeAndNativeContinuousCopyGroupRelation(system, volume,
                mirror, task);
    }

    /**
     * Since all of the protection operations are very similar, this method does all of the work.
     * We keep the actual REST methods separate mostly for the purpose of documentation generators.
     *
     * @param id
     *            the URN of a ViPR source volume
     * @param copyID
     *            id of the target volume
     * @param pointInTime
     *            any point in time used for failover, specified in UTC.
     *            Allowed values: "yyyy-MM-dd_HH:mm:ss" formatted date or datetime in milliseconds. Can be
     *            null.
     * @param op
     *            operation to perform (pause, stop, failover, etc)
     * @return task resource rep
     * @throws InternalException
     */
    private TaskResourceRep performProtectionAction(URI id, Copy copy, String op)
            throws InternalException {
        ArgValidator.checkFieldUriType(copy.getCopyID(), Volume.class, "copyID");
        // Get the volume associated with the URI
        Volume volume = queryVolumeResource(id);
        Volume copyVolume = queryVolumeResource(copy.getCopyID());

        ArgValidator.checkEntity(volume, id, true);
        ArgValidator.checkEntity(copyVolume, copy.getCopyID(), true);

        if (op.equalsIgnoreCase(ProtectionOp.SWAP.getRestOp()) && !NullColumnValueGetter.isNullURI(volume.getConsistencyGroup())) {
            ExportUtils.validateConsistencyGroupBookmarksExported(_dbClient, volume.getConsistencyGroup());
        }

        // Catch any attempts to use an invalid access mode
        if (op.equalsIgnoreCase(ProtectionOp.CHANGE_ACCESS_MODE.getRestOp()) &&
                !Copy.ImageAccessMode.DIRECT_ACCESS.name().equalsIgnoreCase(copy.getAccessMode())) {
            throw APIException.badRequests.unsupportedAccessMode(copy.getAccessMode());
        }

        if (isNullURI(volume.getProtectionController())) {
            throw new ServiceCodeException(ServiceCode.IO_ERROR,
                    "Attempt to do protection link management on unprotected volume: {0}",
                    new Object[] { volume.getWWN() });
        }

        // Make sure that we don't have some pending
        // operation against the volume
        checkForPendingTasks(Arrays.asList(volume.getTenant().getURI()), Arrays.asList(volume));

        String task = UUID.randomUUID().toString();
        Operation status = new Operation();
        status.setResourceType(ProtectionOp.getResourceOperationTypeEnum(op));
        _dbClient.createTaskOpStatus(Volume.class, volume.getId(), task, status);

        _log.info(String.format("Protection %s --- VolumeId id: %s on Protection Appliance: %s",
                task, id, volume.getProtectionController()));

        ProtectionSystem system = _dbClient.queryObject(ProtectionSystem.class,
                volume.getProtectionController());
        String deviceType = system.getSystemType();

        if (!deviceType.equals(DiscoveredDataObject.Type.rp.name())) {
            throw APIException.badRequests.protectionForRpClusters();
        }

        RPController controller = getController(RPController.class, system.getSystemType());

        controller.performProtectionOperation(system.getId(), id, copy.getCopyID(), copy.getPointInTime(), copy.getAccessMode(), op,
                task);
        /*
         * auditOp(OperationTypeEnum.PERFORM_PROTECTION_ACTION, true, AuditLogManager.AUDITOP_BEGIN,
         * op, copyID.toString(), id.toString(), system.getId().toString());
         */
        return toTask(volume, task, status);
    }

    /**
     * Helper method for querying a mirror
     *
     * @param id
     *            the URN of a ViPR mirror to query
     * @return BlockMirror instance
     */
    private BlockMirror queryMirror(URI id) {
        ArgValidator.checkUri(id);
        BlockMirror mirror = _permissionsHelper.getObjectById(id, BlockMirror.class);
        ArgValidator.checkEntityNotNull(mirror, id, isIdEmbeddedInURL(id));
        return mirror;
    }

    /**
     * Helper method for querying a vplex mirror
     *
     * @param id
     *            the URN of a ViPR mirror to query
     * @return VplexMirror instance
     */
    private VplexMirror queryVplexMirror(URI id) {
        ArgValidator.checkUri(id);
        VplexMirror mirror = _permissionsHelper.getObjectById(id, VplexMirror.class);
        ArgValidator.checkEntityNotNull(mirror, id, isIdEmbeddedInURL(id));
        return mirror;
    }

    /**
     * Returns all potential virtual pools for a virtual pool change of the
     * volume specified in the request. Note that not all virtual pool returned
     * by the request will be a valid virtual pool change for the volume. The
     * virtual pools returned are based on the connectivity of the storage
     * system on which the volume resides, the storage pools available to that
     * storage system, and the virtual pools defined in the system that can be
     * supported by those storage pools. For each virtual pool returned, the
     * response identifies whether or not a change to the virtual pool is
     * allowed, and when not allowed, the reason the change is not allowed.
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR volume.
     *
     * @brief Show potential virtual pools
     * @return A VirtualPoolChangeList that identifies each potential virtual
     *         pool, whether or not a change is allowed for the virtual pool,
     *         and if not, the reason why.
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/vpool-change/vpool")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public VirtualPoolChangeList getVirtualPoolForVirtualPoolChange(@PathParam("id") URI id) {

        // Get the volume.
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        Volume volume = queryVolumeResource(id);
        _log.info("Found volume");

        // Get the block service implementation for this volume.
        BlockServiceApi blockServiceApi = getBlockServiceImpl(volume);
        _log.info("Got BlockServiceApi for volume");

        // Return the list of potential VirtualPool for a VirtualPool change for this volume.
        return blockServiceApi.getVirtualPoolForVirtualPoolChange(volume);
    }

    /**
     * Allows the caller to change the virtual pool for the volume identified in
     * the request. Currently, the only virtual pool changes that are supported
     * are as follows:
     *
     * Change the virtual pool for a VPLEX virtual volume. This virtual pool
     * change would allow the caller to change the types of drives, for example,
     * used for the backend volume(s) that are used by the virtual volume.
     *
     * Change the virtual pool for a VPLEX virtual volume, such that a local
     * VPLEX virtual volumes becomes a distributed VPLEX virtual volume.
     *
     * Change the virtual pool of a VMAX or VNX Block volume to make the volume
     * a local or distributed VPLEX virtual volume. Essentially, the volume
     * becomes the backend volume for a VPLEX virtual volume. Similar to
     * creating a virtual volume, but instead of creating a new backend volume,
     * using the volume identified in the request. The VMAX or VNX volume cannot
     * currently be exported for this change.
     *
     * Change the virtual pool of a VMAX or VNX Block volume to make the volume
     * a RecoverPoint protected volume. The volume must be able to stay put, and
     * ViPR will build a protection around it.
     *
     * Change the virtual pool of a VMAX or VNX Block volume to allow native
     * continuous copies to be created for it.
     *
     * Change the virtual pool of a volume to increase the export path parameter max_paths.
     * The number of paths will be upgraded if possible for all Export Groups / Export Masks
     * containing this volume. If the volume is not currently exported, max_paths can be
     * decreased or paths_per_initiator can be changed. Note that changing max_paths does
     * not have any effect on the export of BlockSnapshots that were created from this volume.
     *
     * Change the virtual pool of a VMAX and VNX volume to allow change of Auto-tiering policy
     * associated with it.
     * <p>
     * Since this method has been deprecated use POST /block/volumes/vpool-change
     *
     * @brief Change the virtual pool for a volume.
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR volume.
     * @param param
     *            The parameter specifying the new virtual pool.
     * @return A TaskResourceRep representing the virtual pool change for the
     *         volume.
     * @throws InternalException,
     *             APIException
     */
    @PUT
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/vpool")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    @Deprecated
    public TaskResourceRep changeVolumeVirtualPool(@PathParam("id") URI id,
            VirtualPoolChangeParam param) throws InternalException, APIException {

        _log.info("Request to change VirtualPool for volume {}", id);

        // Get the volume.
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        Volume volume = queryVolumeResource(id);
        _log.info("Found volume");

        // Don't operate on VPLEX backend or RP Journal volumes.
        BlockServiceUtils.validateNotAnInternalBlockObject(volume, false);

        // Don't operate on ingested volumes.
        VolumeIngestionUtil.checkOperationSupportedOnIngestedVolume(volume,
                ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VPOOL, _dbClient);

        // Get the project.
        URI projectURI = volume.getProject().getURI();
        Project project = _permissionsHelper.getObjectById(projectURI, Project.class);
        ArgValidator.checkEntity(project, projectURI, false);
        _log.info("Found volume project {}", projectURI);

        // Verify the user is authorized for the volume's project.
        BlockServiceUtils.verifyUserIsAuthorizedForRequest(project, getUserFromContext(), _permissionsHelper);
        _log.info("User is authorized for volume's project");

        // Get the VirtualPool for the request and verify that the
        // project's tenant has access to the VirtualPool.
        VirtualPool vpool = getVirtualPoolForRequest(project, param.getVirtualPool(), _dbClient,
                _permissionsHelper);
        _log.info("Found new VirtualPool {}", vpool.getId());

        // Verify that the VirtualPool change is allowed for the
        // requested volume and VirtualPool.
        verifyVirtualPoolChangeSupportedForVolumeAndVirtualPool(volume, vpool);
        _log.info("VirtualPool change is supported for requested volume and VirtualPool");

        verifyAllVolumesInCGRequirement(Arrays.asList(volume), vpool);

        // verify quota
        if (!CapacityUtils.validateVirtualPoolQuota(_dbClient, vpool, volume.getProvisionedCapacity())) {
            throw APIException.badRequests.insufficientQuotaForVirtualPool(vpool.getLabel(), "volume");
        }

        // Create a unique task id.
        String taskId = UUID.randomUUID().toString();
        Operation op = _dbClient.createTaskOpStatus(Volume.class, id,
                taskId, ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VPOOL);

        // Get the required block service API implementation to
        // make the desired VirtualPool change on this volume. This
        // essentially determines the controller that will be used
        // execute the VirtualPool update on the volume.
        try {
            BlockServiceApi blockServiceAPI = getBlockServiceImplForVirtualPoolChange(volume, vpool);
            _log.info("Got block service implementation for VirtualPool change request");
            blockServiceAPI.changeVolumeVirtualPool(Arrays.asList(volume), vpool, param, taskId);
            _log.info("Executed VirtualPool change for volume.");
        } catch (InternalException | APIException e) {
            String errorMsg = String.format("Volume VirtualPool change error: %s", e.getMessage());
            op = new Operation(Operation.Status.error.name(), errorMsg);
            _dbClient.updateTaskOpStatus(Volume.class, id, taskId, op);
            throw e;
        }

        auditOp(OperationTypeEnum.CHANGE_VOLUME_VPOOL, true, AuditLogManager.AUDITOP_BEGIN,
                volume.getLabel(), 1, volume.getVirtualArray().toString(), volume.getProject().toString());

        return toTask(volume, taskId, op);
    }

    /**
     * Allows the caller to change the virtual pool for the volumes identified in
     * the request. Currently, the only virtual pool changes that are supported via
     * this method are as follows:
     *
     *
     * Change the virtual pool for a VPLEX virtual volume. This virtual pool
     * change would allow the caller to change the types of drives, for example,
     * used for the backend volume(s) that are used by the virtual volume.
     *
     * Change the virtual pool for a VPLEX virtual volume, such that a local
     * VPLEX virtual volumes becomes a distributed VPLEX virtual volume.
     *
     * Change the virtual pool of a VMAX or VNX Block volume to make the volume
     * a local or distributed VPLEX virtual volume. Essentially, the volume
     * becomes the backend volume for a VPLEX virtual volume. Similar to
     * creating a virtual volume, but instead of creating a new backend volume,
     * using the volume identified in the request. The VMAX or VNX volume cannot
     * currently be exported for this change.
     *
     * Change the virtual pool of a VMAX or VNX Block volume to make the volume
     * a RecoverPoint protected volume. The volume must be able to stay put, and
     * ViPR will build a protection around it.
     *
     * Change the virtual pool of a VMAX or VNX Block volume to allow native
     * continuous copies to be created for it.
     *
     * Change the virtual pool of a volume to increase the export path parameter max_paths.
     * The number of paths will be upgraded if possible for all Export Groups / Export Masks
     * containing this volume. If the volume is not currently exported, max_paths can be
     * decreased or paths_per_initiator can be changed. Note that changing max_paths does
     * not have any effect on the export of BlockSnapshots that were created from this volume.
     *
     * Change the virtual pool of a VMAX and VNX volumes to allow change of Auto-tiering policy
     * associated with it.
     *
     *
     * Note: Operations other than Auto-tiering Policy change will call the
     * internal single volume method (BlockServiceApiImpl) in a loop.
     *
     * @brief Change the virtual pool for the given volumes.
     *
     * @param param
     *            the VolumeVirtualPoolChangeParam
     * @return A List of TaskResourceRep representing the virtual pool change for the
     *         volumes.
     * @throws InternalException,
     *             APIException
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/vpool-change")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList changeVolumesVirtualPool(VolumeVirtualPoolChangeParam param)
            throws InternalException, APIException {

        // verify volume ids list is provided.
        List<URI> ids = param.getVolumes();
        ArgValidator.checkFieldNotEmpty(ids, "volumes");
        _log.info("Request to change VirtualPool for volumes {}", ids);

        List<Volume> volumes = new ArrayList<Volume>();
        TaskList taskList = new TaskList();

        for (URI id : ids) {
            // Get the volume.
            ArgValidator.checkFieldUriType(id, Volume.class, "volume");
            Volume volume = queryVolumeResource(id);
            volumes.add(volume);

            // Make sure that we don't have some pending
            // operation against the volume
            checkForPendingTasks(Arrays.asList(volume.getTenant().getURI()), Arrays.asList(volume));
        }

        _log.info("Found volumes");

        /**
         * verify that all volumes belong to same vPool.
         *
         * If so and vPool change detects it as Auto-tiering policy change,
         * then they are of same system type.
         *
         * Special case: If the request contains a VMAX volume and a VNX volume
         * belonging to a generic vPool and the target vPool has some VMAX FAST policy,
         * the below verifyVirtualPoolChangeSupportedForVolumeAndVirtualPool() check will
         * throw error for VNX volume (saying it does not come under any valid change).
         */
        verifyAllVolumesBelongToSameVpool(volumes);

        // target vPool
        VirtualPool vPool = null;

        // total provisioned capacity to check for vPool quota.
        long totalProvisionedCapacity = 0;

        for (Volume volume : volumes) {
            _log.info("Checking on volume: {}", volume.getId());

            // Don't operate on VPLEX backend or RP Journal volumes.
            BlockServiceUtils.validateNotAnInternalBlockObject(volume, param.getForceFlag());

            // Don't operate on ingested volumes.
            VolumeIngestionUtil.checkOperationSupportedOnIngestedVolume(volume,
                    ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VPOOL, _dbClient);

            // Get the project.
            URI projectURI = volume.getProject().getURI();
            Project project = _permissionsHelper.getObjectById(projectURI,
                    Project.class);
            ArgValidator.checkEntity(project, projectURI, false);
            _log.info("Found volume project {}", projectURI);

            // Verify the user is authorized for the volume's project.
            BlockServiceUtils.verifyUserIsAuthorizedForRequest(project, getUserFromContext(), _permissionsHelper);
            _log.info("User is authorized for volume's project");

            // Get the VirtualPool for the request and verify that the
            // project's tenant has access to the VirtualPool.
            vPool = getVirtualPoolForRequest(project, param.getVirtualPool(),
                    _dbClient, _permissionsHelper);
            _log.info("Found new VirtualPool {}", vPool.getId());

            // Verify that the VirtualPool change is allowed for the
            // requested volume and VirtualPool.
            verifyVirtualPoolChangeSupportedForVolumeAndVirtualPool(volume, vPool);
            _log.info("VirtualPool change is supported for requested volume and VirtualPool");

            totalProvisionedCapacity += volume.getProvisionedCapacity()
                    .longValue();
        }
        verifyAllVolumesInCGRequirement(volumes, vPool);

        // verify target vPool quota
        if (!CapacityUtils.validateVirtualPoolQuota(_dbClient, vPool,
                totalProvisionedCapacity)) {
            throw APIException.badRequests.insufficientQuotaForVirtualPool(
                    vPool.getLabel(), "volume");
        }

        // Create a unique task id.
        String taskId = UUID.randomUUID().toString();

        // if this vpool request change has a consistency group, set its requested types
        if (param.getConsistencyGroup() != null) {
            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, param.getConsistencyGroup());
            if (cg != null && !cg.getInactive()) {
                cg.getRequestedTypes().addAll(getRequestedTypes(vPool));
                _dbClient.updateObject(cg);
            }
        }

        // Get the required block service API implementation to
        // make the desired VirtualPool change on this volume. This
        // essentially determines the controller that will be used
        // to execute the VirtualPool update on the volume.
        try {
            /**
             * If it is Auto-tiering policy change, the system type remains same
             * between source and target vPools.
             * Volumes from single vPool would be of same characteristics and
             * all would specify same operation.
             */
            BlockServiceApi blockServiceAPI = getBlockServiceImplForVirtualPoolChange(
                    volumes.get(0), vPool);
            _log.info("Got block service implementation for VirtualPool change request");
            VirtualPoolChangeParam oldParam = convertNewVirtualPoolChangeParamToOldParam(param);
            TaskList taskList2 = blockServiceAPI.changeVolumeVirtualPool(volumes, vPool,
                    oldParam, taskId);
            if (taskList2 != null && !taskList2.getTaskList().isEmpty()) {
                taskList.getTaskList().addAll(taskList2.getTaskList());
            }
            _log.info("Executed VirtualPool change for given volumes.");
        } catch (Exception e) {
            String errorMsg = String.format(
                    "Volume VirtualPool change error: %s", e.getMessage());
            _log.error(errorMsg, e);
            if (!taskList.getTaskList().isEmpty()) {
                for (TaskResourceRep volumeTask : taskList.getTaskList()) {
                    volumeTask.setState(Operation.Status.error.name());
                    volumeTask.setMessage(errorMsg);
                    _dbClient.updateTaskOpStatus(Volume.class, volumeTask
                            .getResource().getId(), taskId,
                            new Operation(Operation.Status.error.name(), errorMsg));
                }
            } else {
                for (Volume volume : volumes) {
                    _dbClient.updateTaskOpStatus(Volume.class, volume.getId(), taskId,
                            new Operation(Operation.Status.error.name(), errorMsg));
                }
            }
            throw e;
        }

        // Record Audit operation.
        for (Volume volume : volumes) {
            auditOp(OperationTypeEnum.CHANGE_VOLUME_VPOOL, true,
                    AuditLogManager.AUDITOP_BEGIN, volume.getLabel(), 1, volume
                            .getVirtualArray().toString(),
                    volume.getProject()
                            .toString());
        }

        return taskList;
    }

    /**
     * Verify that all volumes belong to same vpool.
     *
     * @param volumes
     *            the volumes
     */
    private void verifyAllVolumesBelongToSameVpool(List<Volume> volumes) {
        URI vPool = null;
        for (Volume volume : volumes) {
            if (vPool != null
                    && !vPool.toString().equalsIgnoreCase(
                            volume.getVirtualPool().toString())) {
                throw APIException.badRequests.volumesShouldBelongToSameVpool();
            }
            vPool = volume.getVirtualPool();
        }
    }

    /**
     * Copy the contents from new virtual pool change param to old param.
     *
     * Old param is passed as an argument in multiple methods and it is not advisable
     * to create over-loaded methods for all those.
     * When we remove the old deprecated param class, we can change the argument in
     * all those methods to take the new param.
     *
     * @param param
     *            the param
     * @return the virtual pool change param
     */
    private VirtualPoolChangeParam convertNewVirtualPoolChangeParamToOldParam(
            VolumeVirtualPoolChangeParam newParam) {
        VirtualPoolChangeParam oldParam = new VirtualPoolChangeParam();
        oldParam.setVirtualPool(newParam.getVirtualPool());
        oldParam.setProtection(newParam.getProtection());
        oldParam.setConsistencyGroup(newParam.getConsistencyGroup());
        oldParam.setTransferSpeedParam(newParam.getTransferSpeedParam());
        oldParam.setMigrationSuspendBeforeCommit(newParam.isMigrationSuspendBeforeCommit());
        oldParam.setMigrationSuspendBeforeDeleteSource(newParam.isMigrationSuspendBeforeDeleteSource());
        return oldParam;
    }

    /**
     *
     * @param projectURI
     * @param varrayURI
     * @return Get Volume for Virtual Array Change
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/varray-change")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public NamedVolumesList getVolumesForVirtualArrayChange(
            @QueryParam("project") URI projectURI, @QueryParam("targetVarray") URI varrayURI) {
        NamedVolumesList volumeList = new NamedVolumesList();

        // Get the project.
        ArgValidator.checkFieldUriType(projectURI, Project.class, "project");
        Project project = _permissionsHelper.getObjectById(projectURI, Project.class);
        ArgValidator.checkEntity(project, projectURI, false);
        _log.info("Found project {}:{}", projectURI);

        // Verify the user is authorized for the project.
        BlockServiceUtils.verifyUserIsAuthorizedForRequest(project, getUserFromContext(), _permissionsHelper);
        _log.info("User is authorized for project");

        // Get the target virtual array.
        ArgValidator.checkFieldUriType(varrayURI, VirtualArray.class, "targetVarray");
        VirtualArray tgtVarray = _permissionsHelper.getObjectById(varrayURI, VirtualArray.class);
        ArgValidator.checkEntity(tgtVarray, varrayURI, false);
        _log.info("Found target virtual array {}:{}", tgtVarray.getLabel(), varrayURI);

        // Determine all volumes in the project that could potentially
        // be moved to the target virtual array.
        URIQueryResultList volumeIds = new URIQueryResultList();
        _dbClient.queryByConstraint(ContainmentConstraint.Factory.getProjectVolumeConstraint(projectURI), volumeIds);
        Iterator<Volume> volumeItr = _dbClient.queryIterativeObjects(Volume.class, volumeIds);
        while (volumeItr.hasNext()) {
            Volume volume = volumeItr.next();
            try {
                // Don't operate on VPLEX backend, RP Journal volumes,
                // or other internal volumes.
                BlockServiceUtils.validateNotAnInternalBlockObject(volume, false);

                // Don't operate on ingested volumes.
                VolumeIngestionUtil.checkOperationSupportedOnIngestedVolume(volume,
                        ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VARRAY, _dbClient);

                // Can't change to the same varray.
                if (volume.getVirtualArray().equals(varrayURI)) {
                    _log.info("Virtual array change not supported for volume {} already in the target varray",
                            volume.getId());
                    continue;
                }

                // Get the appropriate block service implementation.
                BlockServiceApi blockServiceAPI = getBlockServiceImpl(volume);

                // Verify that the virtual array change is allowed for the
                // volume and target virtual array.
                blockServiceAPI.verifyVarrayChangeSupportedForVolumeAndVarray(volume, tgtVarray);

                // If so, add it to the list.
                volumeList.getVolumes().add(toNamedRelatedResource(volume));
            } catch (Exception e) {
                _log.info("Virtual array change not supported for volume {}:{}",
                        volume.getId(), e.getMessage());
            }
        }

        return volumeList;
    }

    /**
     * Allows the caller to change the virtual array of the passed volume. Currently,
     * this is only possible for a local VPlex virtual volumes. Additionally, the
     * volume must not be exported. The volume can be migrated to the other cluster
     * in the VPlex Metro configuration or a new varray in the same cluster. Since this method has been
     * deprecated use POST /block/volumes/varray-change
     *
     * @prereq Volume must not be exported
     *
     * @param id
     *            The URN of a ViPR volume.
     * @param varrayChangeParam
     *            The varray change parameters.
     *
     * @brief Change the virtual array for the specified volume.
     *
     * @return A TaskResourceRep representing the NH change for the volume.
     *
     * @throws InternalException,
     *             APIException
     */
    @PUT
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/varray")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    @Deprecated
    public TaskResourceRep changeVolumeVirtualArray(@PathParam("id") URI id,
            VirtualArrayChangeParam varrayChangeParam) throws InternalException, APIException {
        _log.info("Request to change varray for volume {}", id);
        TaskList taskList = changeVirtualArrayForVolumes(Arrays.asList(id),
                varrayChangeParam.getVirtualArray());
        return taskList.getTaskList().get(0);
    }

    /**
     * Allows the caller to change the virtual array of the passed volumes.
     * Currently, this is only possible for local VPlex virtual volumes.
     * Additionally, the volumes must not be exported. The volume can be
     * migrated to the other cluster in the VPlex Metro configuration or a new
     * varray in the same cluster.
     *
     * @brief Change the virtual array for the given volumes.
     *
     * @prereq Volumes must not be exported
     *
     * @param param
     *            The varray change parameters.
     *
     * @return A TaskList representing the varray change for the volumes.
     *
     * @throws InternalException,
     *             APIException
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/varray-change")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList changeVolumesVirtualArray(VolumeVirtualArrayChangeParam param)
            throws InternalException, APIException {
        _log.info("Request to change varray for volumes {}", param.getVolumes());
        return changeVirtualArrayForVolumes(param.getVolumes(), param.getVirtualArray());
    }

    /**
     * Changes the virtual array for the passed volumes to the passed
     * target virtual array.
     *
     * @param volumeURIs
     *            The URIs of the volumes to move
     * @param tgtVarrayURI
     *            The URI of the target virtual array
     *
     * @return A TaskList of the tasks associated with each volume being moved.
     *
     * @throws InternalException,
     *             APIException
     */
    private TaskList changeVirtualArrayForVolumes(List<URI> volumeURIs, URI tgtVarrayURI)
            throws InternalException, APIException {

        // Create the result.
        TaskList taskList = new TaskList();

        // Create a unique task id.
        String taskId = UUID.randomUUID().toString();

        // Validate that each of the volumes passed in is eligible
        // for the varray change.
        VirtualArray tgtVarray = null;
        BlockConsistencyGroup cg = null;
        BlockServiceApi blockServiceAPI = null;
        List<Volume> volumes = new ArrayList<Volume>();
        List<Volume> cgVolumes = new ArrayList<Volume>();
        boolean foundVolumeNotInCG = false;
        for (URI volumeURI : volumeURIs) {
            // Get and verify the volume.
            ArgValidator.checkFieldUriType(volumeURI, Volume.class, "volume");
            Volume volume = queryVolumeResource(volumeURI);
            ArgValidator.checkEntity(volume, volumeURI, false);
            _log.info("Found volume {}", volumeURI);

            // Don't operate on VPLEX backend or RP Journal volumes.
            BlockServiceUtils.validateNotAnInternalBlockObject(volume, false);

            // Don't operate on ingested volumes.
            VolumeIngestionUtil.checkOperationSupportedOnIngestedVolume(volume,
                    ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VARRAY, _dbClient);

            // Get and validate the volume's project.
            URI projectURI = volume.getProject().getURI();
            Project project = _permissionsHelper.getObjectById(projectURI, Project.class);
            ArgValidator.checkEntity(project, projectURI, false);
            _log.info("Found volume project {}", projectURI);

            // Verify the user is authorized for the volume's project.
            BlockServiceUtils.verifyUserIsAuthorizedForRequest(project, getUserFromContext(), _permissionsHelper);
            _log.info("User is authorized for volume's project");

            // Verify the current and requested virtual arrays are not the same.
            if (volume.getVirtualArray().equals(tgtVarrayURI)) {
                throw APIException.badRequests.currentAndRequestedVArrayAreTheSame();
            }

            // Get and validate the target virtual array.
            if (tgtVarray == null) {
                tgtVarray = BlockServiceUtils.verifyVirtualArrayForRequest(project,
                        tgtVarrayURI, uriInfo, _permissionsHelper, _dbClient);
                _log.info("Found new VirtualArray {}", tgtVarrayURI);
            }

            // Make sure that we don't have some pending
            // operation against the volume
            checkForPendingTasks(Arrays.asList(volume.getTenant().getURI()), Arrays.asList(volume));

            // Get the appropriate block service implementation for the
            // volume. Note that this same implementation is used to
            // execute the change. If it is possible that volumes
            // with multiple implementations can be selected for a
            // varray change, then we would need a map of the
            // implementation to use for a given volume. However,
            // currently only VPLEX volumes can be moved, so valid
            // volumes for a varray change will always have the same
            // implementation.
            blockServiceAPI = getBlockServiceImpl(volume);

            // Verify that the virtual array change is allowed for the
            // requested volume and virtual array.
            blockServiceAPI.verifyVarrayChangeSupportedForVolumeAndVarray(volume, tgtVarray);
            _log.info("Virtual array change is supported for requested volume and varray");

            // All volumes must be a CG or none of the volumes can be
            // in a CG. After processing individual volumes, if the
            // volumes are in a CG, then we make sure all volumes in the
            // CG and only the volumes in the CG are passed.
            URI cgURI = volume.getConsistencyGroup();
            if ((cg == null) && (!foundVolumeNotInCG)) {
                if (!isNullURI(cgURI)) {
                    cg = _permissionsHelper.getObjectById(cgURI, BlockConsistencyGroup.class);
                    _log.info("All volumes should be in CG {}:{}", cgURI, cg.getLabel());
                    cgVolumes.addAll(blockServiceAPI.getActiveCGVolumes(cg));
                } else {
                    _log.info("No volumes should be in CGs");
                    foundVolumeNotInCG = true;
                }
            } else if (((cg != null) && (isNullURI(cgURI))) ||
                    ((foundVolumeNotInCG) && (!isNullURI(cgURI)))) {
                // A volume was in a CG, so all volumes must be in a CG.
                if (cg != null) {
                    // Volumes should all be in the CG and this one is not.
                    _log.error("Volume {}:{} is not in the CG", volumeURI, volume.getLabel());
                } else {
                    _log.error("Volume {}:{} is in CG {}", new Object[] { volumeURI,
                            volume.getLabel(), cgURI });
                }
                throw APIException.badRequests.mixedVolumesinCGForVarrayChange();
            }

            // Add the volume to the list
            volumes.add(volume);
        }

        // If the volumes are in a CG verify that they are
        // all in the same CG and all volumes are passed.
        if (cg != null) {
            // all volume in CG must have been passed.
            _log.info("Verify all volumes in CG {}:{}", cg.getId(), cg.getLabel());
            URI storageId = cg.getStorageController();
            if (!NullColumnValueGetter.isNullURI(storageId)) {
                StorageSystem storage = _dbClient.queryObject(StorageSystem.class, storageId);
                if (DiscoveredDataObject.Type.vplex.name().equals(storage.getSystemType())) {
                    // For VPlex, the volumes should include all volumes, which are in the same backend storage system,
                    // in the CG.
                    if (!VPlexUtil.verifyVolumesInCG(volumes, cgVolumes, _dbClient)) {
                        throw APIException.badRequests.cantChangeVarrayNotAllCGVolumes();
                    }
                } else {
                    verifyVolumesInCG(volumes, cgVolumes);
                }
            } else {
                verifyVolumesInCG(volumes, cgVolumes);
            }
        }

        // Create a task for each volume and set the initial
        // task state to pending.
        for (Volume volume : volumes) {
            Operation op = _dbClient.createTaskOpStatus(Volume.class, volume.getId(), taskId,
                    ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VARRAY);
            TaskResourceRep resourceTask = toTask(volume, taskId, op);
            taskList.addTask(resourceTask);
        }

        // Now execute the varray change for the volumes.
        if (cg != null) {
            try {
                // When the volumes are part of a CG, executed as a single workflow.
                blockServiceAPI.changeVirtualArrayForVolumes(volumes, cg, cgVolumes, tgtVarray, taskId);
                _log.info("Executed virtual array change for volumes");
            } catch (InternalException | APIException e) {
                // Fail all the tasks.
                String errorMsg = String.format("Volume virtual array change error: %s", e.getMessage());
                _log.error(errorMsg);
                for (TaskResourceRep resourceTask : taskList.getTaskList()) {
                    resourceTask.setState(Operation.Status.error.name());
                    resourceTask.setMessage(errorMsg);
                    _dbClient.error(Volume.class, resourceTask.getResource().getId(), taskId, e);
                }
            } catch (Exception e) {
                // Fail all the tasks.
                String errorMsg = String.format("Volume virtual array change error: %s", e.getMessage());
                _log.error(errorMsg);
                for (TaskResourceRep resourceTask : taskList.getTaskList()) {
                    resourceTask.setState(Operation.Status.error.name());
                    resourceTask.setMessage(errorMsg);
                    _dbClient.error(Volume.class, resourceTask.getResource().getId(), taskId,
                            InternalServerErrorException.internalServerErrors
                                    .unexpectedErrorDuringVarrayChange(e));
                }
            }
        } else {
            // When the volumes are not in a CG, then execute as individual workflows.
            for (Volume volume : volumes) {
                try {
                    blockServiceAPI.changeVirtualArrayForVolumes(Arrays.asList(volume), cg, cgVolumes, tgtVarray, taskId);
                    _log.info("Executed virtual array change for volume {}", volume.getId());
                } catch (InternalException | APIException e) {
                    String errorMsg = String.format("Volume virtual array change error: %s", e.getMessage());
                    _log.error(errorMsg);
                    for (TaskResourceRep resourceTask : taskList.getTaskList()) {
                        // Fail the correct task.
                        if (resourceTask.getResource().getId().equals(volume.getId())) {
                            resourceTask.setState(Operation.Status.error.name());
                            resourceTask.setMessage(errorMsg);
                            _dbClient.error(Volume.class, resourceTask.getResource().getId(), taskId, e);
                        }
                    }
                } catch (Exception e) {
                    // Fail all the tasks.
                    String errorMsg = String.format("Volume virtual array change error: %s", e.getMessage());
                    _log.error(errorMsg);
                    for (TaskResourceRep resourceTask : taskList.getTaskList()) {
                        // Fail the correct task.
                        if (resourceTask.getResource().getId().equals(volume.getId())) {
                            resourceTask.setState(Operation.Status.error.name());
                            resourceTask.setMessage(errorMsg);
                            _dbClient.error(Volume.class, resourceTask.getResource().getId(), taskId,
                                    InternalServerErrorException.internalServerErrors
                                            .unexpectedErrorDuringVarrayChange(e));
                        }
                    }
                }
            }
        }

        return taskList;
    }

    /**
     * Verifies that the passed volumes correspond to the passed volumes from
     * a consistency group.
     *
     * @param volumes
     *            The volumes to verify
     * @param cgVolumes
     *            The list of active volumes in a CG.
     */
    private void verifyVolumesInCG(List<Volume> volumes, List<Volume> cgVolumes) {
        // The volumes counts must match. If the number of volumes
        // is less, then not all volumes in the CG were passed.
        if (volumes.size() < cgVolumes.size()) {
            throw APIException.badRequests.cantChangeVarrayNotAllCGVolumes();
        }

        // Make sure only the CG volumes are selected.
        for (Volume volume : volumes) {
            boolean found = false;
            for (Volume cgVolume : cgVolumes) {
                if (volume.getId().equals(cgVolume.getId())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                _log.error("Volume {}:{} not found in CG", volume.getId(), volume.getLabel());
                throw APIException.badRequests.cantChangeVarrayVolumeIsNotInCG();
            }
        }
    }

    /**
     * Returns a list of the migrations associated with the volume identified by
     * the id specified in the request.
     *
     *
     * @prereq none
     *
     * @param id
     *            the URN of a ViPR volume.
     *
     * @brief Show volume migrations
     * @return A list specifying the id, name, and self link of the migrations
     *         associated with the volume
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/migrations")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR, Role.TENANT_ADMIN })
    public MigrationList getVolumeMigrations(@PathParam("id") URI id) {
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        MigrationList volumeMigrations = new MigrationList();
        URIQueryResultList migrationURIs = new URIQueryResultList();
        _dbClient.queryByConstraint(ContainmentConstraint.Factory.getMigrationVolumeConstraint(id), migrationURIs);
        Iterator<URI> migrationURIsIter = migrationURIs.iterator();
        while (migrationURIsIter.hasNext()) {
            URI migrationURI = migrationURIsIter.next();
            Migration migration = _permissionsHelper.getObjectById(migrationURI,
                    Migration.class);
            if (BulkList.MigrationFilter.isUserAuthorizedForMigration(migration, getUserFromContext(), _permissionsHelper)) {
                volumeMigrations.getMigrations().add(toNamedRelatedResource(migration, migration.getLabel()));
            }
        }

        return volumeMigrations;
    }

    /**
     * Retrieve resource representations based on input ids.
     *
     *
     * @prereq none
     *
     * @param param
     *            POST data containing the id list.
     *
     * @brief List data of volume resources
     * @return list of representations.
     */
    @Override
    @POST
    @Path("/bulk")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public VolumeBulkRep getBulkResources(BulkIdParam param) {
        return (VolumeBulkRep) super.getBulkResources(param);
    }

    /**
     * Return all the export information related to volume ids passed.
     * This will be in the form of a list of initiator / target pairs
     * for all the initiators that have been paired with a target
     * storage port.
     *
     *
     * @prereq none
     *
     * @param param
     *            POST data containing the id list.
     *
     * @brief Show export information for volumes
     * @return List of exports
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/exports/bulk")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public ITLBulkRep getVolumesExports(BulkIdParam param) {
        List<URI> volumeIdList = param.getIds();
        ITLBulkRep list = new ITLBulkRep();
        for (URI volumeId : volumeIdList) {
            ArgValidator.checkFieldUriType(volumeId, Volume.class, "id");
            queryResource(volumeId);
            list.getExportList().addAll(
                    ExportUtils.getBlockObjectInitiatorTargets(volumeId, _dbClient, isIdEmbeddedInURL(volumeId)).getExportList());
        }
        return list;
    }

    /**
     * Determines whether or not the passed VirtualPool change for the passed Volume is
     * supported. Throws a ServiceCodeException when the vpool change is not
     * supported.
     *
     * @param volume
     *            A reference to the volume.
     * @param newVpool
     *            A reference to the new VirtualPool.
     */
    private void verifyVirtualPoolChangeSupportedForVolumeAndVirtualPool(Volume volume, VirtualPool newVpool) {
        // Currently, Vpool change is only supported for volumes on
        // VPlex storage systems and volumes (both regular and VPLEX i.e. RP+VPLEX) that are currently
        // unprotected by RP to a Vpool that has RP, as long as the source volume doesn't have to move.
        VirtualPool currentVpool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
        URI systemURI = volume.getStorageController();
        StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
        String systemType = system.getSystemType();

        StringBuffer notSuppReasonBuff = new StringBuffer();
        notSuppReasonBuff.setLength(0);
        /**
         * Do not support following vpool change operations for the volume part of application
         * 1. Move into Vplex
         * 2. Add RecoverPoint
         * 3. Remove RecoverPoint
         * 4. Add SRDF
         */
        if (volume.getApplication(_dbClient) != null) {
            // Move into VPLEX
            if (!VirtualPool.vPoolSpecifiesHighAvailability(currentVpool) && VirtualPool.vPoolSpecifiesHighAvailability(newVpool)) {
                notSuppReasonBuff.append("Non VPLEX volumes in applications cannot be moved into VPLEX pools");
                throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                        notSuppReasonBuff.toString());
            }

            // Add recoverPoint
            if (!VirtualPool.vPoolSpecifiesProtection(currentVpool)
                    && VirtualPool.vPoolSpecifiesProtection(newVpool)) {
                notSuppReasonBuff.append("Non RP volumes in applications cannot be moved into RP pools");
                throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                        notSuppReasonBuff.toString());
            }

            // Remove RecoverPoint
            if (VirtualPool.vPoolSpecifiesProtection(currentVpool)
                    && !VirtualPool.vPoolSpecifiesProtection(newVpool)) {
                notSuppReasonBuff.append("RP volumes in applications cannot be moved into non RP pools");
                throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                        notSuppReasonBuff.toString());
            }

            // Add SRDF
            if (!VirtualPool.vPoolSpecifiesSRDF(currentVpool) && VirtualPool.vPoolSpecifiesSRDF(newVpool)) {
                notSuppReasonBuff.append("volumes in applications cannot be moved into SRDF pools");
                throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                        notSuppReasonBuff.toString());
            }
        }

        // Check if an Export Path Params change.
        if (VirtualPoolChangeAnalyzer.isSupportedPathParamsChange(volume, currentVpool, newVpool,
                _dbClient, notSuppReasonBuff)) {
            ExportPathUpdater updater = new ExportPathUpdater(_dbClient);
            ExportPathParams newParam = new ExportPathParams(newVpool.getNumPaths(),
                    newVpool.getMinPaths(), newVpool.getPathsPerInitiator());
            updater.validateChangePathParams(volume.getId(), newParam);
            _log.info("New VPool specifies an Export Path Params change");
            return;
        }

        // Check if it is an Auto-tiering policy change.
        notSuppReasonBuff.setLength(0);
        if (VirtualPoolChangeAnalyzer.isSupportedAutoTieringPolicyAndLimitsChange(volume, currentVpool, newVpool,
                _dbClient, notSuppReasonBuff)) {
            _log.info("New VPool specifies an Auto-tiering policy change");
            return;
        }

        if (VirtualPoolChangeAnalyzer.isSupportedReplicationModeChange(currentVpool, newVpool,
                notSuppReasonBuff)) {
            _log.info("New VPool specifies a replication mode change");
            return;
        }

        if (DiscoveredDataObject.Type.vplex.name().equals(systemType)) {
            _log.info("Volume is a VPlex virtual volume.");
            // Vpool must specify a valid VPlex high availability.
            // The volume will still be highly available, but maybe
            // the Vpool specifies a different grade of disk drives.
            if (!VirtualPool.vPoolSpecifiesHighAvailability(newVpool)) {
                _log.info("New VirtualPool does not specify VPlex high availability.");
                throw new ServiceCodeException(ServiceCode.API_VOLUME_VPOOL_CHANGE_DISRUPTIVE,
                        "New VirtualPool {0} does not specify vplex high availability",
                        new Object[] { newVpool.getId() });
            } else {
                notSuppReasonBuff.setLength(0);
                // If this a RP+VPLEX Journal check to see if a straight up VPLEX Data migration is
                // allowed.
                //
                // RP+VPLEX Journals are normally hidden in the UI since they are internal volumes, however they
                // can be exposed in the Migration Services catalog to support RP+VPLEX Data Migrations.
                if (volume.checkPersonality(Volume.PersonalityTypes.METADATA)) {
                    if (VirtualPoolChangeAnalyzer.vpoolChangeRequiresMigration(currentVpool, newVpool)) {
                        verifyVPlexVolumeForDataMigration(volume, currentVpool, newVpool, _dbClient);
                        return;
                    }
                }
                // Check to see if this is a RP protected VPLEX volume and
                // if the request is trying to remove RP protection.
                if (volume.checkForRp()
                        && VirtualPool.vPoolSpecifiesProtection(currentVpool)
                        && !VirtualPool.vPoolSpecifiesProtection(newVpool)) {
                    notSuppReasonBuff.setLength(0);
                    if (!VirtualPoolChangeAnalyzer.isSupportedRPRemoveProtectionVirtualPoolChange(volume, currentVpool, newVpool,
                            _dbClient, notSuppReasonBuff)) {
                        throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                                notSuppReasonBuff.toString());
                    }
                } else if (VirtualPool.vPoolSpecifiesRPVPlex(newVpool)) {
                    notSuppReasonBuff.setLength(0);
                    // Check to see if any of the operations for protected vpool to protected vpool changes are supported
                    if (VirtualPool.vPoolSpecifiesRPVPlex(currentVpool)) {
                        if (VirtualPoolChangeAnalyzer.isSupportedRPVPlexMigrationVirtualPoolChange(volume, currentVpool, newVpool,
                                _dbClient, notSuppReasonBuff, null)) {
                            verifyVPlexVolumeForDataMigration(volume, currentVpool, newVpool, _dbClient);
                        } else if (!VirtualPoolChangeAnalyzer.isSupportedUpgradeToMetroPointVirtualPoolChange(volume, currentVpool,
                                newVpool,
                                _dbClient, notSuppReasonBuff)) {
                            _log.warn("RP Change Protection VirtualPool change for volume is not supported: {}",
                                    notSuppReasonBuff.toString());
                            throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                                    notSuppReasonBuff.toString());
                        }
                    }
                    // Otherwise, check to see if we're trying to protect a VPLEX volume.
                    else if (!VirtualPoolChangeAnalyzer.isSupportedAddRPProtectionVirtualPoolChange(volume, currentVpool, newVpool,
                            _dbClient, notSuppReasonBuff)) {
                        _log.warn("RP+VPLEX VirtualPool change for volume is not supported: {}",
                                notSuppReasonBuff.toString());
                        throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                                notSuppReasonBuff.toString());
                    } else if (BlockFullCopyUtils.volumeHasFullCopySession(volume, _dbClient)) {
                        // Full copies not supported for RP protected volumes.
                        throw APIException.badRequests.volumeForRPVpoolChangeHasFullCopies(volume.getLabel());
                    }
                } else {
                    VirtualPoolChangeOperationEnum vplexVpoolChangeOperation = VirtualPoolChangeAnalyzer
                            .getSupportedVPlexVolumeVirtualPoolChangeOperation(volume,
                                    currentVpool, newVpool, _dbClient, notSuppReasonBuff);
                    if (vplexVpoolChangeOperation == null) {
                        _log.warn("VPlex volume VirtualPool change not supported {}", notSuppReasonBuff.toString());
                        throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                                notSuppReasonBuff.toString());
                    } else if (VPlexUtil.isVolumeBuiltOnBlockSnapshot(_dbClient, volume)) {
                        // We will not allow virtual pool change for a VPLEX volume that was
                        // created using the target volume of a block snapshot.
                        throw APIException.badRequests.vpoolChangeNotAllowedVolumeIsExposedSnapshot(volume.getId().toString());
                    } else if (vplexVpoolChangeOperation == VirtualPoolChangeOperationEnum.VPLEX_DATA_MIGRATION) {
                        verifyVPlexVolumeForDataMigration(volume, currentVpool, newVpool, _dbClient);
                    }
                }
            }
        } else if (DiscoveredDataObject.Type.vmax.name().equals(systemType)
                || DiscoveredDataObject.Type.vnxblock.name().equals(systemType)
                || DiscoveredDataObject.Type.hds.name().equals(systemType)
                || DiscoveredDataObject.Type.xtremio.name().equals(systemType)
                || DiscoveredDataObject.Type.ibmxiv.name().equals(systemType)
                || DiscoveredDataObject.Type.unity.name().equals(systemType)) {
            if (VirtualPool.vPoolSpecifiesHighAvailability(newVpool)) {
                // VNX/VMAX import to VPLEX cases
                notSuppReasonBuff.setLength(0);
                if (!VirtualPoolChangeAnalyzer.isVPlexImport(volume, currentVpool, newVpool, notSuppReasonBuff)
                        || (!VirtualPoolChangeAnalyzer.doesVplexVpoolContainVolumeStoragePool(volume, newVpool, notSuppReasonBuff))) {
                    _log.warn("VNX/VMAX cos change for volume is not supported: {}",
                            notSuppReasonBuff.toString());
                    throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                            notSuppReasonBuff.toString());
                }
                if (volume.isVolumeExported(_dbClient)) {
                    throw APIException.badRequests.cannotImportExportedVolumeToVplex(volume.getId());
                } else if (BlockFullCopyUtils.volumeHasFullCopySession(volume, _dbClient)) {
                    // The backend would have a full copy, but the VPLEX volume would not.
                    throw APIException.badRequests.volumeForVpoolChangeHasFullCopies(volume.getLabel());
                } else {
                    // Can't be imported if it has snapshot sessions, because we
                    // don't currently support these behind VPLEX.
                    List<BlockSnapshotSession> snapSessions = CustomQueryUtility.queryActiveResourcesByConstraint(_dbClient,
                            BlockSnapshotSession.class, ContainmentConstraint.Factory.getParentSnapshotSessionConstraint(volume.getId()));
                    if (!snapSessions.isEmpty()) {
                        throw APIException.badRequests.cannotImportVolumeWithSnapshotSessions(volume.getLabel());
                    }
                }
            } else if (VirtualPool.vPoolSpecifiesProtection(newVpool)) {
                // VNX/VMAX import to RP cases (currently one)
                notSuppReasonBuff.setLength(0);
                if (!VirtualPoolChangeAnalyzer.isSupportedAddRPProtectionVirtualPoolChange(volume, currentVpool, newVpool, _dbClient,
                        notSuppReasonBuff)) {
                    _log.warn("VirtualPool change to Add RP Protection for volume is not supported: {}",
                            notSuppReasonBuff.toString());
                    throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                            notSuppReasonBuff.toString());
                } else if (BlockFullCopyUtils.volumeHasFullCopySession(volume, _dbClient)) {
                    // Full copies not supported for RP protected volumes.
                    throw APIException.badRequests.volumeForRPVpoolChangeHasFullCopies(volume.getLabel());
                } else {
                    // Can't add RP if it has snapshot sessions, because we
                    // don't currently support these for RP protected volumes.
                    List<BlockSnapshotSession> snapSessions = CustomQueryUtility.queryActiveResourcesByConstraint(_dbClient,
                            BlockSnapshotSession.class, ContainmentConstraint.Factory.getParentSnapshotSessionConstraint(volume.getId()));
                    if (!snapSessions.isEmpty()) {
                        throw APIException.badRequests.volumeForRPVpoolChangeHasSnapshotSessions(volume.getLabel());
                    }
                }
            } else if (VirtualPool.vPoolSpecifiesProtection(currentVpool)
                    && !VirtualPool.vPoolSpecifiesProtection(newVpool)) {
                notSuppReasonBuff.setLength(0);
                if (!VirtualPoolChangeAnalyzer.isSupportedRPRemoveProtectionVirtualPoolChange(volume, currentVpool, newVpool,
                        _dbClient, notSuppReasonBuff)) {
                    throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                            notSuppReasonBuff.toString());
                }
            } else if (VirtualPool.vPoolSpecifiesSRDF(newVpool)) {
                // VMAX import to SRDF cases (currently one)
                notSuppReasonBuff.setLength(0);
                if (!VirtualPoolChangeAnalyzer.isSupportedSRDFVolumeVirtualPoolChange(volume, currentVpool, newVpool, _dbClient,
                        notSuppReasonBuff)) {
                    _log.warn("VMAX VirtualPool change for volume is not supported: {}",
                            notSuppReasonBuff.toString());
                    throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                            notSuppReasonBuff.toString());
                } else if (BlockFullCopyUtils.volumeHasFullCopySession(volume, _dbClient)) {
                    // Full copy not supported for volumes with asynchronous copy mode.
                    Map<URI, VpoolRemoteCopyProtectionSettings> remoteCopySettingsMap = VirtualPool.getRemoteProtectionSettings(newVpool,
                            _dbClient);
                    VpoolRemoteCopyProtectionSettings remoteCopyProtectionSettings = remoteCopySettingsMap.values().iterator().next();
                    if (SupportedCopyModes.ASYNCHRONOUS.toString().equalsIgnoreCase(remoteCopyProtectionSettings.getCopyMode())) {
                        throw APIException.badRequests.volumeForSRDFVpoolChangeHasFullCopies(volume.getLabel());
                    }
                }
            } else if (!NullColumnValueGetter.isNullNamedURI(volume.getSrdfParent())
                    || (volume.getSrdfTargets() != null && !volume.getSrdfTargets().isEmpty())) {
                // Cannot move SRDF Volume to non SRDF VPool
                throw APIException.badRequests.srdfVolumeVPoolChangeToNonSRDFVPoolNotSupported(volume.getId());
            } else if (VirtualPool.vPoolSpecifiesMirrors(newVpool, _dbClient)) {
                notSuppReasonBuff.setLength(0);
                if (!VirtualPoolChangeAnalyzer.isSupportedAddMirrorsVirtualPoolChange(volume, currentVpool, newVpool,
                        _dbClient, notSuppReasonBuff)) {
                    _log.warn("VirtualPool change to add continuous copies for volume {} is not supported: {}",
                            volume.getId(), notSuppReasonBuff.toString());
                    throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                            notSuppReasonBuff.toString());
                }
            } else {
                String errMsg = "there was an invalid property mismatch between source and target vPools.";
                _log.error(errMsg);
                notSuppReasonBuff.append(errMsg);
                throw APIException.badRequests.changeToVirtualPoolNotSupported(newVpool.getLabel(),
                        notSuppReasonBuff.toString());
            }
        } else {
            _log.info("VirtualPool change volume is not a vplex, vmax or vnxblock volume");
            throw new ServiceCodeException(
                    ServiceCode.API_VOLUME_VPOOL_CHANGE_DISRUPTIVE,
                    "VirtualPool change is not supported for volume {0}",
                    new Object[] { volume.getId() });
        }
    }

    /**
     * Performs verification on the VPLEX volume to ensure it is a candidate for migration.
     *
     * @param volume VPLEX volume to check
     * @param currentVpool The current vpool where the volume is placed
     * @param newVpool The target vpool where the volume will be placed after migration
     */
    public static void verifyVPlexVolumeForDataMigration(Volume volume, VirtualPool currentVpool, VirtualPool newVpool, DbClient _dbClient) {
        _log.info(String.format("Verifying that the VPlex volume[%s](%s) qualifies for Data Migration"
                + " moving from current vpool [%s](%s) to new vpool [%s](%s).",
                volume.getLabel(), volume.getId(),
                currentVpool.getLabel(), currentVpool.getId(),
                newVpool.getLabel(), newVpool.getId()));

        // Determine if source side will be migrated.
        boolean migrateSourceVolume = VirtualPoolChangeAnalyzer
                .vpoolChangeRequiresMigration(currentVpool, newVpool);

        // Determine if HA side will be migrated.
        boolean migrateHAVolume = false;
        VirtualPool currentHaVpool = VirtualPoolChangeAnalyzer
                .getHaVpool(currentVpool, _dbClient);
        if (currentHaVpool != null) {
            VirtualPool newHaVpool = VirtualPoolChangeAnalyzer
                    .getNewHaVpool(currentVpool, newVpool, _dbClient);
            migrateHAVolume = VirtualPoolChangeAnalyzer
                    .vpoolChangeRequiresMigration(currentHaVpool, newHaVpool);
        }

        // Verify the VPLEX volume structure. Ingested volumes
        // can only be migrated if the component structure of
        // the volume is supported by ViPR.
        verifyVPlexVolumeStructureForDataMigration(volume, currentVpool,
                migrateSourceVolume, migrateHAVolume, _dbClient);

        // Check for snaps, mirrors, and full copies
        if (migrateSourceVolume) {
            // The vpool change is a data migration and the source
            // side backend volume will be migrated. If the volume
            // has snapshots, then the vpool change will not be
            // allowed because VPLEX snapshots are just snapshots
            // of this backend volume. The user would lose all
            // snapshots if we allowed the vpool change. The user
            // must explicitly go and delete their snapshots first.
            // the same is true for volumes that have full copies
            // from which they are not detached and also full copy
            // volumes that are not detached from their source. If
            // not detached a full copy session still exists between
            // this backend volume and some other volume.
            //
            // Note: We make this validation here instead of in the
            // verification VirtualPoolChangeAnalyzer method
            // "getSupportedVPlexVolumeVirtualPoolChangeOperation"
            // because this method is called from not only the API
            // to change the volume virtual pool, but also the API
            // that determines the virtual pools to which a volume
            // can be changed. The latter API is used by the UI to
            // populate the list of volumes. We want volumes with
            // snaps to appear in the list, so that the user will
            // know that if they remove the snapshots, they can
            // perform the vpool change.
            Volume srcVolume = VPlexUtil.getVPLEXBackendVolume(volume, true, _dbClient, false);
            if (srcVolume != null) {
                // Has a source volume, so not ingested.
                List<BlockSnapshot> snapshots = CustomQueryUtility
                        .queryActiveResourcesByConstraint(_dbClient,
                                BlockSnapshot.class, ContainmentConstraint.Factory
                                        .getVolumeSnapshotConstraint(srcVolume.getId()));
                if (!snapshots.isEmpty()) {
                    throw APIException.badRequests
                            .volumeForVpoolChangeHasSnaps(volume.getId().toString());
                }

                // Check for snapshot sessions for the volume.
                if (BlockSnapshotSessionUtils.volumeHasSnapshotSession(srcVolume, _dbClient)) {
                    throw APIException.badRequests.volumeForVpoolChangeHasSnaps(volume.getLabel());
                }

                // Can't migrate the source side backend volume if it is
                // has full copy sessions.
                if (BlockFullCopyUtils.volumeHasFullCopySession(srcVolume, _dbClient)) {
                    throw APIException.badRequests.volumeForVpoolChangeHasFullCopies(volume.getLabel());
                }
            }

            // If the volume has mirrors then Vpool change will not
            // be allowed. User needs to explicitly delete mirrors first.
            // This is applicable for both Local and Distributed volumes.
            // For distributed volume getMirrors will get mirror if any
            // on source or HA side.
            StringSet mirrorURIs = volume.getMirrors();
            if (mirrorURIs != null && !mirrorURIs.isEmpty()) {
                List<VplexMirror> mirrors = _dbClient.queryObject(VplexMirror.class,
                        StringSetUtil.stringSetToUriList(mirrorURIs));
                if (mirrors != null && !mirrors.isEmpty()) {
                    throw APIException.badRequests
                            .volumeForVpoolChangeHasMirrors(volume.getId().toString(), volume.getLabel());
                }
            }
        }
    }

    /**
     * Verifies that the component structure of an ingested VPLEX volume is such
     * that ViPR can support a data migration of the backend volumes. The
     * structure must be in the 1-1-1 format in which VGIPR creates VPLEX
     * volumes. That is, 1 backend storage volumes is consumed by 1 extent,
     * which in turn is consumed buy 1 local device. Note that this function
     * will make calls to the VPLEX to determine the volume's structure.
     *
     * @param volume
     *            A reference to a VPLEX volume.
     * @param currentVpool
     *            The vpool for the VPLEX volume.
     * @param migrateSourceVolume
     *            true if the source side requires migration.
     * @param migrateHAVolume
     *            true if the HA side requires migration.
     */
    private static void verifyVPlexVolumeStructureForDataMigration(Volume volume,
            VirtualPool currentVpool, boolean migrateSourceVolume, boolean migrateHAVolume, DbClient _dbClient) {
        boolean structureOK = true;
        if (volume.isIngestedVolumeWithoutBackend(_dbClient)) {
            if (migrateSourceVolume && migrateHAVolume) {
                structureOK = VPlexDeviceController.migrationSupportedForVolume(volume,
                        null, _dbClient);
            } else if (migrateSourceVolume) {
                structureOK = VPlexDeviceController.migrationSupportedForVolume(volume,
                        volume.getVirtualArray(), _dbClient);
            } else if (migrateHAVolume) {
                structureOK = VPlexDeviceController.migrationSupportedForVolume(volume,
                        VirtualPoolChangeAnalyzer.getHaVarrayURI(currentVpool),
                        _dbClient);
            }
        }

        if (!structureOK) {
            throw APIException.badRequests.invalidStructureForIngestedVolume(volume
                    .getLabel());
        }
    }

    /**
     * Returns a reference to the BlockServiceApi that should be used to execute
     * change the VirtualPool for the passed volume to the passed VirtualPool.
     *
     * @param volume
     *            A reference to the volume.
     * @param vpool
     *            A reference to the VirtualPool.
     *
     * @return A reference to the BlockServiceApi that should be used execute
     *         the VirtualPool change for the volume.
     */
    private BlockServiceApi getBlockServiceImplForVirtualPoolChange(Volume volume, VirtualPool vpool) {
        URI protectionSystemURI = isNullURI(volume.getProtectionController()) ? null : volume
                .getProtectionController();
        URI storageSystemURI = volume.getStorageController();
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storageSystemURI);
        String systemType = storageSystem.getSystemType();

        if (protectionSystemURI != null
                || VirtualPool.vPoolSpecifiesProtection(vpool)
                || (volume.checkForRp() && !VirtualPool.vPoolSpecifiesProtection(vpool))) {
            // Assume RP for now if the volume is associated with an
            // RP controller regardless of the VirtualPool change.
            // Also if the volume is unprotected currently and the vpool specifies protection.
            // Or if the volume is protected by RP and we're looking to move to a vpool without
            // protection.
            _log.info("Returning RP block service implementation.");
            return _blockServiceApis.get(DiscoveredDataObject.Type.rp.name());
        } else {
            if ((DiscoveredDataObject.Type.vplex.name().equals(systemType)) ||
                    (VirtualPool.vPoolSpecifiesHighAvailability(vpool))) {
                _log.info("Returning VPlex block service implementation.");
                return getBlockServiceImpl(DiscoveredDataObject.Type.vplex.name());
            } else if ((DiscoveredDataObject.Type.vnxblock.name().equals(systemType) ||
                    DiscoveredDataObject.Type.vmax.name().equals(systemType)) &&
                    VirtualPool.vPoolSpecifiesSRDF(vpool)) {
                _log.info("Returning SRDF service implementation.");
                return getBlockServiceImpl(SRDF);
            } else if ((DiscoveredDataObject.Type.vnxblock.name().equals(systemType) ||
                    DiscoveredDataObject.Type.vmax.name().equals(systemType)) &&
                    VirtualPool.vPoolSpecifiesMirrors(vpool, _dbClient)) {
                _log.info("Returning mirror service implementation.");
                return getBlockServiceImpl("mirror");
            } else {
                // If it's not a VPlex volume and the VirtualPool does not
                // specify high availability, then just assume the
                // default implementation for now.
                _log.info("Returning default service implementation.");
                return getBlockServiceImpl("default");
            }
        }
    }

    /**
     * Gets and verifies the VirtualPool passed in the request.
     *
     * TODO: Reuse the existing function (getVirtualPoolForVolumeCreateRequest) once the
     * capabilities removal is completed by Stalin, but rename the function to just
     * (getVirtualPoolForRequest).
     *
     * @param project
     *            A reference to the project.
     * @param cosURI
     *            The URI of the VirtualPool.
     * @param dbClient
     *            Reference to a database client.
     * @param permissionsHelper
     *            Reference to a permissions helper.
     *
     * @return A reference to the VirtualPool.
     */
    public static VirtualPool getVirtualPoolForRequest(Project project, URI cosURI, DbClient dbClient,
            PermissionsHelper permissionsHelper) {
        ArgValidator.checkUri(cosURI);
        VirtualPool cos = dbClient.queryObject(VirtualPool.class, cosURI);
        ArgValidator.checkEntity(cos, cosURI, false);
        if (!VirtualPool.Type.block.name().equals(cos.getType())) {
            throw APIException.badRequests.virtualPoolNotForFileBlockStorage(VirtualPool.Type.block.name());
        }

        permissionsHelper.checkTenantHasAccessToVirtualPool(project.getTenantOrg().getURI(), cos);
        return cos;
    }

    /**
     * Volume is not a zone level resource
     */
    @Override
    protected boolean isZoneLevelResource() {
        return false;
    }

    @Override
    protected ResourceTypeEnum getResourceType() {
        return ResourceTypeEnum.VOLUME;
    }

    /**
     * Get search results by name in zone or project.
     *
     * @return SearchedResRepList
     */
    @Override
    protected SearchedResRepList getNamedSearchResults(String name, URI projectId) {
        SearchedResRepList resRepList = new SearchedResRepList(getResourceType());
        if (projectId == null) {
            _dbClient.queryByConstraint(
                    PrefixConstraint.Factory.getLabelPrefixConstraint(getResourceClass(), name),
                    resRepList);
        } else {
            _dbClient.queryByConstraint(
                    ContainmentPrefixConstraint.Factory.getVolumeUnderProjectConstraint(
                            projectId, name),
                    resRepList);
        }
        return resRepList;
    }

    /**
     * Get search results by project alone.
     *
     * @return SearchedResRepList
     */
    @Override
    protected SearchedResRepList getProjectSearchResults(URI projectId) {
        SearchedResRepList resRepList = new SearchedResRepList(getResourceType());
        _dbClient.queryByConstraint(
                ContainmentConstraint.Factory.getProjectVolumeConstraint(projectId),
                resRepList);
        return resRepList;
    }

    /**
     * Additional search criteria for a volume.
     *
     * If a matching volume is not found, an empty list is returned.
     *
     * Parameters - wwn String - WWN of the volume
     * - virtual_array String - URI of the source virtual array
     * - personality String - source, target, metadata
     */
    @Override
    protected SearchResults getOtherSearchResults(Map<String, List<String>> parameters, boolean authorized) {
        SearchResults result = new SearchResults();

        String[] searchCriteria = { SEARCH_WWN, SEARCH_VARRAY, SEARCH_PERSONALITY, SEARCH_PROTECTION };

        // Make sure the parameters passed in contain at least one of our search criteria
        // Here we search by wwn or virtual_array
        boolean found = false;
        for (String search : searchCriteria) {
            if (parameters.containsKey(search)) {
                found = true;
            }
        }

        if (!found) {
            throw APIException.badRequests.invalidParameterSearchMissingParameter(getResourceClass().getName(), searchCriteria.toString());
        }

        // Make sure all parameters are our parameters, otherwise post an exception because we don't support other
        // search criteria than our
        // own.
        String nonVolumeKey = null;
        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            found = false;
            for (String search : searchCriteria) {
                if (entry.getKey().equals(search)) {
                    found = true;
                }
            }
            if (!found) {
                nonVolumeKey = entry.getKey();
            }
        }

        if (nonVolumeKey != null) {
            throw APIException.badRequests.parameterForSearchCouldNotBeCombinedWithAnyOtherParameter(getResourceClass().getName(),
                    searchCriteria.toString(), nonVolumeKey);
        }

        boolean simpleSearch = false;

        // Now perform individual searches based on the input criteria. These results are stored and joined later if
        // there were
        // multiple search criteria.
        List<List<SearchResultResourceRep>> resRepLists = new ArrayList<List<SearchResultResourceRep>>();
        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            for (String searchValue : entry.getValue()) {
                SearchedResRepList resRepList = new SearchedResRepList(getResourceType());

                if (entry.getKey().equals(SEARCH_WWN)) {
                    simpleSearch = true;
                    String wwn = searchValue;

                    _dbClient.queryByConstraint(
                            AlternateIdConstraint.Factory.getVolumeWwnConstraint(wwn.toUpperCase()),
                            resRepList);
                } else if (entry.getKey().equals(SEARCH_VARRAY)) {
                    simpleSearch = true;
                    String varrayId = searchValue;

                    _dbClient.queryByConstraint(
                            AlternateIdConstraint.Factory.getConstraint(Volume.class, "varray", varrayId),
                            resRepList);
                } else if (entry.getKey().equals(SEARCH_PERSONALITY)) {
                    simpleSearch = true;
                    String personality = searchValue;

                    // Validate the personality type
                    boolean valid = false;
                    for (PersonalityTypes personalityType : Volume.PersonalityTypes.values()) {
                        if (personalityType.toString().equals(personality)) {
                            valid = true;
                        }
                    }
                    if (!valid) {
                        throw APIException.badRequests.parameterForSearchHasInvalidSearchValueWithSuggestions(getResourceClass().getName(),
                                entry.getKey(),
                                personality,
                                PersonalityTypes.values());
                    }

                    _dbClient.queryByConstraint(
                            AlternateIdConstraint.Factory.getConstraint(Volume.class, "personality", personality),
                            resRepList);
                }

                // Convert to a list; SearchedResRepList is immutable and not really made for what we're doing here.
                List<SearchResultResourceRep> repList = new ArrayList<SearchResultResourceRep>();
                if (resRepList.iterator() != null) {
                    for (SearchResultResourceRep res : resRepList) {
                        repList.add(res);
                    }
                    resRepLists.add(repList);
                }
            }
        }

        // Now perform a "join" on the resRepList entries to create a single set of resources
        Set<SearchResultResourceRep> resRepSet = new HashSet<SearchResultResourceRep>();
        for (List<SearchResultResourceRep> resList : resRepLists) {
            for (SearchResultResourceRep res : resList) {
                resRepSet.add(res);
            }
        }

        // Remove entries that aren't in every collection
        for (List<SearchResultResourceRep> resList : resRepLists) {
            resRepSet.retainAll(resList);
        }

        //
        // Non-Indexed (manual) query result business logic goes here, after we've already reduced the list
        //
        boolean advancedQuery = false;
        if (parameters.containsKey(SEARCH_PROTECTION)) {
            // Prevent the expensive advanced query unless you see certain parameters
            advancedQuery = true;
        }

        // Apply protection search criteria, if applicable
        // Keep the volumes in the outer loop so we will never call the DB more than O(n) times where n is the size of
        // resRepSet
        if (advancedQuery) {

            // If no simple Query was run above, then we have to start here. If the caller likes good performance, they
            // would include other
            // simple parameters, like personality=SOURCE to pre-fill a much smaller list of objects and avoid this.
            if (!simpleSearch) {
                List<URI> volumes = _dbClient.queryByType(Volume.class, true);
                for (URI volumeId : volumes) {
                    RestLinkRep selfLink = new RestLinkRep("self", RestLinkFactory.newLink(ResourceTypeEnum.VOLUME, volumeId));
                    resRepSet.add(new SearchResultResourceRep(volumeId, selfLink, null));
                }
                _log.warn(String.format(
                        "Performance of Volume search is poor when specifying only %s with no additional search parameters." +
                                "Search performs faster when combined with other parameters such as %s, %s",
                        SEARCH_PROTECTION, SEARCH_PERSONALITY, SEARCH_VARRAY));
            }

            List<SearchResultResourceRep> resToInclude = new ArrayList<SearchResultResourceRep>();
            for (SearchResultResourceRep res : resRepSet) {
                Volume volume = _dbClient.queryObject(Volume.class, res.getId());
                boolean personalityIsSource = volume.getPersonality() == null
                        || volume.getPersonality().equalsIgnoreCase(Volume.PersonalityTypes.SOURCE.toString());
                boolean ha = volume.getAssociatedVolumes() != null && !volume.getAssociatedVolumes().isEmpty();
                boolean srdf = volume.getSrdfTargets() != null && !volume.getSrdfTargets().isEmpty();
                boolean rp = volume.getRpTargets() != null && !volume.getRpTargets().isEmpty();
                boolean isProtected = personalityIsSource && (ha || srdf || rp);
                boolean includeResource = true;
                for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
                    for (String searchValue : entry.getValue()) {
                        if (entry.getKey().equals(SEARCH_PROTECTION)) {
                            // Validate the protection parameter
                            boolean valid = false;
                            String validProtection[] = { TRUE_STR, FALSE_STR, RP, SRDF, VPLEX, HA };
                            for (String validValue : validProtection) {
                                if (validValue.toString().equalsIgnoreCase(searchValue)) {
                                    valid = true;
                                }
                            }

                            if (!valid) {
                                throw APIException.badRequests.parameterForSearchHasInvalidSearchValueWithSuggestions(getResourceClass()
                                        .getName(),
                                        entry.getKey(),
                                        searchValue,
                                        validProtection);
                            }

                            if ((searchValue.equals(TRUE_STR) && !isProtected) ||
                                    (searchValue.equals(FALSE_STR) && isProtected)) {
                                includeResource = false;
                            } else if (searchValue.equalsIgnoreCase(RP) && !rp) {
                                includeResource = false;
                            } else if ((searchValue.equalsIgnoreCase(VPLEX) || searchValue.equalsIgnoreCase(HA)) && !ha) {
                                includeResource = false;
                            } else if (searchValue.equalsIgnoreCase(SRDF) && !srdf) {
                                includeResource = false;
                            }
                        }
                    }
                }
                if (includeResource) {
                    resToInclude.add(res);
                }
            }

            // Reduce the set
            resRepSet.retainAll(resToInclude);
        }

        // Convert to a format consumable by our utilities
        List<SearchResultResourceRep> resRepList = new ArrayList<SearchResultResourceRep>();
        for (SearchResultResourceRep res : resRepSet) {
            resRepList.add(res);
        }

        if (!authorized) {
            Iterator<SearchResultResourceRep> _queryResultIterator = resRepList.iterator();
            ResRepFilter<SearchResultResourceRep> resrepFilter = (ResRepFilter<SearchResultResourceRep>) getPermissionFilter(
                    getUserFromContext(), _permissionsHelper);

            SearchedResRepList filteredResRepList = new SearchedResRepList();
            filteredResRepList.setResult(
                    new FilterIterator<SearchResultResourceRep>(_queryResultIterator, resrepFilter));

            result.setResource(filteredResRepList);
        } else {
            result.setResource(resRepList);
        }

        return result;
    }

    /**
     * Get object specific permissions filter
     *
     */
    @Override
    protected ResRepFilter<? extends RelatedResourceRep> getPermissionFilter(StorageOSUser user,
            PermissionsHelper permissionsHelper) {
        return new ProjOwnedResRepFilter(user, permissionsHelper, Volume.class);
    }

    /**
     * Get info for protectionSet including owner, parent protectionSet, and child protectionSets
     *
     *
     * @prereq none
     *
     * @param id
     *            Volume identifier
     * @param pid
     *            the URN of a ViPR ProtectionSet
     *
     * @brief Show protection set
     * @return ProtectionSet details
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/protection-sets/{pid}")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public ProtectionSetRestRep getProtectionSet(@PathParam("id") URI id, @PathParam("pid") URI pid) {
        validateProtectionSetUri(id, pid);
        _log.info("Getting protection set for ID: " + pid);
        ProtectionSet protectionSet = queryProtectionSetResource(pid);
        _log.info("Protection set status: " + protectionSet.getProtectionStatus());
        return map(protectionSet);
    }

    /**
     * This api allows the user to add new journal volume(s) to a recoverpoint
     * consistency group copy
     *
     * @param param
     *            POST data containing the journal volume(s) creation information.
     *
     * @brief Add journal volume(s) to the exiting recoverpoint CG copy
     * @return A reference to a BlockTaskList containing a list of
     *         TaskResourceRep references specifying the task data for the
     *         journal volume creation tasks.
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/protection/addJournalCapacity")
    public TaskList addJournalCapacity(VolumeCreate param) throws InternalException {
        ArgValidator.checkFieldNotNull(param, "volume_create");

        ArgValidator.checkFieldNotNull(param.getName(), "name");

        ArgValidator.checkFieldNotNull(param.getSize(), "size");

        ArgValidator.checkFieldNotNull(param.getCount(), "count");

        ArgValidator.checkFieldUriType(param.getProject(), Project.class, "project");

        // Get and validate the project.
        Project project = _permissionsHelper.getObjectById(param.getProject(), Project.class);
        ArgValidator.checkEntity(project, param.getProject(), isIdEmbeddedInURL(param.getProject()));

        final URI actualId = project.getId();

        // Verify the user is authorized.
        BlockServiceUtils.verifyUserIsAuthorizedForRequest(project, getUserFromContext(), _permissionsHelper);

        // Get and validate the varray
        ArgValidator.checkFieldUriType(param.getVarray(), VirtualArray.class, "varray");
        VirtualArray varray = BlockServiceUtils.verifyVirtualArrayForRequest(project,
                param.getVarray(), uriInfo, _permissionsHelper, _dbClient);
        ArgValidator.checkEntity(varray, param.getVarray(), isIdEmbeddedInURL(param.getVarray()));

        // Get and validate the journal vPool.
        VirtualPool vpool = getVirtualPoolForVolumeCreateRequest(project, param);

        VirtualPoolCapabilityValuesWrapper capabilities = new VirtualPoolCapabilityValuesWrapper();
        capabilities.put(VirtualPoolCapabilityValuesWrapper.ADD_JOURNAL_CAPACITY, Boolean.TRUE);
        // Get the count indicating the number of journal volumes to add. If not
        // passed assume 1.
        Integer volumeCount = 1;
        Long volumeSize = 0L;

        if (param.getCount() <= 0) {
            throw APIException.badRequests.parameterMustBeGreaterThan("count", 0);
        }
        if (param.getCount() > MAX_VOLUME_COUNT) {
            throw APIException.badRequests.exceedingLimit("count", MAX_VOLUME_COUNT);
        }
        volumeCount = param.getCount();
        capabilities.put(VirtualPoolCapabilityValuesWrapper.RESOURCE_COUNT, volumeCount);

        // Validate the requested volume size is greater then 0.
        volumeSize = SizeUtil.translateSize(param.getSize());
        // Validate the requested volume size is at least 1 GB.
        if (volumeSize < GB) {
            throw APIException.badRequests.leastVolumeSize("1");
        }
        capabilities.put(VirtualPoolCapabilityValuesWrapper.SIZE, volumeSize);

        // verify quota
        long size = volumeCount * SizeUtil.translateSize(param.getSize());
        TenantOrg tenant = _dbClient.queryObject(TenantOrg.class, project.getTenantOrg().getURI());
        ArgValidator.checkEntity(tenant, project.getTenantOrg().getURI(), false);
        CapacityUtils.validateQuotasForProvisioning(_dbClient, vpool, project, tenant, size, "volume");

        if (null != vpool.getThinVolumePreAllocationPercentage()
                && 0 < vpool.getThinVolumePreAllocationPercentage()) {
            capabilities.put(VirtualPoolCapabilityValuesWrapper.THIN_VOLUME_PRE_ALLOCATE_SIZE, VirtualPoolUtil
                    .getThinVolumePreAllocationSize(vpool.getThinVolumePreAllocationPercentage(), volumeSize));
        }

        if (VirtualPool.ProvisioningType.Thin.toString().equalsIgnoreCase(
                vpool.getSupportedProvisioningType())) {
            capabilities.put(VirtualPoolCapabilityValuesWrapper.THIN_PROVISIONING, Boolean.TRUE);
        }

        // Get and validate the BlockConsistencyGroup
        BlockConsistencyGroup consistencyGroup = queryConsistencyGroup(param.getConsistencyGroup());

        // Check that the project and the CG project are the same
        final URI expectedId = consistencyGroup.getProject().getURI();
        checkProjectsMatch(expectedId, project.getId());

        // Validate the CG type is RP
        if (!consistencyGroup.getRequestedTypes().contains(BlockConsistencyGroup.Types.RP.toString())) {
            throw APIException.badRequests.consistencyGroupIsNotCompatibleWithRequest(
                    consistencyGroup.getId(), consistencyGroup.getTypes().toString(), BlockConsistencyGroup.Types.RP.toString());
        }
        capabilities.put(VirtualPoolCapabilityValuesWrapper.BLOCK_CONSISTENCY_GROUP, consistencyGroup.getId());

        // Create a unique task id if one is not passed in the request.
        String task = UUID.randomUUID().toString();

        auditOp(OperationTypeEnum.ADD_JOURNAL_VOLUME, true, AuditLogManager.AUDITOP_BEGIN,
                param.getName(), volumeCount, varray.getId().toString(), actualId.toString());

        // add the journal capacity to the CG
        RPBlockServiceApiImpl blockServiceImpl = (RPBlockServiceApiImpl) getBlockServiceImpl(DiscoveredDataObject.Type.rp.name());
        return blockServiceImpl.addJournalCapacity(param, project, varray, vpool, consistencyGroup, capabilities, task);
    }

    /**
     * Perform simple validation-- make sure this volume owns the protection set.
     *
     * @param vid
     *            volume ID
     * @param id
     *            the URN of a ViPR protection set
     */
    private void validateProtectionSetUri(URI vid, URI id) {
        ArgValidator.checkUri(vid);
        ArgValidator.checkUri(id);
        Volume volume = _dbClient.queryObject(Volume.class, vid);
        ArgValidator.checkEntity(volume, vid, isIdEmbeddedInURL(vid));
        if (volume.getProtectionSet() == null || !(volume.getProtectionSet().getURI().equals(id))) {
            throw APIException.badRequests.invalidVolumeForProtectionSet();
        }
    }

    /**
     * queryResource(), but for protection set.
     *
     * @param id
     *            the URN of a ViPR ID of protection set
     * @return protection set object
     */
    private ProtectionSet queryProtectionSetResource(URI id) {
        if (id == null) {
            return null;
        }

        ProtectionSet ret = _permissionsHelper.getObjectById(id, ProtectionSet.class);
        ArgValidator.checkEntityNotNull(ret, id, isIdEmbeddedInURL(id));
        return ret;
    }

    public static void checkVolumesParameter(final BulkDeleteParam volumeURIs) {
        if (volumeURIs == null || volumeURIs.getIds().isEmpty()) {
            throw APIException.badRequests.noVolumesSpecifiedInRequest();
        }
    }

    private void validateContinuousCopyName(String name, Integer count, Volume sourceVolume) {
        if (sourceVolume.getMirrors() == null || sourceVolume.getMirrors().isEmpty()) {
            return;
        }
        boolean vplexVolume = checkIfVolumeIsForVplex(sourceVolume.getId());

        List<String> dupList = new ArrayList<String>();
        for (String mirrorURI : sourceVolume.getMirrors()) {
            if (vplexVolume) {
                VplexMirror mirror = _dbClient.queryObject(VplexMirror.class, URI.create(mirrorURI));
                if (!mirror.getInactive() &&
                        ((count > 1 && mirror.getLabel().matches("^" + name + "\\-\\d+$")) ||
                        (count == 1 && name.equals(mirror.getLabel())))) {
                    dupList.add(mirror.getLabel());
                }
            } else {
                BlockMirror mirror = _dbClient.queryObject(BlockMirror.class, URI.create(mirrorURI));
                if (null != mirror && !mirror.getInactive() &&
                        ((count > 1 && mirror.getLabel().matches("^" + name + "\\-\\d+$")) ||
                        (count == 1 && name.equals(mirror.getLabel())))) {
                    dupList.add(mirror.getLabel());
                }
            }
        }

        if (!dupList.isEmpty()) {
            throw APIException.badRequests.blockSourceAlreadyHasContinuousCopiesOfSameName(dupList);
        }
    }

    /**
     * Validate that none of the passed URIs represents an internal
     * volume such as, a VPLEX volume. If so, throw a bad request
     * exception unless the SUPPORTS_FORCE flag is present AND force is
     * true.
     *
     * @param dbClient
     *            Reference to a database client
     * @param blockObjectURIs
     *            A list of blockObject URIs to verify.
     * @param force
     *            boolean value representing whether or not we want to force the operation
     */
    public static void validateNoInternalBlockObjects(DbClient dbClient,
            List<URI> blockObjectURIs, boolean force) {
        if (blockObjectURIs != null) {
            for (URI blockObjectURI : blockObjectURIs) {
                BlockObject blockObject = BlockObject.fetch(dbClient, blockObjectURI);
                BlockServiceUtils.validateNotAnInternalBlockObject(blockObject, force);
            }
        }
    }

    /**
     * Stop the specified mirror(s) for the source volume
     *
     *
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param copyID
     *            Copy volume ID, none specified stops all copies
     *
     * @return TaskList
     */
    private TaskList stopMirrors(URI id, URI copyID) {
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        Volume sourceVolume = queryVolumeResource(id);
        ArgValidator.checkEntity(sourceVolume, id, true);

        StringSet mirrors = sourceVolume.getMirrors();

        if (mirrors == null || mirrors.isEmpty()) {
            throw APIException.badRequests.invalidParameterVolumeHasNoContinuousCopies(sourceVolume.getId());
        }

        ArrayList<URI> mirrorList = null;

        if (copyID != null) {
            ArgValidator.checkFieldUriType(copyID, BlockMirror.class, "copyID");
            BlockMirror mirror = queryMirror(copyID);
            ArgValidator.checkEntity(mirror, copyID, true);
            if (!mirror.getSource().getURI().equals(id)) {
                throw APIException.badRequests.invalidParameterBlockCopyDoesNotBelongToVolume(copyID, id);
            } else {
                mirrorList = new ArrayList();
                mirrorList.add(mirror.getId());
            }
        }

        String task = UUID.randomUUID().toString();
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, sourceVolume.getStorageController());
        BlockServiceApi blockServiceApi = getBlockServiceImpl("mirror");

        return blockServiceApi.stopNativeContinuousCopies(storageSystem, sourceVolume, mirrorList, task);
    }

    /**
     * Stop the specified vplex mirror(s) for the source volume
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param copyID
     *            Copy volume ID, none specified stops all copies
     *
     * @return TaskList
     */
    private TaskList stopVplexMirrors(URI id, URI copyID) {
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        Volume sourceVolume = queryVolumeResource(id);
        ArgValidator.checkEntity(sourceVolume, id, true);

        StringSet mirrors = sourceVolume.getMirrors();

        if (mirrors == null || mirrors.isEmpty()) {
            throw APIException.badRequests.invalidParameterVolumeHasNoContinuousCopies(sourceVolume.getId());
        }

        ArrayList<URI> mirrorList = null;

        if (copyID != null) {
            ArgValidator.checkFieldUriType(copyID, VplexMirror.class, "copyID");
            VplexMirror mirror = queryVplexMirror(copyID);
            ArgValidator.checkEntity(mirror, copyID, true);
            if (!mirror.getSource().getURI().equals(id)) {
                throw APIException.badRequests.invalidParameterBlockCopyDoesNotBelongToVolume(copyID, id);
            } else {
                mirrorList = new ArrayList();
                mirrorList.add(mirror.getId());
            }
        }

        String task = UUID.randomUUID().toString();
        BlockServiceApi blockServiceApi;
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, sourceVolume.getStorageController());
        blockServiceApi = getBlockServiceImpl(DiscoveredDataObject.Type.vplex.name());

        return blockServiceApi.stopNativeContinuousCopies(storageSystem, sourceVolume, mirrorList, task);
    }

    /**
     * Start the specified mirror(s) for the source volume
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param copy
     *            copyID Copy volume ID, none specified starts all copies
     *
     * @return TaskList
     */
    private TaskList startMirrors(URI id, NativeContinuousCopyCreate copy) {

        String taskId = UUID.randomUUID().toString();

        int count = 1;
        if (copy.getCount() != null) {
            count = copy.getCount();
        }

        Volume sourceVolume = queryVolumeResource(id);
        // Don't operate on VPLEX backend or RP Journal volumes.
        BlockServiceUtils.validateNotAnInternalBlockObject(sourceVolume, false);

        // Make sure that we don't have some pending
        // operation against the volume
        checkForPendingTasks(Arrays.asList(sourceVolume.getTenant().getURI()), Arrays.asList(sourceVolume));

        if (count <= 0) {
            throw APIException.badRequests.invalidParameterRangeLessThanMinimum("count", count, 1);
        }

        ArgValidator.checkEntity(sourceVolume, id, isIdEmbeddedInURL(id));
        validateContinuousCopyName(copy.getName(), count, sourceVolume);

        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, sourceVolume.getStorageController());
        VirtualPool sourceVPool = _dbClient.queryObject(VirtualPool.class, sourceVolume.getVirtualPool());

        validateMirrorCount(sourceVolume, sourceVPool, count);

        // validate VMAX3 source volume for active snap sessions.
        if (storageSystem != null && storageSystem.checkIfVmax3()) {
            BlockServiceUtils.validateVMAX3ActiveSnapSessionsExists(sourceVolume.getId(), _dbClient, MIRRORS);
        }

        VirtualPoolCapabilityValuesWrapper capabilities = new VirtualPoolCapabilityValuesWrapper();
        capabilities.put(VirtualPoolCapabilityValuesWrapper.RESOURCE_COUNT, count);
        capabilities.put(VirtualPoolCapabilityValuesWrapper.SIZE, sourceVolume.getCapacity());
        capabilities.put(VirtualPoolCapabilityValuesWrapper.THIN_PROVISIONING, sourceVolume.getThinlyProvisioned());
        capabilities.put(VirtualPoolCapabilityValuesWrapper.THIN_VOLUME_PRE_ALLOCATE_SIZE,
                sourceVolume.getThinVolumePreAllocationSize());

        BlockServiceApi serviceApi = null;
        if ((storageSystem != null)
                && (DiscoveredDataObject.Type.vplex.name().equals(storageSystem
                        .getSystemType()))) {
            serviceApi = getBlockServiceImpl(storageSystem.getSystemType());
        } else {
            serviceApi = getBlockServiceImpl("mirror");
        }
        return serviceApi.startNativeContinuousCopies(storageSystem, sourceVolume,
                sourceVPool, capabilities, copy, taskId);
    }

    /**
     * This method validates if the count requested by user to create
     * mirror(s) for a volume is valid.
     *
     * @param sourceVolume
     *            The reference to volume for which mirrors needs to be created
     * @param sourceVPool
     *            The reference to virtual pool to which volume is is associated
     * @param count
     *            The number of mirrors requested to be created
     */
    private void validateMirrorCount(Volume sourceVolume, VirtualPool sourceVPool, int count) {
        int currentMirrorCount = (sourceVolume.getMirrors() == null || sourceVolume.getMirrors().isEmpty()) ? 0
                : sourceVolume.getMirrors().size();
        int requestedMirrorCount = currentMirrorCount + count;
        if (sourceVPool.getHighAvailability() != null
                && sourceVPool.getHighAvailability().equals(VirtualPool.HighAvailabilityType.vplex_distributed.name())) {
            VPlexUtil.validateMirrorCountForVplexDistVolume(sourceVolume, sourceVPool, count, currentMirrorCount, requestedMirrorCount,
                    _dbClient);
        } else if (sourceVPool.getMaxNativeContinuousCopies() < requestedMirrorCount) {
            throw APIException.badRequests.invalidParameterBlockMaximumCopiesForVolumeExceeded(sourceVPool.getMaxNativeContinuousCopies(),
                    sourceVolume.getLabel(), sourceVolume.getId(), currentMirrorCount);
        }
    }

    /**
     * Pause the specified mirror(s) for the source volume
     *
     *
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param sync
     *            flag for pause operation; true=split, false=fracture
     * @param copyID
     *            copyID Copy volume ID, none specified pauses all copies
     *
     * @return TaskList
     */
    private TaskList pauseMirrors(URI id, String sync, URI copyID) {
        Volume sourceVolume = queryVolumeResource(id);
        ArgValidator.checkEntity(sourceVolume, id, true);

        StringSet mirrors = sourceVolume.getMirrors();
        if (mirrors == null || mirrors.isEmpty()) {
            throw APIException.badRequests.invalidParameterVolumeHasNoContinuousCopies(sourceVolume.getId());
        }

        ArrayList<BlockMirror> mirrorList = null;

        if (copyID != null) {
            BlockMirror mirror = queryMirror(copyID);
            ArgValidator.checkEntity(mirror, copyID, true);
            if (!mirror.getSource().getURI().equals(id)) {
                throw APIException.badRequests.invalidParameterBlockCopyDoesNotBelongToVolume(copyID, id);
            } else {
                mirrorList = new ArrayList();
                mirrorList.add(mirror);
            }
        }

        if (sync != null) {
            ArgValidator.checkFieldValueFromArrayIgnoreCase(sync, ProtectionOp.SYNC.getRestOp(), TRUE_STR, FALSE_STR);
        }
        Boolean syncParam = Boolean.parseBoolean(sync);

        String task = UUID.randomUUID().toString();
        StorageSystem device = _dbClient.queryObject(StorageSystem.class, sourceVolume.getStorageController());
        BlockServiceApi blockServiceApi = getBlockServiceImpl("mirror");

        auditOp(OperationTypeEnum.FRACTURE_VOLUME_MIRROR, true, AuditLogManager.AUDITOP_BEGIN,
                mirrorList == null ? mirrors : mirrorList, sync);

        return blockServiceApi.pauseNativeContinuousCopies(device, sourceVolume, mirrorList, syncParam, task);
    }

    /**
     * Resume the specified mirror(s) for the source volume
     *
     *
     *
     * @param id
     *            the URN of a ViPR Source volume
     * @param copyID
     *            Copy volume ID, none specified resumes all copies
     *
     * @return TaskList
     */
    private TaskList resumeMirrors(URI id, URI copyID) {
        ArgValidator.checkFieldUriType(id, Volume.class, "id");
        Volume sourceVolume = queryVolumeResource(id);
        ArgValidator.checkEntity(sourceVolume, id, true);

        StringSet mirrors = sourceVolume.getMirrors();
        if (mirrors == null || mirrors.isEmpty()) {
            throw APIException.badRequests.invalidParameterVolumeHasNoContinuousCopies(sourceVolume.getId());
        }

        ArrayList<BlockMirror> mirrorList = null;

        if (copyID != null) {
            ArgValidator.checkFieldUriType(copyID, BlockMirror.class, "copyID");
            BlockMirror mirror = queryMirror(copyID);
            ArgValidator.checkEntity(mirror, copyID, true);
            if (!mirror.getSource().getURI().equals(id)) {
                throw APIException.badRequests.invalidParameterBlockCopyDoesNotBelongToVolume(copyID, id);
            } else {
                mirrorList = new ArrayList();
                mirrorList.add(mirror);
            }
        }

        String task = UUID.randomUUID().toString();
        StorageSystem device = _dbClient.queryObject(StorageSystem.class, sourceVolume.getStorageController());
        BlockServiceApi blockServiceApi = getBlockServiceImpl("mirror");

        auditOp(OperationTypeEnum.RESUME_VOLUME_MIRROR, true, AuditLogManager.AUDITOP_BEGIN,
                mirrors);

        return blockServiceApi.resumeNativeContinuousCopies(device, sourceVolume, mirrorList, task);
    }

    /**
     * Verify that all the copy IDs passed for a protection type are either
     * set to valid URIs, or none are set. A combination of the two is not allowed.
     * When none are set the operation is performed on all copies for the specified source volume.
     *
     * @param param
     *            List of copies to verify
     */

    private void verifyCopyIDs(CopiesParam param) {
        boolean rpEmpty = false;
        boolean rpSet = false;
        boolean nativeEmpty = false;
        boolean nativeSet = false;
        boolean srdfEmpty = false;
        boolean srdfSet = false;

        // Process the list of copies to ensure either all are set or all are empty
        for (Copy copy : param.getCopies()) {
            URI copyID = copy.getCopyID();
            if (URIUtil.isValid(copyID)) {
                if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
                    rpEmpty = true;
                } else if (copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
                    nativeEmpty = true;
                } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
                    srdfEmpty = true;
                }
            } else {
                if (copy.getType().equalsIgnoreCase(TechnologyType.RP.toString())) {
                    rpSet = true;
                } else if (copy.getType().equalsIgnoreCase(TechnologyType.NATIVE.toString())) {
                    nativeSet = true;
                } else if (copy.getType().equalsIgnoreCase(TechnologyType.SRDF.toString())) {
                    srdfSet = true;
                }
            }
        }

        if (rpEmpty && rpSet) {
            throw APIException.badRequests.invalidCopyIDCombination(TechnologyType.RP.toString());
        } else if (nativeEmpty && nativeSet) {
            throw APIException.badRequests.invalidCopyIDCombination(TechnologyType.NATIVE.toString());
        } else if (srdfEmpty && srdfSet) {
            throw APIException.badRequests.invalidCopyIDCombination(TechnologyType.SRDF.toString());
        }
    }

    private boolean checkIfVolumeIsForVplex(URI id) {
        Volume volume = queryVolumeResource(id);
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, volume.getStorageController());
        if ((storageSystem != null)
                && (DiscoveredDataObject.Type.vplex.name().equals(storageSystem
                        .getSystemType()))) {
            return true;
        } else {
            return false;
        }
    }

    /*
     * Validate if the physical array that the consistency group bonded to is associated
     * with the virtual array
     * 
     * @param consistencyGroup
     * 
     * @param varray virtual array
     */
    private void validateCGValidWithVirtualArray(BlockConsistencyGroup consistencyGroup,
            VirtualArray varray) {
        URI storageSystemUri = consistencyGroup.getStorageController();
        if (isNullURI(storageSystemUri)) {
            return;
        }
        URIQueryResultList storagePortURIs = new URIQueryResultList();
        URIQueryResultList assignedStoragePortURIs = new URIQueryResultList();
        boolean isAssociated = false;
        String systemString = storageSystemUri.toString();
        // get all assigned storage ports
        _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                .getVirtualArrayStoragePortsConstraint(varray.getId().toString()),
                assignedStoragePortURIs);
        isAssociated = anyPortsMatchStorageDevice(assignedStoragePortURIs, systemString);

        if (!isAssociated) {
            // get all network storage ports in the virtual array
            _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                    .getImplicitVirtualArrayStoragePortsConstraint(varray.getId().toString()),
                    storagePortURIs);
            isAssociated = anyPortsMatchStorageDevice(storagePortURIs, systemString);
        }

        if (!isAssociated) {
            throw APIException.badRequests.invalidParameterConsistencyGroupStorageSystemMismatchVirtualArray(
                    consistencyGroup.getId(), varray.getId());
        }
    }

    /**
     * Check if any port in the list is from the storage system
     *
     * @param ports
     * @param systemUri
     * @return
     */
    private boolean anyPortsMatchStorageDevice(URIQueryResultList ports, String systemUri) {
        boolean isMatched = false;
        for (URI spUri : ports) {
            StoragePort storagePort = _dbClient.queryObject(StoragePort.class, spUri);
            if (storagePort != null) {
                URI system = storagePort.getStorageDevice();
                if (system != null && system.toString().equals(systemUri)) {
                    isMatched = true;
                    break;
                }
            }
        }
        return isMatched;
    }

    protected static boolean isSuspendCopyRequest(String op, Copy copy) {
        return ProtectionOp.PAUSE.getRestOp().equalsIgnoreCase(op) &&
                (parseBoolean(copy.getSync()) == false);
    }

    /**
     * Determines if the passed volume is an exported HDS volume and if not
     * throws an bad request APIException
     *
     * @param volume
     *            A reference to a volume.
     */
    private void validateSourceVolumeHasExported(Volume volume) {
        URI id = volume.getId();
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class,
                volume.getStorageController());
        if (storageSystem != null
                && DiscoveredDataObject.Type.hds.name().equals(storageSystem.getSystemType())) {
            if (!volume.isVolumeExported(_dbClient)) {
                throw APIException.badRequests.sourceNotExported(id);
            }
        }
    }

    /**
     * Determines the class of the Block resources based on its URI.
     * This is because, BlockService implements Volume and
     * Mirror (BlockMirror and VplexMirror) resources. To query the
     * respective objects from DB, we should use the right class type.
     *
     * @param uriStr
     *            the uri to determine the right resource class type.
     *
     * @return returns the correct resource type of the resource.
     */
    public static Class<? extends DataObject> getBlockServiceResourceClass(String uriStr) {
        Class<? extends DataObject> blockResourceClass = Volume.class;
        if (URIUtil.isType(URI.create(uriStr), BlockMirror.class)) {
            blockResourceClass = BlockMirror.class;
        } else if (URIUtil.isType(URI.create(uriStr), VplexMirror.class)) {
            blockResourceClass = VplexMirror.class;
        }
        return blockResourceClass;
    }

    /**
     * Given a list of volumes, verify that any consistency groups associated with its volumes
     * are fully specified, i.e. the list contains all the members of a consistency group.
     *
     * @param volumes
     * @param targetVPool
     */
    private void verifyAllVolumesInCGRequirement(List<Volume> volumes, VirtualPool targetVPool) {
        StringBuilder errorMsg = new StringBuilder();
        boolean failure = false;
        Collection<URI> volIds = transform(volumes, fctnDataObjectToID());
        Map<URI, Volume> cgId2Volume = new HashMap<>();

        try {
            // Build map of consistency groups to a single group member representative
            for (Volume volume : volumes) {
                URI cgId = volume.getConsistencyGroup();
                if (!isNullURI(cgId) && !cgId2Volume.containsKey(cgId)) {
                    cgId2Volume.put(cgId, volume);
                }
            }

            // Verify that all consistency groups are fully specified
            for (Map.Entry<URI, Volume> entry : cgId2Volume.entrySet()) {
                // Currently, we only care about verifying CG's when adding SRDF protection
                if (!isAddingSRDFProtection(entry.getValue(), targetVPool)) {
                    continue;
                }

                List<URI> memberIds = _dbClient.queryByConstraint(getVolumesByConsistencyGroup(entry.getKey()));

                memberIds.removeAll(volIds);
                if (!memberIds.isEmpty()) {
                    failure = true;
                    errorMsg.append(entry.getValue().getLabel())
                            .append(" is missing other consistency group members.\n");
                }
            }
        } finally {
            if (failure) {
                throw APIException.badRequests.cannotAddSRDFProtectionToPartialCG(errorMsg.toString());
            }
        }
    }

    private boolean isAddingSRDFProtection(Volume v, VirtualPool targetVPool) {
        return v.getSrdfTargets() == null && VirtualPool.vPoolSpecifiesSRDF(targetVPool);
    }

    /**
     * Validate volume being expanded is not an SRDF volume with snapshots attached,
     * which isn't handled.
     * Also make sure that the SRDF Copy Mode is not Active.
     * 
     * @param volume
     *            -- Volume being expanded
     * @throws Exception
     *             if cannot be expanded
     */
    private void validateExpandingSrdfVolume(Volume volume) {
        if (ControllerUtils.checkIfVolumeHasSnapshot(volume, _dbClient)
                || BlockSnapshotSessionUtils.volumeHasSnapshotSession(volume, _dbClient)) {
            throw BadRequestException.badRequests.cannotExpandSRDFVolumeWithSnapshots(volume.getLabel());
        }
        Volume srdfVolume = volume;
        if (volume.isVPlexVolume(_dbClient)) {
            // Find associated SRDF volume
            srdfVolume = VPlexSrdfUtil.getSrdfVolumeFromVplexVolume(_dbClient, volume);
            if (srdfVolume != null) {
                if (ControllerUtils.checkIfVolumeHasSnapshot(srdfVolume, _dbClient)
                        || BlockSnapshotSessionUtils.volumeHasSnapshotSession(srdfVolume, _dbClient)) {
                    throw BadRequestException.badRequests.cannotExpandSRDFVolumeWithSnapshots(srdfVolume.getLabel());
                }
            }
        }
        // Check target volumes
        if (srdfVolume.getSrdfTargets() != null) {
            for (String target : srdfVolume.getSrdfTargets()) {
                Volume targetVolume = _dbClient.queryObject(Volume.class, URI.create(target));
                if (BlockSnapshotSessionUtils.volumeHasSnapshotSession(targetVolume, _dbClient)
                        || BlockSnapshotSessionUtils.volumeHasSnapshotSession(targetVolume, _dbClient)) {
                    throw BadRequestException.badRequests.cannotExpandSRDFVolumeWithSnapshots(targetVolume.getLabel());
                }
                if (Mode.ACTIVE.equals(Mode.valueOf(targetVolume.getSrdfCopyMode()))) {
                    throw BadRequestException.badRequests.cannotExpandSRDFActiveVolume(srdfVolume.getLabel());
                }
            }
        }
    }
}
