/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.systemservices.impl.resource;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedOutputStream;
import java.io.PipedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.net.URI;

import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.DELETE;
import javax.ws.rs.core.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;

import com.emc.storageos.coordinator.client.service.DrUtil;
import com.emc.storageos.coordinator.common.Service;
import com.emc.storageos.security.audit.AuditLogManager;
import com.emc.storageos.security.authorization.CheckPermission;
import com.emc.storageos.security.authorization.Role;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.services.util.Exec;
import com.emc.storageos.services.util.NamedThreadPoolExecutor;
import com.emc.storageos.systemservices.impl.jobs.backupscheduler.BackupScheduler;
import com.emc.storageos.systemservices.impl.jobs.backupscheduler.SchedulerConfig;
import com.emc.storageos.systemservices.impl.restore.DownloadExecutor;
import com.emc.storageos.systemservices.impl.jobs.common.JobProducer;
import com.emc.storageos.systemservices.impl.resource.util.ClusterNodesUtil;
import com.emc.storageos.systemservices.impl.resource.util.NodeInfo;
import com.emc.storageos.systemservices.impl.client.SysClientFactory;

import com.emc.storageos.management.backup.exceptions.BackupException;
import com.emc.storageos.management.backup.util.FtpClient;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.management.backup.*;
import com.emc.storageos.management.backup.util.BackupClient;
import com.emc.storageos.management.backup.util.CifsClient;
import com.emc.storageos.services.util.TimeUtils;
import com.emc.vipr.model.sys.backup.*;

import static com.emc.vipr.model.sys.backup.BackupUploadStatus.Status;

/**
 * Defines the API for making requests to the backup service.
 */
@Path("/backupset/")
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private static final int ASYNC_STATUS = 202;
    private BackupOps backupOps;
    private BackupScheduler backupScheduler;
    private SchedulerConfig backupConfig;
    private JobProducer jobProducer;
    private NamedThreadPoolExecutor backupDownloader = new NamedThreadPoolExecutor("BackupDownloader", 10);
    private final String restoreCmd="/opt/storageos/bin/restore-from-ui.sh";
    private final String restoreLog="/var/log/restore-internal.log";
    private DownloadExecutor downloadTask;

    @Autowired
    private AuditLogManager auditMgr;

    @Autowired
    private DrUtil drUtil;

    @Autowired
    private Service serviceinfo;

    /**
     * Sets backup client
     * 
     * @param backupOps the backup client instance
     */
    public void setBackupOps(BackupOps backupOps) {
        this.backupOps = backupOps;
    }

    /**
     * Sets backup scheduler client
     *
     * @param backupScheduler the backup scheduler client instance
     */
    public void setBackupScheduler(BackupScheduler backupScheduler) {
        this.backupScheduler = backupScheduler;
    }

    /**
     * Sets backup upload job producer
     *
     * @param jobProducer the backup upload job producer
     */
    public void setJobProducer(JobProducer jobProducer) {
        this.jobProducer = jobProducer;
    }

    /**
     * Default constructor.
     */
    public BackupService() {
    }

    /**
     * Get a list of info for valid backupsets on ViPR cluster
     * 
     * @brief List current backupsets info
     * @prereq none
     * @return A list of backupset info
     */
    @GET
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR, Role.RESTRICTED_SYSTEM_ADMIN })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public BackupSets listBackup() {
        List<BackupSetInfo> backupList = new ArrayList<BackupSetInfo>();

        log.info("Received list backup request");
        try {
            backupList = backupOps.listBackup();
        } catch (BackupException e) {
            log.error("Failed to list backup sets, e=", e);
            throw APIException.internalServerErrors.getObjectError("Backup info", e);
        }

        return toBackupSets(backupList);
    }

    private BackupSets toBackupSets(List<BackupSetInfo> backupList) {
        BackupSets backupSets = new BackupSets();
        for (BackupSetInfo backupInfo : backupList) {
            BackupUploadStatus uploadStatus = getBackupUploadStatus(backupInfo.getName());
            backupSets.getBackupSets().add(new BackupSets.BackupSet(
                    backupInfo.getName(),
                    backupInfo.getSize(),
                    backupInfo.getCreateTime(),
                    uploadStatus));
        }
        return backupSets;
    }

    /**
     * Get info for a specific backupset on ViPR cluster
     *
     * @brief Get a specific backupset info
     * @param backupTag The name of backup
     * @prereq none
     * @return Info of a specific backup
     */
    @GET
    @Path("backup/")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR, Role.RESTRICTED_SYSTEM_ADMIN })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public BackupSets.BackupSet queryBackup(@QueryParam("tag") String backupTag) {
        List<BackupSetInfo> backupList;

        log.info("Received query backup request, tag={}", backupTag);
        try {
            backupList = backupOps.listBackup();
        } catch (BackupException e) {
            log.error("Failed to list backup sets", e);
            throw APIException.internalServerErrors.getObjectError("Backup info", e);
        }
        for (BackupSetInfo backupInfo : backupList) {
            if (backupInfo.getName().equals(backupTag)) {
                BackupUploadStatus uploadStatus = getBackupUploadStatus(backupInfo.getName());
                BackupSets.BackupSet backupSet = new BackupSets.BackupSet(backupInfo.getName(), backupInfo.getSize(),
                        backupInfo.getCreateTime(), uploadStatus);
                log.info("BackupSet={}", backupSet.toString());
                return backupSet;
            }
        }
        return new BackupSets.BackupSet();
    }

    /**
     * Get a list of backup files on external server
     *
     * @brief List current backup files on external server
     * @prereq none
     * @return A list of backup files info
     */
    @GET
    @Path("external/")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR, Role.RESTRICTED_SYSTEM_ADMIN })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public ExternalBackups listExternalBackup() {
        log.info("Received list backup files on external server request");
        try {
            backupConfig = backupScheduler.getCfg();
            String externalServerUrl = backupConfig.getExternalServerUrl();
            if (externalServerUrl == null) {
                log.warn("External server has not been configured");
                throw new IllegalStateException("External server has not been configured");
            }
            BackupClient client = getExternalServerClient(backupConfig);
            List<String> backupFiles = client.listAllFiles();
            ExternalBackups backups = new ExternalBackups(backupFiles);
            return backups;
        } catch (Exception e) {
            log.error("Failed to list backup files on external server", e);
            throw APIException.internalServerErrors.listExternalBackupFailed(e);
        }
    }

    /**
     * Get info for a specific backup
     * 
     * @brief Get a specific backup info
     * @param backupName The name of backup
     * @param isLocal The backup is local or not, false by default
     * @prereq none
     * @return Info of a specific backup
     */
    @GET
    @Path("backup/info/")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR, Role.RESTRICTED_SYSTEM_ADMIN })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public BackupInfo queryBackupInfo(@QueryParam("backupname") String backupName, @QueryParam("isLocal") @DefaultValue("false") boolean isLocal) {
        log.info("Query backup info backupName={} isLocal={}", backupName, isLocal);
        try {
            if (isLocal) {
                //query info of a local backup
                return backupOps.queryLocalBackupInfo(backupName);
            }

            checkExternalServer();

            SchedulerConfig cfg = backupScheduler.getCfg();

            BackupInfo backupInfo =  backupOps.getBackupInfo(backupName, getExternalServerClient(cfg));

            log.info("The backupInfo={}", backupInfo);
            return backupInfo;
        } catch (Exception e) {
            log.error("Failed to query external backup info", e);
            throw APIException.internalServerErrors.queryExternalBackupFailed(e);
        }
    }


    /**
     * Create a near Point-In-Time copy of DB & ZK data files on all controller nodes.
     * 
     * @brief Create a backup set
     * 
     *        <p>
     *        Limitations of the argument: 1. Maximum length is 200 characters 2. Underscore "_" is not supported 3. Any character that is
     *        not supported by Linux file name is not allowed
     * 
     * @param backupTag The name of backup. This parameter is optional,
     *            default is timestamp(for example 20140531193000).
     * @param forceCreate If true, force backup creation even when minority nodes are unavailable
     * @prereq none
     * @return server response indicating if the operation succeeds.
     */
    @POST
    @Path("backup/")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
    public Response createBackup(@QueryParam("tag") String backupTag,
            @QueryParam("force") @DefaultValue("false") boolean forceCreate) {
        log.info("Received create backup request, backup tag={}", backupTag);
        List<String> descParams = getDescParams(backupTag);
        try {
            backupOps.createBackup(backupTag, forceCreate);
            auditBackup(OperationTypeEnum.CREATE_BACKUP, AuditLogManager.AUDITLOG_SUCCESS, null, descParams.toArray());
        } catch (BackupException e) {
            log.error("Failed to create backup(tag={}), e=", backupTag, e);
            descParams.add(e.getLocalizedMessage());
            auditBackup(OperationTypeEnum.CREATE_BACKUP, AuditLogManager.AUDITLOG_FAILURE, null, descParams.toArray());
            backupOps.updateBackupCreationStatus(backupTag, TimeUtils.getCurrentTime(), false);
            throw APIException.internalServerErrors.createObjectError("Backup files", e);
        }
        return Response.ok().build();
    }

    /**
     * Delete the specific backup files on each controller node of cluster
     * 
     * @brief Delete a backup
     * @param backupTag The name of backup
     * @prereq This backup sets should have been created
     * @return server response indicating if the operation succeeds.
     */
    @DELETE
    @Path("backup/")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
    public Response deleteBackup(@QueryParam("tag") String backupTag) {
        log.info("Received delete backup request, backup tag={}", backupTag);
        if (backupTag == null) {
            throw APIException.badRequests.parameterIsNotValid(backupTag);
        }
        List<String> descParams = getDescParams(backupTag);
        try {
            backupOps.deleteBackup(backupTag);
            auditBackup(OperationTypeEnum.DELETE_BACKUP, AuditLogManager.AUDITLOG_SUCCESS, null, descParams.toArray());
        } catch (BackupException e) {
            log.error("Failed to delete backup(tag= {}), e=", backupTag, e);
            descParams.add(e.getLocalizedMessage());
            auditBackup(OperationTypeEnum.DELETE_BACKUP, AuditLogManager.AUDITLOG_FAILURE, null, descParams.toArray());
            throw APIException.internalServerErrors.updateObjectError("Backup files", e);
        }
        return Response.ok().build();
    }

    /**
     * Upload the specific backup files from each controller node of cluster to external server
     *
     * @brief Upload a backup
     * @param backupTag The name of backup
     * @prereq This backup sets should have been created
     * @return server response indicating if the operation accepted.
     */
    @POST
    @Path("backup/upload/")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
    public Response uploadBackup(@QueryParam("tag") final String backupTag) {
        log.info("Received upload backup request, backup tag={}", backupTag);

        BackupUploadStatus job = new BackupUploadStatus();
        job.setBackupName(backupTag);
        job.setStatus(Status.NOT_STARTED);
        jobProducer.enqueue(job);

        backupScheduler.getUploadExecutor().addPendingUploadTask(backupTag);

        return Response.status(ASYNC_STATUS).build();
    }

    /**
     * Get the upload status for a specific backup
     *
     * @brief Get upload status
     * @param backupTag The name of backup
     * @prereq none
     * @return Upload status of the backup
     */
    @GET
    @Path("backup/upload/")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR, Role.RESTRICTED_SYSTEM_ADMIN })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public BackupUploadStatus getBackupUploadStatus(@QueryParam("tag") String backupTag) {
        log.info("Received get upload status request, backup tag={}", backupTag);
        try {
            File backupDir = backupOps.getBackupDir();
            BackupUploadStatus uploadStatus = backupScheduler.getUploadExecutor().getUploadStatus(backupTag, backupDir);
            log.info("Current upload status is: {}", uploadStatus);
            return uploadStatus;
        } catch (Exception e) {
            log.error("Failed to get upload status", e);
            throw APIException.internalServerErrors.getObjectError("Upload status", e);
        }
    }

    /**
     * Download the zip archive that composed of DB & ZK backup files on all controller nodes
     * It's suggest to download backupset to external media timely after the creation
     * and then delete it to release the storage space
     * 
     * @brief Download a specific backupset
     * @param backupTag The name of backup
     * @prereq This backup sets should have been created
     * @return Zip file stream if the operation succeeds,
     */
    @GET
    @Path("download/")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
    @Produces({ MediaType.APPLICATION_OCTET_STREAM })
    public Response downloadBackup(@QueryParam("tag") final String backupTag) {
        log.info("Received download backup request, backup tag={}", backupTag);
        try {
            final BackupFileSet files = getDownloadList(backupTag);
            if (files.isEmpty()) {
                throw APIException.internalServerErrors.createObjectError(
                        String.format("can not find target backup set '%s'.", backupTag),
                        null);
            }
            if (!files.isValid()) {
                throw APIException.internalServerErrors.noNodeAvailableError("download backup files");
            }

            InputStream pipeIn = getDownloadStream(files);

            return Response.ok(pipeIn).type(MediaType.APPLICATION_OCTET_STREAM).build();
        } catch (Exception e) {
            log.error("create backup final file failed. e=", e);
            throw APIException.internalServerErrors.createObjectError("Download backup files", e);
        }
    }

    /**
     * *Internal API, used only between nodes*
     * <p>
     * Get backup file name
     * 
     * @param fileName
     * @return the name and content info of backup files
     */
    @POST
    @Path("internal/node-backups/download")
    @Produces({ MediaType.APPLICATION_OCTET_STREAM })
    public Response downloadFileFromNode(String fileName) {
        log.info("getBackup({})", fileName);
        try {
            File file = new File(this.backupOps.getBackupDir(), fileName);
            if (!file.exists()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            InputStream input = new BufferedInputStream(new FileInputStream(file));
            return Response.ok(input).type(MediaType.APPLICATION_OCTET_STREAM).build();
        } catch (Exception e) {
            throw APIException.internalServerErrors.getObjectFromError(
                    "backup file input stream", "local", e);
        }
    }

    /**
     * *Internal API, used only between nodes*
     * Send backup file name to a node
     *
     * @param backupName the backup filename to be downloaded
     */
    @POST
    @Path("internal/pull")
    public Response downloadBackupFile(@QueryParam("backupname") String backupName, @QueryParam("endpoint") URI endpoint) {
        log.info("To download files of backupname={} endpoint={}", backupName, endpoint);

        downloadTask = new DownloadExecutor(backupName, backupOps, endpoint);
        Thread downloadThread = new Thread(downloadTask);
        downloadThread.setDaemon(true);
        downloadThread.setName("PullBackupFromOtherNode");
        downloadThread.start();

        return Response.status(ASYNC_STATUS).build();
    }

    @GET
    @Path("internal/pull-file/")
    @Produces({ MediaType.APPLICATION_OCTET_STREAM })
    public Response getBackupFile(@QueryParam("backupname") String backupName, @QueryParam("filename") String filename) {
        log.info("Get backup file {} from {}", filename, backupName);

        File downloadDir = backupOps.getDownloadDirectory(backupName);
        File backupFile = new File(downloadDir, filename);

        final InputStream in;
        try {
            in = new FileInputStream(backupFile);
        } catch (IOException e) {
            throw BackupException.fatals.backupFileNotFound(filename);
        }

        return Response.ok(in).type(MediaType.APPLICATION_OCTET_STREAM).build();
    }

    /**
     * Download backup data from the backup FTP server
     * each node will only downloads its own backup data
     *
     * @brief  Download the backup file from the remote server
     *
     * @param backupName the name of the backup on the FTP server
     * @param force  true to remove the downloaded data and start from the beginning
     * @return server response indicating if the operation is accpeted or not.
     */
    @POST
    @Path("pull/")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
    public Response pullBackup(@QueryParam("file") String backupName, @QueryParam("force") @DefaultValue("false") boolean force) {
        log.info("To pull the backup file {} force={}", backupName, force);

        checkExternalServer();

        if (backupOps.hasStandbySites()) {
            String errmsg = "Please remove all standby sites before downloading";
            backupOps.setRestoreStatus(backupName, false, BackupRestoreStatus.Status.DOWNLOAD_FAILED, errmsg, false, false);
        }else  if (!force && backupOps.isDownloadComplete(backupName)) {
            log.info("The backup file {} has already been downloaded", backupName);
        }else if (backupOps.isDownloadInProgress()) {
            String curBackupName = backupOps.getCurrentBackupName();
            if (!backupName.equals(curBackupName)) {
                String errmsg = curBackupName + " is downloading";
                backupOps.setRestoreStatus(backupName, false, BackupRestoreStatus.Status.DOWNLOAD_FAILED, errmsg, false, false);
                backupOps.persistCurrentBackupInfo(curBackupName, false);
            }else {
                log.info("The backup {} is downloading, no need to trigger again", backupName);
            }
        }else {
            initDownload(backupName);

            downloadTask = new DownloadExecutor(getExternalServerClient(backupScheduler.getCfg()), backupName, backupOps);

            Thread downloadThread = new Thread(downloadTask);
            downloadThread.setDaemon(true);
            downloadThread.setName("PullBackupFromRemoteServer");
            downloadThread.start();

            auditBackup(OperationTypeEnum.PULL_BACKUP, AuditLogManager.AUDITLOG_SUCCESS, null, backupName);
        }

        return Response.status(ASYNC_STATUS).build();
    }

    private void checkExternalServer() {
        SchedulerConfig cfg = backupScheduler.getCfg();
        if (cfg.uploadUrl == null) {
            throw BackupException.fatals.externalBackupServerError("The server is not set");
        }
    }

    private void initDownload(String backupName) {
        log.info("init download");
        SchedulerConfig cfg = backupScheduler.getCfg();

        //Step1: get the size of compressed backup file from server
        long size = 0;

        try {
            BackupClient client = getExternalServerClient(cfg);
            size = client.getFileSize(backupName);
        }catch(Exception  e) {
            log.warn("Failed to get the backup file size, e=", e);
            throw BackupException.fatals.failedToGetBackupSize(backupName, e);
        }

        //Step2: init status
        BackupRestoreStatus s = new BackupRestoreStatus();
        s.setBackupName(backupName);
        s.setStatusWithDetails(BackupRestoreStatus.Status.DOWNLOADING, null);

        Map<String, String> hosts = backupOps.getHosts();
        int numberOfNodes = hosts.size();
        Map<String, Long> sizesToDownload = new HashMap(numberOfNodes);
        Map<String, Long> downloadedSizes = new HashMap(numberOfNodes);
        for (String hostID : hosts.keySet()) {
            sizesToDownload.put(hostID, (long)0);
            downloadedSizes.put(hostID, (long)0);
        }

        // the zipped backup file will be downloaded to this node
        // so set the size to be downloaded on this node to the size of zip file
        String localHostID = backupOps.getLocalHostID();
        sizesToDownload.put(localHostID, size);
        s.setSizeToDownload(sizesToDownload);

        // check if we've already downloaded some part of zip file before,
        // if so, updated the downloaded size
        File downloadFolder = backupOps.getDownloadDirectory(backupName);
        File zipfile = new File(downloadFolder, backupName);
        if (zipfile.exists()) {
            downloadedSizes.put(localHostID, zipfile.length());
        }
        s.setDownloadedSize(downloadedSizes);

        backupOps.persistBackupRestoreStatus(s, false, true);
    }

    private void redirectRestoreRequest(String backupName, boolean isLocal, String password, boolean isGeoFromScratch) {
        URI restoreURL =
           URI.create(String.format(SysClientFactory.URI_NODE_BACKUPS_RESTORE_TEMPLATE, backupName, isLocal, password, isGeoFromScratch));

        URI endpoint = null;
        try {
            endpoint = backupOps.getFirstNodeURI();
            log.info("redirect restore URI {} to {}", restoreURL, endpoint);
            SysClientFactory.SysClient sysClient = SysClientFactory.getSysClient(endpoint);
            sysClient.post(restoreURL, null, null);
        }catch (Exception e) {
            String errMsg = String.format("Failed to send %s to %s", restoreURL, endpoint);
            setRestoreFailed(backupName, isLocal, errMsg, e);
        }
    }

    /**
     *  Cancel the current download backup operation
     *  If there are no downloading running, do nothing
     *  Client should use query API to check if the download operation
     *  has been canceled or not
     *
     * @brief  Cancel the current downloading from the remote server
     * @return server response indicating if the operation succeeds.
     */
    @POST
    @Path("pull/cancel")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
    public Response cancelDownloading() {
        backupOps.cancelDownload();

        auditBackup(OperationTypeEnum.PULL_BACKUP_CANCEL, AuditLogManager.AUDITLOG_SUCCESS, null);
        log.info("done");
        return Response.status(ASYNC_STATUS).build();
    }

    @POST
    @Path("internal/restore/")
    public Response internalRestore(@QueryParam("backupname") String backupName,
                                  @QueryParam("isLocal") boolean isLocal,
                                  @QueryParam("password") String password,
                                  @QueryParam("isgeofromscratch") @DefaultValue("false") boolean isGeoFromScratch) {
        log.info("Receive internal restore request");
        return doRestore(backupName, isLocal, password, isGeoFromScratch);
    }

    private Response doRestore(String backupName, boolean isLocal, String password, boolean isGeoFromScratch) {
        log.info("Do restore with backup name={} isLocal={} isGeoFromScratch={}", new Object[] {backupName, isLocal, isGeoFromScratch});
        auditBackup(OperationTypeEnum.RESTORE_BACKUP, AuditLogManager.AUDITOP_BEGIN, null, backupName);

        if (!backupOps.isClusterStable()) {
            setRestoreFailed(backupName, isLocal, "The cluster is not stable", null);
            return Response.status(ASYNC_STATUS).build();
        }

        File backupDir= backupOps.getBackupDir(backupName, isLocal);

        String myNodeId = backupOps.getCurrentNodeId();

        try {
            backupOps.checkBackup(backupDir, isLocal);
        }catch (Exception e) {
            if (backupOps.shouldHaveBackupData()) {
                String errMsg = String.format("Invalid backup on %s: %s", myNodeId, e.getMessage());
                setRestoreFailed(backupName, isLocal, errMsg, e);
                auditBackup(OperationTypeEnum.RESTORE_BACKUP, AuditLogManager.AUDITLOG_FAILURE, null, backupName);
                return Response.status(ASYNC_STATUS).build();
            }

            log.info("The current node doesn't have valid backup data {} so redirect to virp1", backupDir.getAbsolutePath());
            redirectRestoreRequest(backupName, isLocal, password, isGeoFromScratch);
            return Response.status(ASYNC_STATUS).build();
        }

        backupOps.setRestoreStatus(backupName, isLocal, BackupRestoreStatus.Status.RESTORING, null, false, false);
        String[] restoreCommand=new String[]{restoreCmd,
                backupDir.getAbsolutePath(), password, Boolean.toString(isGeoFromScratch),
                restoreLog};

        log.info("The restore command parameters: {} {} {} {}",
                new Object[] {restoreCommand[0], restoreCommand[1], restoreCommand[3], restoreCommand[4]});

        Exec.Result result = Exec.exec(120 * 1000, restoreCommand);
        switch (result.getExitValue()) {
            case 1:
                setRestoreFailed(backupName, isLocal, "Invalid password", null);
                break;
        }

        auditBackup(OperationTypeEnum.RESTORE_BACKUP, AuditLogManager.AUDITOP_END, null, backupName);
        log.info("done");
        return Response.status(ASYNC_STATUS).build();
    }

    /**
     * Restore from a given backup
     *   The backup data has been copied to the nodes
     *   The restore will stop all storageos services first
     *   so the UI will be unaccessible for the services restart
     *
     * @brief  Restore from the given backup
     *
     * @param backupName the name of the backup to be restored
     * @param password the root password of the current ViPR cluster
     * @param isGeoFromScratch true if this is the first vdc to be restored in a Geo environment
     * @return server response indicating if the operation is accepted or not.
     */
    @POST
    @Path("restore/")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
    public Response restoreBackup(@QueryParam("backupname") String backupName,
                                  @QueryParam("isLocal") boolean isLocal,
                                  @QueryParam("password") String password,
                                  @QueryParam("isgeofromscratch") @DefaultValue("false") boolean isGeoFromScratch) {
        log.info("Receive restore request");

        if (password == null || password.isEmpty()) {
            log.error("The password is missing");
            throw new IllegalArgumentException("The password is missing");
        }

        if (!canDoRestore(backupName, isLocal)) {
            return Response.status(ASYNC_STATUS).build();
        }

        return doRestore(backupName, isLocal, password, isGeoFromScratch);
    }

    private boolean canDoRestore(String backupName, boolean isLocal) {
        log.info("precheck of restore: {}", backupName);

        if (backupOps.hasStandbySites()) {
            String errmsg = "Please remove all standby sites before restore";
            setRestoreFailed(backupName, isLocal, errmsg, null);
            return false;
        }

        BackupRestoreStatus s = backupOps.queryBackupRestoreStatus(backupName, isLocal);
        log.info("Status:{}", s);

        BackupRestoreStatus.Status status = s.getStatus();
        if (isLocal && status == BackupRestoreStatus.Status.RESTORING) {
            String errmsg = String.format("The restore from the %s is in progress", backupName);
            setRestoreFailed(backupName, isLocal, errmsg, null);
            return false;
        }

        if (!isLocal && status != BackupRestoreStatus.Status.DOWNLOAD_SUCCESS) {
            String errmsg = String.format("The backup %s is not downloaded successfully", backupName);
            setRestoreFailed(backupName, isLocal, errmsg, null);
            return false;
        }

        return true;
    }

    private void setRestoreFailed(String backupName, boolean isLocal, String msg, Throwable cause) {
        log.error("Set restore failed backup name:{} error: {} cause:", new Object[] {backupName, msg, cause});
        BackupRestoreStatus.Status s = BackupRestoreStatus.Status.RESTORE_FAILED;
        backupOps.setRestoreStatus(backupName, isLocal, s, msg, false, false);
    }

    /**
     *  Query restore status
     *  @brief  Query the restore status of a backup
     *
     *  @param backupName the name of the backup
     *  @param isLocal true if the backup is a local backup
     * @return the restore status of the given backup
     */
    @GET
    @Path("restore/status")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR, Role.RESTRICTED_SYSTEM_ADMIN })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public BackupRestoreStatus queryRestoreStatus(@QueryParam("backupname") String backupName,
                                                  @QueryParam("isLocal") @DefaultValue("false") boolean isLocal) {
        log.info("Query restore status backupName={} isLocal={}", backupName, isLocal);

        BackupRestoreStatus status = backupOps.queryBackupRestoreStatus(backupName, isLocal);
        status.setBackupName(backupName); // in case it is not saved in the ZK

        if (isLocal) {
            File backupDir = backupOps.getBackupDir(backupName, true);
            String[] files = backupDir.list();
            if (files.length == 0) {
                throw BackupException.fatals.backupFileNotFound(backupName);
            }

            for (String f : files) {
                if (backupOps.isGeoBackup(f)) {
                    log.info("{} is a geo backup", backupName);
                    status.setGeo(true);
                    break;
                }
            }
        }else {
            checkExternalServer();

            SchedulerConfig cfg = backupScheduler.getCfg();
            BackupClient client = getExternalServerClient(cfg);
            List<String> backupFiles = new ArrayList();
            try {
                backupFiles = client.listFiles(backupName);

                log.info("The remote backup files={}", backupFiles);

                if (backupFiles.isEmpty()) {
                    throw BackupException.fatals.backupFileNotFound(backupName);
                }
            }catch (Exception e) {
                log.error("Failed to list {} from server {} e=", backupName, cfg.getExternalServerUrl(), e);
                throw BackupException.fatals.externalBackupServerError(backupName);
            }
        }

        log.info("The backup/restore status:{}", status);
        return status;
    }

    /**
     * This method returns a list of files on each node to be downloaded for specified tag
     * 
     * @param backupTag
     * @return backupFileSet,
     *         if its size() is 0, means can not find the backup set of specified tag;
     *         if it is not isValid(), means can not get enough files for specified tag.
     */
    public BackupFileSet getDownloadList(String backupTag) {
        BackupFileSet files = this.backupOps.listRawBackup(true);

        BackupFileSet filesForTag = files.subsetOf(backupTag, null, null);
        return filesForTag;
    }

    private InputStream getDownloadStream(final BackupFileSet files) throws IOException {
        final PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(pipeOut);

        Runnable runnable = new Runnable() {
            public void run() {
                try {
                    collectData(files, pipeOut);
                } catch (Exception ex) {
                    log.error("Exception when compressing", ex);
                    try {
                        pipeOut.close();
                    } catch (Exception ex2) {
                        log.error("Exception when terminating output", ex);
                    }
                }
            }
        };
        this.backupDownloader.submit(runnable);

        return pipeIn;
    }

    public void collectData(BackupFileSet files, OutputStream outStream) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(outStream);
        zos.setLevel(Deflater.BEST_SPEED);

        List<String> uniqueNodes = new ArrayList<String>();
        uniqueNodes.addAll(files.uniqueNodes());
        List<NodeInfo> nodes = ClusterNodesUtil.getClusterNodeInfo(uniqueNodes);
        if (nodes.size() < uniqueNodes.size()) {
            log.info("Only {}/{} nodes available for the backup, cannot download.", uniqueNodes.size(), nodes.size());
            return;
        }

        Collections.sort(nodes, new Comparator<NodeInfo>() {
            @Override
            public int compare(NodeInfo o1, NodeInfo o2) {
                return o1.getId().compareTo(o2.getId());
            }
        });

        URI postUri = SysClientFactory.URI_NODE_BACKUPS_DOWNLOAD;
        boolean propertiesFileFound = false;
        int collectFileCount = 0;
        int totalFileCount = files.size() * 2;
        String backupTag = files.first().tag;

        //upload *_info.properties file first
        for (final NodeInfo node : nodes) {
            String baseNodeURL = String.format(SysClientFactory.BASE_URL_FORMAT,
                    node.getIpAddress(), node.getPort());
            SysClientFactory.SysClient sysClient = SysClientFactory.getSysClient(
                    URI.create(baseNodeURL));
            try {
                String fileName = backupTag + BackupConstants.BACKUP_INFO_SUFFIX;
                String fullFileName = backupTag + File.separator + fileName;
                InputStream in = sysClient.post(postUri, InputStream.class, fullFileName);
                newZipEntry(zos, in, fileName);
                propertiesFileFound = true;
                break;
            } catch (Exception ex) {
                log.info("info.properties file is not found on node {}, exception {}", node.getId(), ex.getMessage());
            }
        }

        if (!propertiesFileFound) {
            throw new FileNotFoundException(String.format("No live node contains %s%s",
                    backupTag, BackupConstants.BACKUP_INFO_SUFFIX));
        }

        for (final NodeInfo node : nodes) {
            String baseNodeURL = String.format(SysClientFactory.BASE_URL_FORMAT,
                    node.getIpAddress(), node.getPort());
            SysClientFactory.SysClient sysClient = SysClientFactory.getSysClient(
                    URI.create(baseNodeURL));
            for (String fileName : getFileNameList(files.subsetOf(null, null, node.getId()))) {
                int progress = collectFileCount / totalFileCount * 100;
                backupScheduler.getUploadExecutor().setUploadStatus(null, Status.IN_PROGRESS, progress, null);

                String fullFileName = backupTag + File.separator + fileName;
                InputStream in = sysClient.post(postUri, InputStream.class, fullFileName);
                newZipEntry(zos, in, fileName);
                collectFileCount++;
            }
        }

        // We only close ZIP stream when everything is OK, or the package will be extractable but missing files.
        zos.close();

        log.info("Successfully generated ZIP package");
    }

    private List<String> getFileNameList(BackupFileSet files) {
        List<String> nameList = new ArrayList<>();

        for (BackupFile file : files) {
            String filename = file.info.getName();
            if (filename.endsWith(BackupConstants.BACKUP_INFO_SUFFIX)) {
                continue;
            }
            nameList.add(filename);
            nameList.add(filename + BackupConstants.MD5_SUFFIX);
        }

        Collections.sort(nameList, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                return o1.compareTo(o2);
            }
        });

        return nameList;
    }

    private void newZipEntry(ZipOutputStream zos, InputStream in, String name) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        log.info("zip entry: {}", name);
        int length = 0;
        byte[] buffer = new byte[102400];
        while ((length = in.read(buffer)) != -1) {
            zos.write(buffer, 0, length);
        }
        in.close();
        zos.closeEntry();
    }

    private void auditBackup(OperationTypeEnum auditType,
                            String operationalStatus,
                            String description,
                            Object... descparams) {
        this.auditMgr.recordAuditLog(null, null,
                BackupConstants.EVENT_SERVICE_TYPE,
                auditType,
                System.currentTimeMillis(),
                operationalStatus,
                description,
                descparams);
    }

    private List<String> getDescParams(final String tag) {
        final String nodeId = this.serviceinfo.getNodeId();
        return new ArrayList<String>() {
            {
                add(tag);
                add(nodeId);
                add(drUtil.getLocalSite().getName());
            }
        };
    }
    private BackupClient getExternalServerClient(SchedulerConfig cfg ) {
        if (ExternalServerType.CIFS.equals(cfg.getExternalServerType())) {
            return new CifsClient(cfg.getExternalServerUrl(), cfg.getExternalDomain(), cfg.getExternalServerUserName(), cfg.getExternalServerPassword());
        }else {
            return new FtpClient(cfg.getExternalServerUrl(), cfg.getExternalServerUserName(), cfg.getExternalServerPassword());
        }
    }

    /**
     *  Query backup operation related status
     *  @brief  Query backup operation related status
     *
     * @return backup operation status
     */
    @GET
    @Path("backup-status")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR, Role.RESTRICTED_SYSTEM_ADMIN })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public BackupOperationStatus getBackupOperationStatus() {
        log.info("Received get backup operation status request");
        try {
            BackupOperationStatus backupOperationStatus = backupOps.queryBackupOperationStatus();
            backupOperationStatus.setNextScheduledCreation(backupScheduler.getNextScheduledRunTime().getTime());
            return backupOperationStatus;
        } catch (Exception e) {
            log.error("Failed to get backup operation status", e);
            throw APIException.internalServerErrors.getObjectError("Operation status", e);
        }
    }
}
