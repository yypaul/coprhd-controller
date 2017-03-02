/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.emc.storageos.Controller;
import com.emc.storageos.api.service.authorization.PermissionsHelper;
import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.FilePolicy;
import com.emc.storageos.db.client.model.FileShare;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.TenantOrg;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.common.DependencyChecker;
import com.emc.storageos.fileorchestrationcontroller.FileDescriptor;
import com.emc.storageos.fileorchestrationcontroller.FileOrchestrationController;
import com.emc.storageos.fileorchestrationcontroller.FileStorageSystemAssociation;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.TaskResourceRep;
import com.emc.storageos.model.file.CifsShareACLUpdateParams;
import com.emc.storageos.model.file.FileExportUpdateParams;
import com.emc.storageos.model.file.FileSystemParam;
import com.emc.storageos.model.file.policy.FilePolicyUpdateParam;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.volumecontroller.FileSMBShare;
import com.emc.storageos.volumecontroller.FileShareExport;
import com.emc.storageos.volumecontroller.Recommendation;
import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;

public abstract class AbstractFileServiceApiImpl<T> implements FileServiceApi {
    private static final Logger s_logger = LoggerFactory.getLogger(AbstractFileServiceApiImpl.class);

    @Autowired
    private PermissionsHelper _permissionsHelper;
    @Autowired
    protected DependencyChecker _dependencyChecker;
    protected T _scheduler;
    protected DbClient _dbClient;
    private CoordinatorClient _coordinator;

    // Permissions helper getter/setter
    public void setPermissionsHelper(PermissionsHelper permissionsHelper) {
        _permissionsHelper = permissionsHelper;
    }

    public PermissionsHelper getPermissionsHelper() {
        return _permissionsHelper;
    }

    // Dependency checker getter/setter
    public void setDependencyChecker(DependencyChecker dependencyChecker) {
        _dependencyChecker = dependencyChecker;
    }

    public DependencyChecker getDependencyChecker() {
        return _dependencyChecker;
    }

    // Coordinator getter/setter
    public void setCoordinator(CoordinatorClient locator) {
        _coordinator = locator;
    }

    public CoordinatorClient getCoordinator() {
        return _coordinator;
    }

    // Db client getter/setter
    public void setDbClient(DbClient dbClient) {
        _dbClient = dbClient;
    }

    public DbClient getDbClient() {
        return _dbClient;
    }

    // StorageScheduler getter/setter
    public void setFileScheduler(T scheduler) {
        _scheduler = scheduler;
    }

    public T getFileScheduler() {
        return _scheduler;
    }

    /**
     * Map of implementing class instances; used for iterating through them for
     * connectivity purposes.
     */
    static private Map<String, AbstractFileServiceApiImpl> s_protectionImplementations = new HashMap<String, AbstractFileServiceApiImpl>();

    public AbstractFileServiceApiImpl(String protectionType) {
        if (protectionType != null) {
            s_protectionImplementations.put(protectionType, this);
        }
    }

    static protected Map<String, AbstractFileServiceApiImpl> getProtectionImplementations() {
        return s_protectionImplementations;
    }

    /**
     * Check if a resource can be deactivated safely
     * 
     * @return detail type of the dependency if exist, null otherwise
     * @throws InternalException
     */
    @Override
    public <T extends DataObject> String checkForDelete(T object) throws InternalException {
        String depMsg = getDependencyChecker().checkDependencies(object.getId(), object.getClass(), true);
        if (depMsg != null) {
            return depMsg;
        }
        return object.canBeDeleted();
    }

    /**
     * Looks up controller dependency for given hardware
     * 
     * @param clazz
     *            controller interface
     * @param hw
     *            hardware name
     * @param <T>
     * @return
     */
    protected <T extends Controller> T getController(Class<T> clazz, String hw) {
        return _coordinator.locateService(clazz, CONTROLLER_SVC, CONTROLLER_SVC_VER, hw, clazz.getSimpleName());
    }

    @Override
    public TaskList createFileSystems(FileSystemParam param, Project project,
            VirtualArray varray, VirtualPool vpool, TenantOrg tenantOrg, DataObject.Flag[] flags,
            List<Recommendation> recommendations, TaskList taskList,
            String task, VirtualPoolCapabilityValuesWrapper vpoolCapabilities)
            throws InternalException {
        throw APIException.methodNotAllowed.notSupported();

    }

    @Override
    public void deleteFileSystems(URI systemURI, List<URI> fileSystemURIs,
            String deletionType, boolean forceDelete, boolean deleteOnlyMirrors, String task) throws InternalException {
        // Get volume descriptor for all volumes to be deleted.
        List<FileDescriptor> fileDescriptors = getDescriptorsOfFileShareDeleted(
                systemURI, fileSystemURIs, deletionType, forceDelete, deleteOnlyMirrors);
        // place request in queue
        FileOrchestrationController controller = getController(
                FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.deleteFileSystems(fileDescriptors, task);
    }

    @Override
    public TaskResourceRep createTargetsForExistingSource(FileShare fs, Project project,
            VirtualPool vpool, VirtualArray varray, TaskList taskList, String task, List<Recommendation> recommendations,
            VirtualPoolCapabilityValuesWrapper vpoolCapabilities) throws InternalException {
        throw APIException.methodNotAllowed.notSupported();
    }

    /**
     * get delete file share Descriptors
     * 
     * @param systemURI
     * @param fileShareURIs
     * @param deletionType
     * @param forceDelete
     * @return
     */
    abstract protected List<FileDescriptor> getDescriptorsOfFileShareDeleted(URI systemURI,
            List<URI> fileShareURIs, String deletionType, boolean forceDelete, boolean deleteOnlyMirrors);

    /**
     * Expand fileshare
     */
    @Override
    public void expandFileShare(FileShare fileshare, Long newSize, String taskId)
            throws InternalException {

        FileOrchestrationController controller = getController(
                FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        final List<FileDescriptor> fileDescriptors = new ArrayList<FileDescriptor>();

        if (fileshare.getParentFileShare() != null && fileshare.getPersonality().equals(FileShare.PersonalityTypes.TARGET.name())) {
            throw APIException.badRequests.expandMirrorFileSupportedOnlyOnSource(fileshare.getId());

        } else {

            List<String> targetfileUris = new ArrayList<String>();

            // if filesystem is target then throw exception
            if (fileshare.getMirrorfsTargets() != null && !fileshare.getMirrorfsTargets().isEmpty()) {
                targetfileUris.addAll(fileshare.getMirrorfsTargets());
            }

            FileDescriptor descriptor = new FileDescriptor(
                    FileDescriptor.Type.FILE_DATA,
                    fileshare.getStorageDevice(), fileshare.getId(), fileshare.getPool(), "", false, newSize);
            fileDescriptors.add(descriptor);

            // Prepare the descriptor for targets
            for (String target : targetfileUris) {
                FileShare targetFileShare = _dbClient.queryObject(FileShare.class, URI.create(target));
                descriptor = new FileDescriptor(
                        FileDescriptor.Type.FILE_DATA,
                        targetFileShare.getStorageDevice(), targetFileShare.getId(), targetFileShare.getPool(), "", false, newSize);
                fileDescriptors.add(descriptor);
            }
        }

        // place the expand filesystem call in queue
        controller.expandFileSystem(fileDescriptors, taskId);
    }

    @Override
    public void share(URI storageSystem, URI fileSystem, FileSMBShare smbShare, String taskId) throws InternalException {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.createCIFSShare(storageSystem, fileSystem, smbShare, taskId);
    }

    @Override
    public void export(URI storage, URI fsURI, List<FileShareExport> exports, String opId) throws InternalException {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.createNFSExport(storage, fsURI, exports, opId);
    }

    @Override
    public void updateExportRules(URI storage, URI fsURI, FileExportUpdateParams param, boolean unmountExport, String opId)
            throws InternalException {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.updateExportRules(storage, fsURI, param, unmountExport, opId);
    }

    @Override
    public void updateShareACLs(URI storage, URI fsURI, String shareName, CifsShareACLUpdateParams param, String opId)
            throws InternalException {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.updateShareACLs(storage, fsURI, shareName, param, opId);
    }

    @Override
    public void snapshotFS(URI storage, URI snapshot, URI fsURI, String opId) throws InternalException {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.snapshotFS(storage, snapshot, fsURI, opId);
    }

    @Override
    public void deleteShare(URI storage, URI uri, FileSMBShare fileSMBShare, String task) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.deleteShare(storage, uri, fileSMBShare, task);
    }

    @Override
    public void deleteExportRules(URI storage, URI uri, boolean allDirs, String subDirs, boolean unmountExport, String taskId) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.deleteExportRules(storage, uri, allDirs, subDirs, unmountExport, taskId);
    }

    @Override
    public void failoverFileShare(URI fsURI, StoragePort nfsPort, StoragePort cifsPort, boolean replicateConfiguration, String taskId) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.failoverFileSystem(fsURI, nfsPort, cifsPort, replicateConfiguration, taskId);
    }

    @Override
    public void failbackFileShare(URI fsURI, StoragePort nfsPort, StoragePort cifsPort, boolean replicateConfiguration, String taskId) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.failbackFileSystem(fsURI, nfsPort, cifsPort, replicateConfiguration, taskId);
    }

    @Override
    public void restoreFS(URI storage, URI fs, URI snapshot, String opId) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.restoreFS(storage, fs, snapshot, opId);
    }

    @Override
    public void deleteSnapshot(URI storage, URI pool, URI uri, boolean forceDelete, String deleteType, String opId) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.deleteSnapshot(storage, pool, uri, forceDelete, deleteType, opId);
    }

    @Override
    public void deleteShareACLs(URI storage, URI uri, String shareName, String taskId) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.deleteShareACLs(storage, uri, shareName, taskId);
    }

    @Override
    public void assignFilePolicyToVirtualPools(Map<URI, List<URI>> vpoolToStorageSystemMap, URI filePolicyToAssign, String taskId) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.assignFileSnapshotPolicyToVirtualPools(vpoolToStorageSystemMap, filePolicyToAssign, taskId);

    }

    @Override
    public void assignFilePolicyToProjects(Map<URI, List<URI>> vpoolToStorageSystemMap, List<URI> projectURIs, URI filePolicyToAssign,
            String taskId) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.assignFileSnapshotPolicyToProjects(vpoolToStorageSystemMap, projectURIs, filePolicyToAssign, taskId);

    }

    @Override
    public void updateFileProtectionPolicy(URI policy, FilePolicyUpdateParam param, String taskId) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.updateFileProtectionPolicy(policy, param, taskId);

    }

    @Override
    public void assignFileReplicationPolicyToVirtualPools(List<FileStorageSystemAssociation> associations,
            List<URI> vpoolURIs, URI filePolicyToAssign, String taskId) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.assignFileReplicationPolicyToVirtualPools(associations, vpoolURIs, filePolicyToAssign, taskId);

    }

    @Override
    public void assignFileReplicationPolicyToProjects(List<FileStorageSystemAssociation> associations, URI vpoolURI, List<URI> projectURIs,
            URI filePolicyToAssign, String taskId) {
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        controller.assignFileReplicationPolicyToProjects(associations, vpoolURI, projectURIs, filePolicyToAssign, taskId);

    }

    @Override
    public void assignFilePolicyToFileSystem(FileShare fs, FilePolicy filePolicy, Project project, VirtualPool vpool,
            VirtualArray varray, TaskList taskList, String task, List<Recommendation> recommendations,
            VirtualPoolCapabilityValuesWrapper vpoolCapabilities)
            throws InternalException {
        throw APIException.methodNotAllowed.notSupported();
    }

}