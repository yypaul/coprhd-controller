/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.systemservices.impl.jobs.backupscheduler;

import com.emc.storageos.management.backup.BackupConstants;
import com.emc.storageos.management.backup.BackupFileSet;
import com.emc.storageos.security.audit.AuditLogManager;
import com.emc.storageos.services.OperationTypeEnum;

import com.emc.storageos.services.util.TimeUtils;
import org.apache.commons.lang.StringUtils;
import com.emc.vipr.model.sys.backup.BackupUploadStatus;
import com.emc.vipr.model.sys.backup.BackupUploadStatus.Status;
import com.emc.vipr.model.sys.backup.BackupUploadStatus.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * This class uploads backups to user supplied external file server.
 */
public class UploadExecutor {
    private static final int UPLOAD_RETRY_TIMES = 3;
    private static final int UPLOAD_RETRY_DELAY_MS = 5000; // 5s

    private static final Logger log = LoggerFactory.getLogger(UploadExecutor.class);

    private BackupScheduler cli;
    protected SchedulerConfig cfg;
    protected Uploader uploader;
    private Set<String> pendingUploadTasks = new HashSet();

    public UploadExecutor(SchedulerConfig cfg, BackupScheduler cli) {
        this.cfg = cfg;
        this.cli = cli;
    }

    public void setUploader(Uploader uploader) {
        this.uploader = uploader;
    }

    public void upload() throws Exception {
        upload(null, false);
    }

    public void upload(String backupTag, boolean force) throws Exception {
        setUploader(Uploader.create(cfg, cli));
        if (this.uploader == null) {
            log.info("Upload URL is empty, upload disabled");
            return;
        }

        try (AutoCloseable lock = this.cfg.lock()) {
            this.cfg.reload();
            cleanupCompletedTags();
            doUpload(backupTag, force);
        } catch (Exception e) {
            log.error("Fail to run upload backup", e);
        }
    }

    /**
     * Try several times to upload a backup.
     * 
     * @param tag
     * @param force
     * @return null if succeeded, or error message from last retry if failed.
     * @throws InterruptedException
     */
    private String tryUpload(String tag, boolean force) throws InterruptedException {
        String lastErrorMessage = null;

        setUploadStatus(tag, Status.PENDING, null, null);
        for (int i = 0; i < UPLOAD_RETRY_TIMES; i++) {
            try {
                setUploadStatus(tag, Status.IN_PROGRESS, 0, null);

                log.info("To remove {} from pending upload tasks:{}", tag, pendingUploadTasks);

                pendingUploadTasks.remove(tag);
                BackupFileSet files = this.cli.getDownloadFiles(tag);
                if (files.isEmpty()) {
                    setUploadStatus(null, Status.FAILED, null, ErrorCode.BACKUP_NOT_EXIST);
                    return String.format("Cannot find target backup set '%s'.", tag);
                }
                if (!files.isValid()) {
                    setUploadStatus(null, Status.FAILED, null, ErrorCode.INVALID_BACKUP);
                    return "Cannot get enough files for specified backup";
                }

                String zipName = this.cli.generateZipFileName(tag, files);
                if (hasCompleteBackupFileOnServer(tag, zipName)) {
                    if (force) {
                        zipName = renameToSolveDuplication(zipName);
                    } else {
                        setUploadStatus(null, Status.FAILED, null, ErrorCode.REMOTE_ALREADY_EXIST);
                        return String.format("Backup(%s) already exist on external server", tag);
                    }
                }

                Long existingLen = uploader.getFileSize(zipName);
                long len = existingLen == null ? 0 : existingLen;
                log.info("Uploading {} at offset {}", tag, existingLen);
                try (OutputStream uploadStream = uploader.upload(zipName, len)) {
                    this.cli.uploadTo(files, len, uploadStream);
                }

                markIncompleteZipFileFinished(zipName, true);
                setUploadStatus(null, Status.DONE, 100, null);
                return null;
            } catch (Exception e) {
                lastErrorMessage = e.getMessage();
                if (lastErrorMessage == null || lastErrorMessage.isEmpty()) {
                    lastErrorMessage = e.getClass().getSimpleName();
                }
                log.warn(String.format("An attempt to upload backup %s is failed", tag), e);
            }

            Thread.sleep(UPLOAD_RETRY_DELAY_MS);
        }

        setUploadStatus(null, Status.FAILED, null, ErrorCode.UPLOAD_FAILURE);

        return lastErrorMessage;
    }

    private void doUpload(String backupTag, boolean force) throws Exception {
        log.info("Begin upload");

        List<String> toUpload = getWaitingUploads(backupTag);
        if (toUpload.isEmpty()) {
            log.info("No backups need to be uploaded");
            return;
        }

        List<String> succUploads = new ArrayList<>();
        List<String> failureUploads = new ArrayList<>();
        List<String> errMsgs = new ArrayList<>();

        for (String tag : toUpload) {
            String errMsg = tryUpload(tag, force);
            if (errMsg == null) {
                log.info("Upload backup {} to {} successfully", tag, uploader.cfg.uploadUrl);
                this.cfg.uploadedBackups.add(tag);
                this.cfg.persist();
                succUploads.add(tag);
            } else {
                log.error("Upload backup {} to {} failed", tag, uploader.cfg.uploadUrl);
                failureUploads.add(tag);
                errMsgs.add(errMsg);
            }
            this.cli.updateBackupUploadStatus(tag, TimeUtils.getCurrentTime(), (errMsg == null) ? true: false);
        }

        if (!succUploads.isEmpty()) {
            List<String> descParams = this.cli.getDescParams(StringUtils.join(succUploads, ", "));
            this.cli.auditBackup(OperationTypeEnum.UPLOAD_BACKUP,
                    AuditLogManager.AUDITLOG_SUCCESS, null, descParams.toArray());
        }
        if (!failureUploads.isEmpty()) {
            String failureTags = StringUtils.join(failureUploads, ", ");
            List<String> descParams = this.cli.getDescParams(failureTags);
            descParams.add(StringUtils.join(errMsgs, ", "));
            this.cli.auditBackup(OperationTypeEnum.UPLOAD_BACKUP,
                    AuditLogManager.AUDITLOG_FAILURE, null, descParams.toArray());
            log.info("Sending update failures to root user");
            this.cfg.sendUploadFailureToRoot(failureTags, StringUtils.join(errMsgs, "\r\n"));
        }
        log.info("Finish upload");
    }

    private List<String> getWaitingUploads(String backupTag) {
        List<String> toUpload = new ArrayList<String>();

        List<String> incompleteUploads = getIncompleteUploads();
        if (backupTag == null) {
            toUpload.addAll(incompleteUploads);
        } else {
            if(incompleteUploads.contains(backupTag)) {
                toUpload.add(backupTag);
            } else {
                log.info("Backup({}) has already been uploaded", backupTag);
            }
        }
        return toUpload;
    }

    private List<String> getIncompleteUploads() {
        List<String> toUpload = new ArrayList<>(this.cfg.retainedBackups.size());
        Set<String> allBackups = this.cli.getClusterBackupTags(true);
        allBackups.removeAll(ScheduledBackupTag.pickScheduledBackupTags(allBackups));
        allBackups.addAll(this.cfg.retainedBackups);
        for (String tagName : allBackups) {
            if (!this.cfg.uploadedBackups.contains(tagName)) {
                toUpload.add(tagName);
            }
        }

        log.info("Tags in retain list: {}, incomplete ones are: {}",
                this.cfg.retainedBackups.toArray(new String[this.cfg.retainedBackups.size()]),
                toUpload.toArray(new String[toUpload.size()]));

        return toUpload;
    }

    public void setUploadStatus(String backupTag, Status status, Integer progress, ErrorCode errorCode) {
        BackupUploadStatus uploadStatus = this.cfg.queryBackupUploadStatus();
        uploadStatus.update(backupTag, status, progress, errorCode);
        this.cfg.persistBackupUploadStatus(uploadStatus);
    }

    public BackupUploadStatus getUploadStatus(String backupTag, File backupDir) throws Exception {
        if (backupTag == null) {
            log.error("Query parameter of backupTag is null");
            throw new IllegalStateException("Invalid query parameter");
        }
        this.cfg.reload();
        log.info("Current uploaded backup list: {}", this.cfg.uploadedBackups);
        if (this.cfg.uploadedBackups.contains(backupTag)) {
            log.info("{} is in the uploaded backup list", backupTag);
            return new BackupUploadStatus(backupTag, Status.DONE, 100, null);
        }
        if (!getIncompleteUploads().contains(backupTag)) {
            File backup = new File(backupDir, backupTag);

            if (backup.exists()) {
                log.info("The {} will be reclaimed");
                return new BackupUploadStatus(backupTag, Status.FAILED, 0, ErrorCode.TO_BE_RECLAIMED);
            }

            return new BackupUploadStatus(backupTag, Status.FAILED, 0, ErrorCode.BACKUP_NOT_EXIST);
        }
        if (cfg.uploadUrl == null) {
            return new BackupUploadStatus(backupTag, Status.FAILED, 0, ErrorCode.FTP_NOT_CONFIGURED);
        }
        BackupUploadStatus uploadStatus = this.cfg.queryBackupUploadStatus();
        if (backupTag.equals(uploadStatus.getBackupName())) {
            return uploadStatus;
        }

        if (isPendingUploadTask(backupTag)) {
            return new BackupUploadStatus(backupTag, Status.PENDING, null, null);
        }

        return new BackupUploadStatus(backupTag, Status.NOT_STARTED, null, null);
    }

    public void addPendingUploadTask(String tagName) {
        pendingUploadTasks.add(tagName);
    }

    public boolean isPendingUploadTask(String tagName) {
        return pendingUploadTasks.contains(tagName);
    }

    /**
     * Some tags in completeTags may not exist on disk anymore, need to remove them from the list
     * to free up space in ZK.
     * 
     * @throws Exception
     */
    private void cleanupCompletedTags() throws Exception {
        boolean modified = false;
        // Get all tags by ignoring down nodes - tag is returned even found in only one node
        // This guarantees if quorum nodes say no such tag, the tag is invalid even exist in remaining nodes.
        Set<String> manualBackups = this.cli.getClusterBackupTags(true);
        manualBackups.removeAll(ScheduledBackupTag.pickScheduledBackupTags(manualBackups));

        // Auto and manual backups need be checked separately because for auto backups, it could be invalid
        // even it present in cluster, only those recorded in .retainedBackups are valid auto backups.
        for (String tag : new ArrayList<>(this.cfg.uploadedBackups)) {
            if (!this.cfg.retainedBackups.contains(tag) && !manualBackups.contains(tag)) {
                this.cfg.uploadedBackups.remove(tag);
                modified = true;
            }
        }

        if (modified) {
            this.cfg.persist();
        }
    }

    private boolean hasCompleteBackupFileOnServer(String backupTag, String toUploadedFileName) throws Exception {
        String prefix = backupTag + BackupConstants.UPLOAD_ZIP_FILE_NAME_DELIMITER;
        log.info("Check with prefix  {}", prefix);

        List<String> ftpFiles = uploader.listFiles(prefix);
        for (String file : ftpFiles) {
            if (isCompletedFile(file)) {
                log.warn("There is complete uploaded backup zip file on server already");
                return true;
            }
            // Mark invalid for stale incomplete backup file on server based on the input filename
            if (!isFullNodeFileName(toUploadedFileName) || !isFullNodeFileName(file)) {
                markIncompleteZipFileFinished(file, false);
                continue;
            }
            log.info("Found incomplete uploaded file:{}, will continue from the break point", file);
        }
        return false;
    }

    private boolean isFullNodeFileName(String fileName) {
        String[] nameSegs = fileName.split(BackupConstants.UPLOAD_ZIP_FILE_NAME_DELIMITER);
        String availableNodes = nameSegs[2];
        String allNodes = nameSegs[1];
        return allNodes.equals(availableNodes);
    }

    private boolean isCompletedFile(String fileName) {
        return fileName.endsWith(BackupConstants.COMPRESS_SUFFIX);
    }

    private void markIncompleteZipFileFinished(String fileName, boolean success) throws Exception {
        try {
            String suffix = success ? BackupConstants.COMPRESS_SUFFIX : BackupConstants.INVALID_COMPRESS_SUFFIX;
            String finishedName = fileName.replaceFirst(BackupConstants.INCOMPLETE_COMPRESS_SUFFIX + "$", suffix);
            uploader.rename(fileName, finishedName);
            log.warn("Marked the uploading backup zip file({}) as {}", fileName, (success ? "completed" : "invalid"));
        } catch (Exception e) {
            log.error("Failed to rename the uploading backup zip file({})", fileName, e);
            throw e;
        }
    }

    public static String toZipFileName(String tag, int totalNodes, int backupNodes, String siteName) {
        return String.format(BackupConstants.UPLOAD_ZIP_FILENAME_FORMAT,
                tag, totalNodes, backupNodes, siteName, BackupConstants.INCOMPLETE_COMPRESS_SUFFIX);
    }

    private String renameToSolveDuplication(String zipFileName) {
        return zipFileName.split(BackupConstants.INCOMPLETE_COMPRESS_SUFFIX)[0]
                + "(" + System.currentTimeMillis() + ")"
                + BackupConstants.INCOMPLETE_COMPRESS_SUFFIX + "$";
    }
}
