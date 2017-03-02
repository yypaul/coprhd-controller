/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.management.backup;

import com.emc.storageos.coordinator.client.model.Constants;
import com.emc.storageos.management.backup.util.ZipUtil;
import com.google.common.base.Preconditions;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.emc.storageos.services.util.FileUtils.chown;
import static com.emc.storageos.services.util.FileUtils.chmod;

public class RestoreHandler {

    private static final Logger log = LoggerFactory.getLogger(RestoreHandler.class);

    private File rootDir;
    private File viprDataDir;
    private List<String> extraCleanDirs = new ArrayList<>();
    private File backupArchive;
    private boolean onlyRestoreSiteId;

    public RestoreHandler(String rootDir, String viprDataDir) {
        Preconditions.checkArgument(rootDir != null && viprDataDir != null,
                "ViPR data directory is not configured");
        this.rootDir = new File(rootDir);
        this.viprDataDir = new File(viprDataDir);
    }

    RestoreHandler() {
    }

    public void setOnlyRestoreSiteId(boolean onlyRestoreSiteId) {
        this.onlyRestoreSiteId = onlyRestoreSiteId;
    }

    /**
     * Sets root directory of ViPR db/zk
     * 
     * @param rootDir
     *            The path of ViPR db/zk root directory
     */
    void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    /**
     * Sets ViPR service data directory
     * 
     * @param viprDataDir
     *            The directory which saves ViPR service data
     */
    void setViprDataDir(File viprDataDir) {
        this.viprDataDir = viprDataDir;
    }

    /**
     * Sets extra directories which should be clean before restore
     * 
     * @param extraCleanDirs
     *            The extra clean directory list
     */
    public void setExtraCleanDirs(List<String> extraCleanDirs) {
        if (extraCleanDirs != null) {
            this.extraCleanDirs = extraCleanDirs;
        }
    }

    /**
     * Sets backup compress package
     * 
     * @param backupArchive
     *            The backup package
     */
    public void setBackupArchive(File backupArchive) {
        this.backupArchive = backupArchive;
    }

    /**
     * Purges ViPR data files before restore.
     */
    public void purge() throws Exception {
        if (!viprDataDir.getParentFile().exists()) {
            throw new FileNotFoundException(String.format(
                    "%s is not exist, please initialize ViPR first", viprDataDir.getParent()));
        }

        log.info("\tDelete: {}", viprDataDir.getAbsolutePath());

        try {
            FileUtils.deleteDirectory(viprDataDir);
            for (String fileName : extraCleanDirs) {
                log.info("\tDelete: {}", fileName);
                File file = new File(fileName);
                if (file.exists()) {
                    FileUtils.forceDelete(file);
                }
            }
        }catch (Exception e) {
            log.error("Purge data failed e=", e);
            throw e;
        }
    }

    /**
     * Uncompresses backup file into vipr data directory.
     */
    public void replace() throws IOException {
        replace(false);
    }

    /**
     * Uncompresses backup file into vipr data directory.
     */
    public void replace(final boolean geoRestoreFromScratch) throws IOException {
        String backupName = backupArchive.getName().substring(0,
                backupArchive.getName().lastIndexOf('.'));
        // Check reinit flag for multi vdc env
        checkReinit(backupName, geoRestoreFromScratch);
        final File tmpDir = new File(viprDataDir.getParentFile(), backupName);
        log.debug("Temporary backup folder: {}", tmpDir.getAbsolutePath());
        try {
            ZipUtil.unpack(backupArchive, viprDataDir.getParentFile());
            String backupType = backupName.split(BackupConstants.BACKUP_NAME_DELIMITER)[1];
            if (BackupType.zk.name().equalsIgnoreCase(backupType)) {
                replaceSiteIdFile(tmpDir);
            }

            if (onlyRestoreSiteId) {
                return;
            }

            tmpDir.renameTo(viprDataDir);

            //if there are more files in the data dir, chown may take more than 10 seconds to complete,
            //so set timeout to 1 minute
            chown(viprDataDir, BackupConstants.STORAGEOS_USER, BackupConstants.STORAGEOS_GROUP,60*1000);

            restoreDrivers();
        } finally {
            if (tmpDir.exists()) {
                FileUtils.deleteQuietly(tmpDir);
            }
        }
    }

    private void restoreDrivers() {
        File backupDriverDir = new File(viprDataDir, BackupConstants.DRIVERS_FOLDER_NAME);
        if (!backupDriverDir.exists() || !backupDriverDir.isDirectory()) {
            return;
        }
        File[] drivers = backupDriverDir.listFiles();
        if (drivers == null || drivers.length == 0) {
            return;
        }
        log.info("Found drivers in backup, prepare to restore drivers ...");
        File sysDriverDir = new File(BackupConstants.DRIVERS_DIR);
        for (File f : drivers) {
            try {
                chmod(f, BackupConstants.BACKUP_FILE_PERMISSION);
                FileUtils.moveFileToDirectory(f, sysDriverDir, true);
                log.info("Successfully restored driver file: {}", f.getName());
            } catch (IOException e) {
                log.error("Error happened when moving driver file {} from backup to data directory", f.getName(), e);
            }
        }
        try {
            FileUtils.deleteDirectory(backupDriverDir);
            log.info("Successfully deleted driver backup directory");
        } catch (IOException e) {
            log.error("Failed to delete tmp driver directory {}", backupDriverDir.getAbsolutePath(), e);
        }
        chown(sysDriverDir, BackupConstants.STORAGEOS_USER, BackupConstants.STORAGEOS_GROUP);
        chmod(sysDriverDir, BackupConstants.DRIVER_DIR_PERMISSION, false);
    }

    private void replaceSiteIdFile(File siteIdFileDir) throws IOException {
        log.info("Replacing site id file ...");
        File unpackedSiteIdFile = new File(siteIdFileDir, BackupConstants.SITE_ID_FILE_NAME);
        chown(unpackedSiteIdFile, BackupConstants.STORAGEOS_USER, BackupConstants.STORAGEOS_GROUP);
        FileUtils.moveFileToDirectory(unpackedSiteIdFile, rootDir, false);
    }

    /**
     * Checks reinit flag for (geo)db to pull data from remote vdc/nodes
     * 
     * @param backupName
     *            The name of backup file
     * @param geoRestoreFromScratch
     *            True if restore geodb from scratch, or else if false
     * @throws IOException
     */
    private void checkReinit(final String backupName, final boolean geoRestoreFromScratch) throws IOException {
        // Add reinit file for multi vdc geodb synchronization
        String backupType = backupName.split(BackupConstants.BACKUP_NAME_DELIMITER)[1];
        if (BackupType.geodbmultivdc.name().equalsIgnoreCase(backupType)) {
            log.info("This backup was taken in multi vdc scenario");
            boolean needReinit = geoRestoreFromScratch ? false : true;
            checkReinitFile(needReinit);
        }
    }

    /**
     * Checks reinit file according to argument needReinit
     * 
     * @param needReinit
     *            Need to add reinit marker or not
     * @throws IOException
     */
    public void checkReinitFile(final boolean needReinit) throws IOException {
        File bootModeFile = new File(rootDir, Constants.STARTUPMODE);
        if (!needReinit) {
            log.info("Reinit flag is false");
            if (bootModeFile.exists()) {
                bootModeFile.delete();
            }
            return;
        }
        if (!bootModeFile.exists()) {
            setDbStartupModeAsRestoreReinit(rootDir);
        }
        chown(bootModeFile, BackupConstants.STORAGEOS_USER, BackupConstants.STORAGEOS_GROUP);
        log.info("Startup mode file({}) has been created", bootModeFile.getAbsolutePath());
    }

    private void setDbStartupModeAsRestoreReinit(File dir) throws IOException {
        File bootModeFile = new File(dir, Constants.STARTUPMODE);
        try (OutputStream fos = new FileOutputStream(bootModeFile)) {
            Properties properties = new Properties();
            properties.setProperty(Constants.STARTUPMODE, Constants.STARTUPMODE_RESTORE_REINIT);
            properties.store(fos, null);
            log.info("Set startup mode as restore reinit under {} successful", dir);
        }
    }

}
