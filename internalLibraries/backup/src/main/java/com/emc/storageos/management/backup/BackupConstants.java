/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.management.backup;

import java.io.File;

/**
 * Constants for backup project
 */
public interface BackupConstants {

    // quota limit for backup directory
    public static final String QUOTA = "quota";

    public static final String BACKUP_UPLOAD_STATUS = "uploadStatus";

    // These are internal state tracking entries under
    public static final String BACKUP_TAGS_RETAINED = "retained_tags";
    public static final String BACKUP_TAGS_UPLOADED = "uploaded_tags";

    // These are backup related configurations in system properties
    public static final String BACKUP_MAX_USED_DISK_PERCENTAGE = "backup_max_used_disk_percentage";
    public static final String BACKUP_THRESHOLD_DISK_PERCENTAGE = "backup_threshold_disk_percentage";

    // These are manual backup configurations in system properties
    public static final String BACKUP_MAX_MANUAL_COPIES = "backup_max_manual_copies";

    // These are Backup Scheduler related configurations in system properties
    public static final String SCHEDULER_ENABLED = "backup_scheduler_enable";
    public static final String SCHEDULE_INTERVAL = "backup_scheduler_interval";
    public static final String SCHEDULE_TIME = "backup_scheduler_time";
    public static final String COPIES_TO_KEEP = "backup_scheduler_copies_to_keep";
    public static final String UPLOAD_SERVER_TYPE= "backup_external_server_type";
    public static final String UPLOAD_SERVER_DOMAIN = "backup_external_location_domain";
    public static final String UPLOAD_URL = "backup_external_location_url";
    public static final String UPLOAD_USERNAME = "backup_external_location_username";
    public static final String UPLOAD_PASSWD = "backup_external_location_password";
    public static final int DEFAULT_BACKUP_COPIES_TO_KEEP = 5;

    public static final int BACKUP_RETRY_COUNT = 3;

    // These are backup operation status related keys in ZK configuration
    public static final String LAST_MANUAL_CREATION="lastManualCreation";
    public static final String LAST_SCHEDULED_CREATION="lastScheduledCreation";
    public static final String LAST_SUCCESSFUL_CREATION="lastSuccessfulCreation";
    public static final String LAST_UPLOAD="lastUpload";
    public static final String NEXT_SCHEDULED_CREATION="nextScheduledCreation";
    public static final String OPERATION_NAME="name";
    public static final String OPERATION_TIME="time";
    public static final String OPERATION_MESSAGE="msg";
    public static final String BACKUP_OPERATION_STATUS_KEY_FORMAT = "%s_%s";

    // The sleep time for scheduler when the cluster is upgrading
    public static final int SCHEDULER_SLEEP_TIME_FOR_UPGRADING = 10 * 60 * 1000;

    public static final String SCHEDULED_BACKUP_DATE_PATTERN = "yyyyMMddHHmmss";
    public static final String SCHEDULED_BACKUP_TAG_REGEX_PATTERN = "^%s-[0-9]\\.[0-9].*-\\d+-\\d{%d}$";

    // Number of Gigabyte compare to byte
    public static final long GIGABYTE = 1024 * 1024 * 1024;

    // Number of Megabyte compare to byte
    public static final long MEGABYTE = 1024 * 1024;

    // Number of Kilobyte compare to byte
    public static final int KILOBYTE = 1024;

    // Delimiter for backup file name
    public static final String BACKUP_NAME_DELIMITER = "_";
    public static final String SCHEDULED_BACKUP_TAG_DELIMITER = "-";
    public static final String UPLOAD_ZIP_FILE_NAME_DELIMITER = "_";

    // Backup related name format
    public static final String SCHEDULED_BACKUP_TAG_TEMPLATE = "%s-%d-%s";
    public static final String UPLOAD_ZIP_FILENAME_FORMAT = "%s_%s_%s_%s%s"; //tag_totalNodes_availableNodes_siteId.COMPRESS_SUFFIX

    // Backup compress format
    public static final String COMPRESS_SUFFIX = ".zip";
    public static final String INVALID_COMPRESS_SUFFIX = ".zip.invalid";
    public static final String INCOMPLETE_COMPRESS_SUFFIX = ".zip.tmp";

    // Backup retry max count
    public static final int RETRY_MAX_CNT = 3;

    // Standard date string format
    public static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String SCHEDULED_BACKUP_DATE_FORMAT = "yyyyMMddHHmmss";

    // Dynamic string format of listing backups
    public static final String LIST_BACKUP_TITLE = "  %%-%ds%%-10s%%-20s";
    public static final String LIST_BACKUP_INFO = "  %%-%ds%%-10.2f%%-20s";
    public static final String COLLECTED_BACKUP_REGEX_PATTERN = "^(\\S+)*-\\d+-\\d+-(\\S+)*\\.zip$";
    public static final String SCHEDULED_BACKUP_DATE_REGEX_PATTERN = "^\\d{%d}$";

    // The common part of backup info file name
    public static final String BACKUP_INFO_SUFFIX = BACKUP_NAME_DELIMITER + "info.properties";
    public static final String BACKUP_ZK_FILE_SUFFIX = BACKUP_NAME_DELIMITER + "zk.zip";
    public static final String BACKUP_INFO_VERSION = "version";
    public static final String BACKUP_INFO_HOSTS = "hosts";
    public static final String BACKUP_INFO_CREATE_TIME = "time";
    public static final String BACKUP_INFO_SITE_ID="siteID";
    public static final String BACKUP_INFO_SITE_NAME="siteName";
    public static final String EVENT_SERVICE_TYPE = "backup";
    public static final String BACKUP_LEADER_PATH = "backupleader";
    public static final long BACKUP_WAINT_BEFORE_RETRY_ZK_CONN = 1000L;

    // Delimiter for host IPv4 and IPv6 in _info.properties file
    public static final String HOSTS_IP_DELIMITER = "/";

    public static final String STORAGEOS_USER = "storageos";
    public static final String STORAGEOS_GROUP = "storageos";

    public static final File[] EMPTY_ARRAY = {};

    String FTPS_URL_PREFIX = "ftps://";
    String FTP_URL_PREFIX = "ftp://";
    String SMB_URL_PREFIX = "smb://";
    String CONTENT_LENGTH_HEADER = "Content-Length:";
    int FILE_DOES_NOT_EXIST = 19;
    String MD5_SUFFIX = ".md5";
    int LOCK_TIMEOUT = 1000;

    //constants for restore
    int DOWNLOAD_BUFFER_SIZE=0x20000;

    // The directory to persist downloaded backup files from FTP server
    String RESTORE_DIR= "/data/restore";

    String PULL_RESTORE_STATUS = "pull-restore-status";
    String LOCAL_RESTORE_KIND_PREFIX= PULL_RESTORE_STATUS + "/local";
    String REMOTE_RESTORE_KIND_PREFIX= PULL_RESTORE_STATUS + "/remote";
    String DOWNLOAD_OWNER_SUFFIX="/downloaders";
    String RESTORE_LOCK="restore";
    String RESTORE_STATUS_UPDATE_LOCK="restore-status-update";
    String BACKUP_LOCK = "backup";
    String CURRENT_DOWNLOADING_BACKUP_NAME_KEY="name";
    String CURRENT_DOWNLOADING_BACKUP_ISLOCAL_KEY="isLocal";

    public String CASSANDRA_CF_NAME_DELIMITER = "-";
    public static final String SITE_ID_FILE_NAME = "siteid";
    public static final int SYSTOOL_TIMEOUT_MILLIS = 120000; // 2 min
    public static final String VDC_PROPS_FILE_NAME = "vdcconfig.properties";

    public static final String DRIVERS_FOLDER_NAME = "drivers";
    public static final String DRIVERS_DIR = "/data/drivers";
    public static final String BACKUP_FILE_PERMISSION = "644";
    public static final String DRIVER_DIR_PERMISSION = "755";
}
