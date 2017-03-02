/*

 * Copyright (c) 2008-2012 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.volumecontroller.impl.isilon;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.emc.storageos.customconfigcontroller.CustomConfigConstants;
import com.emc.storageos.customconfigcontroller.DataSource;
import com.emc.storageos.customconfigcontroller.DataSourceFactory;
import com.emc.storageos.customconfigcontroller.impl.CustomConfigHandler;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.model.FSExportMap;
import com.emc.storageos.db.client.model.FileExport;
import com.emc.storageos.db.client.model.FilePolicy;
import com.emc.storageos.db.client.model.FilePolicy.FilePolicyApplyLevel;
import com.emc.storageos.db.client.model.FilePolicy.FilePolicyType;
import com.emc.storageos.db.client.model.FilePolicy.FileReplicationCopyMode;
import com.emc.storageos.db.client.model.FilePolicy.FileReplicationType;
import com.emc.storageos.db.client.model.FileShare;
import com.emc.storageos.db.client.model.FileShare.PersonalityTypes;
import com.emc.storageos.db.client.model.NASServer;
import com.emc.storageos.db.client.model.NamedURI;
import com.emc.storageos.db.client.model.OpStatusMap;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.PolicyStorageResource;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.QuotaDirectory;
import com.emc.storageos.db.client.model.SMBFileShare;
import com.emc.storageos.db.client.model.SMBShareMap;
import com.emc.storageos.db.client.model.SchedulePolicy;
import com.emc.storageos.db.client.model.SchedulePolicy.ScheduleFrequency;
import com.emc.storageos.db.client.model.SchedulePolicy.SnapshotExpireType;
import com.emc.storageos.db.client.model.Snapshot;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.Task;
import com.emc.storageos.db.client.model.VirtualNAS;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.util.TaskUtils;
import com.emc.storageos.exceptions.DeviceControllerErrors;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.fileorchestrationcontroller.FileOrchestrationUtils;
import com.emc.storageos.isilon.restapi.IsilonApi;
import com.emc.storageos.isilon.restapi.IsilonApi.IsilonList;
import com.emc.storageos.isilon.restapi.IsilonApiFactory;
import com.emc.storageos.isilon.restapi.IsilonException;
import com.emc.storageos.isilon.restapi.IsilonExport;
import com.emc.storageos.isilon.restapi.IsilonNFSACL;
import com.emc.storageos.isilon.restapi.IsilonNFSACL.Acl;
import com.emc.storageos.isilon.restapi.IsilonSMBShare;
import com.emc.storageos.isilon.restapi.IsilonSMBShare.Permission;
import com.emc.storageos.isilon.restapi.IsilonSmartQuota;
import com.emc.storageos.isilon.restapi.IsilonSnapshot;
import com.emc.storageos.isilon.restapi.IsilonSnapshotSchedule;
import com.emc.storageos.isilon.restapi.IsilonSshApi;
import com.emc.storageos.isilon.restapi.IsilonSyncPolicy;
import com.emc.storageos.isilon.restapi.IsilonSyncPolicy.Action;
import com.emc.storageos.isilon.restapi.IsilonSyncPolicy.JobState;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.model.file.ExportRule;
import com.emc.storageos.model.file.NfsACE;
import com.emc.storageos.model.file.ShareACL;
import com.emc.storageos.model.file.policy.FilePolicyScheduleParams;
import com.emc.storageos.model.file.policy.FilePolicyUpdateParam;
import com.emc.storageos.model.file.policy.FileReplicationPolicyParam;
import com.emc.storageos.model.file.policy.FileSnapshotPolicyExpireParam;
import com.emc.storageos.model.file.policy.FileSnapshotPolicyParam;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.FileControllerConstants;
import com.emc.storageos.volumecontroller.FileDeviceInputOutput;
import com.emc.storageos.volumecontroller.FileShareExport;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.BiosCommandResult;
import com.emc.storageos.volumecontroller.impl.file.AbstractFileStorageDevice;
import com.google.common.collect.Sets;

/**
 * Isilon specific file controller implementation.
 */
public class IsilonFileStorageDevice extends AbstractFileStorageDevice {

    private static final Logger _log = LoggerFactory.getLogger(IsilonFileStorageDevice.class);

    private static final String IFS_ROOT = "/ifs";
    private static final String FW_SLASH = "/";
    private static final String VIPR_DIR = "vipr";

    private static final String QUOTA = "quota";

    private static final String EXPORT_OP_NAME = "Snapshot Export";
    private static final String SHARE_OP_NAME = "Snapshot Share";
    public static final long SEC_IN_MILLI = 1000L;
    private static final String STR_WITH_NO_SPECIAL_SYMBOLS = "[^A-Za-z0-9_\\-/]";
    private static final String MIRROR_POLICY = "_mirror";

    private IsilonApiFactory _factory;
    private HashMap<String, String> configinfo;

    private DbClient _dbClient;
    @Autowired
    private CustomConfigHandler customConfigHandler;
    @Autowired
    private DataSourceFactory dataSourceFactory;

    private IsilonMirrorOperations mirrorOperations;

    public IsilonMirrorOperations getMirrorOperations() {
        return mirrorOperations;
    }

    public void setMirrorOperations(IsilonMirrorOperations mirrorOperations) {
        this.mirrorOperations = mirrorOperations;
    }

    /**
     * Set Isilon API factory
     * 
     * @param factory
     */
    public void setIsilonApiFactory(IsilonApiFactory factory) {
        _factory = factory;
    }

    /**
     * Get Isilon config info
     * 
     * @param factory
     */
    public HashMap<String, String> getConfiginfo() {
        return configinfo;
    }

    /**
     * Set Isilon config info
     * 
     * @param factory
     */
    public void setConfiginfo(HashMap<String, String> configinfo) {
        this.configinfo = configinfo;
    }

    public void setDbClient(DbClient dbc) {
        _dbClient = dbc;
    }

    /**
     * Set the controller config info
     * 
     * @return
     */
    public void setCustomConfigHandler(CustomConfigHandler customConfigHandler) {
        this.customConfigHandler = customConfigHandler;
    }

    /**
     * Get isilon device represented by the StorageDevice
     * 
     * @param StorageSystem
     *            object
     * @return IsilonSshApi object
     */
    IsilonSshApi getIsilonDeviceSsh(StorageSystem device) throws IsilonException {
        IsilonSshApi sshDmApi = new IsilonSshApi();
        sshDmApi.setConnParams(device.getIpAddress(), device.getUsername(), device.getPassword());
        return sshDmApi;
    }

    /**
     * Get isilon device represented by the StorageDevice
     * 
     * @param device
     *            StorageDevice object
     * @return IsilonApi object
     * @throws IsilonException
     */
    IsilonApi getIsilonDevice(StorageSystem device) throws IsilonException {
        IsilonApi isilonAPI;
        URI deviceURI;
        try {
            deviceURI = new URI("https", null, device.getIpAddress(), device.getPortNumber(), "/", null, null);
        } catch (URISyntaxException ex) {
            throw IsilonException.exceptions.errorCreatingServerURL(device.getIpAddress(), device.getPortNumber(), ex);
        }
        if (device.getUsername() != null && !device.getUsername().isEmpty()) {
            isilonAPI = _factory.getRESTClient(deviceURI, device.getUsername(), device.getPassword());
        } else {
            isilonAPI = _factory.getRESTClient(deviceURI);
        }

        return isilonAPI;

    }

    /**
     * create isilon snapshot path from file share path and snapshot name
     * 
     * @param fsMountPath
     *            mount path of the fileshare
     * @param name
     *            snapshot name
     * @return String
     */
    private String getSnapshotPath(String fsMountPath, String name) {
        String prefix = IFS_ROOT + "/" + VIPR_DIR;
        return String.format("%1$s/.snapshot/%2$s/%3$s%4$s", IFS_ROOT, name, VIPR_DIR,
                fsMountPath.substring(prefix.length()));
    }

    /**
     * Delete isilon export
     * 
     * @param isi
     *            IsilonApi object
     * @param exportMap
     *            exports to be deleted
     * @throws IsilonException
     */
    private void isiDeleteExports(IsilonApi isi, FileDeviceInputOutput args) throws IsilonException {

        FSExportMap exportMap = null;

        if (args.getFileOperation()) {
            FileShare fileObj = args.getFs();
            if (fileObj != null) {
                exportMap = fileObj.getFsExports();
            }
        } else {
            Snapshot snap = args.getFileSnapshot();
            if (snap != null) {
                exportMap = snap.getFsExports();
            }
        }

        if (exportMap == null || exportMap.isEmpty()) {
            return;
        }

        String zoneName = getZoneName(args.getvNAS());

        Set<String> deletedExports = new HashSet<String>();
        Iterator<Map.Entry<String, FileExport>> it = exportMap.entrySet().iterator();
        try {
            while (it.hasNext()) {
                Map.Entry<String, FileExport> entry = it.next();
                String key = entry.getKey();
                FileExport fsExport = entry.getValue();
                if (zoneName != null) {
                    isi.deleteExport(fsExport.getIsilonId(), zoneName);
                } else {
                    isi.deleteExport(fsExport.getIsilonId());
                }

                // Safe removal from the backing map. Can not do this through
                // iterator since this does not track changes and is not
                // reflected in the database.
                deletedExports.add(key);
            }
        } finally {
            // remove exports from the map in database.
            for (String key : deletedExports) {
                exportMap.remove(key);
            }
        }
    }

    /**
     * Deleting a file share: - deletes existing exports and smb shares for the
     * file share (only created by storage os)
     * 
     * @param isi
     *            IsilonApi object
     * @param args
     *            FileDeviceInputOutput
     * @throws IsilonException
     */
    private void isiDeleteFS(IsilonApi isi, FileDeviceInputOutput args) throws IsilonException {

        /*
         * Delete the exports for this file system
         */
        isiDeleteExports(isi, args);

        /*
         * Delete the SMB shares for this file system
         */
        isiDeleteShares(isi, args);

        /*
         * Delete quota on this path, if one exists
         */
        if (args.getFsExtensions() != null && args.getFsExtensions().containsKey(QUOTA)) {
            isi.deleteQuota(args.getFsExtensions().get(QUOTA));
            // delete from extensions
            args.getFsExtensions().remove(QUOTA);
        }

        /*
         * Delete the snapshots for this file system
         */
        isiDeleteSnapshots(isi, args);

        /*
         * Delete quota dirs, if one exists
         */
        isiDeleteQuotaDirs(isi, args);

        /**
         * Delete the directory associated with the file share.
         */
        isi.deleteDir(args.getFsMountPath(), true);

        /**
         * Delete the Schedule Policy for the file system
         */
        isiDeleteSnapshotSchedules(isi, args);
    }

    /**
     * Deleting snapshots: - deletes snapshots of a file system
     * 
     * @param isi
     *            IsilonApi object
     * @param args
     *            FileDeviceInputOutput
     * @throws IsilonException
     */
    private void isiDeleteSnapshots(IsilonApi isi, FileDeviceInputOutput args) throws IsilonException {

        List<URI> snapURIList = _dbClient
                .queryByConstraint(ContainmentConstraint.Factory.getFileshareSnapshotConstraint(args.getFsId()));
        for (URI snapURI : snapURIList) {
            Snapshot snap = _dbClient.queryObject(Snapshot.class, snapURI);
            if (snap != null && (!snap.getInactive())) {
                args.addSnapshot(snap);
                isiDeleteSnapshot(isi, args);
            }
        }
    }

    /**
     * Deleting snapshots: - deletes snapshots of a file system
     * 
     * @param isi
     *            IsilonApi object
     * @param args
     *            FileDeviceInputOutput
     * @throws IsilonException
     */
    private void isiDeleteSnapshotSchedules(IsilonApi isi, FileDeviceInputOutput args) throws IsilonException {

        StringSet policies = args.getFs().getFilePolicies();

        for (String policy : policies) {
            SchedulePolicy fp = _dbClient.queryObject(SchedulePolicy.class, URI.create(policy));
            String snapshotScheduleName = fp.getPolicyName() + "_" + args.getFsName();
            isi.deleteSnapshotSchedule(snapshotScheduleName);
        }
    }

    /**
     * Deleting a snapshot: - deletes existing exports and smb shares for the
     * snapshot (only created by storage os)
     * 
     * @param isi
     *            IsilonApi object
     * @param args
     *            FileDeviceInputOutput
     * @throws IsilonException
     */
    private void isiDeleteSnapshot(IsilonApi isi, FileDeviceInputOutput args) throws IsilonException {

        args.setFileOperation(false);
        /*
         * Delete the exports first
         */
        isiDeleteExports(isi, args);

        /*
         * Delete the SMB shares
         */
        isiDeleteShares(isi, args);

        /**
         * Delete the snapshot.
         */
        if (args.getSnapshotExtensions() != null && args.getSnapshotExtensions().containsKey("id")) {
            isi.deleteSnapshot(args.getSnapshotExtensions().get("id"));
        }
    }

    /**
     * Deleting Quota dirs: - deletes quota dirs of a file system
     * 
     * @param isi
     *            IsilonApi object
     * @param args
     *            FileDeviceInputOutput
     * @throws IsilonException
     */
    private void isiDeleteQuotaDirs(IsilonApi isi, FileDeviceInputOutput args) throws IsilonException {
        List<URI> quotaDirURIList = _dbClient
                .queryByConstraint(ContainmentConstraint.Factory.getQuotaDirectoryConstraint(args.getFsId()));
        for (URI quotaDirURI : quotaDirURIList) {
            QuotaDirectory quotaDir = _dbClient.queryObject(QuotaDirectory.class, quotaDirURI);
            if (quotaDir != null && (!quotaDir.getInactive())) {
                if (quotaDir.getExtensions() != null && quotaDir.getExtensions().containsKey(QUOTA)) {
                    String quotaId = quotaDir.getExtensions().get(QUOTA);
                    _log.info("IsilonFileStorageDevice isiDeleteQuotaDirs , Delete Quota {}", quotaId);
                    isi.deleteQuota(quotaId);
                    // delete from quota extensions
                    quotaDir.getExtensions().remove(QUOTA);

                    // delete directory for the Quota Directory
                    String quotaDirPath = args.getFsMountPath() + "/" + quotaDir.getName();
                    isi.deleteDir(quotaDirPath, true);
                }
            }
        }

    }

    /**
     * Create/modify Isilon SMB share.
     * 
     * @param isi
     * @param args
     * @param smbFileShare
     * @throws IsilonException
     */
    private void isiShare(IsilonApi isi, FileDeviceInputOutput args, SMBFileShare smbFileShare) throws IsilonException {

        IsilonSMBShare isilonSMBShare = new IsilonSMBShare(smbFileShare.getName(), smbFileShare.getPath(),
                smbFileShare.getDescription());

        // Check if this is a new share or update of the existing share
        SMBShareMap smbShareMap = args.getFileObjShares();
        SMBFileShare existingShare = (smbShareMap == null) ? null : smbShareMap.get(smbFileShare.getName());

        String shareId;

        String zoneName = getZoneName(args.getvNAS());

        if (existingShare != null) {
            shareId = existingShare.getNativeId();
            // modify share
            if (zoneName != null) {
                isi.modifyShare(shareId, zoneName, isilonSMBShare);
            } else {
                isi.modifyShare(shareId, isilonSMBShare);
            }

        } else {
            /**
             * inheritablePathAcl - true: Apply Windows Default ACLs false: Do
             * not change existing permissions.
             **/
            boolean inheritablePathAcl = true;
            if (configinfo != null && configinfo.containsKey("inheritablePathAcl")) {
                inheritablePathAcl = Boolean.parseBoolean(configinfo.get("inheritablePathAcl"));
                isilonSMBShare.setInheritablePathAcl(inheritablePathAcl);
            }
            // new share
            if (zoneName != null) {
                _log.debug("Share will be created in zone: {}", zoneName);
                shareId = isi.createShare(isilonSMBShare, zoneName);
            } else {
                shareId = isi.createShare(isilonSMBShare);
            }
        }
        smbFileShare.setNativeId(shareId);

        // Set Mount Point
        smbFileShare.setMountPoint(smbFileShare.getStoragePortNetworkId(), smbFileShare.getStoragePortName(),
                smbFileShare.getName());
        // int file share map
        if (args.getFileObjShares() == null) {
            args.initFileObjShares();
        }
        args.getFileObjShares().put(smbFileShare.getName(), smbFileShare);
    }

    private void isiDeleteShare(IsilonApi isi, FileDeviceInputOutput args, SMBFileShare smbFileShare)
            throws IsilonException {

        SMBShareMap currentShares = args.getFileObjShares();
        // Do nothing if there are no shares
        if (currentShares == null || smbFileShare == null) {
            return;
        }

        SMBFileShare fileShare = currentShares.get(smbFileShare.getName());
        if (fileShare != null) {

            String nativeId = fileShare.getNativeId();
            String zoneName = getZoneName(args.getvNAS());

            if (zoneName != null) {
                isi.deleteShare(nativeId, zoneName);
            } else {
                isi.deleteShare(nativeId);
            }

            currentShares.remove(smbFileShare.getName());
        }
    }

    private void isiDeleteShares(IsilonApi isi, FileDeviceInputOutput args) throws IsilonException {

        SMBShareMap currentShares = null;
        if (args.getFileOperation()) {
            FileShare fileObj = args.getFs();
            if (fileObj != null) {
                currentShares = fileObj.getSMBFileShares();
            }
        } else {
            Snapshot snap = args.getFileSnapshot();
            if (snap != null) {
                currentShares = snap.getSMBFileShares();
            }
        }
        if (currentShares == null || currentShares.isEmpty()) {
            return;
        }

        Set<String> deletedShares = new HashSet<String>();
        Iterator<Map.Entry<String, SMBFileShare>> it = currentShares.entrySet().iterator();

        String zoneName = getZoneName(args.getvNAS());

        try {
            while (it.hasNext()) {
                Map.Entry<String, SMBFileShare> entry = it.next();
                String key = entry.getKey();
                SMBFileShare smbFileShare = entry.getValue();
                if (zoneName != null) {
                    isi.deleteShare(smbFileShare.getNativeId(), zoneName);
                } else {
                    isi.deleteShare(smbFileShare.getNativeId());
                }

                // Safe removal from the backing map. Can not do this through
                // iterator since this does not track changes and is not
                // reflected in the database.
                deletedShares.add(key);
            }
        } finally {
            // remove shares from the map in database.
            for (String key : deletedShares) {
                currentShares.remove(key);
            }
        }

    }

    /**
     * Create isilon exports
     * 
     * @param isi
     *            IsilonApi object
     * @param args
     *            FileDeviceInputOutput object
     * @param exports
     *            new exports to add
     * @throws IsilonException
     */
    private void isiExport(IsilonApi isi, FileDeviceInputOutput args, List<FileExport> exports) throws IsilonException {

        // process and export each NFSExport independently.
        for (FileExport fileExport : exports) {

            // create and set IsilonExport instance from NFSExport
            String permissions = fileExport.getPermissions();
            Set<String> orderedSecTypes = new TreeSet<String>();
            for (String securityType : fileExport.getSecurityType().split(",")) {
                securityType = securityType.trim();
                orderedSecTypes.add(securityType);
            }
            Iterator<String> orderedList = orderedSecTypes.iterator();
            String strCSSecurityType = orderedList.next().toString();
            while (orderedList.hasNext()) {
                strCSSecurityType += "," + orderedList.next().toString();
            }

            String root_user = fileExport.getRootUserMapping();
            String storagePortName = fileExport.getStoragePortName();
            String storagePort = fileExport.getStoragePort();
            String protocol = fileExport.getProtocol();
            String path = fileExport.getPath();
            String mountPath = fileExport.getMountPath();
            String comments = fileExport.getComments();
            String subDirectory = fileExport.getSubDirectory();

            List<String> securityTypes = new ArrayList<String>(orderedSecTypes);
            IsilonExport newIsilonExport = setIsilonExport(fileExport, permissions, securityTypes, root_user, mountPath,
                    comments);

            _log.info("IsilonExport:" + fileExport.getClients() + ":" + fileExport.getStoragePortName() + ":"
                    + fileExport.getStoragePort() + ":" + fileExport.getRootUserMapping() + ":"
                    + fileExport.getPermissions() + ":" + fileExport.getProtocol() + ":" + fileExport.getSecurityType()
                    + ":" + fileExport.getMountPoint() + ":" + fileExport.getPath() + ":" + fileExport.getSubDirectory()
                    + ":" + fileExport.getComments());
            // Initialize exports map, if its not already initialized
            if (args.getFileObjExports() == null) {
                args.initFileObjExports();
            }

            String accessZoneName = getZoneName(args.getvNAS());

            // Create/update export in Isilon.
            String exportKey = fileExport.getFileExportKey();
            // If export with the given key does not exist, we create a new
            // export in Isilon and add it to the exports map.
            // In the other case, when export with a given key already exists in
            // Isilon, we need to overwrite endpoints in the current
            // export with endpoints in the
            // new export.
            FileExport fExport = args.getFileObjExports().get(exportKey);

            // check Isilon to verify if export does not exist.
            IsilonExport currentIsilonExport = null;
            if (fExport != null) {
                if (accessZoneName != null) {
                    currentIsilonExport = isi.getExport(fExport.getIsilonId(), accessZoneName);
                } else {
                    currentIsilonExport = isi.getExport(fExport.getIsilonId());
                }

            }
            if (fExport == null || currentIsilonExport == null) {
                // There is no Isilon export. Create Isilon export and set it
                // the map.
                String id = null;
                if (accessZoneName != null) {
                    _log.debug("Export will be created in zone: {}", accessZoneName);
                    id = isi.createExport(newIsilonExport, accessZoneName);
                } else {
                    id = isi.createExport(newIsilonExport);
                }

                // set file export data and add it to the export map
                fExport = new FileExport(newIsilonExport.getClients(), storagePortName, mountPath, strCSSecurityType,
                        permissions, root_user, protocol, storagePort, path, mountPath, subDirectory, comments);
                fExport.setIsilonId(id);
            } else {
                // There is export in Isilon with the given id.
                // Overwrite this export with a new set of clients.
                // We overwrite only clients element in exports. Isilon API does
                // not use read_only_clients, read_write_clients or
                // root_clients.
                List<String> newClients = newIsilonExport.getClients();
                newIsilonExport.setClients(new ArrayList<String>(newClients));

                // modify current export in isilon.
                if (accessZoneName != null) {
                    isi.modifyExport(fExport.getIsilonId(), accessZoneName, newIsilonExport);
                } else {
                    isi.modifyExport(fExport.getIsilonId(), newIsilonExport);
                }

                // update clients
                fExport.setClients(newIsilonExport.getClients());
            }

            args.getFileObjExports().put(exportKey, fExport);
        }
    }

    private IsilonExport setIsilonExport(FileExport fileExport, String permissions, List<String> securityType,
            String root_user, String mountPath, String comments) {

        IsilonExport newIsilonExport = new IsilonExport();
        newIsilonExport.addPath(mountPath);
        if (comments == null) {
            comments = "";
        }
        newIsilonExport.setComment(comments);

        // Empty list of clients means --- all clients.
        newIsilonExport.addClients(fileExport.getClients());

        // set security type
        // Need to use "unix" instead of "sys" . Isilon requires "unix", not
        // "sys".
        List<String> securityFlavors = new ArrayList<String>();
        for (String secType : securityType) {
            if (secType.equals(FileShareExport.SecurityTypes.sys.name())) {
                securityFlavors.add("unix");
            } else {
                securityFlavors.add(secType);
            }
        }
        newIsilonExport.setSecurityFlavors(new ArrayList<String>(securityFlavors));
        newIsilonExport.setMapRoot(root_user);

        // set permission and add clients (endpoints) to the right group
        // we need to set/reset read_only and map_all to support case when list
        // of clients in the request is empty.
        if (permissions.equals(FileShareExport.Permissions.ro.name())) {
            newIsilonExport.addReadOnlyClients(fileExport.getClients());
            newIsilonExport.setReadOnly();
        } else if (permissions.equals(FileShareExport.Permissions.rw.name())) {
            newIsilonExport.addReadWriteClients(fileExport.getClients());
            newIsilonExport.resetReadOnly();
        } else if (permissions.equals(FileShareExport.Permissions.root.name())) {
            newIsilonExport.addRootClients(fileExport.getClients());
            newIsilonExport.resetReadOnly();
        }

        return newIsilonExport;
    }

    private IsilonExport setIsilonExport(ExportRule expRule) {

        // String permissions, List<String> securityType, String root_user,
        // String mountPath, String comments) {

        _log.info("setIsilonExport called with {}", expRule.toString());
        String mountPath = expRule.getExportPath();
        String comments = "";
        String root_user = expRule.getAnon();

        IsilonExport newIsilonExport = new IsilonExport();
        newIsilonExport.addPath(mountPath);
        newIsilonExport.setComment(comments);

        int roHosts = 0;
        int rwHosts = 0;
        int rootHosts = 0;

        // Empty list of clients means --- all clients.
        if (expRule.getReadOnlyHosts() != null) {
            newIsilonExport.addClients(new ArrayList<String>(expRule.getReadOnlyHosts()));
            roHosts = expRule.getReadOnlyHosts().size();
            newIsilonExport.addReadOnlyClients(new ArrayList<String>(expRule.getReadOnlyHosts()));
        }

        if (expRule.getReadWriteHosts() != null) {
            newIsilonExport.addClients(new ArrayList<String>(expRule.getReadWriteHosts()));
            rwHosts = expRule.getReadWriteHosts().size();
            newIsilonExport.addReadWriteClients(new ArrayList<String>(expRule.getReadWriteHosts()));
        }

        if (expRule.getRootHosts() != null) {
            newIsilonExport.addClients(new ArrayList<String>(expRule.getRootHosts()));
            rootHosts = expRule.getRootHosts().size();
            newIsilonExport.addRootClients(new ArrayList<String>(expRule.getRootHosts()));
        }

        // set security type
        // Need to use "unix" instead of "sys" . Isilon requires "unix", not
        // "sys".
        // input export may contain one or more security types in a string separated by comma.
        ArrayList<String> secFlavors = new ArrayList<>();
        for (String securityType : expRule.getSecFlavor().split(",")) {
            securityType = securityType.trim();
            if (securityType.equals(FileShareExport.SecurityTypes.sys.name())) {
                securityType = "unix";
            }
            secFlavors.add(securityType);
        }
        newIsilonExport.setSecurityFlavors(secFlavors);
        newIsilonExport.setMapRoot(root_user);
        newIsilonExport.resetReadOnly();

        if (roHosts > 0 && rwHosts == 0 && rootHosts == 0) {
            // RO Export
            newIsilonExport.setReadOnly();
        }

        _log.info("setIsilonExport completed with creating {}", newIsilonExport.toString());
        return newIsilonExport;
    }

    /**
     * Delete exports
     * 
     * @param isi
     *            IsilonApi object to be used for communicating to the isilon
     *            system
     * @param currentExports
     *            Current exports map
     * @param exports
     *            exports to be deleted
     * @throws ControllerException
     * @throws IsilonException
     */
    private void isiUnexport(IsilonApi isi, FileDeviceInputOutput args, List<FileExport> exports)
            throws ControllerException, IsilonException {

        FSExportMap currentExports = args.getFileObjExports();
        // Do nothing if there are no exports
        if (currentExports == null || exports == null || exports.isEmpty()) {
            return;
        }

        for (FileExport fileExport : exports) {
            String key = fileExport.getFileExportKey(); // isiExportKey(req);
            String id = null;

            FileExport fExport = currentExports.get(key);
            if (fExport != null) {
                id = fExport.getIsilonId();
            }
            if (id != null) {
                String zoneName = getZoneName(args.getvNAS());
                if (zoneName != null) {
                    isi.deleteExport(id, zoneName);
                } else {
                    isi.deleteExport(id);
                }

                currentExports.remove(key);
            }
        }
    }

    private void isiExpandFS(IsilonApi isi, String quotaId, FileDeviceInputOutput args) throws ControllerException, IsilonException {

        // get quota from Isilon and check that requested capacity is larger than the current capacity
        Long capacity = args.getNewFSCapacity();

        IsilonSmartQuota quota = isi.getQuota(quotaId);
        Long hard = quota.getThresholds().getHard();
        if (capacity.compareTo(hard) < 0) {
            String msg = String
                    .format(
                            "In expanding Isilon FS requested capacity is less than current capacity of file system. Path: %s, current capacity: %d",
                            quota.getPath(), quota.getThresholds().getHard());
            _log.error(msg);
            throw IsilonException.exceptions.expandFsFailedinvalidParameters(quota.getPath(),
                    quota.getThresholds().getHard());
        }
        // Modify quota for file system.
        IsilonSmartQuota expandedQuota = getExpandedQuota(isi, args, capacity);
        isi.modifyQuota(quotaId, expandedQuota);
    }

    private IsilonSmartQuota getExpandedQuota(IsilonApi isi, FileDeviceInputOutput args, Long capacity) {
        Long notificationLimit = 0L;
        Long softLimit = 0L;
        Long softGracePeriod = 0L;

        if (args.getFsNotificationLimit() != null) {
            notificationLimit = Long.valueOf(args.getFsNotificationLimit());
        }

        if (args.getFsSoftLimit() != null) {
            softLimit = Long.valueOf(args.getFsSoftLimit());
        }

        if (args.getFsSoftGracePeriod() != null) {
            softGracePeriod = Long.valueOf(args.getFsSoftGracePeriod());
        }

        return isi.constructIsilonSmartQuotaObjectWithThreshold(null, null, capacity, false, null, capacity,
                notificationLimit, softLimit, softGracePeriod);
    }

    @Override
    public BiosCommandResult doCreateFS(StorageSystem storage, FileDeviceInputOutput args) throws ControllerException {
        try {
            _log.info("IsilonFileStorageDevice doCreateFS {} with name {} - start", args.getFsId(), args.getFsName());
            IsilonApi isi = getIsilonDevice(storage);

            VirtualNAS vNAS = args.getvNAS();
            String vNASPath = null;

            // get the custom path from the controller configuration
            String customPath = getCustomPath(storage, args);
            if (vNAS != null) {
                vNASPath = vNAS.getBaseDirPath();
                _log.info("vNAS base directory path: {}", vNASPath);
            }

            String usePhysicalNASForProvisioning = customConfigHandler.getComputedCustomConfigValue(
                    CustomConfigConstants.USE_PHYSICAL_NAS_FOR_PROVISIONING, "isilon", null);
            _log.info("Use System access zone to provision filesystem? {}", usePhysicalNASForProvisioning);

            String mountPath = null;
            String fsName = args.getFsName();
            if (args.getFs().getPersonality() != null && args.getFs().getPersonality().equalsIgnoreCase(PersonalityTypes.TARGET.name())) {
                FileShare fsParent = _dbClient.queryObject(FileShare.class, args.getFs().getParentFileShare().getURI());
                fsName = fsParent.getName();
            }
            // Update the mount path as required
            if (vNASPath != null && !vNASPath.trim().isEmpty()) {
                mountPath = vNASPath + FW_SLASH + customPath + FW_SLASH + fsName;

            } else if (Boolean.valueOf(usePhysicalNASForProvisioning)) {

                mountPath = IFS_ROOT + FW_SLASH + getSystemAccessZoneNamespace() + FW_SLASH + customPath + FW_SLASH + fsName;
            } else {
                _log.error(
                        "No suitable access zone found for provisioning. Provisioning on System access zone is disabled");
                throw DeviceControllerException.exceptions.createFileSystemOnPhysicalNASDisabled();
            }

            // replace extra forward slash with single one
            mountPath = mountPath.replaceAll("/+", "/");
            _log.info("Mount path to mount the Isilon File System {}", mountPath);
            args.setFsMountPath(mountPath);
            args.setFsNativeGuid(args.getFsMountPath());
            args.setFsNativeId(args.getFsMountPath());
            args.setFsPath(args.getFsMountPath());

            // Update the mount path for local target!!!
            updateLocalTargetFileSystemPath(storage, args);

            // Create the target directory only if the replication policy was not applied!!
            // If policy was applied at higher level, policy would create target file system directories!
            if (FileOrchestrationUtils.isPrimaryFileSystemOrNormalFileSystem(args.getFs())
                    || !FileOrchestrationUtils.isReplicationPolicyExistsOnTarget(_dbClient, storage, args.getVPool(),
                            args.getProject(), args.getFs())) {
                // create directory for the file share
                isi.createDir(args.getFsMountPath(), true);

                Long softGrace = null;
                if (args.getFsSoftGracePeriod() != null) {
                    softGrace = Long.valueOf(args.getFsSoftGracePeriod());
                }

                // set quota - save the quota id to extensions
                String qid = createQuotaWithThreshold(args.getFsMountPath(), args.getFsCapacity(), args.getFsSoftLimit(),
                        args.getFsNotificationLimit(), softGrace, null, isi);

                if (args.getFsExtensions() == null) {
                    args.initFsExtensions();
                }
                args.getFsExtensions().put(QUOTA, qid);
            }

            // set protection level
            // String protection = args.getFSProtectionLevel();
            // Call isilon api to set protection level

            _log.info("IsilonFileStorageDevice doCreateFS {} - complete", args.getFsId());
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doCreateFS failed.", e);
            // rollback this operation to prevent partial result of file share
            // create
            BiosCommandResult rollbackResult = doDeleteFS(storage, args);
            if (rollbackResult.isCommandSuccess()) {
                _log.info("IsilonFileStorageDevice doCreateFS {} - rollback completed.", args.getFsId());
            } else {
                _log.error("IsilonFileStorageDevice doCreateFS {} - rollback failed,  message: {} .", args.getFsId(),
                        rollbackResult.getMessage());
            }

            return BiosCommandResult.createErrorResult(e);
        }
    }

    private FileDeviceInputOutput prepareFileDeviceInputOutput(boolean forceDelete, URI uri, String opId) {
        FileDeviceInputOutput args = new FileDeviceInputOutput();
        boolean isFile = false;
        args.setOpId(opId);
        if (URIUtil.isType(uri, FileShare.class)) {
            isFile = true;
            args.setForceDelete(forceDelete);
            FileShare fsObj = _dbClient.queryObject(FileShare.class, uri);

            if (fsObj.getVirtualNAS() != null) {
                VirtualNAS vNAS = _dbClient.queryObject(VirtualNAS.class, fsObj.getVirtualNAS());
                args.setvNAS(vNAS);
            }

            args.addFileShare(fsObj);
            args.setFileOperation(isFile);
        }
        return args;
    }

    @Override
    public BiosCommandResult doDeleteFS(StorageSystem storage, FileDeviceInputOutput args) throws ControllerException {
        try {
            _log.info("IsilonFileStorageDevice doDeleteFS {} - start", args.getFsId());
            IsilonApi isi = getIsilonDevice(storage);
            isiDeleteFS(isi, args);
            _log.info("IsilonFileStorageDevice doDeleteFS {} - complete", args.getFsId());
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doDeleteFS failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    @Override
    public boolean doCheckFSExists(StorageSystem storage, FileDeviceInputOutput args) throws ControllerException {
        _log.info("checking file system existence on array: ", args.getFsName());
        boolean isFSExists = true; // setting true by default for safer side
        try {
            IsilonApi isi = getIsilonDevice(storage);
            isFSExists = isi.existsDir(args.getFsMountPath());
        } catch (IsilonException e) {
            _log.error("Querying FS failed", e);
        }
        return isFSExists;
    }

    @Override
    public BiosCommandResult doExpandFS(StorageSystem storage, FileDeviceInputOutput args) throws ControllerException {
        try {
            _log.info("IsilonFileStorageDevice doExpandFS {} - start", args.getFsId());
            IsilonApi isi = getIsilonDevice(storage);
            String quotaId = null;
            if (args.getFsExtensions() != null && args.getFsExtensions().get(QUOTA) != null) {
                quotaId = args.getFsExtensions().get(QUOTA);
            } else {
                final ServiceError serviceError = DeviceControllerErrors.isilon.doExpandFSFailed(args.getFsId());
                _log.error(serviceError.getMessage());
                return BiosCommandResult.createErrorResult(serviceError);
            }

            isiExpandFS(isi, quotaId, args);
            _log.info("IsilonFileStorageDevice doExpandFS {} - complete", args.getFsId());
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doExpandFS failed.", e);
            return BiosCommandResult.createErrorResult(e);
        } catch (Exception e) {
            _log.error("doExpandFS failed.", e);
            // convert this to a ServiceError and create/or reuse a service
            // code
            ServiceError serviceError = DeviceControllerErrors.isilon.unableToExpandFileSystem();
            return BiosCommandResult.createErrorResult(serviceError);
        }
    }

    @Override
    public BiosCommandResult doExport(StorageSystem storage, FileDeviceInputOutput args, List<FileExport> exportList)
            throws ControllerException {

        // Snapshot Export operation is not supported by ISILON.
        if (args.getFileOperation() == false) {
            return BiosCommandResult
                    .createErrorResult(DeviceControllerErrors.isilon.unSupportedOperation(EXPORT_OP_NAME));
        }

        try {
            _log.info("IsilonFileStorageDevice doExport {} - start", args.getFileObjId());
            IsilonApi isi = getIsilonDevice(storage);
            isiExport(isi, args, exportList);
            _log.info("IsilonFileStorageDevice doExport {} - complete", args.getFileObjId());
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doExport failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    @Override
    public BiosCommandResult doUnexport(StorageSystem storage, FileDeviceInputOutput args, List<FileExport> exportList)
            throws ControllerException {

        try {
            _log.info("IsilonFileStorageDevice doUnexport: {} - start", args.getFileObjId());
            IsilonApi isi = getIsilonDevice(storage);
            isiUnexport(isi, args, exportList);
            _log.info("IsilonFileStorageDevice doUnexport {} - complete", args.getFileObjId());
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doUnexport failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    @Override
    public BiosCommandResult doShare(StorageSystem storage, FileDeviceInputOutput args, SMBFileShare smbFileShare)
            throws ControllerException {
        // Snapshot Share operation is not supported by ISILON.
        if (args.getFileOperation() == false) {
            return BiosCommandResult
                    .createErrorResult(DeviceControllerErrors.isilon.unSupportedOperation(SHARE_OP_NAME));
        }

        try {
            _log.info("IsilonFileStorageDevice doShare() - start");
            IsilonApi isi = getIsilonDevice(storage);
            isiShare(isi, args, smbFileShare);
            _log.info("IsilonFileStorageDevice doShare() - complete");
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doShare failed.", e);
            return BiosCommandResult.createErrorResult(e);
        } catch (Exception e) {
            _log.error("doShare failed.", e);
            // convert this to a ServiceError and create/or reuse a service
            // code
            ServiceError serviceError = DeviceControllerErrors.isilon.unableToCreateFileShare();
            return BiosCommandResult.createErrorResult(serviceError);
        }
    }

    @Override
    public BiosCommandResult doDeleteShare(StorageSystem storage, FileDeviceInputOutput args, SMBFileShare smbFileShare)
            throws ControllerException {
        try {
            _log.info("IsilonFileStorageDevice doDeleteShare: {} - start");
            IsilonApi isi = getIsilonDevice(storage);
            isiDeleteShare(isi, args, smbFileShare);
            _log.info("IsilonFileStorageDevice doDeleteShare {} - complete");
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doDeleteShare failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    @Override
    public BiosCommandResult doDeleteShares(StorageSystem storage, FileDeviceInputOutput args)
            throws ControllerException {
        try {
            _log.info("IsilonFileStorageDevice doDeleteShares: {} - start");
            IsilonApi isi = getIsilonDevice(storage);
            isiDeleteShares(isi, args);
            _log.info("IsilonFileStorageDevice doDeleteShares {} - complete");
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doDeleteShares failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    @Override
    public BiosCommandResult doModifyFS(StorageSystem storage, FileDeviceInputOutput args) throws ControllerException {
        try {
            _log.info("IsilonFileStorageDevice doModifyFS {} - start", args.getFsId());
            IsilonApi isi = getIsilonDevice(storage);
            String quotaId = null;
            if (args.getFsExtensions() != null && args.getFsExtensions().get(QUOTA) != null) {
                quotaId = args.getFsExtensions().get(QUOTA);
            } else {
                final ServiceError serviceError = DeviceControllerErrors.isilon.unableToUpdateFileSystem(args.getFsId());
                _log.error(serviceError.getMessage());
                return BiosCommandResult.createErrorResult(serviceError);
            }

            IsilonSmartQuota expandedQuota = getExpandedQuota(isi, args, args.getFsCapacity());
            isi.modifyQuota(quotaId, expandedQuota);
            _log.info("IsilonFileStorageDevice doModifyFS {} - complete", args.getFsId());
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doModifyFS failed.", e);
            return BiosCommandResult.createErrorResult(e);
        } catch (Exception e) {
            _log.error("doModifyFS failed.", e);
            // convert this to a ServiceError and create/or reuse a service
            // code
            ServiceError serviceError = DeviceControllerErrors.isilon.unableToUpdateFileSystem(args.getFsId());
            return BiosCommandResult.createErrorResult(serviceError);
        }
    }

    @Override
    public BiosCommandResult doSnapshotFS(StorageSystem storage, FileDeviceInputOutput args)
            throws ControllerException {
        try {
            _log.info("IsilonFileStorageDevice doSnapshotFS {} {} - start", args.getSnapshotId(),
                    args.getSnapshotName());
            IsilonApi isi = getIsilonDevice(storage);
            // To Do - add timestamp for uniqueness
            String snapId = isi.createSnapshot(args.getSnapshotName(), args.getFsMountPath());
            if (args.getSnapshotExtensions() == null) {
                args.initSnapshotExtensions();
            }
            args.getSnapshotExtensions().put("id", snapId);
            args.setSnapNativeId(snapId);
            String path = getSnapshotPath(args.getFsMountPath(), args.getSnapshotName());
            args.setSnapshotMountPath(path);
            args.setSnapshotPath(path);
            _log.info("IsilonFileStorageDevice doSnapshotFS {} - complete", args.getSnapshotId());
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doSnapshotFS failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    @Override
    public BiosCommandResult doRestoreFS(StorageSystem storage, FileDeviceInputOutput args) throws ControllerException {
        BiosCommandResult result = new BiosCommandResult();
        String opName = ResourceOperationTypeEnum.RESTORE_FILE_SNAPSHOT.getName();
        ServiceError serviceError = IsilonException.errors.jobFailed(opName);
        result.error(serviceError);
        return result;
    }

    @Override
    public BiosCommandResult doDeleteSnapshot(StorageSystem storage, FileDeviceInputOutput args)
            throws ControllerException {
        try {
            _log.info("IsilonFileStorageDevice doDeleteSnapshot {} - start", args.getSnapshotId());
            IsilonApi isi = getIsilonDevice(storage);
            isiDeleteSnapshot(isi, args);
            _log.info("IsilonFileStorageDevice doDeleteSnapshot {} - complete", args.getSnapshotId());
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doDeleteSnapshot failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    // Get FS snapshot list from the array
    @Override
    public BiosCommandResult getFSSnapshotList(StorageSystem storage, FileDeviceInputOutput args,
            List<String> snapshots) throws ControllerException {

        String op = "getFSSnapshotList";
        String devType = storage.getSystemType();
        BiosCommandResult result = BiosCommandResult
                .createErrorResult(DeviceControllerException.errors.unsupportedOperationOnDevType(op, devType));

        return result;

    }

    @Override
    public void doConnect(StorageSystem storage) throws ControllerException {
        try {
            _log.info("doConnect {} - start", storage.getId());
            IsilonApi isi = getIsilonDevice(storage);
            isi.getClusterInfo();
            String msg = String.format("doConnect %1$s - complete", storage.getId());
            _log.info(msg);
        } catch (IsilonException e) {
            _log.error("doConnect failed.", e);
            throw DeviceControllerException.exceptions.connectStorageFailed(e);
        }
    }

    @Override
    public void doDisconnect(StorageSystem storage) {
        // not much to do here ... just reply success
    }

    @Override
    public BiosCommandResult getPhysicalInventory(StorageSystem storage) {
        ServiceError serviceError = DeviceControllerErrors.isilon.unableToGetPhysicalInventory(storage.getId());
        return BiosCommandResult.createErrorResult(serviceError);
    }

    @Override
    public BiosCommandResult doCreateQuotaDirectory(StorageSystem storage, FileDeviceInputOutput args,
            QuotaDirectory quotaDir) throws ControllerException {

        // Get Parent FS mount path
        // Get Quota Directory Name
        // Get Quota Size
        // Call create Directory
        // Call create Quota (Aways use that quota for updating the size)

        String fsMountPath = args.getFsMountPath();
        Long qDirSize = quotaDir.getSize();
        String qDirPath = fsMountPath + "/" + quotaDir.getName();
        _log.info("IsilonFileStorageDevice doCreateQuotaDirectory {} with size {} - start", qDirPath, qDirSize);
        try {
            IsilonApi isi = getIsilonDevice(storage);
            // create directory for the file share
            isi.createDir(qDirPath, true);

            String qid = checkThresholdAndcreateQuota(quotaDir, qDirSize, qDirPath, args.getFsCapacity(), isi);

            if (args.getQuotaDirExtensions() == null) {
                args.initQuotaDirExtensions();
            }
            args.getQuotaDirExtensions().put(QUOTA, qid);

            _log.info("IsilonFileStorageDevice doCreateQuotaDirectory {} with size {} - complete", qDirPath, qDirSize);
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doCreateQuotaDirectory failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    @Override
    public BiosCommandResult doDeleteQuotaDirectory(StorageSystem storage, FileDeviceInputOutput args)
            throws ControllerException {

        // Get Parent FS Mount Path
        // Get Quota Directory Name
        // Get Quota Size
        // Call Delete Quota
        // Call Delete Directory recursively

        QuotaDirectory quotaDir = args.getQuotaDirectory();
        String fsMountPath = args.getFsMountPath();
        Long qDirSize = quotaDir.getSize();
        String qDirPath = fsMountPath + "/" + quotaDir.getName();
        _log.info("IsilonFileStorageDevice doDeleteQuotaDirectory {} with size {} - start", qDirPath, qDirSize);
        try {
            IsilonApi isi = getIsilonDevice(storage);

            String quotaId = null;
            if (quotaDir.getExtensions() != null) {
                quotaId = quotaDir.getExtensions().get(QUOTA);
            }
            if (quotaId != null) {
                _log.info("IsilonFileStorageDevice doDeleteQuotaDirectory , Delete Quota {}", quotaId);
                isi.deleteQuota(quotaId);
            }

            // delete directory for the Quota Directory
            isi.deleteDir(qDirPath, true);
            _log.info("IsilonFileStorageDevice doDeleteQuotaDirectory {} with size {} - complete", qDirPath, qDirSize);
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doDeleteQuotaDirectory failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    @Override
    public BiosCommandResult doUpdateQuotaDirectory(StorageSystem storage, FileDeviceInputOutput args,
            QuotaDirectory quotaDir) throws ControllerException {
        // Get Parent FS mount path
        // Get Quota Directory Name
        // Get Quota Size
        // Call Update Quota (Aways use that quota for updating the size)

        String fsMountPath = args.getFsMountPath();
        Long qDirSize = quotaDir.getSize();
        String qDirPath = fsMountPath + "/" + quotaDir.getName();
        _log.info("IsilonFileStorageDevice doUpdateQuotaDirectory {} with size {} - start", qDirPath, qDirSize);
        try {
            IsilonApi isi = getIsilonDevice(storage);

            String quotaId = null;
            if (quotaDir.getExtensions() != null) {
                quotaId = quotaDir.getExtensions().get(QUOTA);
            }

            if (quotaId != null) {
                // Isilon does not allow to update quota directory to zero.
                if (qDirSize > 0) {
                    _log.info("IsilonFileStorageDevice doUpdateQuotaDirectory , Update Quota {} with Capacity {}", quotaId, qDirSize);
                    IsilonSmartQuota expandedQuota = getQuotaDirectoryExpandedSmartQuota(quotaDir, qDirSize, args.getFsCapacity(), isi);
                    isi.modifyQuota(quotaId, expandedQuota);
                }

            } else {
                // Create a new Quota
                String qid = checkThresholdAndcreateQuota(quotaDir, qDirSize, qDirPath, null, isi);

                if (args.getQuotaDirExtensions() == null) {
                    args.initQuotaDirExtensions();
                }
                args.getQuotaDirExtensions().put(QUOTA, qid);

            }
            _log.info("IsilonFileStorageDevice doUpdateQuotaDirectory {} with size {} - complete", qDirPath, qDirSize);
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("doUpdateQuotaDirectory failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    private IsilonSmartQuota getQuotaDirectoryExpandedSmartQuota(QuotaDirectory quotaDir, Long qDirSize, Long fsSize, IsilonApi isi) {
        Long notificationLimit = 0L;
        Long softlimit = 0L;
        Long softGrace = 0L;

        if (quotaDir.getNotificationLimit() != null) {
            notificationLimit = Long.valueOf(quotaDir.getNotificationLimit());
        }

        if (quotaDir.getSoftLimit() != null) {
            softlimit = Long.valueOf(quotaDir.getSoftLimit());
        }

        if (quotaDir.getSoftGrace() != null) {
            softGrace = Long.valueOf(quotaDir.getSoftGrace());
        }

        return isi.constructIsilonSmartQuotaObjectWithThreshold(null, null, fsSize, false, null, qDirSize,
                notificationLimit, softlimit, softGrace);
    }

    private String checkThresholdAndcreateQuota(QuotaDirectory quotaDir, Long qDirSize, String qDirPath, Long fsSize, IsilonApi isi) {
        Long notificationLimit = 0L;
        Long softlimit = 0L;
        Long softGrace = 0L;

        if (quotaDir.getNotificationLimit() != null) {
            notificationLimit = Long.valueOf(quotaDir.getNotificationLimit());
        }

        if (quotaDir.getSoftLimit() != null) {
            softlimit = Long.valueOf(quotaDir.getSoftLimit());
        }

        if (quotaDir.getSoftGrace() != null) {
            softGrace = Long.valueOf(quotaDir.getSoftGrace());
        }

        return createQuotaWithThreshold(qDirPath, qDirSize,
                softlimit, notificationLimit, softGrace, fsSize, isi);
    }

    public String createQuotaWithThreshold(String qDirPath, Long qDirSize, Long softLimitSize, Long notificationLimitSize,
            Long softGracePeriod, Long fsSize, IsilonApi isi) {
        boolean bThresholdsIncludeOverhead = true;
        boolean bIncludeSnapshots = true;

        if (configinfo != null) {
            if (configinfo.containsKey("thresholdsIncludeOverhead")) {
                bThresholdsIncludeOverhead = Boolean.parseBoolean(configinfo.get("thresholdsIncludeOverhead"));
            }
            if (configinfo.containsKey("includeSnapshots")) {
                bIncludeSnapshots = Boolean.parseBoolean(configinfo.get("includeSnapshots"));
            }

        }

        // set quota - save the quota id to extensions
        String qid = isi.createQuota(qDirPath, fsSize, bThresholdsIncludeOverhead,
                bIncludeSnapshots, qDirSize, notificationLimitSize != null ? notificationLimitSize : 0L,
                        softLimitSize != null ? softLimitSize : 0L, softGracePeriod != null ? softGracePeriod : 0L);
        return qid;
    }

    @Override
    public BiosCommandResult deleteExportRules(StorageSystem storage, FileDeviceInputOutput args)
            throws ControllerException {
        BiosCommandResult result = new BiosCommandResult();
        List<ExportRule> allExports = args.getExistingDBExportRules();
        String subDir = args.getSubDirectory();
        boolean allDirs = args.isAllDir();

        String exportPath;
        String subDirExportPath = "";
        subDir = args.getSubDirectory();

        if (!args.getFileOperation()) {
            exportPath = args.getSnapshotPath();
            if (subDir != null && subDir.length() > 0) {
                subDirExportPath = args.getSnapshotPath() + "/" + subDir;
            }

        } else {
            exportPath = args.getFs().getPath();
            if (subDir != null && subDir.length() > 0) {
                subDirExportPath = args.getFs().getPath() + "/" + subDir;
            }
        }

        _log.info("exportPath : {}", exportPath);
        args.setExportPath(exportPath);

        _log.info("Number of existing exports found {}", allExports.size());

        try {

            IsilonApi isi = getIsilonDevice(storage);
            String zoneName = getZoneName(args.getvNAS());

            if (allDirs) {
                // ALL EXPORTS
                _log.info(
                        "Deleting all exports specific to filesystem at device and rules from DB including sub dirs rules and exports");
                for (ExportRule rule : allExports) {
                    _log.info("Delete IsilonExport id {} for path {}", rule.getDeviceExportId(), rule.getExportPath());
                    if (zoneName != null) {
                        isi.deleteExport(rule.getDeviceExportId(), zoneName);
                    } else {
                        isi.deleteExport(rule.getDeviceExportId());
                    }
                }

            } else if (subDir != null && !subDir.isEmpty()) {
                // Filter for a specific Sub Directory export
                _log.info("Deleting all subdir exports rules at ViPR and  sub directory export at device {}", subDir);
                for (ExportRule rule : allExports) {
                    _log.info("Delete IsilonExport id for path {} f containing subdirectory {}",
                            rule.getDeviceExportId() + ":" + rule.getExportPath(), subDir);

                    String fsExportPathWithSub = args.getFsPath() + "/" + subDir;
                    if (rule.getExportPath().equalsIgnoreCase(fsExportPathWithSub)) {
                        _log.info("Delete IsilonExport id {} for path {}", rule.getDeviceExportId(),
                                rule.getExportPath());
                        if (zoneName != null) {
                            isi.deleteExport(rule.getDeviceExportId(), zoneName);
                        } else {
                            isi.deleteExport(rule.getDeviceExportId());
                        }
                    }
                }

            } else {
                // Filter for No SUBDIR - main export rules with no sub dirs
                _log.info("Deleting all export rules  from DB and export at device not included sub dirs");
                for (ExportRule rule : allExports) {
                    if (rule.getExportPath().equalsIgnoreCase(exportPath)) {
                        _log.info("Delete IsilonExport id {} for path {}", rule.getDeviceExportId(),
                                rule.getExportPath());
                        if (zoneName != null) {
                            isi.deleteExport(rule.getDeviceExportId(), zoneName);
                        } else {
                            isi.deleteExport(rule.getDeviceExportId());
                        }
                    }
                }
            }

        } catch (IsilonException ie) {
            _log.info("Exception: {}", ie);

            throw new DeviceControllerException("Exception while performing export for {0} ",
                    new Object[] { args.getFsId() });
        }

        _log.info("IsilonFileStorageDevice exportFS {} - complete", args.getFsId());
        result.setCommandSuccess(true);
        result.setCommandStatus(Operation.Status.ready.name());
        return result;
    }

    @Override
    public BiosCommandResult updateExportRules(StorageSystem storage, FileDeviceInputOutput args)
            throws ControllerException {
        // Requested Export Rules
        List<ExportRule> exportAdd = args.getExportRulesToAdd();
        List<ExportRule> exportDelete = args.getExportRulesToDelete();
        List<ExportRule> exportModify = args.getExportRulesToModify();

        // To be processed export rules
        List<ExportRule> exportsToRemove = new ArrayList<>();
        List<ExportRule> exportsToModify = new ArrayList<>();
        List<ExportRule> exportsToAdd = new ArrayList<>();

        // Calculate Export Path
        String exportPath;
        String subDir = args.getSubDirectory();

        // It is a Snapshot Export Update and so Sub Directory will be
        // ".snapshot"
        if (!args.getFileOperation()) {
            exportPath = args.getSnapshotPath();
            if (subDir != null && subDir.length() > 0) {
                exportPath = args.getSnapshotPath() + "/" + subDir;
            }

        } else {
            exportPath = args.getFs().getPath();
            if (subDir != null && subDir.length() > 0) {
                exportPath = args.getFs().getPath() + "/" + subDir;
            }
        }

        _log.info("exportPath : {}", exportPath);
        args.setExportPath(exportPath);

        try {
            // add the new export rule from the array into the update request.
            Map<String, ExportRule> arrayExportRuleMap = extraExportRuleFromArray(storage, args);

            if (!arrayExportRuleMap.isEmpty()) {
                if (exportModify != null) {
                    // merge the end point for which sec flavor is common.
                    for (ExportRule exportRule : exportModify) {
                        ExportRule arrayExportRule = arrayExportRuleMap.remove(exportRule.getSecFlavor());
                        if (arrayExportRule != null) {

                            if (exportRule.getReadOnlyHosts() != null) {
                                exportRule.getReadOnlyHosts().addAll(arrayExportRule.getReadOnlyHosts());
                            } else {
                                exportRule.setReadOnlyHosts(arrayExportRule.getReadOnlyHosts());

                            }
                            if (exportRule.getReadWriteHosts() != null) {
                                exportRule.getReadWriteHosts().addAll(arrayExportRule.getReadWriteHosts());
                            } else {
                                exportRule.setReadWriteHosts(arrayExportRule.getReadWriteHosts());

                            }
                            if (exportRule.getRootHosts() != null) {
                                exportRule.getRootHosts().addAll(arrayExportRule.getRootHosts());
                            } else {
                                exportRule.setRootHosts(arrayExportRule.getRootHosts());

                            }
                        }
                    }
                    // now add the remaining export rule
                    exportModify.addAll(arrayExportRuleMap.values());

                } else {
                    // if exportModify is null then create a new export rule and add
                    exportModify = new ArrayList<ExportRule>();
                    exportModify.addAll(arrayExportRuleMap.values());

                }
            }
        } catch (Exception e) {
            // TODO Auto-generated catch block
            _log.error("Not able to fetch latest Export rule from backend array.", e);

        }

        // ALL EXPORTS
        List<ExportRule> existingDBExportRule = args.getExistingDBExportRules();
        List<ExportRule> exportsToprocess = new ArrayList<>();
        for (ExportRule rule : existingDBExportRule) {
            if (rule.getExportPath().equalsIgnoreCase(exportPath)) {
                exportsToprocess.add(rule);
            }
        }

        _log.info("Number of existing Rules found {} for exportPath {}", exportsToprocess.size(), exportPath);
        // Isilon have separate entry for read only and read/write host list
        // if we want to modify export from host H1 with permission read to H2
        // with read/write. then need to delete the entry from read
        // list and add to read/Write list.
        if (!exportsToprocess.isEmpty() || !exportAdd.isEmpty()) {
            if (exportModify != null && !exportModify.isEmpty()) {
                for (ExportRule existingRule : exportsToprocess) {
                    for (ExportRule newExportRule : exportModify) {
                        if (newExportRule.getSecFlavor().equals(existingRule.getSecFlavor())) {
                            newExportRule.setDeviceExportId(existingRule.getDeviceExportId());
                            exportsToModify.add(newExportRule);
                        }
                    }
                }
            }
            // Handle Delete export Rules
            if (exportDelete != null && !exportDelete.isEmpty()) {
                for (ExportRule existingRule : exportsToprocess) {
                    for (ExportRule oldExport : exportDelete) {
                        if (oldExport.getSecFlavor().equals(existingRule.getSecFlavor())) {
                            _log.info("Deleting Export Rule {}", existingRule);
                            exportsToRemove.add(existingRule);
                        }
                    }
                }
            }
            // No of exports found to remove from the list
            _log.info("No of exports found to remove from the existing exports list {}", exportsToRemove.size());
            exportsToprocess.removeAll(exportsToRemove);

            // Handle Add Export Rules
            if (exportAdd != null && !exportAdd.isEmpty()) {
                for (ExportRule newExport : exportAdd) {
                    _log.info("Add Export Rule {}", newExport);
                    newExport.setExportPath(exportPath);
                    exportsToAdd.add(newExport);
                }
            }
            exportsToprocess.addAll(exportAdd);
        }

        // Process Mods
        IsilonApi isi = getIsilonDevice(storage);

        for (ExportRule existingRule : exportsToModify) {
            _log.info("Modify Export rule : {}", existingRule.toString());
        }

        processIsiExport(isi, args, exportsToModify);

        for (ExportRule existingRule : exportsToRemove) {
            _log.info("Remove Export rule : {}", existingRule.toString());
        }

        processRemoveIsiExport(isi, args, exportsToRemove);

        for (ExportRule existingRule : exportsToAdd) {
            _log.info("Add Export rule : {}", existingRule.toString());
        }

        processAddIsiExport(isi, args, exportsToAdd);

        BiosCommandResult result = BiosCommandResult.createSuccessfulResult();
        return result;

    }

    /**
     * Get the export rule which are present in arry but not in CoprHD Database.
     * 
     * @param storage
     * @param args
     * @return map with security flavor and export rule
     */
    private Map<String, ExportRule> extraExportRuleFromArray(StorageSystem storage, FileDeviceInputOutput args) {

        // map to store the export rule grouped by sec flavor
        Map<String, ExportRule> exportRuleMap = new HashMap<>();
        List<IsilonExport> exportsList = new ArrayList<IsilonExport>();

        Set<String> arrayReadOnlyHost = new HashSet<>();
        Set<String> arrayReadWriteHost = new HashSet<>();
        Set<String> arrayRootHost = new HashSet<>();

        Set<String> dbReadOnlyHost = new HashSet<>();
        Set<String> dbReadWriteHost = new HashSet<>();
        Set<String> dbRootHost = new HashSet<>();

        // get all export rule from CoprHD data base
        List<ExportRule> existingDBExportRules = args.getExistingDBExportRules();

        // get the all the export from the storage system.
        IsilonApi isi = getIsilonDevice(storage);
        for (ExportRule exportRule : existingDBExportRules) {
            if (exportRule.getReadOnlyHosts() != null) {
                dbReadOnlyHost.addAll(exportRule.getReadOnlyHosts());
            }
            if (exportRule.getReadWriteHosts() != null) {
                dbReadWriteHost.addAll(exportRule.getReadWriteHosts());
            }
            if (exportRule.getRootHosts() != null) {
                dbRootHost.addAll(exportRule.getRootHosts());
            }

            String isilonExportId = exportRule.getDeviceExportId();
            if (isilonExportId != null) {
                IsilonExport isilonExport = null;
                String zoneName = getZoneName(args.getvNAS());
                if (zoneName != null) {
                    isilonExport = isi.getExport(isilonExportId, zoneName);
                } else {
                    isilonExport = isi.getExport(isilonExportId);
                }
                exportsList.add(isilonExport);

                arrayReadOnlyHost.addAll(isilonExport.getReadOnlyClients());
                arrayReadWriteHost.addAll(isilonExport.getReadWriteClients());
                arrayRootHost.addAll(isilonExport.getRootClients());

            }

            // find out the change between array and CoprHD database.
            Set<String> arrayExtraReadOnlyHost = Sets.difference(arrayReadOnlyHost, dbReadOnlyHost);
            Set<String> arrayExtraReadWriteHost = Sets.difference(arrayReadWriteHost, dbReadWriteHost);
            Set<String> arrayExtraRootHost = Sets.difference(arrayRootHost, dbRootHost);
            // if change found update the exportRuleMap
            if (!arrayExtraReadOnlyHost.isEmpty() || !arrayExtraReadWriteHost.isEmpty() || !arrayExtraRootHost.isEmpty()) {
                ExportRule extraRuleFromArray = new ExportRule();
                extraRuleFromArray.setDeviceExportId(exportRule.getDeviceExportId());
                extraRuleFromArray.setAnon(exportRule.getAnon());
                extraRuleFromArray.setSecFlavor(exportRule.getSecFlavor());
                extraRuleFromArray.setExportPath(exportRule.getExportPath());
                extraRuleFromArray.setReadOnlyHosts(arrayExtraReadOnlyHost);
                extraRuleFromArray.setReadWriteHosts(arrayExtraReadWriteHost);
                extraRuleFromArray.setRootHosts(arrayExtraRootHost);
                exportRuleMap.put(exportRule.getSecFlavor(), extraRuleFromArray);
            }

        }

        return exportRuleMap;

    }

    /**
     * Add isilon exports
     * 
     * @param isi
     *            IsilonApi object
     * @param args
     *            FileDeviceInputOutput object
     * @param exports
     *            new exports to add
     * @throws IsilonException
     */
    private void processAddIsiExport(IsilonApi isi, FileDeviceInputOutput args, List<ExportRule> exports)
            throws IsilonException {

        _log.info("ProcessAddExport  Start");

        List<ExportRule> modifyRules = new ArrayList<>();

        // process and export each NFSExport independently.
        for (ExportRule exportRule : exports) {

            // create and set IsilonExport instance from ExportRule

            _log.info("Add this export rule {}", exportRule.toString());

            String isilonExportId = exportRule.getDeviceExportId();
            String zoneName = getZoneName(args.getvNAS());
            if (isilonExportId != null) {
                // The Export Rule already exists on the array so modify it
                _log.info("Export rule exists on the device so modify it: {}", exportRule);
                modifyRules.add(exportRule);
            } else {
                // Create the Export
                _log.info("Export rule does not exist on the device so create it: {}", exportRule);
                IsilonExport newIsilonExport = setIsilonExport(exportRule);
                String expId = null;
                if (zoneName != null) {
                    expId = isi.createExport(newIsilonExport, zoneName);
                } else {
                    expId = isi.createExport(newIsilonExport);
                }
                exportRule.setDeviceExportId(expId);
            }

            if (!modifyRules.isEmpty()) {
                // Call Process Isi Export
                processIsiExport(isi, args, modifyRules);
            }
        }
        _log.info("ProcessAddExport completed.");
    }

    /**
     * Update isilon exports
     * 
     * @param isi
     *            IsilonApi object
     * @param args
     *            FileDeviceInputOutput object
     * @param exports
     *            new exports to add
     * @throws IsilonException
     */
    private void processIsiExport(IsilonApi isi, FileDeviceInputOutput args, List<ExportRule> exports)
            throws IsilonException {

        _log.info("ProcessIsiExport  Start");
        // process and export each NFSExport independently.
        for (ExportRule exportRule : exports) {

            // create and set IsilonExport instance from ExportRule

            String root_user = exportRule.getAnon();
            Set<String> rootHosts = exportRule.getRootHosts();

            String isilonExportId = exportRule.getDeviceExportId();

            if (isilonExportId != null) {
                IsilonExport isilonExport = null;
                String zoneName = getZoneName(args.getvNAS());
                if (zoneName != null) {
                    isilonExport = isi.getExport(isilonExportId, zoneName);
                } else {
                    isilonExport = isi.getExport(isilonExportId);
                }

                // Update the comment
                if (exportRule.getComments() != null && !exportRule.getComments().isEmpty()) {
                    isilonExport.setComment(exportRule.getComments());
                }

                _log.info("Update Isilon Export with id {} and {}", isilonExportId, isilonExport);
                List<String> allClients = new ArrayList<>();
                if (isilonExport != null) {

                    boolean hasrwClients = false;
                    boolean hasrootClients = false;

                    if ((isilonExport.getReadWriteClients() != null && !isilonExport.getReadWriteClients().isEmpty())
                            || (exportRule.getReadWriteHosts() != null && !exportRule.getReadWriteHosts().isEmpty())) {
                        hasrwClients = true;
                    }
                    if ((isilonExport.getRootClients() != null && !isilonExport.getRootClients().isEmpty())
                            || (exportRule.getRootHosts() != null && !exportRule.getRootHosts().isEmpty())) {
                        hasrootClients = true;
                    }

                    List<String> roClients = new ArrayList<>();
                    // over write roClients
                    if (exportRule.getReadOnlyHosts() != null) {
                        roClients.addAll(exportRule.getReadOnlyHosts());
                        allClients.addAll(exportRule.getReadOnlyHosts());

                        List<String> existingRWRootClients = new ArrayList<String>();
                        existingRWRootClients.addAll(isilonExport.getReadWriteClients());
                        existingRWRootClients.addAll(isilonExport.getRootClients());

                        List<String> commonHosts = getIntersection(existingRWRootClients, roClients);

                        if (!commonHosts.isEmpty()) {
                            // RW, RO and Root permissions cannot co-exist for
                            // same client hosts
                            // Using Set to eliminate duplicates
                            Set<String> existingRWClients = new HashSet<String>(isilonExport.getReadWriteClients());
                            Set<String> existingRootClients = new HashSet<String>(isilonExport.getRootClients());
                            // Remove common hosts
                            existingRWClients.removeAll(commonHosts);
                            existingRootClients.removeAll(commonHosts);
                            isilonExport.setRootClients(new ArrayList<String>(existingRootClients));
                            isilonExport.setReadWriteClients(new ArrayList<String>(existingRWClients));
                        } else {
                            setClientsIntoIsilonExport("root", exportRule.getRootHosts(), isilonExport);
                            setClientsIntoIsilonExport("rw", exportRule.getReadWriteHosts(), isilonExport);
                        }
                        isilonExport.setReadOnlyClients(new ArrayList<String>(roClients));
                    }

                    List<String> rwClients = new ArrayList<>();
                    // over write rwClients has emptypayload or it contains
                    // elements
                    if (exportRule.getReadWriteHosts() != null) {
                        rwClients.addAll(exportRule.getReadWriteHosts());
                        allClients.addAll(exportRule.getReadWriteHosts());

                        List<String> existingRORootClients = new ArrayList<String>();
                        existingRORootClients.addAll(isilonExport.getReadOnlyClients());
                        existingRORootClients.addAll(isilonExport.getRootClients());

                        List<String> commonHosts = getIntersection(existingRORootClients, rwClients);

                        if (!commonHosts.isEmpty()) {

                            // RW, RO and Root permissions cannot co-exist for
                            // same client hosts
                            // Using Set to eliminate duplicates
                            Set<String> existingROClients = new HashSet<String>(isilonExport.getReadOnlyClients());
                            Set<String> existingRootClients = new HashSet<String>(isilonExport.getRootClients());
                            // Remove common hosts
                            existingROClients.removeAll(commonHosts);
                            existingRootClients.removeAll(commonHosts);
                            isilonExport.setRootClients(new ArrayList<String>(existingRootClients));
                            isilonExport.setReadOnlyClients(new ArrayList<String>(existingROClients));
                        } else {
                            setClientsIntoIsilonExport("root", exportRule.getRootHosts(), isilonExport);
                            setClientsIntoIsilonExport("ro", exportRule.getReadOnlyHosts(), isilonExport);
                        }
                        isilonExport.setReadWriteClients(new ArrayList<String>(rwClients));
                    }

                    // over write rootClients
                    List<String> rootClients = new ArrayList<>();
                    if (rootHosts != null) {
                        rootClients.addAll(rootHosts);
                        allClients.addAll(rootHosts);

                        List<String> existingRORWClients = new ArrayList<String>();
                        existingRORWClients.addAll(isilonExport.getReadOnlyClients());
                        existingRORWClients.addAll(isilonExport.getReadWriteClients());

                        List<String> commonHosts = getIntersection(existingRORWClients, rootClients);

                        if (!commonHosts.isEmpty()) {
                            // RW, RO and Root permissions cannot co-exist for
                            // same client hosts

                            Set<String> existingROClients = new HashSet<String>(isilonExport.getReadOnlyClients());
                            Set<String> existingRWClients = new HashSet<String>(isilonExport.getReadWriteClients());
                            existingROClients.removeAll(commonHosts);
                            existingRWClients.removeAll(commonHosts);
                            isilonExport.setReadWriteClients(new ArrayList<String>(existingRWClients));
                            isilonExport.setReadOnlyClients(new ArrayList<String>(existingROClients));
                        } else {
                            setClientsIntoIsilonExport("ro", exportRule.getReadOnlyHosts(), isilonExport);
                            setClientsIntoIsilonExport("rw", exportRule.getReadWriteHosts(), isilonExport);
                        }
                        isilonExport.setRootClients(new ArrayList<String>(rootClients));
                    }

                    if (hasrwClients || hasrootClients) {
                        isilonExport.resetReadOnly();
                    } else {
                        isilonExport.setReadOnly();
                    }

                    isilonExport.setMapAll(null);
                    isilonExport.setMapRoot(root_user);

                    // There is export in Isilon with the given id.
                    // Overwrite this export with a new set of clients.
                    // We overwrite only clients element in exports. Isilon API
                    // does not use read_only_clients,
                    // read_write_clients or root_clients.

                    // List<String> newClients = isilonExport.getClients();
                    // newClients.addAll(allClients);
                    isilonExport.setClients(new ArrayList<String>(allClients));

                    IsilonExport clonedExport = cloneExport(isilonExport);

                    _log.info("Update Isilon Export with id {} and new info {}", isilonExportId,
                            clonedExport.toString());

                    if (zoneName != null) {
                        isi.modifyExport(isilonExportId, zoneName, clonedExport);
                    } else {
                        isi.modifyExport(isilonExportId, clonedExport);
                    }

                }
            }
        }
        _log.info("ProcessIsiExport  Completed");
    }

    /**
     * Delete isilon exports
     * 
     * @param isi
     *            IsilonApi object
     * @param args
     *            FileDeviceInputOutput object
     * @param exports
     *            new exports to add
     * @throws IsilonException
     */
    private void processRemoveIsiExport(IsilonApi isi, FileDeviceInputOutput args, List<ExportRule> exports)
            throws IsilonException {

        _log.info("processRemoveIsiExport  Start");

        // process and export each NFSExport independently.
        for (ExportRule exportRule : exports) {

            // create and set IsilonExport instance from ExportRule
            _log.info("Remove this export rule {}", exportRule.toString());
            String isilonExportId = exportRule.getDeviceExportId();

            if (isilonExportId != null) {
                // The Export Rule already exists on the array so modify it
                _log.info("Export rule exists on the device so remove it: {}", exportRule);
                String zoneName = getZoneName(args.getvNAS());
                if (zoneName != null) {
                    isi.deleteExport(isilonExportId, zoneName);
                } else {
                    isi.deleteExport(isilonExportId);
                }

            }
        }
        _log.info("processRemoveIsiExport  Completed");
    }

    private IsilonExport cloneExport(IsilonExport exp) {
        IsilonExport newExport = new IsilonExport();

        newExport.addPath(exp.getPaths().get(0));
        newExport.addRootClients(exp.getRootClients());
        newExport.addReadWriteClients(exp.getReadWriteClients());
        newExport.addReadOnlyClients(exp.getReadOnlyClients());

        if (exp.getReadOnly()) {
            newExport.setReadOnly();
        } else {
            newExport.resetReadOnly();
        }

        if (exp.getAllDirs()) {
            newExport.setAllDirs();
        } else {
            newExport.resetAllDirs();
        }
        newExport.addClients(exp.getClients());
        if (exp.getComment() != null) {
            newExport.setComment(exp.getComment());
        }
        newExport.setSecurityFlavors(exp.getSecurityFlavors());

        if (exp.getMap_all().getUser() != null && !exp.getMap_all().getUser().isEmpty()) {
            newExport.setMapAll(exp.getMap_all().getUser());
        }
        if (exp.getMap_root().getUser() != null && !exp.getMap_root().getUser().isEmpty()) {
            newExport.setMapRoot(exp.getMap_root().getUser());
        }

        return newExport;
    }

    private List<String> getIntersection(List<String> oldList, List<String> newList) {

        Set<String> a = new HashSet<String>(oldList);
        a.retainAll(newList);
        return new ArrayList<String>(a);
    }

    @Override
    public BiosCommandResult updateShareACLs(StorageSystem storage, FileDeviceInputOutput args) {
        // Requested Share ACL
        List<ShareACL> aclsToAdd = args.getShareAclsToAdd();
        List<ShareACL> aclsToDelete = args.getShareAclsToDelete();
        List<ShareACL> aclsToModify = args.getShareAclsToModify();
        try {
            // add the new Share ACL from the array into the add request.
            Map<String, ShareACL> arrayExtraShareACL = extraShareACLFromArray(storage, args);
            _log.info("Number of extra ACLs found on array  is: {}", arrayExtraShareACL.size());
            if (!arrayExtraShareACL.isEmpty()) {
                if (aclsToAdd != null) {
                    // now add the remaining Share ACL
                    aclsToAdd.addAll(arrayExtraShareACL.values());
                } else {
                    // if add acl is null then create a new Share ACL and add
                    aclsToAdd = new ArrayList<ShareACL>();
                    aclsToAdd.addAll(arrayExtraShareACL.values());
                    // update the args so new acl get persisted in CoprHD DB.
                    args.setShareAclsToAdd(aclsToAdd);
                }

            }
        } catch (Exception e) {
            // TODO Auto-generated catch block
            _log.error("Not able to fetch latest Share ACL from backend array.", e);

        }
        // Get existing Acls for the share
        List<ShareACL> aclsToProcess = args.getExistingShareAcls();

        _log.info("Share name : {}", args.getShareName());

        // Process Acls
        _log.info("Number of existing ACLs found {}", aclsToProcess.size());

        // Process ACLs to add
        aclsToProcess.addAll(aclsToAdd);

        // Process ACLs to modify
        for (ShareACL existingAcl : aclsToProcess) {
            String domainOfExistingAce = existingAcl.getDomain();
            if (domainOfExistingAce == null) {
                domainOfExistingAce = "";
            }
            for (ShareACL aclToModify : aclsToModify) {
                String domainOfmodifiedAce = aclToModify.getDomain();
                if (domainOfmodifiedAce == null) {
                    domainOfmodifiedAce = "";
                }

                if (aclToModify.getUser() != null && existingAcl.getUser() != null) {
                    if (domainOfExistingAce.concat(existingAcl.getUser())
                            .equalsIgnoreCase(domainOfmodifiedAce.concat(aclToModify.getUser()))) {

                        existingAcl.setPermission(aclToModify.getPermission());
                    }
                }

                if (aclToModify.getGroup() != null && existingAcl.getGroup() != null) {
                    if (domainOfExistingAce.concat(existingAcl.getGroup())
                            .equalsIgnoreCase(domainOfmodifiedAce.concat(aclToModify.getGroup()))) {
                        existingAcl.setPermission(aclToModify.getPermission());
                    }
                }
            }
        }

        // Process ACLs to delete
        for (ShareACL aclToDelete : aclsToDelete) {

            String domainOfDeleteAce = aclToDelete.getDomain();
            if (domainOfDeleteAce == null) {
                domainOfDeleteAce = "";
            }

            for (Iterator<ShareACL> iterator = aclsToProcess.iterator(); iterator.hasNext();) {
                ShareACL existingAcl = iterator.next();

                String domainOfExistingAce = existingAcl.getDomain();
                if (domainOfExistingAce == null) {
                    domainOfExistingAce = "";
                }

                if (aclToDelete.getUser() != null && existingAcl.getUser() != null) {
                    if (domainOfDeleteAce.concat(aclToDelete.getUser())
                            .equalsIgnoreCase(domainOfExistingAce.concat(existingAcl.getUser()))) {
                        iterator.remove();
                    }
                }

                if (aclToDelete.getGroup() != null && existingAcl.getGroup() != null) {
                    if (domainOfDeleteAce.concat(aclToDelete.getGroup())
                            .equalsIgnoreCase(domainOfExistingAce.concat(existingAcl.getGroup()))) {
                        iterator.remove();
                    }
                }
            }
        }

        // Process new ACLs
        IsilonApi isi = getIsilonDevice(storage);
        processAclsForShare(isi, args, aclsToProcess);

        BiosCommandResult result = BiosCommandResult.createSuccessfulResult();
        return result;

    }

    /**
     * Get the Share ACL which are present in array but not in CoprHD Database.
     * 
     * @param storage
     * @param args
     * @return Map with domain+ group or username with ShareACL
     */
    private Map<String, ShareACL> extraShareACLFromArray(StorageSystem storage, FileDeviceInputOutput args) {

        // get all Share ACL from CoprHD data base
        List<ShareACL> existingDBShareACL = args.getExistingShareAcls();

        Map<String, ShareACL> arrayShareACLMap = new HashMap<>();

        // get the all the Share ACL from the storage system.
        IsilonApi isi = getIsilonDevice(storage);
        String zoneName = getZoneName(args.getvNAS());
        IsilonSMBShare share = null;
        if (zoneName != null) {
            share = isi.getShare(args.getShareName(), zoneName);
        } else {
            share = isi.getShare(args.getShareName());
        }
        if (share != null) {
            List<Permission> permissions = share.getPermissions();
            for (Permission perm : permissions) {
                if (perm.getPermissionType().equalsIgnoreCase(Permission.PERMISSION_TYPE_ALLOW)) {
                    ShareACL shareACL = new ShareACL();
                    shareACL.setPermission(perm.getPermission());
                    String userAndDomain = perm.getTrustee().getName();
                    String[] trustees = new String[2];
                    trustees = userAndDomain.split("\\\\");
                    String trusteesType = perm.getTrustee().getType();
                    if (trustees.length > 1) {
                        shareACL.setDomain(trustees[0]);
                        if (trusteesType.equals("group")) {
                            shareACL.setGroup(trustees[1]);
                        } else {
                            shareACL.setUser(trustees[1]);
                        }
                    } else {
                        if (trusteesType.equals("group")) {
                            shareACL.setGroup(trustees[0]);
                        } else {
                            shareACL.setUser(trustees[0]);
                        }
                    }
                    arrayShareACLMap.put(perm.getTrustee().getName(), shareACL);

                }
            }
            for (Iterator iterator = existingDBShareACL.iterator(); iterator.hasNext();) {
                ShareACL shareACL = (ShareACL) iterator.next();
                String key = "";
                String domain = "";
                String user = shareACL.getUser();
                String group = shareACL.getGroup();
                if (shareACL.getDomain() != null && !shareACL.getDomain().isEmpty()) {
                    domain = shareACL.getDomain() + "\\";
                }
                if (user != null && !user.isEmpty()) {
                    key = domain + user;
                } else if (group != null && !group.isEmpty()) {
                    key = domain + group;
                }
                if (arrayShareACLMap.containsKey(key)) {

                    arrayShareACLMap.remove(key);
                }
            }
        }
        return arrayShareACLMap;

    }

    /**
     * get share details
     * 
     * @param isilonApi
     * @param shareId
     * @return
     */
    private IsilonSMBShare getIsilonSMBShare(IsilonApi isilonApi, String shareId, String zoneName) {
        _log.debug("call getIsilonSMBShare for {} ", shareId);
        IsilonSMBShare isilonSMBShare = null;
        try {
            if (isilonApi != null) {
                isilonSMBShare = isilonApi.getShare(shareId, zoneName);
                _log.debug("call getIsilonSMBShare {}", isilonSMBShare.toString());
            }
        } catch (Exception e) {
            _log.error("Exception while getting SMBShare for {}", shareId);
        }
        return isilonSMBShare;
    }

    @Override
    public BiosCommandResult deleteShareACLs(StorageSystem storage, FileDeviceInputOutput args) {

        IsilonApi isi = getIsilonDevice(storage);
        processAclsForShare(isi, args, null);

        BiosCommandResult result = BiosCommandResult.createSuccessfulResult();
        return result;
    }

    /**
     * Sets permissions on Isilon SMB share.
     * 
     * @param isi
     *            the isilon API handle
     * @param args
     *            in which the attribute <code>shareName</code> must be set
     * @param aclsToProcess
     *            the ACEs to set on Isilon SMB share. If this value is null,
     *            then no permissions (ACEs) will be set
     */
    private void processAclsForShare(IsilonApi isi, FileDeviceInputOutput args, List<ShareACL> aclsToProcess) {

        _log.info("Start processAclsForShare to set ACL for share {}: ACL: {}", args.getShareName(), aclsToProcess);

        IsilonSMBShare isilonSMBShare = new IsilonSMBShare(args.getShareName());
        ArrayList<Permission> permissions = new ArrayList<Permission>();
        String permissionValue = null;
        String permissionTypeValue = null;
        if (aclsToProcess != null) {
            for (ShareACL acl : aclsToProcess) {
                String domain = acl.getDomain();
                if (domain == null) {
                    domain = "";
                }
                domain = domain.toLowerCase();
                String userOrGroup = acl.getUser() == null ? acl.getGroup().toLowerCase() : acl.getUser().toLowerCase();
                if (domain.length() > 0) {
                    userOrGroup = domain + "\\" + userOrGroup;
                }
                permissionValue = acl.getPermission().toLowerCase();
                if (permissionValue.startsWith("full")) {
                    permissionValue = Permission.PERMISSION_FULL;
                }

                permissionTypeValue = Permission.PERMISSION_TYPE_ALLOW;
                Permission permission = isilonSMBShare.new Permission(permissionTypeValue, permissionValue,
                        userOrGroup);
                permissions.add(permission);
            }
        }
        /*
         * If permissions array list is empty, it means to remove all ACEs on
         * the share.
         */
        isilonSMBShare.setPermissions(permissions);
        _log.info("Calling Isilon API: modifyShare. Share {}, permissions {}", isilonSMBShare, permissions);
        String zoneName = getZoneName(args.getvNAS());
        if (zoneName != null) {
            isi.modifyShare(args.getShareName(), zoneName, isilonSMBShare);
        } else {
            isi.modifyShare(args.getShareName(), isilonSMBShare);
        }

        _log.info("End processAclsForShare");
    }

    /**
     * getIsilonAclFromNfsACE function will convert the nfsACE object to Isilon
     * ACL object.
     * 
     * @param nfsACE
     *            - vipr ACE object.
     * @return
     */
    private Acl getIsilonAclFromNfsACE(NfsACE nfsACE) {

        IsilonNFSACL isilonAcl = new IsilonNFSACL();
        Acl acl = isilonAcl.new Acl();

        ArrayList<String> inheritFlags = new ArrayList<String>();

        inheritFlags.add("object_inherit");
        inheritFlags.add("inherit_only");
        acl.setInherit_flags(inheritFlags);
        acl.setAccessrights(getIsilonAccessList(nfsACE.getPermissionSet()));
        acl.setOp("add");
        acl.setAccesstype(nfsACE.getPermissionType());
        String user = nfsACE.getUser();
        if (nfsACE.getDomain() != null && !nfsACE.getDomain().isEmpty()) {
            user = nfsACE.getDomain() + "\\" + nfsACE.getUser();
        }

        IsilonNFSACL.Persona trustee = isilonAcl.new Persona(nfsACE.getType(), null, user);
        acl.setTrustee(trustee);

        return acl;
    }

    @Override
    public BiosCommandResult updateNfsACLs(StorageSystem storage, FileDeviceInputOutput args) {

        IsilonNFSACL isilonAcl = new IsilonNFSACL();
        ArrayList<Acl> aclCompleteList = new ArrayList<Acl>();
        List<NfsACE> aceToAdd = args.getNfsAclsToAdd();
        for (NfsACE nfsACE : aceToAdd) {
            Acl acl = getIsilonAclFromNfsACE(nfsACE);
            acl.setOp("add");
            aclCompleteList.add(acl);
        }

        List<NfsACE> aceToModify = args.getNfsAclsToModify();
        for (NfsACE nfsACE : aceToModify) {
            Acl acl = getIsilonAclFromNfsACE(nfsACE);
            acl.setOp("replace");
            aclCompleteList.add(acl);
        }

        List<NfsACE> aceToDelete = args.getNfsAclsToDelete();
        for (NfsACE nfsACE : aceToDelete) {
            Acl acl = getIsilonAclFromNfsACE(nfsACE);
            acl.setOp("delete");
            aclCompleteList.add(acl);
        }

        isilonAcl.setAction("update");
        isilonAcl.setAuthoritative("acl");
        isilonAcl.setAcl(aclCompleteList);
        String path = args.getFileSystemPath();
        if (args.getSubDirectory() != null && !args.getSubDirectory().isEmpty()) {
            path = path + "/" + args.getSubDirectory();

        }

        // Process new ACLs
        IsilonApi isi = getIsilonDevice(storage);
        _log.info("Calling Isilon API: modify NFS Acl for  {}, acl  {}", args.getFileSystemPath(), isilonAcl);
        isi.modifyNFSACL(path, isilonAcl);
        _log.info("End updateNfsACLs");
        BiosCommandResult result = BiosCommandResult.createSuccessfulResult();
        return result;
    }

    private ArrayList<String> getIsilonAccessList(Set<String> permissions) {

        ArrayList<String> accessRights = new ArrayList<String>();
        for (String per : permissions) {

            if (per.equalsIgnoreCase(FileControllerConstants.NFS_FILE_PERMISSION_READ)) {
                accessRights.add(IsilonNFSACL.AccessRights.dir_gen_read.toString());
            }

            if (per.equalsIgnoreCase(FileControllerConstants.NFS_FILE_PERMISSION_WRITE)) {
                accessRights.add(IsilonNFSACL.AccessRights.dir_gen_write.toString());
            }

            if (per.equalsIgnoreCase(FileControllerConstants.NFS_FILE_PERMISSION_EXECUTE)) {
                accessRights.add(IsilonNFSACL.AccessRights.dir_gen_execute.toString());
            }

            if (per.equalsIgnoreCase(FileControllerConstants.NFS_FILE_PERMISSION_FULLCONTROL)) {
                accessRights.add(IsilonNFSACL.AccessRights.dir_gen_all.toString());
            }
        }
        return accessRights;
    }

    @Override
    public BiosCommandResult deleteNfsACLs(StorageSystem storage, FileDeviceInputOutput args) {

        IsilonNFSACL isilonAcl = new IsilonNFSACL();
        ArrayList<Acl> aclCompleteList = new ArrayList<Acl>();

        List<NfsACE> aceToDelete = args.getNfsAclsToDelete();
        for (NfsACE nfsACE : aceToDelete) {
            Acl acl = getIsilonAclFromNfsACE(nfsACE);
            acl.setOp("delete");
            aclCompleteList.add(acl);
        }

        isilonAcl.setAction("update");
        isilonAcl.setAuthoritative("acl");
        isilonAcl.setAcl(aclCompleteList);
        String path = args.getFileSystemPath();
        if (args.getSubDirectory() != null && !args.getSubDirectory().isEmpty()) {
            path = path + "/" + args.getSubDirectory();

        }

        // Process new ACLs
        IsilonApi isi = getIsilonDevice(storage);
        _log.info("Calling Isilon API: to delete NFS Acl for  {}, acl  {}", args.getFileSystemPath(), isilonAcl);
        isi.modifyNFSACL(path, isilonAcl);
        _log.info("End deleteNfsACLs");
        BiosCommandResult result = BiosCommandResult.createSuccessfulResult();
        return result;
    }

    private String getZoneName(VirtualNAS vNAS) {
        String zoneName = null;
        if (vNAS != null) {
            zoneName = vNAS.getNasName();
        }
        return zoneName;

    }

    /**
     * Set the clients to isilon export based on type
     * 
     * @param type
     *            one of "rw", "root" or "ro"
     * @param hosts
     *            the clients to be set
     * @param isilonExport
     */
    private void setClientsIntoIsilonExport(String type, Set<String> hosts, IsilonExport isilonExport) {

        ArrayList<String> clients = new ArrayList<String>();
        if (hosts != null && !hosts.isEmpty()) {
            clients.addAll(hosts);
        }

        switch (type) {
            case "root":
                isilonExport.setRootClients(clients);
                break;
            case "rw":
                isilonExport.setReadWriteClients(clients);
                break;
            case "ro":
                isilonExport.setReadOnlyClients(clients);
                break;
        }

    }

    @Override
    public void doCreateMirrorLink(StorageSystem system, URI source, URI target, TaskCompleter completer) {
        // mirrorOperations.createMirrorFileShareLink(system, source, target, completer);
    }

    @Override
    public BiosCommandResult doStartMirrorLink(StorageSystem system, FileShare fs, TaskCompleter completer) {
        FileShare sourceFS = null;
        if (fs.getPersonality().equals(PersonalityTypes.TARGET.name())) {
            sourceFS = _dbClient.queryObject(FileShare.class, fs.getParentFileShare());
        } else if (fs.getPersonality().equals(PersonalityTypes.SOURCE.name())) {
            sourceFS = fs;
        }
        PolicyStorageResource policyStrRes = getEquivalentPolicyStorageResource(sourceFS, _dbClient);
        if (policyStrRes != null) {
            String policyName = policyStrRes.getPolicyNativeId();
            // In case of fail back we need to append _mirror name since we are starting the target FS mirror policy
            if (fs.getPersonality().equals(PersonalityTypes.TARGET.name())) {
                policyName = policyName.concat(MIRROR_POLICY);
            }

            return mirrorOperations.doStartReplicationPolicy(system, policyName, completer);
        }
        ServiceError serviceError = DeviceControllerErrors.isilon.unableToCreateFileShare();
        return BiosCommandResult.createErrorResult(serviceError);
    }

    @Override
    public BiosCommandResult doRefreshMirrorLink(StorageSystem system, FileShare source) {
        PolicyStorageResource policyStrRes = getEquivalentPolicyStorageResource(source, _dbClient);
        if (policyStrRes != null) {
            String policyName = policyStrRes.getPolicyNativeId();
            return mirrorOperations.doRefreshMirrorFileShareLink(system, source, policyName);
        }
        ServiceError serviceError = DeviceControllerErrors.isilon.unableToCreateFileShare();
        return BiosCommandResult.createErrorResult(serviceError);
    }

    @Override
    public BiosCommandResult doPauseLink(StorageSystem system, FileShare source) {

        PolicyStorageResource policyStrRes = getEquivalentPolicyStorageResource(source, _dbClient);
        if (policyStrRes != null) {
            String policyName = policyStrRes.getPolicyNativeId();
            IsilonApi isi = getIsilonDevice(system);
            IsilonSyncPolicy policy = isi.getReplicationPolicy(policyName);
            JobState policyState = policy.getLastJobState();

            if (policyState.equals(JobState.running) || policyState.equals(JobState.paused)) {
                mirrorOperations.doCancelReplicationPolicy(system, policyName);
            }
            return mirrorOperations.doStopReplicationPolicy(system, policyName);
        }
        ServiceError serviceError = DeviceControllerErrors.isilon.unableToCreateFileShare();
        return BiosCommandResult.createErrorResult(serviceError);
    }

    String gerneratePolicyName(StorageSystem system, FileShare fileShare) {
        return fileShare.getLabel();
    }

    @Override
    public BiosCommandResult doResumeLink(StorageSystem system, FileShare source, TaskCompleter completer) {

        PolicyStorageResource policyStrRes = getEquivalentPolicyStorageResource(source, _dbClient);
        if (policyStrRes != null) {
            String policyName = policyStrRes.getPolicyNativeId();
            return mirrorOperations.doResumeReplicationPolicy(system, policyName);
        }
        ServiceError serviceError = DeviceControllerErrors.isilon.unableToCreateFileShare();
        return BiosCommandResult.createErrorResult(serviceError);
    }

    @Override
    public BiosCommandResult doFailoverLink(StorageSystem systemTarget, FileShare fs, TaskCompleter completer) {
        FileShare sourceFS = null;
        if (fs.getPersonality().equals(PersonalityTypes.TARGET.name())) {
            sourceFS = _dbClient.queryObject(FileShare.class, fs.getParentFileShare());
        } else if (fs.getPersonality().equals(PersonalityTypes.SOURCE.name())) {
            sourceFS = fs;
        }
        PolicyStorageResource policyStrRes = getEquivalentPolicyStorageResource(sourceFS, _dbClient);
        if (policyStrRes != null) {
            String policyName = policyStrRes.getPolicyNativeId();
            // In case of failback we do failover on the source file system, so we need to append _mirror
            if (fs.getPersonality().equals(PersonalityTypes.SOURCE.name())) {
                policyName = policyName.concat(MIRROR_POLICY);
            }
            return mirrorOperations.doFailover(systemTarget, policyName, completer);
        }
        ServiceError serviceError = DeviceControllerErrors.isilon
                .unableToCreateFileShare();
        return BiosCommandResult.createErrorResult(serviceError);
    }

    @Override
    public BiosCommandResult doResyncLink(StorageSystem system, FileShare fs,
            TaskCompleter completer) {
        FileShare sourceFS = null;
        if (fs.getPersonality().equals(PersonalityTypes.TARGET.name())) {
            sourceFS = _dbClient.queryObject(FileShare.class, fs.getParentFileShare());
        } else if (fs.getPersonality().equals(PersonalityTypes.SOURCE.name())) {
            sourceFS = fs;
        }
        PolicyStorageResource policyStrRes = getEquivalentPolicyStorageResource(sourceFS, _dbClient);
        if (policyStrRes != null) {
            String policyName = policyStrRes.getPolicyNativeId();
            // In case of failback step 4 we do resysc on the target file system, so we need to append _mirror
            if (fs.getPersonality().equals(PersonalityTypes.TARGET.name())) {
                policyName = policyName.concat(MIRROR_POLICY);
            }
            return mirrorOperations.doResyncPrep(system, policyName, completer);
        }
        ServiceError serviceError = DeviceControllerErrors.isilon.unableToCreateFileShare();
        return BiosCommandResult.createErrorResult(serviceError);
    }

    /**
     * rollback the target filesystems
     */
    @Override
    public void doRollbackMirrorLink(StorageSystem system, List<URI> sources, List<URI> targets, TaskCompleter completer, String opId) {
        BiosCommandResult biosCommandResult = null;
        // delete the target objects
        if (targets != null && !targets.isEmpty()) {
            for (URI target : targets) {
                FileShare fileShare = _dbClient.queryObject(FileShare.class, target);
                StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, fileShare.getStorageDevice());
                URI uriParent = fileShare.getParentFileShare().getURI();
                if (sources.contains(uriParent) == true) {
                    biosCommandResult = rollbackCreatedFilesystem(storageSystem, target, opId, true);
                    if (biosCommandResult.getCommandSuccess()) {
                        fileShare.getOpStatus().updateTaskStatus(opId, biosCommandResult.toOperation());
                        fileShare.setInactive(true);
                        _dbClient.updateObject(fileShare);
                    }
                }
            }
        }
        completer.ready(_dbClient);
    }

    /**
     * rollback the filesystem
     * 
     * @param system
     * @param uri
     * @param opId
     * @param isForceDelete
     * @return
     */
    private BiosCommandResult rollbackCreatedFilesystem(StorageSystem system, URI uri, String opId, boolean isForceDelete) {
        FileDeviceInputOutput fileInputOutput = this.prepareFileDeviceInputOutput(isForceDelete, uri, opId);
        return this.doDeleteFS(system, fileInputOutput);
    }

    @Deprecated
    @Override
    public BiosCommandResult assignFilePolicy(StorageSystem storage, FileDeviceInputOutput args) {
        // for isilon we need to create a new policy for each individual file system

        SchedulePolicy fp = args.getFilePolicy();
        String snapshotScheduleName = fp.getPolicyName() + "_" + args.getFsName();
        String pattern = snapshotScheduleName + "_%Y-%m-%d_%H-%M";
        String Schedulevalue = getIsilonScheduleString(fp);
        Integer expireValue = getSnapshotExpireValue(fp);
        _log.info("File Policy  name : {}", snapshotScheduleName);
        IsilonApi isi = getIsilonDevice(storage);
        try {
            isi.createSnapshotSchedule(snapshotScheduleName, args.getFileSystemPath(), Schedulevalue, pattern, expireValue);

        } catch (IsilonException e) {
            _log.error("assign file policy failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
        return BiosCommandResult.createSuccessfulResult();
    }

    @Deprecated
    @Override
    public BiosCommandResult unassignFilePolicy(StorageSystem storageObj, FileDeviceInputOutput args) {

        SchedulePolicy fp = args.getFilePolicy();
        String snapshotScheduleName = fp.getPolicyName() + "_" + args.getFsName();
        IsilonApi isi = getIsilonDevice(storageObj);
        try {
            isi.deleteSnapshotSchedule(snapshotScheduleName);
        } catch (IsilonException e) {
            _log.error("unassign file policy failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
        return BiosCommandResult.createSuccessfulResult();

    }

    @Deprecated
    private String getIsilonScheduleString(SchedulePolicy schedule) {
        StringBuilder builder = new StringBuilder();

        ScheduleFrequency scheduleFreq = ScheduleFrequency.valueOf(schedule.getScheduleFrequency().toUpperCase());
        switch (scheduleFreq) {

            case DAYS:
                builder.append("every ");
                builder.append(schedule.getScheduleRepeat());
                builder.append(" days at ");
                builder.append(schedule.getScheduleTime());
                break;
            case WEEKS:
                builder.append("every ");
                builder.append(schedule.getScheduleRepeat());
                builder.append(" weeks on ");
                builder.append(schedule.getScheduleDayOfWeek());
                builder.append(" at ");
                builder.append(schedule.getScheduleTime());
                break;
            case MONTHS:
                builder.append("the ");
                builder.append(schedule.getScheduleDayOfMonth());
                builder.append(" every ");
                builder.append(schedule.getScheduleRepeat());
                builder.append(" month at ");
                builder.append(schedule.getScheduleTime());
                break;
            default:
                _log.error("Not a valid schedule frequency: " + schedule.getScheduleFrequency().toLowerCase());
                return null;

        }
        return builder.toString();

    }

    @Deprecated
    private Integer getSnapshotExpireValue(SchedulePolicy schedulePolicy) {
        Long seconds = 0L;
        String snapshotExpire = schedulePolicy.getSnapshotExpireType();
        if (snapshotExpire != null && !snapshotExpire.isEmpty()) {
            Long expireValue = schedulePolicy.getSnapshotExpireTime();
            SnapshotExpireType expireType = SnapshotExpireType.valueOf(snapshotExpire.toUpperCase());
            switch (expireType) {
                case HOURS:
                    seconds = TimeUnit.HOURS.toSeconds(expireValue);
                    break;
                case DAYS:
                    seconds = TimeUnit.DAYS.toSeconds(expireValue);
                    break;
                case WEEKS:
                    seconds = TimeUnit.DAYS.toSeconds(expireValue * 7);
                    break;
                case MONTHS:
                    seconds = TimeUnit.DAYS.toSeconds(expireValue * 30);
                    break;
                case NEVER:
                    return null;
                default:
                    _log.error("Not a valid expire type: " + expireType);
                    return null;
            }
        }
        return seconds.intValue();
    }

    private Integer getSnapshotExpireValue(FileSnapshotPolicyExpireParam snapExpireParam) {
        Long seconds = 0L;
        String snapshotExpire = snapExpireParam.getExpireType();
        if (snapshotExpire != null && !snapshotExpire.isEmpty()) {
            int expireValue = snapExpireParam.getExpireValue();
            SnapshotExpireType expireType = SnapshotExpireType.valueOf(snapshotExpire.toUpperCase());
            switch (expireType) {
                case HOURS:
                    seconds = TimeUnit.HOURS.toSeconds(expireValue);
                    break;
                case DAYS:
                    seconds = TimeUnit.DAYS.toSeconds(expireValue);
                    break;
                case WEEKS:
                    seconds = TimeUnit.DAYS.toSeconds(expireValue * 7);
                    break;
                case MONTHS:
                    seconds = TimeUnit.DAYS.toSeconds(expireValue * 30);
                    break;
                case NEVER:
                    return null;
                default:
                    _log.error("Not a valid expire type: " + expireType);
                    return null;
            }
        }
        return seconds.intValue();
    }

    @Override
    public BiosCommandResult listSanpshotByPolicy(StorageSystem storageObj, FileDeviceInputOutput args) {
        SchedulePolicy sp = args.getFilePolicy();
        FileShare fs = args.getFs();
        String snapshotScheduleName = sp.getPolicyName() + "_" + args.getFsName();
        IsilonApi isi = getIsilonDevice(storageObj);
        String resumeToken = null;

        try {
            do {
                IsilonList<IsilonSnapshot> snapshots = isi.listSnapshotsCreatedByPolicy(resumeToken, snapshotScheduleName);
                if (snapshots != null) {

                    for (IsilonSnapshot islon_snap : snapshots.getList()) {
                        _log.info("file policy snapshot is  : " + islon_snap.getName());
                        Snapshot snap = new Snapshot();
                        snap.setLabel(islon_snap.getName());
                        snap.setMountPath(islon_snap.getPath());
                        snap.setName(islon_snap.getName());
                        snap.setId(URIUtil.createId(Snapshot.class));
                        snap.setOpStatus(new OpStatusMap());
                        snap.setProject(new NamedURI(fs.getProject().getURI(), islon_snap.getName()));
                        snap.setMountPath(getSnapshotPath(islon_snap.getPath(), islon_snap.getName()));
                        snap.setParent(new NamedURI(fs.getId(), islon_snap.getName()));
                        StringMap map = new StringMap();
                        Long createdTime = Long.parseLong(islon_snap.getCreated()) * SEC_IN_MILLI;
                        String expiresTime = "Never";
                        if (islon_snap.getExpires() != null && !islon_snap.getExpires().isEmpty()) {
                            Long expTime = Long.parseLong(islon_snap.getExpires()) * SEC_IN_MILLI;
                            expiresTime = expTime.toString();
                        }
                        map.put("created", createdTime.toString());
                        map.put("expires", expiresTime);
                        map.put("schedule", sp.getPolicyName());
                        snap.setExtensions(map);
                        _dbClient.updateObject(snap);

                    }
                    resumeToken = snapshots.getToken();
                }
            } while (resumeToken != null && !resumeToken.equalsIgnoreCase("null"));

        } catch (IsilonException e) {
            _log.error("listing snapshot by file policy failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
        Task task = TaskUtils.findTaskForRequestId(_dbClient, fs.getId(), args.getOpId());
        // set task to completed and progress to 100 and store in DB, so waiting thread in apisvc can read it.
        task.ready();
        task.setProgress(100);
        _dbClient.updateObject(task);
        return BiosCommandResult.createSuccessfulResult();
    }

    @Override
    public BiosCommandResult updateStorageSystemFileProtectionPolicy(StorageSystem storage, FileDeviceInputOutput args) {
        FilePolicy existingPolicy = args.getFileProtectionPolicy();
        PolicyStorageResource policyRes = args.getPolicyStorageResource();
        FilePolicyUpdateParam policyUpdateParam = args.getFileProtectionPolicyUpdateParam();
        IsilonApi isi = getIsilonDevice(storage);

        BiosCommandResult result = null;

        try {
            if (existingPolicy.getFilePolicyType().equals(FilePolicy.FilePolicyType.file_replication.name())) {
                return updateStorageSystemFileReplicationPolicy(isi, policyRes, existingPolicy, policyUpdateParam);
            } else if (existingPolicy.getFilePolicyType().equals(FilePolicy.FilePolicyType.file_snapshot.name())) {
                return updateStorageSystemFileSnapshotPolicy(isi, policyRes, existingPolicy, policyUpdateParam);
            } else {
                String errorMsg = "Invalid policy type {} " + existingPolicy.getFilePolicyType();
                _log.error(errorMsg);
                final ServiceCoded serviceCoded = DeviceControllerException.errors.jobFailedOpMsg(
                        OperationTypeEnum.UPDATE_STORAGE_SYSTEM_POLICY_BY_POLICY_RESOURCE.toString(), errorMsg);
                result = BiosCommandResult.createErrorResult(serviceCoded);
                existingPolicy.getOpStatus().updateTaskStatus(args.getOpId(), result.toOperation());
                return result;
            }
        } catch (IsilonException e) {
            _log.error("Update storage system policy for file policy failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    private BiosCommandResult updateStorageSystemFileReplicationPolicy(IsilonApi isi, PolicyStorageResource policyRes,
            FilePolicy viprPolicy, FilePolicyUpdateParam policyUpdateParam) {

        try {
            ArrayList<IsilonSyncPolicy> isiSyncIQPolicies = isi.getReplicationPolicies().getList();
            IsilonSyncPolicy syncpolicyAtPath = null;
            _log.info("Checking the right syncIQ policy ...");
            for (IsilonSyncPolicy syncIqPolicy : isiSyncIQPolicies) {
                // check for policy path
                if (syncIqPolicy.getSourceRootPath() != null
                        && syncIqPolicy.getSourceRootPath().equalsIgnoreCase(policyRes.getResourcePath())
                        && syncIqPolicy.getName() != null && syncIqPolicy.getName().equalsIgnoreCase(policyRes.getPolicyNativeId())) {
                    syncpolicyAtPath = syncIqPolicy;
                    break;
                }
            }
            if (syncpolicyAtPath != null) {
                _log.info("Found SyncIQ policy{} for path {} ", syncpolicyAtPath.getName(), syncpolicyAtPath.getSourceRootPath());
                boolean bModifyPolicy = false;
                // Temp policy to store modified values.
                IsilonSyncPolicy modifiedPolicy = new IsilonSyncPolicy();
                modifiedPolicy.setName(syncpolicyAtPath.getName());
                if (policyUpdateParam.getNumWorkerThreads() > 0
                        && syncpolicyAtPath.getWorkersPerNode() != policyUpdateParam.getNumWorkerThreads()) {
                    _log.debug("Changing NumWorkerThreads to {} ", policyUpdateParam.getNumWorkerThreads());
                    modifiedPolicy.setWorkersPerNode(policyUpdateParam.getNumWorkerThreads());
                    bModifyPolicy = true;
                }

                if (policyUpdateParam.getPolicyDescription() != null && !policyUpdateParam.getPolicyDescription().isEmpty()
                        && !policyUpdateParam.getPolicyDescription().equalsIgnoreCase(syncpolicyAtPath.getDescription())) {
                    modifiedPolicy.setDescription(policyUpdateParam.getPolicyDescription());
                    bModifyPolicy = true;
                }

                // Priority needs to be changed
                if (policyUpdateParam.getPriority() != null && !policyUpdateParam.getPriority().isEmpty()) {

                }

                if (policyUpdateParam.getReplicationPolicyParams() != null) {
                    FileReplicationPolicyParam replParam = policyUpdateParam.getReplicationPolicyParams();

                    if (replParam.getReplicationCopyMode() != null && !replParam.getReplicationCopyMode().isEmpty()
                            && !FileReplicationCopyMode.ASYNC.name().equalsIgnoreCase(replParam.getReplicationCopyMode())) {
                        _log.warn("Replication copy mode {} is not supported by Isilon {} ", replParam.getReplicationCopyMode());
                    }

                    if (replParam.getPolicySchedule() != null) {
                        String strSchedule = getIsilonPolicySchedule(replParam.getPolicySchedule());
                        if (strSchedule != null && !strSchedule.isEmpty()
                                && !strSchedule.equalsIgnoreCase(syncpolicyAtPath.getSchedule())) {
                            modifiedPolicy.setSchedule(strSchedule);
                            bModifyPolicy = true;
                        }

                    }
                }

                if (bModifyPolicy) {
                    JobState policyState = syncpolicyAtPath.getLastJobState();
                    if (!policyState.equals(JobState.running) && !policyState.equals(JobState.paused)) {
                        isi.modifyReplicationPolicy(syncpolicyAtPath.getName(), modifiedPolicy);
                        _log.info("Modify Replication Policy- {} finished successfully", syncpolicyAtPath.getName());
                        return BiosCommandResult.createSuccessfulResult();
                    } else {
                        _log.error("Replication Policy - {} can't be MODIFIED because policy has an active job",
                                syncpolicyAtPath.getName());
                        ServiceError error = DeviceControllerErrors.isilon
                                .jobFailed("doModifyReplicationPolicy as : The policy has an active job and cannot be modified.");
                        return BiosCommandResult.createErrorResult(error);
                    }
                } else {
                    _log.info("No parameters changed to modify Replication Policy- {} finished successfully", syncpolicyAtPath.getName());
                    return BiosCommandResult.createSuccessfulResult();
                }
            } else {
                _log.error("No SyncIQ policy found at path {} , Hence can't be MODIFIED",
                        policyRes.getResourcePath());
                ServiceError error = DeviceControllerErrors.isilon
                        .jobFailed("doModifyReplicationPolicy as : No SyncIQ policy found at given path.");
                return BiosCommandResult.createErrorResult(error);
            }
        } catch (IsilonException e) {
            return BiosCommandResult.createErrorResult(e);
        }
    }

    private BiosCommandResult updateStorageSystemFileSnapshotPolicy(IsilonApi isi, PolicyStorageResource policyRes,
            FilePolicy viprPolicy, FilePolicyUpdateParam policyUpdateParam) {
        try {
            ArrayList<IsilonSnapshotSchedule> isiSnapshotPolicies = isi.getSnapshotSchedules().getList();
            IsilonSnapshotSchedule snapPolicyAtPath = null;
            _log.info("Checking the right snapshotIQ policy ...");
            for (IsilonSnapshotSchedule snapPolicy : isiSnapshotPolicies) {
                // check for policy path
                if (snapPolicy.getPath() != null && snapPolicy.getPath().equalsIgnoreCase(policyRes.getResourcePath())
                        && snapPolicy.getName() != null && snapPolicy.getName().equalsIgnoreCase(policyRes.getPolicyNativeId())) {
                    snapPolicyAtPath = snapPolicy;
                    break;
                }
            }
            if (snapPolicyAtPath != null) {
                _log.info("Found SnapshotIQ policy{} for path {} ", snapPolicyAtPath.getName(), snapPolicyAtPath.getPath());
                boolean bModifyPolicy = false;
                // Temp policy to store modified values.
                IsilonSnapshotSchedule modifiedPolicy = new IsilonSnapshotSchedule();
                modifiedPolicy.setName(snapPolicyAtPath.getName());

                if (policyUpdateParam.getSnapshotPolicyPrams() != null) {
                    FileSnapshotPolicyParam snapParam = policyUpdateParam.getSnapshotPolicyPrams();

                    if (snapParam.getSnapshotExpireParams() != null) {
                        Integer expireTime = getSnapshotExpireValue(snapParam.getSnapshotExpireParams());
                        if (expireTime != null && snapPolicyAtPath.getDuration() != null) {
                            if (snapPolicyAtPath.getDuration().intValue() != expireTime.intValue()) {
                                modifiedPolicy.setDuration(expireTime);
                                bModifyPolicy = true;
                            }
                        } else if (expireTime != null && snapPolicyAtPath.getDuration() == null) {
                            modifiedPolicy.setDuration(expireTime);
                            bModifyPolicy = true;
                        } else if (snapPolicyAtPath.getDuration() != null) {
                            modifiedPolicy.setDuration(0);
                            bModifyPolicy = true;
                        }
                    }

                    if (snapParam.getPolicySchedule() != null) {
                        String strSchedule = getIsilonPolicySchedule(snapParam.getPolicySchedule());
                        if (strSchedule != null && !strSchedule.isEmpty()
                                && !strSchedule.equalsIgnoreCase(snapPolicyAtPath.getSchedule())) {
                            modifiedPolicy.setSchedule(strSchedule);
                            bModifyPolicy = true;
                        }
                    }

                }

                if (bModifyPolicy) {
                    isi.modifySnapshotSchedule(snapPolicyAtPath.getName(), modifiedPolicy);
                    _log.info("Modify Snapshot Policy- {} finished successfully", snapPolicyAtPath.getName());
                    return BiosCommandResult.createSuccessfulResult();
                } else {
                    _log.info("No parameters changed to modify Snapshot Policy- {} finished successfully", snapPolicyAtPath.getName());
                    return BiosCommandResult.createSuccessfulResult();
                }
            } else {
                _log.error("No SnapshotIQ policy found at path {} , Hence can't be MODIFIED",
                        policyRes.getResourcePath());
                ServiceError error = DeviceControllerErrors.isilon
                        .jobFailed("Modify Snapshot policy Failed as : No SnapshotIQ policy found at given path.");
                return BiosCommandResult.createErrorResult(error);
            }
        } catch (IsilonException e) {
            return BiosCommandResult.createErrorResult(e);
        }
    }

    /**
     * Gets the file system custom path value from controller configuration
     * 
     * @param storage
     *            Isilon storage system
     * @param args
     *            FileDeviceInputOutput object
     * @return evaluated custom path
     */
    private String getCustomPath(StorageSystem storage, FileDeviceInputOutput args) {

        String path = "";

        IsilonApi isi = getIsilonDevice(storage);
        String clusterName = isi.getClusterConfig().getName();
        FileShare fs = args.getFs();
        // Source and taget path sould be same
        // source cluster name should be included in target path instead of target cluster name.
        if (fs != null && fs.getPersonality() != null && fs.getPersonality().equalsIgnoreCase(PersonalityTypes.TARGET.name())) {
            FileShare sourceFS = _dbClient.queryObject(FileShare.class, fs.getParentFileShare());
            if (sourceFS != null && sourceFS.getStorageDevice() != null) {
                StorageSystem sourceSystem = _dbClient.queryObject(StorageSystem.class, sourceFS.getStorageDevice());
                if (sourceSystem != null) {
                    IsilonApi sourceCluster = getIsilonDevice(sourceSystem);
                    clusterName = sourceCluster.getClusterConfig().getName();
                    // Add source access zone name to cluster name
                    // if the replication happens from user defined access zone to system access zone!!
                    if (sourceFS.getVirtualNAS() != null) {
                        VirtualNAS sourcevNAS = _dbClient.queryObject(VirtualNAS.class, sourceFS.getVirtualNAS());
                        String vNASName = sourcevNAS.getNasName();
                        vNASName = getNameWithNoSpecialCharacters(vNASName, args);
                        clusterName = clusterName + vNASName;
                    }
                    _log.debug("Generating path for target and the source cluster is is  {}", clusterName);
                }
            }
        } else if (args.isTarget()) {
            if (args.getSourceSystem() != null) {
                IsilonApi sourceCluster = getIsilonDevice(args.getSourceSystem());
                clusterName = sourceCluster.getClusterConfig().getName();
            }
            // Add source access zone name to cluster name
            // if the replication happens from user defined access zone to system access zone!!
            if (args.getSourceVNAS() != null && args.getvNAS() == null) {
                VirtualNAS sourcevNAS = args.getSourceVNAS();
                String vNASName = sourcevNAS.getNasName();
                vNASName = getNameWithNoSpecialCharacters(vNASName, args);
                clusterName = clusterName + vNASName;
            }
            _log.debug("Generating path for target and the source cluster is is  {}", clusterName);
        }

        DataSource dataSource = dataSourceFactory.createIsilonFileSystemPathDataSource(args.getProject(), args.getVPool(),
                args.getTenantOrg(), storage);
        dataSource.addProperty(CustomConfigConstants.ISILON_CLUSTER_NAME, clusterName);
        String configPath = customConfigHandler.getComputedCustomConfigValue(CustomConfigConstants.ISILON_PATH_CUSTOMIZATION, "isilon",
                dataSource);
        _log.debug("The isilon user defined custom path is  {}", configPath);
        if (configPath != null && !configPath.isEmpty()) {
            path = args.getPathWithoutSpecialCharacters(configPath);
        }
        return path;
    }

    /**
     * Get the File System default system access zone from
     * controller configuration.
     * 
     * @return access zone folder name
     */

    private String getSystemAccessZoneNamespace() {

        String namespace = "";
        DataSource dataSource = new DataSource();
        dataSource.addProperty(CustomConfigConstants.ISILON_NO_DIR, "");
        dataSource.addProperty(CustomConfigConstants.ISILON_DIR_NAME, "");
        namespace = customConfigHandler.getComputedCustomConfigValue(CustomConfigConstants.ISILON_SYSTEM_ACCESS_ZONE_NAMESPACE, "isilon",
                dataSource);
        // framework does't allow empty variable to be set. To work around if = is added to variable via conf and then
        // remove it here
        namespace = namespace.replaceAll("=", "");
        return namespace;
    }

    @Override
    public BiosCommandResult doApplyFilePolicy(StorageSystem storageObj, FileDeviceInputOutput args) {

        FileShare fs = args.getFs();
        FileShare targetFS = null;
        try {
            IsilonApi isi = getIsilonDevice(storageObj);
            FilePolicy filePolicy = args.getFileProtectionPolicy();

            if (filePolicy.getFilePolicyType().equals(FilePolicy.FilePolicyType.file_replication.name())) {
                String sourcePath = generatePathForPolicy(filePolicy, fs, args);
                checkAppliedResourceNamePartOfFilePolicyPath(sourcePath, filePolicy, args);
                String scheduleValue = getIsilonPolicySchedule(filePolicy);
                String targetPath = null;
                String targetHost = null;
                StorageSystem targetSystem = null;
                NASServer targetNasServer = null;
                if (fs.getPersonality() != null && PersonalityTypes.SOURCE.name().equalsIgnoreCase(fs.getPersonality())) {
                    String targetFs = fs.getMirrorfsTargets().iterator().next();
                    targetFS = _dbClient.queryObject(FileShare.class, URI.create(targetFs));
                    targetPath = generatePathForPolicy(filePolicy, targetFS, args);
                    if (filePolicy.getFileReplicationType().equalsIgnoreCase(FileReplicationType.LOCAL.name())) {
                        targetPath = targetPath + "_localTarget";
                    }
                    // Get the target smart connect zone!!
                    targetHost = FileOrchestrationUtils.getTargetHostPortForReplication(_dbClient, targetFS);
                    targetSystem = _dbClient.queryObject(StorageSystem.class, targetFS.getStorageDevice());
                    if (targetFS.getVirtualNAS() != null) {
                        targetNasServer = _dbClient.queryObject(VirtualNAS.class, targetFS.getVirtualNAS());
                    }
                }
                IsilonSyncPolicy isiSynIQPolicy = getEquivalentIsilonSyncIQPolicy(isi, sourcePath);

                if (isiSynIQPolicy != null) {

                    boolean validPolicy = validateIsilonReplicationPolicy(isiSynIQPolicy, filePolicy, targetPath,
                            targetSystem, storageObj);
                    if (validPolicy) {
                        _log.info("File Policy {} is already applied and running.", filePolicy.toString());
                        // Verify the policy was mapped to FileStorageResource
                        if (null == FileOrchestrationUtils.findPolicyStorageResourceByNativeId(_dbClient, storageObj,
                                filePolicy, args, sourcePath)) {
                            _log.info("Isilon policy found for {}, creating policy storage resouce to further management",
                                    filePolicy.getFilePolicyName());
                            FileOrchestrationUtils.updatePolicyStorageResource(_dbClient, storageObj, filePolicy,
                                    args, sourcePath, isiSynIQPolicy.getName(), targetSystem, targetNasServer, targetPath);
                        }
                        return BiosCommandResult.createSuccessfulResult();
                    } else {
                        throw DeviceControllerException.exceptions.assignFilePolicyFailed(filePolicy.getFilePolicyName(),
                                filePolicy.getApplyAt(), "File policy and Isilon syncIQ policy differs for path: "
                                        + sourcePath);
                    }
                } else {
                    IsilonApi isiApiOfTarget = getIsilonDevice(targetSystem);
                    String targetClusterName = isiApiOfTarget.getClusterConfig().getName();
                    String sourceClustername = isi.getClusterConfig().getName();
                    String policyName = FileOrchestrationUtils.generateNameForSyncIQPolicy(sourceClustername, targetClusterName,
                            filePolicy, fs, args);
                    IsilonSyncPolicy policy = new IsilonSyncPolicy(policyName, sourcePath, targetPath, targetHost,
                            IsilonSyncPolicy.Action.sync);
                    if (scheduleValue != null && !scheduleValue.isEmpty()) {
                        policy.setSchedule(scheduleValue);
                    }
                    if (filePolicy.getFilePolicyDescription() != null) {
                        policy.setDescription(filePolicy.getFilePolicyDescription());
                    }
                    if (filePolicy.getNumWorkerThreads() != null && filePolicy.getNumWorkerThreads() > 0) {
                        policy.setWorkersPerNode(filePolicy.getNumWorkerThreads().intValue());
                    }
                    policy.setEnabled(true);
                    String policyId = isi.createReplicationPolicy(policy);
                    if (policyId != null) {
                        _log.info("Isilon File Policy {} created successfully.", policyId);
                        FileOrchestrationUtils.updatePolicyStorageResource(_dbClient, storageObj, filePolicy, args, sourcePath, policyName,
                                targetSystem, targetNasServer, targetPath);
                        return BiosCommandResult.createSuccessfulResult();
                    }
                }

            } else if (filePolicy.getFilePolicyType().equals(FilePolicyType.file_snapshot.name())) {

                String path = generatePathForPolicy(filePolicy, fs, args);
                checkAppliedResourceNamePartOfFilePolicyPath(path, filePolicy, args);
                String clusterName = isi.getClusterConfig().getName();
                String snapshotScheduleName = FileOrchestrationUtils.generateNameForSnapshotIQPolicy(clusterName, filePolicy, fs, args);
                IsilonSnapshotSchedule isiSnapshotSch = getEquivalentIsilonSnapshotSchedule(isi, path);
                if (isiSnapshotSch != null) {
                    String filePolicySnapshotSchedule = getIsilonPolicySchedule(filePolicy);
                    _log.info("Comparing snapshot schedule between CoprHD policy: {} and Isilon policy: {}.", filePolicySnapshotSchedule,
                            isiSnapshotSch.getSchedule());
                    if (isiSnapshotSch.getSchedule() != null && isiSnapshotSch.getSchedule().equalsIgnoreCase(filePolicySnapshotSchedule)) {
                        // Verify the policy was mapped to FileStorageResource
                        if (null == FileOrchestrationUtils.findPolicyStorageResourceByNativeId(_dbClient, storageObj,
                                filePolicy, args, path)) {
                            _log.info("Isilon snapshot policy found for {}, creating policy storage resouce to further management",
                                    filePolicy.getFilePolicyName());
                            FileOrchestrationUtils.updatePolicyStorageResource(_dbClient, storageObj, filePolicy,
                                    args, path, isiSnapshotSch.getName(), null, null, null);
                            _log.info("File Policy {} is already applied and running.", filePolicy.getFilePolicyName());
                        }
                        return BiosCommandResult.createSuccessfulResult();
                    } else {
                        _log.info("Snapshot schedule differs between Isilon policy and CoprHD file policy. So, create policy in Isilon...");
                        // Create snapshot policy.
                        createIsilonSnapshotPolicySchedule(storageObj, filePolicy, path,
                                snapshotScheduleName, args, path);
                        return BiosCommandResult.createSuccessfulResult();
                    }
                } else {
                    // Create snapshot policy.
                    createIsilonSnapshotPolicySchedule(storageObj, filePolicy, path,
                            snapshotScheduleName, args, path);
                    return BiosCommandResult.createSuccessfulResult();
                }
            }
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("apply file policy failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    @Override
    public BiosCommandResult doUnassignFilePolicy(StorageSystem storage, FileDeviceInputOutput args)
            throws ControllerException {

        try {
            IsilonApi isi = getIsilonDevice(storage);
            FilePolicy filePolicy = args.getFileProtectionPolicy();
            PolicyStorageResource policyResource = args.getPolicyStorageResource();

            if (filePolicy.getFilePolicyType().equals(FilePolicyType.file_replication.name())) {

                IsilonSyncPolicy isiSyncPolicy = getEquivalentIsilonSyncIQPolicy(isi, policyResource.getResourcePath());
                if (isiSyncPolicy != null) {
                    _log.info("deleting Isilon replication policy: {}", isiSyncPolicy.toString());
                    IsilonSyncPolicy policy = isi.getReplicationPolicy(isiSyncPolicy.getName());
                    String policyName = isiSyncPolicy.getName();
                    JobState policyState = policy.getLastJobState();

                    if (policyState.equals(JobState.running) || policyState.equals(JobState.paused)) {
                        _log.info("Canceling Replication Policy  -{} because policy is in - {} state ", policyName,
                                policyState);
                        IsilonSyncPolicy modifiedPolicy = new IsilonSyncPolicy();
                        modifiedPolicy.setName(policyName);
                        modifiedPolicy.setLastJobState(JobState.canceled);
                        isi.modifyReplicationPolicy(policyName, modifiedPolicy);
                    }
                    isi.deleteReplicationPolicy(policyName);
                    isi.deleteReplicationPolicy(policyResource.getPolicyNativeId());

                } else {
                    _log.info("replication policy: {} doesn't exists on storage system", filePolicy.toString());
                }
                return BiosCommandResult.createSuccessfulResult();

            } else if (filePolicy.getFilePolicyType().equals(FilePolicyType.file_snapshot.name())) {

                IsilonSnapshotSchedule isiSchedulePolicy = getEquivalentIsilonSnapshotSchedule(isi,
                        policyResource.getResourcePath());

                if (isiSchedulePolicy != null) {
                    _log.info("deleting Isilon Snapshot schedule: {}", isiSchedulePolicy.toString());
                    isi.deleteSnapshotSchedule(policyResource.getPolicyNativeId());
                } else {
                    _log.info("snapshot schedule: {} doesn't exists on storage system", filePolicy.toString());
                }
                return BiosCommandResult.createSuccessfulResult();
            }
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("unassign file policy failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }
    }

    private String getNameWithNoSpecialCharacters(String str, FileDeviceInputOutput args) {
        // Custom configuration using the below two level regular expressions to generate name with no special symbols.
        // Using the same here.
        String regex = STR_WITH_NO_SPECIAL_SYMBOLS;
        String path = str.replaceAll(regex, "");
        if (path != null && !path.isEmpty()) {
            path = args.getPathWithoutSpecialCharacters(path);
        }
        return path;
    }

    private String generatePathForPolicy(FilePolicy filePolicy, FileShare fileShare, FileDeviceInputOutput args) {
        String policyPath = "";
        FilePolicyApplyLevel applyLevel = FilePolicyApplyLevel.valueOf(filePolicy.getApplyAt());
        switch (applyLevel) {
            case vpool:
                String vpool = getNameWithNoSpecialCharacters(args.getVPool().getLabel(), args);
                policyPath = fileShare.getNativeId().split(vpool)[0] + vpool;
                break;
            case project:
                String project = getNameWithNoSpecialCharacters(args.getProject().getLabel(), args);
                policyPath = fileShare.getNativeId().split(project)[0] + project;
                break;
            case file_system:
                policyPath = fileShare.getNativeId();
                break;
            default:
                _log.error("Not a valid policy apply level: " + applyLevel);
        }
        return policyPath;
    }

    private static IsilonSnapshotSchedule getEquivalentIsilonSnapshotSchedule(IsilonApi isi, String path) {
        IsilonSnapshotSchedule isiSchedule = null;
        ArrayList<IsilonSnapshotSchedule> isiSnapshotPolicies = isi.getSnapshotSchedules().getList();
        if (isiSnapshotPolicies != null && !isiSnapshotPolicies.isEmpty()) {
            for (IsilonSnapshotSchedule isiSnapshotPolicy : isiSnapshotPolicies) {
                if (isiSnapshotPolicy.getPath().equals(path)) {
                    isiSchedule = isiSnapshotPolicy;
                    break;
                }
            }
        }
        return isiSchedule;
    }

    private static IsilonSyncPolicy getEquivalentIsilonSyncIQPolicy(IsilonApi isi, String path) {
        IsilonSyncPolicy isiSyncPolicy = null;
        ArrayList<IsilonSyncPolicy> isiSyncIQPolicies = isi.getReplicationPolicies().getList();
        if (isiSyncIQPolicies != null && !isiSyncIQPolicies.isEmpty()) {
            for (IsilonSyncPolicy isiSyncIQPolicy : isiSyncIQPolicies) {
                if (isiSyncIQPolicy.getSourceRootPath().equals(path)) {
                    isiSyncPolicy = isiSyncIQPolicy;
                }
            }
        }
        return isiSyncPolicy;
    }

    private static String getIsilonPolicySchedule(FilePolicy policy) {

        FilePolicyScheduleParams scheduleParam = new FilePolicyScheduleParams();
        scheduleParam.setScheduleDayOfMonth(policy.getScheduleDayOfMonth());
        scheduleParam.setScheduleDayOfWeek(policy.getScheduleDayOfWeek());
        scheduleParam.setScheduleFrequency(policy.getScheduleFrequency());
        scheduleParam.setScheduleRepeat(policy.getScheduleRepeat());
        scheduleParam.setScheduleTime(policy.getScheduleTime());
        return getIsilonPolicySchedule(scheduleParam);
    }

    private static String getIsilonPolicySchedule(FilePolicyScheduleParams schedule) {
        StringBuilder builder = new StringBuilder();

        ScheduleFrequency scheduleFreq = ScheduleFrequency.valueOf(schedule.getScheduleFrequency().toUpperCase());
        switch (scheduleFreq) {

            case MINUTES:
                builder.append("every 1 days every");
                builder.append(schedule.getScheduleRepeat());
                builder.append(" minutes between ");
                builder.append(schedule.getScheduleTime());
                builder.append(" and ");
                // If we add 23 hours 59 min to start time to get end time
                // result time come smaller in most of the case
                // Like for start time 3:00 AM it comes at 2:59 AM. and Isilon API does not accept it.
                // Fixing End time at 11:59 PM for now.(need to get it from user in future)
                builder.append("11:59 PM");
                break;

            case HOURS:
                builder.append("every 1 days every");
                builder.append(schedule.getScheduleRepeat());
                builder.append(" hours between ");
                builder.append(schedule.getScheduleTime());
                builder.append(" and ");
                builder.append("11:59 PM");
                break;

            case DAYS:
                builder.append("every ");
                builder.append(schedule.getScheduleRepeat());
                builder.append(" days at ");
                builder.append(schedule.getScheduleTime());
                break;
            case WEEKS:
                builder.append("every ");
                builder.append(schedule.getScheduleRepeat());
                builder.append(" weeks on ");
                builder.append(schedule.getScheduleDayOfWeek());
                builder.append(" at ");
                builder.append(schedule.getScheduleTime());
                break;
            case MONTHS:
                builder.append("the ");
                builder.append(schedule.getScheduleDayOfMonth());
                builder.append(" every ");
                builder.append(schedule.getScheduleRepeat());
                builder.append(" month at ");
                builder.append(schedule.getScheduleTime());
                break;
            default:
                _log.error("Not a valid schedule frequency: " + schedule.getScheduleFrequency().toLowerCase());
                return null;

        }
        return builder.toString();

    }


    private Integer getIsilonSnapshotExpireValue(FilePolicy policy) {
        Long seconds = 0L;
        String snapshotExpire = policy.getSnapshotExpireType();
        if (snapshotExpire != null && !snapshotExpire.isEmpty()) {
            Long expireValue = policy.getSnapshotExpireTime();
            SnapshotExpireType expireType = SnapshotExpireType.valueOf(snapshotExpire.toUpperCase());
            switch (expireType) {
                case HOURS:
                    seconds = TimeUnit.HOURS.toSeconds(expireValue);
                    break;
                case DAYS:
                    seconds = TimeUnit.DAYS.toSeconds(expireValue);
                    break;
                case WEEKS:
                    seconds = TimeUnit.DAYS.toSeconds(expireValue * 7);
                    break;
                case MONTHS:
                    seconds = TimeUnit.DAYS.toSeconds(expireValue * 30);
                    break;
                case NEVER:
                    return null;
                default:
                    _log.error("Not a valid expire type: " + expireType);
                    return null;
            }
        }
        return seconds.intValue();
    }

    private String generatePathForLocalTarget(FilePolicy filePolicy, FileShare fileShare, FileDeviceInputOutput args) {
        String policyPath = "";
        FilePolicyApplyLevel applyLevel = FilePolicyApplyLevel.valueOf(filePolicy.getApplyAt());
        String[] fsPathParts = new String[3];
        switch (applyLevel) {
            case vpool:
                String vpool = args.getVPoolNameWithNoSpecialCharacters();
                fsPathParts = fileShare.getNativeId().split(vpool);
                policyPath = fsPathParts[0] + vpool + "_localTarget" + fsPathParts[1];
                break;
            case project:
                String project = args.getProjectNameWithNoSpecialCharacters();
                fsPathParts = fileShare.getNativeId().split(project);
                policyPath = fsPathParts[0] + project + "_localTarget" + fsPathParts[1];
                break;
            case file_system:
                policyPath = fileShare.getNativeId();
                break;
            default:
                _log.error("Not a valid policy apply level: " + applyLevel);
        }
        return policyPath;
    }

    /**
     * 
     * @param dbClient
     * @param project
     * @param storageSystem
     * @return
     */
    public void updateLocalTargetFileSystemPath(StorageSystem system, FileDeviceInputOutput args) {
        VirtualPool vpool = args.getVPool();
        Project project = args.getProject();
        FileShare fs = args.getFs();

        List<FilePolicy> replicationPolicies = FileOrchestrationUtils.getReplicationPolices(_dbClient, vpool, project, fs);
        if (replicationPolicies != null && !replicationPolicies.isEmpty()) {
            if (replicationPolicies.size() > 1) {
                _log.warn("More than one replication policy found {}", replicationPolicies.size());
            } else {
                FilePolicy replPolicy = replicationPolicies.get(0);
                if (fs.getPersonality() != null && fs.getPersonality().equalsIgnoreCase(PersonalityTypes.TARGET.name())
                        && replPolicy.getFileReplicationType().equalsIgnoreCase(FileReplicationType.LOCAL.name())) {
                    // For local replication, the path sould be different
                    // add localTaget to file path at directory level where the policy is applied!!!
                    String mountPath = generatePathForLocalTarget(replPolicy, fs, args);
                    // replace extra forward slash with single one
                    mountPath = mountPath.replaceAll("/+", "/");
                    _log.info("Mount path to mount the Isilon File System {}", mountPath);
                    args.setFsMountPath(mountPath);
                    args.setFsNativeGuid(args.getFsMountPath());
                    args.setFsNativeId(args.getFsMountPath());
                    args.setFsPath(args.getFsMountPath());
                }
            }
        }
        return;
    }

    @Override
    public BiosCommandResult checkFilePolicyExistsOrCreate(StorageSystem storageObj, FileDeviceInputOutput args) {

        FilePolicy filePolicy = args.getFileProtectionPolicy();
        BiosCommandResult result = null;

        try {
            IsilonApi isi = getIsilonDevice(storageObj);
            String clusterName = isi.getClusterConfig().getName();
            String snapshotPolicySceduleName = FileOrchestrationUtils
                    .generateNameForSnapshotIQPolicy(clusterName, filePolicy, null, args);

            String filePolicyBasePath = getFilePolicyPath(storageObj, args);
            checkAppliedResourceNamePartOfFilePolicyPath(filePolicyBasePath, filePolicy, args);

            IsilonSnapshotSchedule isilonSnapshotSchedule = getEquivalentIsilonSnapshotSchedule(isi, filePolicyBasePath);
            if (isilonSnapshotSchedule != null) {

                String filePolicySnapshotSchedule = getIsilonPolicySchedule(filePolicy);
                _log.info("Comparing snapshot schedule between CoprHD policy: {} and Isilon policy: {}.", filePolicySnapshotSchedule,
                        isilonSnapshotSchedule.getSchedule());
                if (isilonSnapshotSchedule.getSchedule().equalsIgnoreCase(filePolicySnapshotSchedule)) {
                    // Verify the policy was mapped to FileStorageResource
                    if (null == FileOrchestrationUtils.findPolicyStorageResourceByNativeId(_dbClient, storageObj,
                            filePolicy, args, filePolicyBasePath)) {
                        _log.info("Isilon policy found for {}, creating policy storage resouce to further management",
                                filePolicy.getFilePolicyName());
                        FileOrchestrationUtils.updatePolicyStorageResource(_dbClient, storageObj, filePolicy,
                                args, filePolicyBasePath, isilonSnapshotSchedule.getName(),
                                null, null, null);
                    }
                    result = BiosCommandResult.createSuccessfulResult();
                } else {
                    _log.info("Snapshot schedule differs between Isilon policy and CoprHD file policy. So, create policy in Isilon...");
                    // Create snapshot policy.
                    createIsilonSnapshotPolicySchedule(storageObj, filePolicy, filePolicyBasePath,
                            snapshotPolicySceduleName, args, filePolicyBasePath);
                    result = BiosCommandResult.createSuccessfulResult();
                }
            } else {
                // Create snapshot policy.
                createIsilonSnapshotPolicySchedule(storageObj, filePolicy, filePolicyBasePath,
                        snapshotPolicySceduleName, args, filePolicyBasePath);
                result = BiosCommandResult.createSuccessfulResult();
            }
        } catch (IsilonException e) {
            _log.error("Assigning file policy failed.", e);
            result = BiosCommandResult.createErrorResult(e);
        }
        return result;
    }

    private void createIsilonSnapshotPolicySchedule(StorageSystem storageObj, FilePolicy filePolicy,
            String path, String snapshotSchedulePolicyName, FileDeviceInputOutput args, String filePolicyBasePath) {

        String pattern = snapshotSchedulePolicyName + "_%Y-%m-%d_%H-%M";
        String scheduleValue = getIsilonPolicySchedule(filePolicy);
        Integer expireValue = getIsilonSnapshotExpireValue(filePolicy);

        _log.info("File Policy : {} creation started", filePolicy.toString());
        try {
            IsilonApi isi = getIsilonDevice(storageObj);
            isi.createDir(path, true);
            isi.createSnapshotSchedule(snapshotSchedulePolicyName, path, scheduleValue, pattern, expireValue);
            _log.info("Isilon File Policy {} created successfully.", snapshotSchedulePolicyName);
            FileOrchestrationUtils.updatePolicyStorageResource(_dbClient, storageObj, filePolicy, args, filePolicyBasePath,
                    snapshotSchedulePolicyName, null, null, null);
        } catch (IsilonException e) {
            throw e;
        }
    }

    @Override
    public BiosCommandResult checkFileReplicationPolicyExistsOrCreate(StorageSystem sourceStorageObj,
            StorageSystem targetStorageObj, FileDeviceInputOutput sourceSytemArgs, FileDeviceInputOutput targetSytemArgs) {

        FilePolicy filePolicy = sourceSytemArgs.getFileProtectionPolicy();

        // Source Path
        String sourcePath = getFilePolicyPath(sourceStorageObj, sourceSytemArgs);
        String targetPath = getFilePolicyPath(targetStorageObj, targetSytemArgs);

        if (FileReplicationType.LOCAL.name().equalsIgnoreCase(filePolicy.getFileReplicationType())) {
            targetPath = targetPath + "_localTarget";
        }
        // Policy Name

        BiosCommandResult result = null;

        try {
            IsilonApi sourceIsi = getIsilonDevice(sourceStorageObj);
            IsilonApi targetIsi = getIsilonDevice(targetStorageObj);
            String sourceClusterName = sourceIsi.getClusterConfig().getName();
            String targetClusterName = targetIsi.getClusterConfig().getName();
            String policyName = FileOrchestrationUtils.generateNameForSyncIQPolicy(sourceClusterName, targetClusterName, filePolicy,
                    null, sourceSytemArgs);
            checkAppliedResourceNamePartOfFilePolicyPath(sourcePath, filePolicy, sourceSytemArgs);

            ArrayList<IsilonSyncPolicy> isiReplicationPolicies = sourceIsi.getReplicationPolicies().getList();
            IsilonSyncPolicy isilonReplicationSchedule = checkForReplicationPolicyOnIsilon(isiReplicationPolicies,
                    filePolicy, sourcePath, targetPath);

            if (isilonReplicationSchedule != null) {
                boolean validPolicy = validateIsilonReplicationPolicy(isilonReplicationSchedule, filePolicy, targetPath,
                        targetStorageObj, sourceStorageObj);
                if (validPolicy) {
                    // Verify the policy was mapped to FileStorageResource
                    if (null == FileOrchestrationUtils.findPolicyStorageResourceByNativeId(_dbClient, sourceStorageObj,
                            filePolicy, sourceSytemArgs, sourcePath)) {
                        _log.info("Isilon policy found for {}, creating policy storage resouce to further management",
                                filePolicy.getFilePolicyName());
                        FileOrchestrationUtils.updatePolicyStorageResource(_dbClient, sourceStorageObj, filePolicy,
                                sourceSytemArgs, sourcePath, isilonReplicationSchedule.getName(),
                                targetStorageObj, targetSytemArgs.getvNAS(), targetPath);
                    }
                    result = BiosCommandResult.createSuccessfulResult();
                } else {
                    throw DeviceControllerException.exceptions.assignFilePolicyFailed(filePolicy.getFilePolicyName(),
                            filePolicy.getApplyAt(),
                            "File policy and Isilon syncIQ policy differs for path: "
                                    + sourcePath);
                }
            } else {
                // Create replication sync policy.
                createIsilonSyncPolicy(sourceStorageObj, targetStorageObj, filePolicy, sourcePath, targetPath,
                        policyName, sourceSytemArgs, targetSytemArgs);

                result = BiosCommandResult.createSuccessfulResult();
            }
        } catch (IsilonException e) {
            _log.error("Assigning file policy failed.", e);
            result = BiosCommandResult.createErrorResult(e);
        }
        return result;
    }

    private String createIsilonSyncPolicy(StorageSystem storageObj, StorageSystem targetStorage,
            FilePolicy filePolicy, String sourcePath, String targetPath, String syncPolicyName,
            FileDeviceInputOutput sourceSystemArgs, FileDeviceInputOutput targetSystemArgs) {

        String scheduleValue = getIsilonPolicySchedule(filePolicy);

        _log.info("File replication policy : {} creation started", filePolicy.toString());

        try {
            VirtualNAS targetVNas = targetSystemArgs.getvNAS();
            URI targetVNasURI = null;
            if (targetVNas != null) {
                targetVNasURI = targetVNas.getId();
            }

            String targetHost = FileOrchestrationUtils.getTargetHostPortForReplication(_dbClient, targetStorage.getId(),
                    targetSystemArgs.getVarray().getId(), targetVNasURI);

            IsilonApi isi = getIsilonDevice(storageObj);
            isi.createDir(sourcePath, true);
            IsilonSyncPolicy replicationPolicy = new IsilonSyncPolicy(syncPolicyName, sourcePath, targetPath, targetHost,
                    Action.sync);
            if (scheduleValue != null && !scheduleValue.isEmpty()) {
                replicationPolicy.setSchedule(scheduleValue);
            }
            if (filePolicy.getFilePolicyDescription() != null) {
                replicationPolicy.setDescription(filePolicy.getFilePolicyDescription());
            }
            if (filePolicy.getNumWorkerThreads() != null && filePolicy.getNumWorkerThreads() > 0) {
                replicationPolicy.setWorkersPerNode(filePolicy.getNumWorkerThreads().intValue());
            }
            replicationPolicy.setEnabled(true);
            replicationPolicy.setSchedule(scheduleValue);
            String scheduleId = isi.createReplicationPolicy(replicationPolicy);

            FileOrchestrationUtils.updatePolicyStorageResource(_dbClient, storageObj,
                    filePolicy, sourceSystemArgs, sourcePath, syncPolicyName, targetStorage, targetSystemArgs.getvNAS(), targetPath);

            return scheduleId;
        } catch (IsilonException e) {
            throw e;
        }
    }

    private boolean isValidTargetHostOnExistingPolicy(String existingPolicyTargetHost, StorageSystem system) {
        if (existingPolicyTargetHost != null && !existingPolicyTargetHost.isEmpty()) {
            // target cluster IP address is matching????
            if (existingPolicyTargetHost.equalsIgnoreCase(system.getIpAddress())) {
                return true;
            }
            IsilonApi isi = getIsilonDevice(system);
            String targetClusterName = isi.getClusterConfig().getName();
            // target cluster name is matching????
            if (existingPolicyTargetHost.equalsIgnoreCase(targetClusterName)) {
                return true;
            }
            // target cluster smart connect zone is matching???
            for (com.emc.storageos.db.client.model.StoragePort port : FileOrchestrationUtils
                    .getStorageSystemPorts(_dbClient, system)) {
                if (existingPolicyTargetHost.equalsIgnoreCase(port.getPortName())) {
                    return true;
                } else if (existingPolicyTargetHost.equalsIgnoreCase(port.getPortNetworkId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean validateIsilonReplicationPolicy(IsilonSyncPolicy isiMatchedPolicy, FilePolicy filePolicy,
            String targetPath, StorageSystem targetSystem, StorageSystem sourceSystem) {
        _log.info("Comparing filepolicy: {} with SyncIQ policy: {}", filePolicy.getFilePolicyName(), isiMatchedPolicy.getName());

        if (isiMatchedPolicy != null) {
            // replication type validation
            if (!isiMatchedPolicy.getAction().equals(IsilonSyncPolicy.Action.sync)) {
                _log.error("Isilon policy replication type is not valid: {}", isiMatchedPolicy.getAction().name());
                return false;
            }

            // Verify the remote/local cluster
            if (filePolicy.getFileReplicationType().equalsIgnoreCase(FilePolicy.FileReplicationType.REMOTE.name())) {
                if (!isValidTargetHostOnExistingPolicy(isiMatchedPolicy.getTargetHost(), targetSystem)) {
                    _log.error("Target host is not matching for REMOTE replication.");
                    return false;
                }
            } else {
                if (!isValidTargetHostOnExistingPolicy(isiMatchedPolicy.getTargetHost(), sourceSystem)) {
                    _log.error("Target host is not matching for LOCAL replication.");
                    return false;
                }
            }
            // schedule validation
            String viprSchedule = getIsilonPolicySchedule(filePolicy);
            String isiSchedule = StringUtils.substringBefore(isiMatchedPolicy.getSchedule(), " between");
            if (!viprSchedule.equalsIgnoreCase(isiSchedule)) {
                _log.error("File policy schedule: {} is different compared to isilon SyncIQ schedule: {}", viprSchedule, isiSchedule);
                return false;
            }

            // target path validation
            if (!isiMatchedPolicy.getTargetPath().equals(targetPath)) {
                _log.error("Target path: {} is different compared to SyncIQ policy path: {}", targetPath, isiMatchedPolicy);
                return false;
            }
        }
        return true;
    }

    private static IsilonSyncPolicy checkForReplicationPolicyOnIsilon(ArrayList<IsilonSyncPolicy> isiPolicies, FilePolicy filePolicy,
            String sourceRootPath, String targetPath) {
        IsilonSyncPolicy isiMatchedPolicy = null;

        for (IsilonSyncPolicy isiPolicy : isiPolicies) {
            if (isiPolicy.getSourceRootPath().equals(sourceRootPath) && isiPolicy.getTargetPath().equals(targetPath)) {
                isiMatchedPolicy = isiPolicy;
                break;
            }
        }

        return isiMatchedPolicy;
    }

    private String getFilePolicyPath(StorageSystem storageObj, FileDeviceInputOutput args) {
        String customPath = getCustomPath(storageObj, args);
        String filePolicyBasePath = null;
        VirtualNAS vNAS = args.getvNAS();
        if (vNAS != null) {
            String vNASPath = vNAS.getBaseDirPath();
            if (vNASPath != null && !vNASPath.trim().isEmpty()) {
                filePolicyBasePath = vNASPath + FW_SLASH + customPath;
            } else {
                filePolicyBasePath = IFS_ROOT + FW_SLASH + getSystemAccessZoneNamespace() + FW_SLASH + customPath;
            }

        } else {
            filePolicyBasePath = IFS_ROOT + FW_SLASH + getSystemAccessZoneNamespace() + FW_SLASH + customPath;
        }

        filePolicyBasePath = filePolicyBasePath.replaceAll("/+", "/").replaceAll("/$", "");

        _log.info("Computed file policy path: {}", filePolicyBasePath);
        return filePolicyBasePath;
    }

    private void checkAppliedResourceNamePartOfFilePolicyPath(String filePolicyBasePath,
            FilePolicy filePolicy, FileDeviceInputOutput args) {

        FilePolicyApplyLevel appliedAt = FilePolicyApplyLevel.valueOf(filePolicy.getApplyAt());
        String resourceName = null;
        switch (appliedAt) {
            case project:
                if (args.getProject() != null) {
                    resourceName = args.getProjectNameWithNoSpecialCharacters().replaceAll("_", "");
                    if (!filePolicyBasePath.contains(resourceName)) {
                        _log.error("File policy base path does not contain project: {}", resourceName);
                        throw DeviceControllerException.exceptions.assignFilePolicyFailed(filePolicy.getFilePolicyName(),
                                filePolicy.getApplyAt(),
                                "File policy base path does not contain project: " + resourceName);
                    }
                } else {
                    throw DeviceControllerException.exceptions.assignFilePolicyFailed(filePolicy.getFilePolicyName(),
                            filePolicy.getApplyAt(), "No project was provided in the input.");
                }
                break;
            case vpool:
                if (args.getVPool() != null) {
                    resourceName = args.getVPoolNameWithNoSpecialCharacters().replaceAll("_", "");
                    if (!filePolicyBasePath.contains(resourceName)) {
                        _log.error("File policy base path does not contain vpool: {}", resourceName);
                        throw DeviceControllerException.exceptions.assignFilePolicyFailed(filePolicy.getFilePolicyName(),
                                filePolicy.getApplyAt(),
                                "File policy base path does not contain vpool: " + resourceName);
                    }
                } else {
                    throw DeviceControllerException.exceptions.assignFilePolicyFailed(filePolicy.getFilePolicyName(),
                            filePolicy.getApplyAt(), "No vpool was provided in the input.");
                }
                break;
            case file_system:
                if (args.getFs() != null) {
                    resourceName = args.getFSNameWithNoSpecialCharacters().replaceAll("_", "");
                    if (!filePolicyBasePath.contains(resourceName)) {
                        _log.error("File policy base path does not contain fileshare: {}", resourceName);
                        throw DeviceControllerException.exceptions.assignFilePolicyFailed(filePolicy.getFilePolicyName(),
                                filePolicy.getApplyAt(),
                                "File policy base path does not contain fileshare: " + resourceName);
                    }
                } else {
                    throw DeviceControllerException.exceptions.assignFilePolicyFailed(filePolicy.getFilePolicyName(),
                            filePolicy.getApplyAt(), "No fileshare was provided in the input.");
                }
                break;

            default:
                break;
        }
    }

    public static FilePolicy getReplicationPolicyAppliedOnFS(FileShare fs, DbClient dbClient) {
        StringSet existingFSPolicies = fs.getFilePolicies();
        List<URI> existingFSPolicyURIs = new ArrayList<>();
        for (String filePolicyURI : existingFSPolicies) {
            existingFSPolicyURIs.add(URI.create(filePolicyURI));
        }
        Iterator<FilePolicy> iterator = dbClient.queryIterativeObjects(FilePolicy.class, existingFSPolicyURIs, true);
        while (iterator.hasNext()) {
            FilePolicy fp = iterator.next();
            if (fp.getFilePolicyType().equals(FilePolicy.FilePolicyType.file_replication.name())) {
                _log.info("Found replication policy :{}  applied to the file system:  {}.",
                        fp.toString(), fs.getId());
                return fp;
            }
        }
        return null;
    }

    public static PolicyStorageResource getEquivalentPolicyStorageResource(FileShare fs, DbClient dbClient) {
        FilePolicy fp = getReplicationPolicyAppliedOnFS(fs, dbClient);
        if (fp != null) {
            StringSet policyStrResources = fp.getPolicyStorageResources();
            List<URI> policyStrURIs = new ArrayList<>();
            for (String policyStrResource : policyStrResources) {
                policyStrURIs.add(URI.create(policyStrResource));
            }
            Iterator<PolicyStorageResource> iterator = dbClient.queryIterativeObjects(PolicyStorageResource.class, policyStrURIs, true);
            while (iterator.hasNext()) {
                PolicyStorageResource policyRes = iterator.next();
                if (policyRes.getAppliedAt().equals(fs.getId()) && policyRes.getStorageSystem().equals(fs.getStorageDevice())) {
                    _log.info("Found replication policy:{} corresponding storage resource: {}  applied to the file system: {}.",
                            fp.getLabel(), policyRes.toString(), fs.getId());
                    return policyRes;
                }
            }
        }
        return null;
    }

    @Override
    public void doFailbackLink(StorageSystem system, FileShare target, TaskCompleter completer) {
        // TODO Auto-generated method stub

    }

    @Override
    public BiosCommandResult checkFilePolicyPathHasResourceLabel(StorageSystem system, FileDeviceInputOutput args) {

        _log.info("Inside checkFilePolicyPathHasResourceLabel()");

        try {
            FilePolicy filePolicy = args.getFileProtectionPolicy();
            String filePolicyBasePath = getFilePolicyPath(system, args);
            checkAppliedResourceNamePartOfFilePolicyPath(filePolicyBasePath, filePolicy, args);
            _log.info("checkFilePolicyPathHasResourceLabel successful.");
            return BiosCommandResult.createSuccessfulResult();
        } catch (IsilonException e) {
            _log.error("checkFilePolicyPathHasResourceLabel failed.", e);
            return BiosCommandResult.createErrorResult(e);
        }

    }
}