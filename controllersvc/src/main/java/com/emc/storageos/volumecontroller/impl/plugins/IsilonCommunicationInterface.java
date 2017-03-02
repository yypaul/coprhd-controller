/*
 * Copyright (c) 2008-2013 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.plugins;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.emc.storageos.customconfigcontroller.CustomConfigConstants;
import com.emc.storageos.customconfigcontroller.DataSource;
import com.emc.storageos.customconfigcontroller.DataSourceFactory;
import com.emc.storageos.customconfigcontroller.impl.CustomConfigHandler;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.CifsServerMap;
import com.emc.storageos.db.client.model.CustomConfig;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.DiscoveredDataObject.DiscoveryStatus;
import com.emc.storageos.db.client.model.DiscoveredDataObject.RegistrationStatus;
import com.emc.storageos.db.client.model.DiscoveredDataObject.Type;
import com.emc.storageos.db.client.model.FileShare;
import com.emc.storageos.db.client.model.NASServer;
import com.emc.storageos.db.client.model.NasCifsServer;
import com.emc.storageos.db.client.model.PhysicalNAS;
import com.emc.storageos.db.client.model.QuotaDirectory;
import com.emc.storageos.db.client.model.Stat;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StoragePool.CopyTypes;
import com.emc.storageos.db.client.model.StoragePool.PoolServiceType;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StorageSystem.SupportedFileReplicationTypes;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.VirtualNAS;
import com.emc.storageos.db.client.model.VirtualNAS.VirtualNasState;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedCifsShareACL;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedFSExport;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedFSExportMap;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedFileExportRule;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedFileQuotaDirectory;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedFileSystem;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedNFSShareACL;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedSMBFileShare;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedSMBShareMap;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.emc.storageos.isilon.restapi.IsilonAccessZone;
import com.emc.storageos.isilon.restapi.IsilonApi;
import com.emc.storageos.isilon.restapi.IsilonApi.IsilonLicenseType;
import com.emc.storageos.isilon.restapi.IsilonApi.IsilonList;
import com.emc.storageos.isilon.restapi.IsilonApiFactory;
import com.emc.storageos.isilon.restapi.IsilonClusterConfig;
import com.emc.storageos.isilon.restapi.IsilonException;
import com.emc.storageos.isilon.restapi.IsilonExport;
import com.emc.storageos.isilon.restapi.IsilonNFSACL;
import com.emc.storageos.isilon.restapi.IsilonNetworkPool;
import com.emc.storageos.isilon.restapi.IsilonPool;
import com.emc.storageos.isilon.restapi.IsilonSMBShare;
import com.emc.storageos.isilon.restapi.IsilonSmartConnectInfo;
import com.emc.storageos.isilon.restapi.IsilonSmartConnectInfoV2;
import com.emc.storageos.isilon.restapi.IsilonSmartQuota;
import com.emc.storageos.isilon.restapi.IsilonSnapshot;
import com.emc.storageos.isilon.restapi.IsilonSshApi;
import com.emc.storageos.isilon.restapi.IsilonStoragePort;
import com.emc.storageos.plugins.AccessProfile;
import com.emc.storageos.plugins.BaseCollectionException;
import com.emc.storageos.plugins.common.Constants;
import com.emc.storageos.plugins.metering.isilon.IsilonCollectionException;
import com.emc.storageos.util.VersionChecker;
import com.emc.storageos.volumecontroller.FileControllerConstants;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;
import com.emc.storageos.volumecontroller.impl.StoragePortAssociationHelper;
import com.emc.storageos.volumecontroller.impl.plugins.metering.CassandraInsertion;
import com.emc.storageos.volumecontroller.impl.plugins.metering.ZeroRecordGenerator;
import com.emc.storageos.volumecontroller.impl.plugins.metering.file.FileDBInsertion;
import com.emc.storageos.volumecontroller.impl.plugins.metering.file.FileZeroRecordGenerator;
import com.emc.storageos.volumecontroller.impl.plugins.metering.isilon.IsilonStatsRecorder;
import com.emc.storageos.volumecontroller.impl.plugins.metering.smis.processor.MetricsKeys;
import com.emc.storageos.volumecontroller.impl.utils.DiscoveryUtils;
import com.emc.storageos.volumecontroller.impl.utils.ImplicitPoolMatcher;
import com.emc.storageos.volumecontroller.impl.utils.UnManagedExportVerificationUtility;
import com.google.common.base.Joiner;
import com.google.common.collect.Sets;

/**
 * Class for Isilon discovery and collecting stats from Isilon storage device
 */
public class IsilonCommunicationInterface extends ExtendedCommunicationInterfaceImpl {
    private final Logger _log = LoggerFactory.getLogger(IsilonCommunicationInterface.class);
    private static final String POOL_TYPE = "IsilonNodePool";
    private static final int BYTESCONVERTER = 1024;
    private static final int PATH_IS_FILE =1;
    private static final int PATH_IS_QUOTA =2;
    private static final int PATH_IS_INVALID=3;
    
    private static final String UNMANAGED_EXPORT_RULE = "UnManagedExportRule";
    private static final String UNMANAGED_SHARE_ACL = "UnManagedCifsShareACL";
    private static final String IFS_ROOT = "/ifs";
    private static final String SOS_DIR = "sos";
    private static final String QUOTA = "quota";
    private static final String TRUE = "true";
    private static final String FALSE = "false";
    private static final String NEW = "new";
    private static final String EXISTING = "existing";
    private static final String RO = "ro";
    private static final String RW = "rw";
    private static final String ROOT = "root";
    private static final String NFS = "NFS";
    private static final String CIFS = "CIFS";
    private static final String UNIXSECURITY = "unix";
    private static final Integer MAX_UMFS_RECORD_SIZE = 1000;
    private static final String SYSSECURITY = "sys";
    private static final String NFSv4 = "NFSv4";
    private static final String UMFS_DETAILS="FS_DETAILS";
    private static final String UMFSQD_DETAILS="UMFSQD_DETAILS";
    private static final String INITIAL_PATH="/ifs/accesszone/";

    private static final Long MAX_NFS_EXPORTS_V7_2 = 1500L;
    private static final Long MAX_CIFS_SHARES = 40000L;
    private static final Long MAX_STORAGE_OBJECTS = 40000L;
    private static final String SYSTEM_ACCESS_ZONE_NAME = "System";
    private static final Long GB_IN_BYTES = 1073741824L;
    private static final Long GB_IN_KB = 1048576L;
    private static final Long MB_IN_BYTES = 1048576L;
    private static final Long KB_IN_BYTES = 1024L;
    private static final String ONEFS_V8 = "8.0.0.0";
    private static final String ONEFS_V7_2 = "7.2.0.0";
    private static final String ACTIVATED = "Activated";
    private static final String EVALUATION = "Evaluation";
    private IsilonApiFactory _factory;
    private static final String LICENSE_ACTIVATED = "Activated";
    private static final String LICENSE_EVALUATION = "Evaluation";
    private static final String CHECKPOINT_SCHEDULE = "checkpoint_schedule";
    private static final String ISILON_PATH_CUSTOMIZATION = "IsilonPathCustomization";

    private List<String> _discPathsForUnManaged;
    private int _discPathsLength;
    private static String _discCustomPath;
    @Autowired
    private CustomConfigHandler customConfigHandler;
    @Autowired
    private DataSourceFactory dataSourceFactory;

    /**
     * Get Unmanaged File System Container paths
     * 
     * @return List object
     */
    public List<String> getDiscPathsForUnManaged() {
        if (null == _discPathsForUnManaged) {
            _discPathsForUnManaged = new ArrayList<String>();

        }
        return _discPathsForUnManaged;
    }

    /**
     * Set Unmanaged File System Container paths
     * 
     * @param ;discPathsForUnManaged
     */
    public void setDiscPathsForUnManaged(List<String> discPathsForUnManaged) {
        this._discPathsForUnManaged = discPathsForUnManaged;
    }

    /**
     * Set Isilon API factory
     * 
     * @param ;factory
     */
    public void setIsilonApiFactory(IsilonApiFactory factory) {
        _factory = factory;
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
     * Set the dataSource info
     * 
     * @return
     */
    public void setDataSourceFactory(DataSourceFactory dataSourceFactory) {
        this.dataSourceFactory = dataSourceFactory;
    }

    /**
     * Get isilon device represented by the StorageDevice
     * 
     * @param accessProfile
     *            StorageDevice object
     * @return IsilonApi object
     * @throws IsilonException
     * @throws URISyntaxException
     */
    private IsilonApi getIsilonDevice(AccessProfile accessProfile) throws IsilonException, URISyntaxException {
        URI deviceURI = new URI("https", null, accessProfile.getIpAddress(), accessProfile.getPortNumber(), "/", null, null);
        // if no username, assume its the isilon simulator device
        if (accessProfile.getUserName() != null && !accessProfile.getUserName().isEmpty()) {
            return _factory
                    .getRESTClient(deviceURI, accessProfile.getUserName(), accessProfile.getPassword());
        } else {
            return _factory.getRESTClient(deviceURI);
        }
    }

    /**
     * Get isilon device represented by the StorageDevice
     * 
     * @param isilonCluster
     *            StorageDevice object
     * @return IsilonApi object
     * @throws IsilonException
     * @throws URISyntaxException
     */
    private IsilonApi getIsilonDevice(StorageSystem isilonCluster) throws IsilonException, URISyntaxException {
        URI deviceURI = new URI("https", null, isilonCluster.getIpAddress(), isilonCluster.getPortNumber(), "/", null, null);

        return _factory
                .getRESTClient(deviceURI, isilonCluster.getUsername(), isilonCluster.getPassword());
    }

    @Override
    public void collectStatisticsInformation(AccessProfile accessProfile)
            throws BaseCollectionException {
        URI storageSystemId = null;
        StorageSystem isilonCluster = null;
        long statsCount = 0;
        try {
            _log.info("Metering for {} using ip {}", accessProfile.getSystemId(),
                    accessProfile.getIpAddress());
            IsilonApi api = getIsilonDevice(accessProfile);
            long latestSampleTime = accessProfile.getLastSampleTime();
            storageSystemId = accessProfile.getSystemId();
            isilonCluster = _dbClient.queryObject(StorageSystem.class, storageSystemId);
            String serialNumber = isilonCluster.getSerialNumber();
            String deviceType = isilonCluster.getSystemType();
            initializeKeyMap(accessProfile);
            boolean fsChanged = false;
            List<Stat> stats = new ArrayList<Stat>();

            ZeroRecordGenerator zeroRecordGenerator = new FileZeroRecordGenerator();
            CassandraInsertion statsColumnInjector = new FileDBInsertion();
            // get usage stats from quotas
            IsilonStatsRecorder recorder = new IsilonStatsRecorder(zeroRecordGenerator, statsColumnInjector);
            _keyMap.put(Constants._TimeCollected, System.currentTimeMillis());

            // compute static load processor code
            computeStaticLoadMetrics(storageSystemId);

            // get first page of quota data, process and insert to database
            IsilonApi.IsilonList<IsilonSmartQuota> quotas = api.listQuotas(null);
            for (IsilonSmartQuota quota : quotas.getList()) {
                String fsNativeId = quota.getPath();
                String fsNativeGuid = NativeGUIDGenerator.generateNativeGuid(deviceType, serialNumber, fsNativeId);
                Stat stat = recorder.addUsageStat(quota, _keyMap, fsNativeGuid, api);
                fsChanged = false;
                if (null != stat) {
                    stats.add(stat);
                    // Persists the file system, only if change in used capacity.
                    FileShare fileSystem = _dbClient.queryObject(FileShare.class, stat.getResourceId());
                    if (fileSystem != null) {
                        if (!fileSystem.getInactive()) {
                            if (fileSystem.getUsedCapacity() != stat.getAllocatedCapacity()) {
                                fileSystem.setUsedCapacity(stat.getAllocatedCapacity());
                                fsChanged = true;
                            }
                            if (null != fileSystem.getSoftLimit()) { // if softlimit is set then get the value for
                                                                     // softLimitExceeded
                                fileSystem.setSoftLimitExceeded(quota.getThresholds().getsoftExceeded());
                                fsChanged = true;
                            }
                            if (fsChanged) {
                                _dbClient.updateObject(fileSystem);
                            }
                        }
                    }
                }
            }
            persistStatsInDB(stats);
            statsCount = statsCount + quotas.size();
            _log.info("Processed {} file system stats for device {} ", quotas.size(), storageSystemId);

            // get all other pages of quota data, process and insert to database page by page
            while (quotas.getToken() != null && !quotas.getToken().isEmpty()) {
                quotas = api.listQuotas(quotas.getToken());
                for (IsilonSmartQuota quota : quotas.getList()) {
                    String fsNativeId = quota.getPath();
                    String fsNativeGuid = NativeGUIDGenerator.generateNativeGuid(deviceType, serialNumber, fsNativeId);
                    Stat stat = recorder.addUsageStat(quota, _keyMap, fsNativeGuid, api);
                    fsChanged = false;
                    if (null != stat) {
                        stats.add(stat);
                        // Persists the file system, only if change in used capacity.
                        FileShare fileSystem = _dbClient.queryObject(FileShare.class, stat.getResourceId());
                        if (fileSystem != null) {
                            if (!fileSystem.getInactive()) {
                                if (fileSystem.getUsedCapacity() != stat.getAllocatedCapacity()) {
                                    fileSystem.setUsedCapacity(stat.getAllocatedCapacity());
                                    fsChanged = true;
                                }
                                if (null != fileSystem.getSoftLimit()) {
                                    fileSystem.setSoftLimitExceeded(quota.getThresholds().getsoftExceeded());
                                    fsChanged = true;
                                }
                                if (fsChanged) {
                                    _dbClient.updateObject(fileSystem);
                                }
                            }
                        }
                    }
                }
                statsCount = statsCount + quotas.size();
                _log.info("Processed {} file system stats for device {} ", quotas.size(), storageSystemId);
            }
            zeroRecordGenerator.identifyRecordstobeZeroed(_keyMap, stats, FileShare.class);
            persistStatsInDB(stats);
            latestSampleTime = System.currentTimeMillis();
            accessProfile.setLastSampleTime(latestSampleTime);
            _log.info("Done metering device {}, processed {} file system stats ", storageSystemId, statsCount);
        } catch (Exception e) {
            if (isilonCluster != null) {
                cleanupDiscovery(isilonCluster);
            }
            _log.error("CollectStatisticsInformation failed. Storage system: " + storageSystemId, e);
            throw (new IsilonCollectionException(e.getMessage()));
        }
    }

    private void computeStaticLoadMetrics(final URI storageSystemId) throws BaseCollectionException {
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storageSystemId);

        _log.info("started computeStaticLoadMetrics for storagesystem: {}", storageSystem.getLabel());
        StringMap dbMetrics = null;
        String accessZoneId = null;
        try {
            IsilonApi isilonApi = getIsilonDevice(storageSystem);
            VirtualNAS virtualNAS = null;
            // //step-1 process the dbmetrics for user define access zones
            List<IsilonAccessZone> accessZoneList = isilonApi.getAccessZones(null);
            for (IsilonAccessZone isAccessZone : accessZoneList) {
                accessZoneId = isAccessZone.getZone_id().toString();
                // get the total fs count and capacity for AZ
                if (isAccessZone.isSystem() != true) {
                    virtualNAS = findvNasByNativeId(storageSystem, accessZoneId);
                    if (virtualNAS != null) {
                        _log.info("Process db metrics for access zone : {}", isAccessZone.getName());
                        dbMetrics = virtualNAS.getMetrics();
                        if (dbMetrics == null) {
                            dbMetrics = new StringMap();
                        }
                        // process db metrics
                        populateDbMetricsAz(isAccessZone, isilonApi, dbMetrics);

                        // set AZ dbMetrics in db
                        virtualNAS.setMetrics(dbMetrics);
                        _dbClient.updateObject(virtualNAS);
                    }
                } else {
                    PhysicalNAS physicalNAS = findPhysicalNasByNativeId(storageSystem, accessZoneId);
                    if (physicalNAS == null) {
                        _log.error(String.format("computeStaticLoadMetrics is failed for  Storagesystemid: %s", storageSystemId));
                        return;
                    }
                    dbMetrics = physicalNAS.getMetrics();
                    if (dbMetrics == null) {
                        dbMetrics = new StringMap();
                    }
                    /* process the system accesszone dbmetrics */
                    _log.info("process db metrics for access zone : {}", isAccessZone.getName());
                    populateDbMetricsAz(isAccessZone, isilonApi, dbMetrics);
                    physicalNAS.setMetrics(dbMetrics);
                    _dbClient.updateObject(physicalNAS);
                }
            }
        } catch (Exception e) {
            _log.error("CollectStatisticsInformation failed. Storage system: " + storageSystemId, e);
        }
    }

    /**
     * process dbmetrics for total count and capacity
     * 
     * @param azName
     * @param isilonApi
     * @param dbMetrics
     */
    private void populateDbMetricsAz(final IsilonAccessZone accessZone, IsilonApi isilonApi, StringMap dbMetrics) {

        long totalProvCap = 0L;
        long totalFsCount = 0L;
        String resumeToken = null;
        String zoneName = accessZone.getName();
        String baseDirPath = accessZone.getPath() + "/";

        // filesystems count & used Capacity
        IsilonList<IsilonSmartQuota> quotas = null;

        do {
            quotas = isilonApi.listQuotas(resumeToken, baseDirPath);

            if (quotas != null && !quotas.getList().isEmpty()) {
                for (IsilonSmartQuota quota : quotas.getList()) {

                    totalProvCap = totalProvCap + quota.getUsagePhysical();
                    totalFsCount++;
                }
            }
            resumeToken = quotas.getToken();

        } while (resumeToken != null);

        // create a list of access zone for which base dir is not same as system access zone.
        // we get all snapshot list at once. baseDirPaths list is used to
        // find snaphot belong to which access zone.
        List<String> baseDirPaths = null;
        if (accessZone.isSystem() == true) {
            List<IsilonAccessZone> isilonAccessZoneList = isilonApi.getAccessZones(resumeToken);
            baseDirPaths = new ArrayList<String>();
            for (IsilonAccessZone isiAccessZone : isilonAccessZoneList) {

                if (!baseDirPath.equals(IFS_ROOT + "/")) {
                    baseDirPaths.add(isiAccessZone.getPath() + "/");
                }
            }
        }
        // snapshots count & snap capacity
        resumeToken = null;
        IsilonList<IsilonSnapshot> snapshots = null;
        do {
            snapshots = isilonApi.listSnapshots(resumeToken);
            if (snapshots != null && !snapshots.getList().isEmpty()) {

                if (!baseDirPath.equals(IFS_ROOT + "/")) {
                    // if it is not system access zone then compare
                    // with fs path with base dir path
                    _log.info("access zone base directory path {}", baseDirPath);
                    for (IsilonSnapshot isilonSnap : snapshots.getList()) {
                        if (isilonSnap.getPath().startsWith(baseDirPath)) {
                            totalProvCap = totalProvCap + Long.valueOf(isilonSnap.getSize());
                            totalFsCount++;
                        }
                    }
                } else {// process the snapshots for system access zone
                    boolean snapSystem = true;
                    for (IsilonSnapshot isilonSnap : snapshots.getList()) {
                        snapSystem = true;
                        // first check fs path with user defined AZ's paths
                        if (baseDirPaths != null && !baseDirPaths.isEmpty()) {
                            for (String basePath : baseDirPaths) {
                                if (isilonSnap.getPath().startsWith(basePath)) {
                                    snapSystem = false;
                                    break;
                                }
                            }
                        }

                        // it then it is belongs to access zone with basedir same as system access zone.
                        if (snapSystem) {
                            totalProvCap = totalProvCap + Long.valueOf(isilonSnap.getSize());
                            totalFsCount++;
                            _log.info("Access zone base directory path: {}", accessZone.getPath());

                        }
                    }
                }
                resumeToken = snapshots.getToken();
            }
        } while (resumeToken != null);

        if (totalProvCap > 0) {
            totalProvCap = (totalProvCap / KB_IN_BYTES);
        }
        _log.info("Total fs Count {} for access zone : {}", String.valueOf(totalFsCount), accessZone.getName());
        _log.info("Total fs Capacity {} for access zone : {}", String.valueOf(totalProvCap), accessZone.getName());

        // get total exports
        int nfsExportsCount = 0;
        int cifsSharesCount = 0;
        resumeToken = null;
        IsilonList<IsilonExport> isilonNfsExports = null;
        do {
            isilonNfsExports = isilonApi.listExports(resumeToken, zoneName);
            if (isilonNfsExports != null) {
                nfsExportsCount = nfsExportsCount + isilonNfsExports.size();
                resumeToken = isilonNfsExports.getToken();
            }
        } while (resumeToken != null);
        _log.info("Total NFS exports {} for access zone : {}", String.valueOf(nfsExportsCount), accessZone.getName());

        // get cifs exports for given access zone
        resumeToken = null;
        IsilonList<IsilonSMBShare> isilonCifsExports = null;
        do {
            isilonCifsExports = isilonApi.listShares(resumeToken, zoneName);
            if (isilonCifsExports != null) {
                cifsSharesCount = cifsSharesCount + isilonCifsExports.size();
                resumeToken = isilonCifsExports.getToken();
            }
        } while (resumeToken != null);
        _log.info("Total CIFS sharess {} for access zone : {}", String.valueOf(cifsSharesCount), accessZone.getName());

        if (dbMetrics == null) {
            dbMetrics = new StringMap();
        }
        // set total nfs and cifs exports for give AZ
        dbMetrics.put(MetricsKeys.totalNfsExports.name(), String.valueOf(nfsExportsCount));
        dbMetrics.put(MetricsKeys.totalCifsShares.name(), String.valueOf(cifsSharesCount));
        // set total fs objects and their sum of capacity for give AZ
        dbMetrics.put(MetricsKeys.storageObjects.name(), String.valueOf(totalFsCount));
        dbMetrics.put(MetricsKeys.usedStorageCapacity.name(), String.valueOf(totalProvCap));

        Long maxExports = MetricsKeys.getLong(MetricsKeys.maxNFSExports, dbMetrics) +
                MetricsKeys.getLong(MetricsKeys.maxCifsShares, dbMetrics);
        Long maxStorObjs = MetricsKeys.getLong(MetricsKeys.maxStorageObjects, dbMetrics);
        Long maxCapacity = MetricsKeys.getLong(MetricsKeys.maxStorageCapacity, dbMetrics);

        Long totalExports = Long.valueOf(nfsExportsCount + cifsSharesCount);
        // setting overLoad factor (true or false)
        String overLoaded = FALSE;
        if (totalExports >= maxExports || totalProvCap >= maxCapacity || totalFsCount >= maxStorObjs) {
            overLoaded = TRUE;
        }

        double percentageLoadExports = 0.0;
        // percentage calculator
        if (totalExports > 0.0) {
            percentageLoadExports = ((double) (totalExports) / maxExports) * 100;
        }
        double percentageLoadStorObj = ((double) (totalProvCap) / maxCapacity) * 100;
        double percentageLoad = (percentageLoadExports + percentageLoadStorObj) / 2;

        dbMetrics.put(MetricsKeys.percentLoad.name(), String.valueOf(percentageLoad));
        dbMetrics.put(MetricsKeys.overLoaded.name(), overLoaded);
        return;
    }

    /**
     * Dump records on disk & persist the records in db.
     */
    private void persistStatsInDB(List<Stat> stats) throws BaseCollectionException {
        if (!stats.isEmpty()) {
            _keyMap.put(Constants._Stats, stats);
            dumpStatRecords();
            // Persist in db after processing the paged data.
            injectStats();
            // clear collection as we have already persisted in db.
            stats.clear();
        }
    }

    /**
     * populate keyMap with required attributes.
     */
    private void initializeKeyMap(AccessProfile accessProfile) {
        _keyMap.put(Constants.dbClient, _dbClient);
        _keyMap.put(Constants.ACCESSPROFILE, accessProfile);
        _keyMap.put(Constants.PROPS, accessProfile.getProps());
        _keyMap.put(Constants._serialID, accessProfile.getserialID());
        _keyMap.put(Constants._nativeGUIDs, Sets.newHashSet());
    }

    @Override
    public void discover(AccessProfile accessProfile) throws BaseCollectionException {
        _log.info("Access Profile Details :  IpAddress : PortNumber : {}, namespace : {}",
                accessProfile.getIpAddress() + ":" + accessProfile.getPortNumber(),
                accessProfile.getnamespace());

        if ((null != accessProfile.getnamespace())
                && (accessProfile.getnamespace()
                        .equals(StorageSystem.Discovery_Namespaces.UNMANAGED_FILESYSTEMS
                                .toString()))) {
            discoverUmanagedFileSystems(accessProfile);
        } else {
            discoverAll(accessProfile);
        }
    }

    public void discoverAll(AccessProfile accessProfile) throws BaseCollectionException {
        URI storageSystemId = null;
        StorageSystem storageSystem = null;
        String detailedStatusMessage = "Unknown Status";

        try {
            storageSystemId = accessProfile.getSystemId();
            storageSystem = _dbClient.queryObject(StorageSystem.class, storageSystemId);

            // try to connect to the Isilon cluster first to check if cluster is available
            IsilonApi isilonApi = getIsilonDevice(storageSystem);
            isilonApi.getClusterInfo();

            discoverCluster(storageSystem);
            _dbClient.persistObject(storageSystem);
            if (!storageSystem.getReachableStatus()) {
                throw new IsilonCollectionException("Failed to connect to " + storageSystem.getIpAddress());
            }
            _completer.statusPending(_dbClient, "Completed cluster discovery");
            List<StoragePool> poolsToMatchWithVpool = new ArrayList<StoragePool>();
            List<StoragePool> allPools = new ArrayList<StoragePool>();
            // discover pools
            Map<String, List<StoragePool>> pools = discoverPools(storageSystem, poolsToMatchWithVpool);
            _log.info("No of newly discovered pools {}", pools.get(NEW).size());
            _log.info("No of existing discovered pools {}", pools.get(EXISTING).size());
            if (!pools.get(NEW).isEmpty()) {
                allPools.addAll(pools.get(NEW));
                _dbClient.createObject(pools.get(NEW));
            }

            if (!pools.get(EXISTING).isEmpty()) {
                allPools.addAll(pools.get(EXISTING));
                _dbClient.persistObject(pools.get(EXISTING));
            }

            List<StoragePool> notVisiblePools = DiscoveryUtils.checkStoragePoolsNotVisible(allPools,
                    _dbClient, storageSystemId);
            poolsToMatchWithVpool.addAll(notVisiblePools);
            _completer.statusPending(_dbClient, "Completed pool discovery");

            // discover ports
            List<StoragePort> allPorts = new ArrayList<StoragePort>();
            Map<String, List<StoragePort>> ports = discoverPorts(storageSystem);
            _log.info("No of newly discovered ports {}", ports.get(NEW).size());
            _log.info("No of existing discovered ports {}", ports.get(EXISTING).size());
            if (null != ports && !ports.get(NEW).isEmpty()) {
                allPorts.addAll(ports.get(NEW));
                _dbClient.createObject(ports.get(NEW));
            }

            if (null != ports && !ports.get(EXISTING).isEmpty()) {
                allPorts.addAll(ports.get(EXISTING));
                _dbClient.persistObject(ports.get(EXISTING));
            }
            List<StoragePort> notVisiblePorts = DiscoveryUtils.checkStoragePortsNotVisible(allPorts,
                    _dbClient, storageSystemId);
            List<StoragePort> allExistPorts = new ArrayList<StoragePort>(ports.get(EXISTING));
            allExistPorts.addAll(notVisiblePorts);
            _completer.statusPending(_dbClient, "Completed port discovery");

            StoragePortAssociationHelper.runUpdatePortAssociationsProcess(ports.get(NEW),
                    allExistPorts, _dbClient, _coordinator, poolsToMatchWithVpool);
            // discover the access zone and its network interfaces
            discoverAccessZones(storageSystem);

            // Update the virtual nas association with virtual arrays!!!
            // For existing virtual nas ports!!
            StoragePortAssociationHelper.runUpdateVirtualNasAssociationsProcess(allExistPorts, null, _dbClient);
            _completer.statusPending(_dbClient, "Completed Access Zone discovery");

            // discovery succeeds
            detailedStatusMessage = String.format("Discovery completed successfully for Isilon: %s",
                    storageSystemId.toString());
        } catch (Exception e) {
            if (storageSystem != null) {
                cleanupDiscovery(storageSystem);
            }
            detailedStatusMessage = String.format("Discovery failed for Isilon %s because %s",
                    storageSystemId.toString(), e.getLocalizedMessage());
            _log.error(detailedStatusMessage, e);
            throw new IsilonCollectionException(detailedStatusMessage);
        } finally {
            if (storageSystem != null) {
                try {
                    // set detailed message
                    storageSystem.setLastDiscoveryStatusMessage(detailedStatusMessage);
                    _dbClient.persistObject(storageSystem);
                } catch (DatabaseException ex) {
                    _log.error("Error while persisting object to DB", ex);
                }
            }
        }
    }

    /**
     * discover the network interface of given Isilon storage cluster
     * 
     * @param storageSystem
     * @return
     * @throws IsilonCollectionException
     */
    private List<IsilonNetworkPool> discoverNetworkPools(StorageSystem storageSystem) throws IsilonCollectionException {
        List<IsilonNetworkPool> isilonNetworkPoolList = new ArrayList<IsilonNetworkPool>();
        URI storageSystemId = storageSystem.getId();
        _log.info("discoverNetworkPools for storage system {} - start", storageSystemId);
        List<IsilonNetworkPool> isilonNetworkPoolsTemp = null;
        try {
            if (VersionChecker.verifyVersionDetails(ONEFS_V8, storageSystem.getFirmwareVersion()) >= 0) {
                _log.info("Isilon release version {} and storagesystem label {}",
                        storageSystem.getFirmwareVersion(), storageSystem.getLabel());
                IsilonApi isilonApi = getIsilonDevice(storageSystem);
                isilonNetworkPoolsTemp = isilonApi.getNetworkPools(null);
                if (isilonNetworkPoolsTemp != null) {
                    isilonNetworkPoolList.addAll(isilonNetworkPoolsTemp);
                }
            } else {
                IsilonSshApi sshDmApi = new IsilonSshApi();
                sshDmApi.setConnParams(storageSystem.getIpAddress(), storageSystem.getUsername(),
                        storageSystem.getPassword());
                Map<String, List<String>> networkPools = sshDmApi.getNetworkPools();
                List<String> smartconnects = null;
                IsilonNetworkPool isiNetworkPool = null;
                for (Map.Entry<String, List<String>> networkpool : networkPools.entrySet()) {
                    smartconnects = networkpool.getValue();
                    if (smartconnects != null) {

                        for (String smartconnect : smartconnects) {
                            isiNetworkPool = new IsilonNetworkPool();
                            isiNetworkPool.setAccess_zone(networkpool.getKey());
                            isiNetworkPool.setSc_dns_zone(smartconnect);
                            isilonNetworkPoolList.add(isiNetworkPool);
                        }
                    }
                }
            }
        } catch (Exception e) {
            _log.error("discover of NetworkPools is failed. %s", e.getMessage());
        }
        return isilonNetworkPoolList;
    }

    /**
     * discover the access zone and add to vipr db
     * 
     * @param storageSystem
     */
    private void discoverAccessZones(StorageSystem storageSystem) {
        URI storageSystemId = storageSystem.getId();

        VirtualNAS virtualNAS = null;
        PhysicalNAS physicalNAS = null;

        List<VirtualNAS> newvNASList = new ArrayList<VirtualNAS>();
        List<VirtualNAS> existingvNASList = new ArrayList<VirtualNAS>();

        List<PhysicalNAS> newPhysicalNASList = new ArrayList<PhysicalNAS>();
        List<PhysicalNAS> existingPhysicalNASList = new ArrayList<PhysicalNAS>();

        List<VirtualNAS> discoveredVNASList = new ArrayList<VirtualNAS>();

        // Discover storage ports
        try {
            _log.info("discoverAccessZones for storage system {} - start", storageSystemId);

            IsilonApi isilonApi = getIsilonDevice(storageSystem);
            // Make restapi call to get access zones
            List<IsilonAccessZone> accessZoneList = isilonApi.getAccessZones(null);
            if (accessZoneList == null || accessZoneList.isEmpty()) {
                // No access zones defined. Throw an exception and fail the discovery
                IsilonCollectionException ice = new IsilonCollectionException("discoverAccessZones failed. No Zones defined");
                throw ice;
            }

            // Find the smart connect zones
            List<IsilonNetworkPool> isilonNetworkPoolsSysAZ = new ArrayList<>();

            // get the system access zone and use it later
            List<IsilonNetworkPool> isilonNetworkPoolList = discoverNetworkPools(storageSystem);
            for (IsilonNetworkPool isilonNetworkPool : isilonNetworkPoolList) {
                if (isilonNetworkPool.getAccess_zone().equalsIgnoreCase(SYSTEM_ACCESS_ZONE_NAME)) {
                    isilonNetworkPoolsSysAZ.add(isilonNetworkPool);
                }
            }
            // set the protocol based storagesystem version
            // by default all version support CIFS and version above 7.2 NFS also
            StringSet protocols = new StringSet();
            protocols.add(CIFS);
            boolean isNfsV4Enabled = isilonApi.nfsv4Enabled(storageSystem.getFirmwareVersion());
            if (VersionChecker.verifyVersionDetails(ONEFS_V7_2, storageSystem.getFirmwareVersion()) >= 0) {
                protocols.add(NFS);
                if (isNfsV4Enabled) {
                    protocols.add(NFSv4);
                }
            }

            List<IsilonNetworkPool> isilonNetworkPools = null;

            // process the access zones list
            for (IsilonAccessZone isilonAccessZone : accessZoneList) {
                // add protocol to NAS servers
                // is it a System access zone?
                isilonNetworkPools = null;

                if (isilonAccessZone.isSystem() == false) {
                    _log.info("Process the user defined access zone {} ", isilonAccessZone.toString());
                    isilonNetworkPools = new ArrayList<IsilonNetworkPool>();
                    // get the smart connect zone information
                    for (IsilonNetworkPool eachNetworkPool : isilonNetworkPoolList) {
                        if (eachNetworkPool.getAccess_zone().equalsIgnoreCase(isilonAccessZone.getName())) {
                            isilonNetworkPools.add(eachNetworkPool);
                        }
                    }

                    // find virtualNAS in db
                    virtualNAS = findvNasByNativeId(storageSystem, isilonAccessZone.getZone_id().toString());
                    if (virtualNAS == null) {
                        if (isilonNetworkPools != null && !isilonNetworkPools.isEmpty()) {
                            virtualNAS = createVirtualNas(storageSystem, isilonAccessZone);
                            newvNASList.add(virtualNAS);
                        }
                    } else {
                        copyUpdatedPropertiesInVNAS(storageSystem, isilonAccessZone, virtualNAS);
                        existingvNASList.add(virtualNAS);

                    }

                    // Set authentication providers
                    setCifsServerMapForNASServer(isilonAccessZone, virtualNAS);

                    // set protocol support
                    if (virtualNAS != null) {
                        virtualNAS.setProtocols(protocols);
                    }

                    // set the smart connect
                    setStoragePortsForNASServer(isilonNetworkPools, storageSystem, virtualNAS);

                } else {
                    _log.info("Process the System access zone {} ", isilonAccessZone.toString());
                    // set protocols
                    StringSet protocolSet = new StringSet();
                    protocolSet.add(CIFS);
                    protocolSet.add(NFS);
                    if (isNfsV4Enabled) {
                        protocolSet.add(NFSv4);
                    }

                    physicalNAS = findPhysicalNasByNativeId(storageSystem, isilonAccessZone.getZone_id().toString());
                    if (physicalNAS == null) {
                        physicalNAS = createPhysicalNas(storageSystem, isilonAccessZone);
                        physicalNAS.setProtocols(protocolSet);
                        // add system access zone
                        newPhysicalNASList.add(physicalNAS);
                    } else {
                        setMaxDbMetricsAz(storageSystem, physicalNAS.getMetrics());
                        existingPhysicalNASList.add(physicalNAS);
                    }
                    // Set authentication providers
                    setCifsServerMapForNASServer(isilonAccessZone, physicalNAS);

                    // set the smart connect zone
                    setStoragePortsForNASServer(isilonNetworkPoolsSysAZ, storageSystem, physicalNAS);
                }
            }

            // Persist the vNAS servers and
            if (newvNASList != null && !newvNASList.isEmpty()) {
                // add the parent system access zone to user defined access zones
                if (physicalNAS != null) {
                    for (VirtualNAS vNas : newvNASList) {
                        // set the parent uri or system access zone uri to vNAS
                        vNas.setParentNasUri(physicalNAS.getId());
                    }
                }
                _log.info("New Virtual NAS servers size {}", newvNASList.size());
                _dbClient.createObject(newvNASList);
                discoveredVNASList.addAll(newvNASList);
            }

            if (existingvNASList != null && !existingvNASList.isEmpty()) {
                _log.info("Modified Virtual NAS servers size {}", existingvNASList.size());
                _dbClient.updateObject(existingvNASList);
                discoveredVNASList.addAll(existingvNASList);
            }
            // Persist the NAS servers!!!
            if (existingPhysicalNASList != null && !existingPhysicalNASList.isEmpty()) {
                _log.info("Modified Physical NAS servers size {}", existingPhysicalNASList.size());
                _dbClient.updateObject(existingPhysicalNASList);
            }

            if (newPhysicalNASList != null && !newPhysicalNASList.isEmpty()) {
                _log.info("New Physical NAS servers size {}", newPhysicalNASList.size());
                _dbClient.createObject(newPhysicalNASList);
            }

            DiscoveryUtils.checkVirtualNasNotVisible(discoveredVNASList, _dbClient, storageSystemId);

        } catch (Exception e) {
            _log.error("discoverAccessZones failed. Storage system: {}", storageSystemId, e);
            IsilonCollectionException ice = new IsilonCollectionException("discoverAccessZones failed. Storage system: " + storageSystemId);
            throw ice;
        }
    }

    private void setStoragePortsForNASServer(List<IsilonNetworkPool> isilonNetworkPools,
            StorageSystem storageSystem, NASServer nasServer) {

        if (nasServer == null) {
            return;
        }

        StringSet storagePorts = nasServer.getStoragePorts();

        if (storagePorts == null) {
            storagePorts = new StringSet();
        } else {
            storagePorts.clear();
        }

        if (isilonNetworkPools != null && !isilonNetworkPools.isEmpty()) {

            for (IsilonNetworkPool isiNetworkPool : isilonNetworkPools) {
                StoragePort storagePort = findStoragePortByNativeId(storageSystem,
                        isiNetworkPool.getSc_dns_zone());
                if (storagePort != null) {
                    storagePorts.add(storagePort.getId().toString());
                }
            }
        } else {
            /*
             * Smart connect zones are dissociated with this access zone.
             * So mark this access zone as not visible.
             */
            _log.info("Setting discovery status of vnas {} as NOTVISIBLE", nasServer.getNasName());
            nasServer.setDiscoveryStatus(DiscoveredDataObject.DiscoveryStatus.NOTVISIBLE.name());
            nasServer.setNasState(VirtualNAS.VirtualNasState.UNKNOWN.name());
            StringSet assignedVarrays = nasServer.getAssignedVirtualArrays();
            if (assignedVarrays != null) {
                nasServer.removeAssignedVirtualArrays(assignedVarrays);
            }
        }

        _log.info("Setting storage ports for vNAS [{}] : {}", nasServer.getNasName(), storagePorts);
        nasServer.setStoragePorts(storagePorts);
    }

    private void discoverCluster(StorageSystem storageSystem) throws IsilonCollectionException {

        URI storageSystemId = storageSystem.getId();

        try {
            _log.info("discoverCluster information for storage system {} - start", storageSystemId);

            IsilonApi isilonApi = getIsilonDevice(storageSystem);
            IsilonClusterConfig clusterConfig = isilonApi.getClusterConfig();

            storageSystem.setSerialNumber(clusterConfig.getGuid());
            String nativeGuid = NativeGUIDGenerator.generateNativeGuid(DiscoveredDataObject.Type.isilon.toString(),
                    clusterConfig.getGuid());
            storageSystem.setNativeGuid(nativeGuid);
            String clusterReleaseVersion = clusterConfig.getOnefs_version_info().getReleaseVersionNumber();
            storageSystem.setFirmwareVersion(clusterReleaseVersion);

            String minimumSupportedVersion = VersionChecker.getMinimumSupportedVersion(Type.valueOf(storageSystem.getSystemType()));
            _log.info("Verifying version details : Minimum Supported Version {} - Discovered Cluster Version {}", minimumSupportedVersion,
                    clusterReleaseVersion);
            if (VersionChecker.verifyVersionDetails(minimumSupportedVersion, clusterReleaseVersion) < 0) {
                storageSystem.setCompatibilityStatus(DiscoveredDataObject.CompatibilityStatus.INCOMPATIBLE.name());
                storageSystem.setReachableStatus(false);
                DiscoveryUtils.setSystemResourcesIncompatible(_dbClient, _coordinator, storageSystem.getId());
                IsilonCollectionException ice = new IsilonCollectionException(String.format(
                        " ** This version of Isilon firmware is not supported ** Should be a minimum of %s", minimumSupportedVersion));
                throw ice;
            }
            storageSystem.setSupportSoftLimit(false);
            storageSystem.setSupportNotificationLimit(false);
            // Check license status for smart quota and set the support attributes as true
            if (ACTIVATED.equalsIgnoreCase(isilonApi.getLicenseInfo(IsilonLicenseType.SMARTQUOTA))
                    || EVALUATION.equalsIgnoreCase(isilonApi.getLicenseInfo(IsilonLicenseType.SMARTQUOTA))) {
                storageSystem.setSupportSoftLimit(true);
                storageSystem.setSupportNotificationLimit(true);
            }
            storageSystem.setCompatibilityStatus(DiscoveredDataObject.CompatibilityStatus.COMPATIBLE.name());
            storageSystem.setReachableStatus(true);

            _log.info("discoverCluster information for storage system {} - complete", storageSystemId);
        } catch (Exception e) {
            storageSystem.setReachableStatus(false);
            String errMsg = String.format("discoverCluster failed. %s", e.getMessage());
            _log.error(errMsg, e);
            IsilonCollectionException ice = new IsilonCollectionException(errMsg);
            throw ice;
        }
    }

    private Map<String, List<StoragePool>> discoverPools(StorageSystem storageSystem, List<StoragePool> poolsToMatchWithVpool)
            throws IsilonCollectionException {

        // Discover storage pools
        Map<String, List<StoragePool>> storagePools = new HashMap<String, List<StoragePool>>();

        List<StoragePool> newPools = new ArrayList<StoragePool>();
        List<StoragePool> existingPools = new ArrayList<StoragePool>();

        URI storageSystemId = storageSystem.getId();
        try {
            _log.info("discoverPools for storage system {} - start", storageSystemId);

            IsilonApi isilonApi = getIsilonDevice(storageSystem);
            boolean isNfsV4Enabled = isilonApi.nfsv4Enabled(storageSystem.getFirmwareVersion());
            boolean syncLicenseValid = isValidLicense(isilonApi.getReplicationLicenseInfo(), storageSystem);
            boolean snapLicenseValid = isValidLicense(isilonApi.snapshotIQLicenseInfo(), storageSystem);

            // Set file replication type for Isilon storage system!!!
            if (syncLicenseValid) {
                StringSet supportReplicationTypes = new StringSet();
                supportReplicationTypes.add(SupportedFileReplicationTypes.REMOTE.name());
                supportReplicationTypes.add(SupportedFileReplicationTypes.LOCAL.name());
                storageSystem.setSupportedReplicationTypes(supportReplicationTypes);
            }

            _log.info("Isilon OneFS version: {}", storageSystem.getFirmwareVersion());
            List<? extends IsilonPool> isilonPools = null;
            if (VersionChecker.verifyVersionDetails(ONEFS_V7_2, storageSystem.getFirmwareVersion()) >= 0) {
                _log.info("Querying for Isilon storage pools...");
                isilonPools = isilonApi.getStoragePools();
            } else {
                _log.info("Querying for Isilon disk pools...");
                isilonPools = isilonApi.getDiskPools();
            }

            for (IsilonPool isilonPool : isilonPools) {
                // Check if this storage pool was already discovered
                StoragePool storagePool = null;
                String poolNativeGuid = NativeGUIDGenerator.generateNativeGuid(
                        storageSystem, isilonPool.getNativeId(),
                        NativeGUIDGenerator.POOL);
                @SuppressWarnings("deprecation")
                List<URI> poolURIs = _dbClient
                        .queryByConstraint(AlternateIdConstraint.Factory.getStoragePoolByNativeGuidConstraint(poolNativeGuid));

                for (URI poolUri : poolURIs) {
                    StoragePool pool = _dbClient.queryObject(StoragePool.class, poolUri);
                    if (!pool.getInactive() && pool.getStorageDevice().equals(storageSystemId)) {
                        storagePool = pool;
                        break;
                    }
                }

                if (storagePool == null) {
                    // New storage pool
                    storagePool = new StoragePool();
                    storagePool.setId(URIUtil.createId(StoragePool.class));
                    storagePool.setNativeGuid(poolNativeGuid);
                    storagePool.setLabel(poolNativeGuid);
                    storagePool.setPoolClassName(POOL_TYPE);
                    storagePool.setPoolServiceType(PoolServiceType.file.toString());
                    storagePool.setStorageDevice(storageSystemId);

                    StringSet protocols = new StringSet();
                    protocols.add("NFS");
                    protocols.add("CIFS");

                    storagePool.setProtocols(protocols);
                    storagePool.setPoolName(isilonPool.getNativeId());
                    storagePool.setNativeId(isilonPool.getNativeId());
                    storagePool.setLabel(poolNativeGuid);
                    storagePool.setSupportedResourceTypes(StoragePool.SupportedResourceTypes.THIN_AND_THICK.toString());
                    storagePool.setOperationalStatus(StoragePool.PoolOperationalStatus.READY.toString());
                    storagePool.setRegistrationStatus(RegistrationStatus.REGISTERED.toString());
                    _log.info("Creating new storage pool using NativeGuid : {}", poolNativeGuid);
                    newPools.add(storagePool);
                } else {
                    existingPools.add(storagePool);
                }

                if (isNfsV4Enabled) {
                    storagePool.getProtocols().add(NFSv4);
                } else {
                    storagePool.getProtocols().remove(NFSv4);
                }

                // Add the Copy type ASYNC, if the Isilon is enabled with SyncIQ service!!
                StringSet copyTypesSupported = new StringSet();

                if (syncLicenseValid) {
                    copyTypesSupported.add(CopyTypes.ASYNC.name());
                    storagePool.setSupportedCopyTypes(copyTypesSupported);
                } else {
                    if (storagePool.getSupportedCopyTypes() != null &&
                            storagePool.getSupportedCopyTypes().contains(CopyTypes.ASYNC.name())) {
                        storagePool.getSupportedCopyTypes().remove(CopyTypes.ASYNC.name());
                    }
                }

                // Add the Copy type ScheduleSnapshot, if the Isilon is enabled with SnapshotIQ
                if (snapLicenseValid) {
                    copyTypesSupported.add(CHECKPOINT_SCHEDULE);
                    storagePool.setSupportedCopyTypes(copyTypesSupported);
                } else {
                    if (storagePool.getSupportedCopyTypes() != null &&
                            storagePool.getSupportedCopyTypes().contains(CHECKPOINT_SCHEDULE)) {
                        storagePool.getSupportedCopyTypes().remove(CHECKPOINT_SCHEDULE);
                    }
                }

                // scale capacity size
                storagePool.setFreeCapacity(isilonPool.getAvailableBytes() / BYTESCONVERTER);
                storagePool.setTotalCapacity(isilonPool.getTotalBytes() / BYTESCONVERTER);
                storagePool.setSubscribedCapacity(isilonPool.getUsedBytes() / BYTESCONVERTER);
                if (ImplicitPoolMatcher.checkPoolPropertiesChanged(storagePool.getCompatibilityStatus(),
                        DiscoveredDataObject.CompatibilityStatus.COMPATIBLE.name())
                        || ImplicitPoolMatcher.checkPoolPropertiesChanged(storagePool.getDiscoveryStatus(),
                                DiscoveryStatus.VISIBLE.name())) {
                    poolsToMatchWithVpool.add(storagePool);
                }
                storagePool.setDiscoveryStatus(DiscoveryStatus.VISIBLE.name());
                storagePool.setCompatibilityStatus(DiscoveredDataObject.CompatibilityStatus.COMPATIBLE.name());
            }
            _log.info("discoverPools for storage system {} - complete", storageSystemId);

            storagePools.put(NEW, newPools);
            storagePools.put(EXISTING, existingPools);
            return storagePools;

        } catch (IsilonException ie) {
            _log.error("discoverPools failed. Storage system: {}", storageSystemId, ie);
            IsilonCollectionException ice = new IsilonCollectionException("discoverPools failed. Storage system: " + storageSystemId);
            ice.initCause(ie);
            throw ice;
        } catch (Exception e) {
            _log.error("discoverPools failed. Storage system: {}", storageSystemId, e);
            IsilonCollectionException ice = new IsilonCollectionException("discoverPools failed. Storage system: " + storageSystemId);
            ice.initCause(e);
            throw ice;
        }
    }

    private HashMap<String, List<StoragePort>> discoverPorts(StorageSystem storageSystem) throws IsilonCollectionException {

        URI storageSystemId = storageSystem.getId();
        HashMap<String, List<StoragePort>> storagePorts = new HashMap<String, List<StoragePort>>();

        List<StoragePort> newStoragePorts = new ArrayList<StoragePort>();
        List<StoragePort> existingStoragePorts = new ArrayList<StoragePort>();
        // Discover storage ports
        try {
            _log.info("discoverPorts for storage system {} - start", storageSystemId);
            IsilonApi isilonApi = getIsilonDevice(storageSystem);

            List<IsilonStoragePort> isilonStoragePorts = new ArrayList<IsilonStoragePort>();

            try {
                _log.info("Trying to get latest smart connect version");
                IsilonSmartConnectInfoV2 connInfo = isilonApi.getSmartConnectInfoV2();
                if (connInfo == null || (connInfo != null && connInfo.getSmartZones() == null)) {
                    throw new Exception("Failed new Interface, try old Interface");
                }
                if (connInfo != null) {
                    isilonStoragePorts = connInfo.getPorts();
                }
            } catch (Exception e) {
                _log.info("Latest version failed so Trying to get old smart connect version");
                IsilonSmartConnectInfo connInfo = isilonApi.getSmartConnectInfo();
                if (connInfo != null) {
                    isilonStoragePorts = connInfo.getPorts();
                }
            }

            if (isilonStoragePorts == null || isilonStoragePorts.isEmpty()) {
                // No ports defined throw an exception and fail the discovery
                IsilonCollectionException ice = new IsilonCollectionException("discoverPorts failed. No Smartzones defined");
                throw ice;
            }

            for (IsilonStoragePort isilonPort : isilonStoragePorts) {

                StoragePort storagePort = null;

                String portNativeGuid = NativeGUIDGenerator.generateNativeGuid(
                        storageSystem, isilonPort.getIpAddress(),
                        NativeGUIDGenerator.PORT);
                // Check if storage port was already discovered
                @SuppressWarnings("deprecation")
                List<URI> portURIs = _dbClient
                        .queryByConstraint(AlternateIdConstraint.Factory.getStoragePortByNativeGuidConstraint(portNativeGuid));
                for (URI portUri : portURIs) {
                    StoragePort port = _dbClient.queryObject(StoragePort.class, portUri);
                    if (port.getStorageDevice().equals(storageSystemId) && !port.getInactive()) {
                        storagePort = port;
                        break;
                    }
                }
                if (storagePort == null) {
                    // Create Isilon storage port for Isilon cluster IP address (smart connect ip)
                    storagePort = new StoragePort();
                    storagePort.setId(URIUtil.createId(StoragePort.class));
                    storagePort.setTransportType("IP");
                    storagePort.setNativeGuid(portNativeGuid);
                    storagePort.setLabel(portNativeGuid);
                    storagePort.setStorageDevice(storageSystemId);
                    storagePort.setPortNetworkId(isilonPort.getIpAddress().toLowerCase());
                    storagePort.setPortName(isilonPort.getPortName());
                    storagePort.setLabel(isilonPort.getPortName());
                    storagePort.setPortSpeed(isilonPort.getPortSpeed());
                    storagePort.setPortGroup(isilonPort.getPortName());
                    storagePort.setRegistrationStatus(RegistrationStatus.REGISTERED.toString());
                    _log.info("Creating new storage port using NativeGuid : {}", portNativeGuid);
                    newStoragePorts.add(storagePort);
                } else {
                    existingStoragePorts.add(storagePort);
                }
                storagePort.setDiscoveryStatus(DiscoveryStatus.VISIBLE.name());
                storagePort.setCompatibilityStatus(DiscoveredDataObject.CompatibilityStatus.COMPATIBLE.name());
            }
            _log.info("discoverPorts for storage system {} - complete", storageSystemId);

            storagePorts.put(NEW, newStoragePorts);
            storagePorts.put(EXISTING, existingStoragePorts);
            return storagePorts;
        } catch (Exception e) {
            _log.error("discoverPorts failed. Storage system: {}", storageSystemId, e);
            IsilonCollectionException ice = new IsilonCollectionException("discoverPorts failed. Storage system: " + storageSystemId);
            throw ice;
        }
    }

    /**
     * get user define the access zone location separated by comma
     * 
     * @param nasServers
     */
    String getUserAccessZonePath(Map<String, NASServer> nasServers) {
        String accessZonePath = ",";
        // Initialized with comma as empty can lead to exception at controller configuration.
        if (nasServers != null && !nasServers.isEmpty()) {
            for (String path : nasServers.keySet()) {
                String nasType = URIUtil.getTypeName(nasServers.get(path).getId());
                if (StringUtils.isNotEmpty(path) && StringUtils.equals(nasType, "VirtualNAS")) {
                    accessZonePath = accessZonePath + path + ",";
                }
            }
        }
        return accessZonePath;
    }

    /**
     * Add custom discovery directory paths from controller configuration
     */
    private void updateDiscoveryPathForUnManagedFS(Map<String, NASServer> nasServer, StorageSystem storage)
            throws IsilonCollectionException {
        String paths = "";
        String systemAccessZone = "";
        String userAccessZone = "";
        String namespace = "";
        String customLocations = ",";

        // get the system access zones
        DataSource ds = new DataSource();
        ds.addProperty(CustomConfigConstants.ISILON_NO_DIR, "");
        ds.addProperty(CustomConfigConstants.ISILON_DIR_NAME, "");
        namespace = customConfigHandler.getComputedCustomConfigValue(CustomConfigConstants.ISILON_SYSTEM_ACCESS_ZONE_NAMESPACE, "isilon",
                ds);
        namespace = namespace.replaceAll("=", "");
        systemAccessZone = IFS_ROOT + "/" + namespace + "/";
        // get the user access zone
        userAccessZone = getUserAccessZonePath(nasServer);
        // create a dataSouce and place the value for system and user access zone
        DataSource dataSource = dataSourceFactory.createIsilonUnmanagedFileSystemLocationsDataSource(storage);
        dataSource.addProperty(CustomConfigConstants.ISILON_SYSTEM_ACCESS_ZONE, systemAccessZone);
        dataSource.addProperty(CustomConfigConstants.ISILON_USER_ACCESS_ZONE, userAccessZone);
        dataSource.addProperty(CustomConfigConstants.ISILON_CUSTOM_DIR_PATH, customLocations);

        paths = customConfigHandler.getComputedCustomConfigValue(CustomConfigConstants.ISILON_UNMANAGED_FILE_SYSTEM_LOCATIONS,
                "isilon",
                dataSource);
        // trim leading or trailing or multiple comma.
        paths = paths.replaceAll("^,+", "").replaceAll(",+$", "").replaceAll(",+", ",");
        if (paths.equals(",") || paths.isEmpty()) {
            IsilonCollectionException ice = new IsilonCollectionException(
                    "computed unmanaged file system location is empty. Please verify Isilon controller config settings");

            throw ice;
        }
        _log.info("Unmanaged file system locations are {}", paths);
        List<String> pathList = Arrays.asList(paths.split(","));
        
        /*
         * fix COP-27008: if system-access-zone's dir has been removed 
         * and is just /ifs/ 
         */
        pathList.remove("/ifs/");

        setDiscPathsForUnManaged(pathList);
        
        
        _discCustomPath = getCustomConfigPath();
    }

    /**
     * Check license is valid or not
     * 
     * @param licenseStatus
     *            Status of the license
     * @param system
     *            Storage System
     * @return true/false
     * @throws IsilonException
     * @throws JSONException
     */
    private boolean isValidLicense(String licenseStatus, StorageSystem system)
            throws IsilonException, JSONException {
        Set<String> validLicenseStatus = new HashSet<String>();
        validLicenseStatus.add(LICENSE_ACTIVATED);
        validLicenseStatus.add(LICENSE_EVALUATION);

        if (validLicenseStatus.contains(licenseStatus)) {
            return true;
        }
        return false;
    }

    /**
     * get the NAS Server object
     * 
     * @param nasServerMap
     * @param fsPath
     * @return
     */
    private NASServer getMatchedNASServer(Map<String, NASServer> nasServerMap, String fsPath) {
        NASServer nasServer = null;
        if (nasServerMap != null && !nasServerMap.isEmpty()) {
            for (Entry<String, NASServer> entry : nasServerMap.entrySet()) {
                if (!SYSTEM_ACCESS_ZONE_NAME.equals(entry.getValue().getNasName())) {
                    if (fsPath.startsWith(entry.getKey())) {
                        nasServer = entry.getValue();
                        break;
                    }
                }
            }
            if (nasServer == null) {
                nasServer = nasServerMap.get(IFS_ROOT + "/");
            }
        }

        return nasServer;
    }

    private void discoverUmanagedFileSystems(AccessProfile profile) throws BaseCollectionException {

        _log.debug("Access Profile Details :  IpAddress : PortNumber : {}, namespace : {}",
                profile.getIpAddress() + profile.getPortNumber(),
                profile.getnamespace());

        URI storageSystemId = profile.getSystemId();

        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storageSystemId);
        if (null == storageSystem) {
            return;
        }

        List<UnManagedFileSystem> unManagedFileSystems = new ArrayList<UnManagedFileSystem>();
        List<UnManagedFileSystem> existingUnManagedFileSystems = new ArrayList<UnManagedFileSystem>();
        List<UnManagedFileQuotaDirectory> unManagedFileQuotaDir = new ArrayList<UnManagedFileQuotaDirectory>();
        List<UnManagedFileQuotaDirectory> existingUnManagedFileQuotaDir = new ArrayList<UnManagedFileQuotaDirectory>();

        Set<URI> allDiscoveredUnManagedFileSystems = new HashSet<URI>();

        String detailedStatusMessage = "Discovery of Isilon Unmanaged FileSystem started";
        long unmanagedFsCount = 0;
        try {
            IsilonApi isilonApi = getIsilonDevice(storageSystem);

            URIQueryResultList storagePoolURIs = new URIQueryResultList();
            _dbClient.queryByConstraint(ContainmentConstraint.Factory
                    .getStorageDeviceStoragePoolConstraint(storageSystem.getId()),
                    storagePoolURIs);

            ArrayList<StoragePool> pools = new ArrayList();
            Iterator<URI> poolsItr = storagePoolURIs.iterator();
            while (poolsItr.hasNext()) {
                URI storagePoolURI = poolsItr.next();
                StoragePool storagePool = _dbClient.queryObject(StoragePool.class, storagePoolURI);
                if (storagePool != null && !storagePool.getInactive()) {
                    pools.add(storagePool);
                }
            }

            StoragePool storagePool = null;
            if (pools != null && !pools.isEmpty()) {
                storagePool = pools.get(0);
            }

            StoragePort storagePort = getStoragePortPool(storageSystem);

            String resumeToken = null;

            int totalIsilonFSDiscovered = 0;

            // get the associated storage port for vnas Server
            List<IsilonAccessZone> isilonAccessZones = isilonApi.getAccessZones(null);
            Map<String, NASServer> nasServers = getNASServer(storageSystem, isilonAccessZones);
            // update the path from controller configuration
            updateDiscoveryPathForUnManagedFS(nasServers, storageSystem);

            // Get All FileShare
            HashMap<String, HashSet<String>> allSMBShares = discoverAllSMBShares(storageSystem, isilonAccessZones);
            List<UnManagedCifsShareACL> unManagedCifsShareACLList = new ArrayList<UnManagedCifsShareACL>();
            List<UnManagedCifsShareACL> oldunManagedCifsShareACLList = new ArrayList<UnManagedCifsShareACL>();

            HashMap<String, HashSet<Integer>> expMap = discoverAllExports(storageSystem, isilonAccessZones);
            List<UnManagedNFSShareACL> unManagedNfsShareACLList = new ArrayList<UnManagedNFSShareACL>();
            List<UnManagedNFSShareACL> oldunManagedNfsShareACLList = new ArrayList<UnManagedNFSShareACL>();

            List<UnManagedFileExportRule> newUnManagedExportRules = new ArrayList<UnManagedFileExportRule>();
            List<UnManagedFileExportRule> oldUnManagedExportRules = new ArrayList<UnManagedFileExportRule>();

            List<FileShare> discoveredFS = new ArrayList<FileShare>();
            do {
                
                HashMap<String,Object> discoverdFileDetails =discoverAllFileSystem(storageSystem, resumeToken);
                
                IsilonApi.IsilonList<FileShare> discoveredIsilonFS = (IsilonApi.IsilonList<FileShare>) discoverdFileDetails.get(UMFS_DETAILS);
                
                resumeToken = discoveredIsilonFS.getToken();
                discoveredFS = discoveredIsilonFS.getList();
                
                ArrayList<UnManagedFileQuotaDirectory> discoveredUmfsQd = (ArrayList<UnManagedFileQuotaDirectory>) discoverdFileDetails.get(UMFSQD_DETAILS);

                totalIsilonFSDiscovered += discoveredFS.size();

                unManagedFileSystems = new ArrayList<UnManagedFileSystem>();
                existingUnManagedFileSystems = new ArrayList<UnManagedFileSystem>();
                int newFileSystemsCount = 0;
                int existingFileSystemsCount = 0;
                HashMap<String, HashMap<String, HashSet<Integer>>> exportMapTree = getExportsWithSubDirForFS(discoveredFS, expMap);

                // NFSv4 enabled on storage system!!!
                boolean isNfsV4Enabled = isilonApi.nfsv4Enabled(storageSystem.getFirmwareVersion());
                
                
                
                

                for (FileShare fs : discoveredFS) {
                    if (!checkStorageFileSystemExistsInDB(fs.getNativeGuid())) {
                        // Create UnManaged FS
                        String fsUnManagedFsNativeGuid = NativeGUIDGenerator.generateNativeGuidForPreExistingFileSystem(
                                storageSystem.getSystemType(),
                                storageSystem.getSerialNumber(), fs.getNativeId());
                        String fsPathName = fs.getPath();
                        UnManagedFileSystem unManagedFs = checkUnManagedFileSystemExistsInDB(fsUnManagedFsNativeGuid);
                        // get the matched vNAS Server
                        NASServer nasServer = getMatchedNASServer(nasServers, fsPathName);
                        if (nasServer != null) {
                            // Get valid storage port from the NAS server!!!
                            _log.info("fs path {} and nas server details {}", fs.getPath(), nasServer.toString());
                            storagePort = getStoragePortFromNasServer(nasServer);
                        } else {
                            _log.info("fs path {} and vnas server not found", fs.getPath());
                            continue; // Skip further ingestion steps on this file share & move to next file share
                        }

                        boolean alreadyExist = unManagedFs == null ? false : true;
                        unManagedFs = createUnManagedFileSystem(unManagedFs,
                                fsUnManagedFsNativeGuid, storageSystem, storagePool, nasServer, fs);

                        unManagedFs.setHasNFSAcl(false);
                        // Get the NFS ACLs only if the system is enabled with NFSv4!!!
                        if (isNfsV4Enabled) {
                            /*
                             * Get all file exports with given file system
                             */
                            HashSet<String> fsExportPaths = new HashSet<String>();
                            for (Entry<String, HashSet<Integer>> entry : expMap.entrySet()) {
                                if (entry.getKey().equalsIgnoreCase(fsPathName) || entry.getKey().startsWith(fsPathName + "/")) {
                                    _log.info("filesystem path : {} and export path: {}", fs.getPath(), entry.getKey());
                                    fsExportPaths.add(entry.getKey());
                                }
                            }

                            List<UnManagedNFSShareACL> tempUnManagedNfsShareACL = new ArrayList<UnManagedNFSShareACL>();
                            UnManagedNFSShareACL existingNfsACL = null;
                            getUnmanagedNfsShareACL(unManagedFs, tempUnManagedNfsShareACL, storagePort, fs, isilonApi, fsExportPaths);

                            if (tempUnManagedNfsShareACL != null && !tempUnManagedNfsShareACL.isEmpty()) {
                                unManagedFs.setHasNFSAcl(true);
                            }
                            for (UnManagedNFSShareACL unManagedNFSACL : tempUnManagedNfsShareACL) {
                                _log.info("Unmanaged File share acls : {}", unManagedNFSACL);
                                String fsShareNativeId = unManagedNFSACL.getFileSystemNfsACLIndex();
                                _log.info("UMFS Share ACL index {}", fsShareNativeId);
                                String fsUnManagedFileShareNativeGuid = NativeGUIDGenerator
                                        .generateNativeGuidForPreExistingFileShare(storageSystem, fsShareNativeId);
                                _log.info("Native GUID {}", fsUnManagedFileShareNativeGuid);
                                // set native guid, so each entry unique
                                unManagedNFSACL.setNativeGuid(fsUnManagedFileShareNativeGuid);
                                // Check whether the NFS share ACL was present in ViPR DB.
                                existingNfsACL = checkUnManagedFsNfssACLExistsInDB(_dbClient, unManagedNFSACL.getNativeGuid());
                                if (existingNfsACL == null) {
                                    unManagedNfsShareACLList.add(unManagedNFSACL);
                                } else {
                                    unManagedNfsShareACLList.add(unManagedNFSACL);
                                    // delete the existing acl
                                    existingNfsACL.setInactive(true);
                                    oldunManagedNfsShareACLList.add(existingNfsACL);
                                }
                            }
                        }

                        // get all shares for given file system path
                        HashSet<String> smbShareHashSet = new HashSet<String>();
                        for (Entry<String, HashSet<String>> entry : allSMBShares.entrySet()) {
                            if (entry.getKey().equalsIgnoreCase(fsPathName) || entry.getKey().startsWith(fsPathName + "/")) {
                                _log.info("filesystem path : {} and share path: {}", fs.getPath(), entry.getKey());
                                smbShareHashSet.addAll(entry.getValue());
                            }
                        }

                        _log.info("File System {} has shares and their size is {}", unManagedFs.getId(), smbShareHashSet.size());

                        if (!smbShareHashSet.isEmpty()) {

                            List<UnManagedCifsShareACL> umfsCifsShareACL = new ArrayList<UnManagedCifsShareACL>();
                            // Set UnManaged ACL and also set the shares in fs object
                            setUnmanagedCifsShareACL(unManagedFs, smbShareHashSet,
                                    umfsCifsShareACL, storagePort, fs.getName(), nasServer.getNasName(), isilonApi);
                            if (!umfsCifsShareACL.isEmpty()) {

                                for (UnManagedCifsShareACL unManagedCifsShareACL : umfsCifsShareACL) {
                                    _log.info("Unmanaged File share acl : {}", unManagedCifsShareACL);
                                    String fsShareNativeId = unManagedCifsShareACL.getFileSystemShareACLIndex();
                                    _log.info("UMFS Share ACL index {}", fsShareNativeId);
                                    String fsUnManagedFileShareNativeGuid = NativeGUIDGenerator
                                            .generateNativeGuidForPreExistingFileShare(storageSystem, fsShareNativeId);
                                    _log.info("Native GUID {}", fsUnManagedFileShareNativeGuid);
                                    // set native guid, so each entry unique
                                    unManagedCifsShareACL.setNativeGuid(fsUnManagedFileShareNativeGuid);
                                    // Check whether the CIFS share ACL was present in ViPR DB.
                                    UnManagedCifsShareACL existingCifsShareACL = checkUnManagedFsCifsACLExistsInDB(_dbClient,
                                            unManagedCifsShareACL.getNativeGuid());
                                    if (existingCifsShareACL == null) {
                                        unManagedCifsShareACLList.add(unManagedCifsShareACL);
                                    } else {
                                        unManagedCifsShareACLList.add(unManagedCifsShareACL);
                                        // delete the existing acl
                                        existingCifsShareACL.setInactive(true);
                                        oldunManagedCifsShareACLList.add(existingCifsShareACL);
                                    }
                                }

                                _log.info("UMFS ID {} - Size of ACL of all CIFS shares is {}", unManagedFs.getId(),
                                        umfsCifsShareACL.size());
                            }
                        }

                        // Get Export info
                        _log.info("Getting export for {}", fs.getPath());
                        HashMap<String, HashSet<Integer>> expIdMap = exportMapTree.get(fs.getPath());

                        if (expIdMap == null) {
                            expIdMap = new HashMap<>();
                        }

                        List<UnManagedFileExportRule> unManagedExportRules = new ArrayList<UnManagedFileExportRule>();
                        if (!expIdMap.keySet().isEmpty()) {
                            List<UnManagedFileExportRule> validExportRules = new ArrayList<UnManagedFileExportRule>();
                            boolean validExportsFound = getUnManagedFSExportMap(unManagedFs, expIdMap, storagePort,
                                    fs.getPath(), nasServer.getNasName(), isilonApi, validExportRules);
                            if (!validExportsFound) {
                                // Invalid exports so ignore the FS
                                String invalidExports = "";
                                for (String path : expIdMap.keySet()) {
                                    invalidExports += expIdMap.get(path);
                                }
                                _log.info("FS {} is ignored because it has conflicting exports {}", fs.getPath(), invalidExports);
                                unManagedFs.setInactive(true);
                                // Persists the inactive state before picking next UMFS!!!
                                _dbClient.persistObject(unManagedFs);
                                continue;
                            }
                            _log.info("Number of exports discovered for file system {} is {}", unManagedFs.getId(),
                                    validExportRules.size());

                            UnManagedFileExportRule existingRule = null;
                            for (UnManagedFileExportRule dbExportRule : validExportRules) {
                                _log.info("Un Managed File Export Rule : {}", dbExportRule);
                                String fsExportRulenativeId = dbExportRule.getFsExportIndex();
                                _log.info("Native Id using to build Native Guid {}", fsExportRulenativeId);
                                String fsUnManagedFileExportRuleNativeGuid = NativeGUIDGenerator
                                        .generateNativeGuidForPreExistingFileExportRule(
                                                storageSystem, fsExportRulenativeId);
                                _log.info("Native GUID {}", fsUnManagedFileExportRuleNativeGuid);
                                dbExportRule.setNativeGuid(fsUnManagedFileExportRuleNativeGuid);
                                dbExportRule.setFileSystemId(unManagedFs.getId());
                                dbExportRule.setId(URIUtil.createId(UnManagedFileExportRule.class));
                                existingRule = checkUnManagedFsExportRuleExistsInDB(_dbClient, dbExportRule.getNativeGuid());
                                if (null == existingRule) {
                                    unManagedExportRules.add(dbExportRule);
                                } else {
                                    existingRule.setInactive(true);
                                    oldUnManagedExportRules.add(existingRule);
                                    unManagedExportRules.add(dbExportRule);
                                }
                            }

                            // Validate Rules Compatible with ViPR - Same rules should
                            // apply as per API SVC Validations.
                            if (!unManagedExportRules.isEmpty()) {
                                _log.info("Validating rules success for export {}", fs.getName());
                                newUnManagedExportRules.addAll(unManagedExportRules);
                                unManagedFs.setHasExports(true);
                                _log.info("File System {} has Exports and their size is {}", unManagedFs.getId(),
                                        newUnManagedExportRules.size());
                            }
                        }

                        if (unManagedFs.getHasExports() || unManagedFs.getHasShares()) {
                            _log.info("FS {} is having exports/shares", fs.getPath());
                            unManagedFs.putFileSystemCharacterstics(
                                    UnManagedFileSystem.SupportedFileSystemCharacterstics.IS_FILESYSTEM_EXPORTED.toString(), TRUE);
                        } else {
                            // NO exports found
                            _log.info("FS {} does not have export or share", fs.getPath());
                        }

                        if (alreadyExist) {
                            existingUnManagedFileSystems.add(unManagedFs);
                            existingFileSystemsCount++;
                        } else {
                            unManagedFileSystems.add(unManagedFs);
                            newFileSystemsCount++;
                        }
                        // Saving bunch of Unmanaged objects!!!
                        if (!newUnManagedExportRules.isEmpty() && newUnManagedExportRules.size() >= MAX_UMFS_RECORD_SIZE) {
                            _log.info("Saving Number of UnManagedFileExportRule(s) {}", newUnManagedExportRules.size());
                            _dbClient.createObject(newUnManagedExportRules);
                            newUnManagedExportRules.clear();
                        }

                        if (!oldUnManagedExportRules.isEmpty() && oldUnManagedExportRules.size() >= MAX_UMFS_RECORD_SIZE) {
                            _log.info("Saving Number of UnManagedFileExportRule(s) {}", newUnManagedExportRules.size());
                            _dbClient.updateObject(oldUnManagedExportRules);
                            oldUnManagedExportRules.clear();
                        }

                        // save ACLs in db
                        if (!unManagedCifsShareACLList.isEmpty() && unManagedCifsShareACLList.size() >= MAX_UMFS_RECORD_SIZE) {
                            _log.info("Saving Number of new UnManagedCifsShareACL(s) {}", unManagedCifsShareACLList.size());
                            _dbClient.createObject(unManagedCifsShareACLList);
                            unManagedCifsShareACLList.clear();
                        }
                        // save old acls
                        if (!oldunManagedCifsShareACLList.isEmpty() && oldunManagedCifsShareACLList.size() >= MAX_UMFS_RECORD_SIZE) {
                            _log.info("Saving Number of existing UnManagedCifsShareACL(s) {}", oldunManagedCifsShareACLList.size());
                            _dbClient.updateObject(oldunManagedCifsShareACLList);
                            oldunManagedCifsShareACLList.clear();
                        }

                        // save NFS ACLs in db
                        if (!unManagedNfsShareACLList.isEmpty() && unManagedNfsShareACLList.size() >= MAX_UMFS_RECORD_SIZE) {
                            _log.info("Saving Number of new UnManagedNfsShareACL(s) {}", unManagedNfsShareACLList.size());
                            _dbClient.createObject(unManagedNfsShareACLList);
                            unManagedNfsShareACLList.clear();
                        }

                        // save old acls
                        if (!oldunManagedNfsShareACLList.isEmpty() && oldunManagedNfsShareACLList.size() >= MAX_UMFS_RECORD_SIZE) {
                            _log.info("Saving Number of old NFS UnManagedFileExportRule(s) {}", oldunManagedNfsShareACLList.size());
                            _dbClient.updateObject(oldunManagedNfsShareACLList);
                            oldunManagedNfsShareACLList.clear();
                        }

                        allDiscoveredUnManagedFileSystems.add(unManagedFs.getId());
                        /**
                         * Persist 200 objects and clear them to avoid memory issue
                         */
                        validateListSizeLimitAndPersist(unManagedFileSystems, existingUnManagedFileSystems,
                                Constants.DEFAULT_PARTITION_SIZE * 2);

                    }
                }
                _log.info("New unmanaged Isilon file systems count: {}", newFileSystemsCount);
                _log.info("Update unmanaged Isilon file systems count: {}", existingFileSystemsCount);
                if (!unManagedFileSystems.isEmpty()) {
                    _dbClient.createObject(unManagedFileSystems);
                }
                if (!existingUnManagedFileSystems.isEmpty()) {
                    _dbClient.updateAndReindexObject(existingUnManagedFileSystems);
                }
                
                List<UnManagedFileQuotaDirectory> existingUmfsQd = new ArrayList<UnManagedFileQuotaDirectory>();
                List<UnManagedFileQuotaDirectory> newUmfsQd = new ArrayList<UnManagedFileQuotaDirectory>();
                
                for(UnManagedFileQuotaDirectory umfsQd : discoveredUmfsQd ){
                    if(!checkStorageQuotaDirectoryExistsInDB(umfsQd.getNativeGuid())){
                        
                    	
                    	String fsUnManagedQdNativeGuid = NativeGUIDGenerator.generateNativeGuidForUnManagedQuotaDir(storageSystem.getSystemType(), storageSystem.getSerialNumber(), umfsQd.getNativeId(), "");
                        
                        String qdPathName = umfsQd.getPath();
                        UnManagedFileQuotaDirectory unManagedFileQd = checkUnManagedFileSystemQuotaDirectoryExistsInDB(fsUnManagedQdNativeGuid);
                        
                        boolean umfsQdExists = (unManagedFileQd == null) ? false : true ;
                        if(umfsQdExists){
                        	umfsQd.setId(unManagedFileQd.getId());
                            existingUnManagedFileQuotaDir.add(umfsQd);
                        }else if(null != umfsQd){
                        	umfsQd.setId(URIUtil.createId(UnManagedFileQuotaDirectory.class));
                            unManagedFileQuotaDir.add(umfsQd);
                        }
                    }
                }
                
                _log.info("New unmanaged Isilon file systems QuotaDirecotry  count: {}", unManagedFileQuotaDir.size());
                _log.info("Update unmanaged Isilon file systems QuotaDirectory count: {}", existingUnManagedFileQuotaDir.size());
                if (!unManagedFileQuotaDir.isEmpty()) {
                    _dbClient.createObject(unManagedFileQuotaDir);
                }
                if (!existingUnManagedFileQuotaDir.isEmpty()) {
                    _dbClient.updateObject(existingUnManagedFileQuotaDir);
                }
                
            } while (resumeToken != null);

            // Saving bunch of Unmanaged objects!!!
            if (!newUnManagedExportRules.isEmpty()) {
                _log.info("Saving Number of UnManagedFileExportRule(s) {}", newUnManagedExportRules.size());
                _dbClient.createObject(newUnManagedExportRules);
                newUnManagedExportRules.clear();
            }

            if (!oldUnManagedExportRules.isEmpty()) {
                _log.info("Saving Number of UnManagedFileExportRule(s) {}", newUnManagedExportRules.size());
                _dbClient.updateObject(newUnManagedExportRules);
                oldUnManagedExportRules.clear();
            }

            // save ACLs in db
            if (!unManagedCifsShareACLList.isEmpty()) {
                _log.info("Saving Number of UnManagedCifsShareACL(s) {}", unManagedCifsShareACLList.size());
                _dbClient.createObject(unManagedCifsShareACLList);
                unManagedCifsShareACLList.clear();
            }

            // save old acls
            if (!oldunManagedCifsShareACLList.isEmpty()) {
                _log.info("Saving Number of UnManagedFileExportRule(s) {}", oldunManagedCifsShareACLList.size());
                _dbClient.persistObject(oldunManagedCifsShareACLList);
                oldunManagedCifsShareACLList.clear();
            }

            // save NFS ACLs in db
            if (!unManagedNfsShareACLList.isEmpty()) {
                _log.info("Saving Number of UnManagedNfsShareACL(s) {}", unManagedNfsShareACLList.size());
                _dbClient.createObject(unManagedNfsShareACLList);
                unManagedNfsShareACLList.clear();
            }

            // save old acls
            if (!oldunManagedNfsShareACLList.isEmpty()) {
                _log.info("Saving Number of NFS UnManagedFileExportRule(s) {}", oldunManagedNfsShareACLList.size());
                _dbClient.updateObject(oldunManagedNfsShareACLList);
                oldunManagedNfsShareACLList.clear();
            }

            _log.info("Discovered {} Isilon file systems.", totalIsilonFSDiscovered);
            // Process those active unmanaged fs objects available in database but not in newly discovered items, to
            // mark them inactive.
            markUnManagedFSObjectsInActive(storageSystem, allDiscoveredUnManagedFileSystems);

            // discovery succeeds
            detailedStatusMessage = String.format("Discovery completed successfully for Isilon: %s; new unmanaged file systems count: %s",
                    storageSystemId.toString(), unmanagedFsCount);
            _log.info(detailedStatusMessage);
        } catch (Exception e) {
            if (storageSystem != null) {
                cleanupDiscovery(storageSystem);
            }
            detailedStatusMessage = String.format("Discovery failed for Isilon %s because %s",
                    storageSystemId.toString(), e.getLocalizedMessage());
            _log.error(detailedStatusMessage, e);
            throw new IsilonCollectionException(detailedStatusMessage);
        } finally {
            if (storageSystem != null) {
                try {
                    // set detailed message
                    storageSystem.setLastDiscoveryStatusMessage(detailedStatusMessage);
                    _dbClient.persistObject(storageSystem);
                } catch (Exception ex) {
                    _log.error("Error while persisting object to DB", ex);
                }
            }
        }
    }

    /**
     * Get all SMB shares of storagesystem
     * 
     * @param storageSystem
     * @return
     */

    private HashMap<String, HashSet<String>> discoverAllSMBShares(final StorageSystem storageSystem,
            final List<IsilonAccessZone> isilonAccessZones) {
        // Discover All FileShares
        String resumeToken = null;
        HashMap<String, HashSet<String>> allShares = new HashMap<String, HashSet<String>>();
        URI storageSystemId = storageSystem.getId();
        _log.info("discoverAllShares for storage system {} - start", storageSystemId);

        try {
            IsilonApi isilonApi = getIsilonDevice(storageSystem);
            for (IsilonAccessZone isilonAccessZone : isilonAccessZones) {
                do {
                    IsilonApi.IsilonList<IsilonSMBShare> isilonShares = isilonApi.listShares(resumeToken, isilonAccessZone.getName());

                    List<IsilonSMBShare> isilonSMBShareList = isilonShares.getList();
                    HashSet<String> sharesHashSet = null;
                    for (IsilonSMBShare share : isilonSMBShareList) {
                        // get the filesystem path and shareid
                        String path = share.getPath();
                        String shareId = share.getId();
                        sharesHashSet = allShares.get(path);
                        if (null == sharesHashSet) {
                            sharesHashSet = new HashSet<String>();
                            sharesHashSet.add(shareId);
                            allShares.put(path, sharesHashSet);
                        } else {
                            // if shares already exist for path then add
                            sharesHashSet.add(shareId);
                            allShares.put(path, sharesHashSet);
                        }

                        _log.info("Discovered SMB Share name {} and path {}", shareId, path);
                    }

                    resumeToken = isilonShares.getToken();
                } while (resumeToken != null);
                _log.info("discoverd AllShares for access zone {} ", isilonAccessZone.getName());
                resumeToken = null;
            }

            return allShares;
        } catch (IsilonException ie) {
            _log.error("discoverAllShares failed. Storage system: {}", storageSystemId, ie);
            IsilonCollectionException ice = new IsilonCollectionException("discoverAllShares failed. Storage system: " + storageSystemId);
            ice.initCause(ie);
            throw ice;
        } catch (Exception e) {
            _log.error("discoverAllShares failed. Storage system: {}", storageSystemId, e);
            IsilonCollectionException ice = new IsilonCollectionException("discoverAllShares failed. Storage system: " + storageSystemId);
            ice.initCause(e);
            throw ice;
        }
    }

    private void validateListSizeLimitAndPersist(List<UnManagedFileSystem> newUnManagedFileSystems,
            List<UnManagedFileSystem> existingUnManagedFileSystems, int limit) {

        if (newUnManagedFileSystems != null && !newUnManagedFileSystems.isEmpty() &&
                newUnManagedFileSystems.size() >= limit) {
            _partitionManager.insertInBatches(newUnManagedFileSystems,
                    Constants.DEFAULT_PARTITION_SIZE, _dbClient,
                    UNMANAGED_FILESYSTEM);
            newUnManagedFileSystems.clear();
        }

        if (existingUnManagedFileSystems != null && !existingUnManagedFileSystems.isEmpty() &&
                existingUnManagedFileSystems.size() >= limit) {
            _partitionManager.updateInBatches(existingUnManagedFileSystems,
                    Constants.DEFAULT_PARTITION_SIZE, _dbClient,
                    UNMANAGED_FILESYSTEM);
            existingUnManagedFileSystems.clear();
        }
    }

    private  HashMap<String, Object> discoverAllFileSystem(StorageSystem storageSystem, String resumeToken)
            throws IsilonCollectionException {

        // Discover All FileSystem
        List<FileShare> discoveredFS = new ArrayList<FileShare>();
        List<UnManagedFileQuotaDirectory> discoverdQuotaDirectory = new ArrayList<UnManagedFileQuotaDirectory>();

        URI storageSystemId = storageSystem.getId();

        try {
            _log.info("discoverAllFileSystem for storage system {} - start", storageSystemId);

            IsilonApi isilonApi = getIsilonDevice(storageSystem);

            HashMap<String, FileShare> fsWithQuotaMap = new HashMap<String, FileShare>();
            HashMap<String, IsilonSmartQuota> fsQuotaMap = new HashMap<String, IsilonSmartQuota>();
            HashMap<String, IsilonSmartQuota> quotaDirMap = new HashMap<String, IsilonSmartQuota>();
            HashMap<String, UnManagedFileQuotaDirectory> qdMap = new HashMap<String, UnManagedFileQuotaDirectory>();
             
            // get first page of quota data, process and insert to database
            
            HashMap<String, IsilonSmartQuota> tempQuotaMap = new HashMap<String, IsilonSmartQuota>();
            
            
            List<IsilonAccessZone> accessZones = isilonApi.getAccessZones(null);
            
            List<String> tempAccessZonePath = new ArrayList<String>();
            
            for (IsilonAccessZone accessZone : accessZones) {
                if (!accessZone.isSystem()) {
                    tempAccessZonePath.add(accessZone.getPath() + "/");
                }
            }
            
            /*
             *Corner case code: JIRA COP-27008
             * code scenario : remote remote-access-zone path starts in another access-zone path 
             * remote access zone-1 : /ifs/zone-a
             * remote access zone-2 : /ifs/zone-a/zone-b
             * To handle this scenario, we are taking the discoverable path to start from access zone 
             * FS path will be : /<access-zone-path>/<v-pool>/<tenant-name>/<project-name> 
             *   
             */
            
            for (String umfsDiscoverPath : _discPathsForUnManaged) {

                int accessZoneDiscPathLength = computeCustomConfigPathLengths(umfsDiscoverPath);

                IsilonApi.IsilonList<IsilonSmartQuota> quotas = isilonApi.listQuotas(null, umfsDiscoverPath);

                for (IsilonSmartQuota quota : quotas.getList()) {

                    tempQuotaMap.put(quota.getPath(), quota);
                    String fsNativeId = quota.getPath();
                    if (isUnderUnmanagedDiscoveryPath(fsNativeId)) {
                        int fsPathType = isQuotaOrFile(fsNativeId, accessZoneDiscPathLength);

                        if (fsPathType == PATH_IS_FILE) {
                            fsQuotaMap.put(fsNativeId, quota);
                        }
                        if (fsPathType == PATH_IS_QUOTA) {
                            quotaDirMap.put(fsNativeId, quota);
                        }
                    }
                }
            }
            
            Set<String> filePaths = fsQuotaMap.keySet();
            Set<String> quotaPaths = quotaDirMap.keySet();
            /*
             * Associate Quota directories with correct File paths
             */
            HashMap<String, Set<String>> fileQuotas = new HashMap<String, Set<String>>();
            for (String filePath : filePaths) {
                HashSet<String> qdPaths = new HashSet<String>();

                for (String qdPath : quotaPaths) {
                    if (qdPath.startsWith(filePath + "/")) {
                        qdPaths.add(qdPath);
                    }
                }
                if (!qdPaths.isEmpty()) {
                    quotaPaths.removeAll(qdPaths);
                    fileQuotas.put(filePath, qdPaths);
                }
            }
            
            for (String fsNativeId : filePaths) {
                IsilonSmartQuota fileFsQuota = fsQuotaMap.get(fsNativeId);
                FileShare fs = extractFileShare(fsNativeId, fileFsQuota, storageSystem);

                _log.debug("quota id {} with capacity {}", fsNativeId + ":QUOTA:" + fileFsQuota.getId(),
                        fs.getCapacity() + " used capacity " + fs.getUsedCapacity());
                fsWithQuotaMap.put(fsNativeId, fs);

                Set<String> fsQuotaIds = fileQuotas.get(fsNativeId);
                if (null != fsQuotaIds) {
                    for (String quotaNativeId : fsQuotaIds) {
                        IsilonSmartQuota qdQuota = tempQuotaMap.get(quotaNativeId);
                        if (null != qdQuota) {
                            UnManagedFileQuotaDirectory qd = getUnManagedFileQuotaDirectory(fs.getNativeGuid(), qdQuota,
                                    storageSystem);
                            qdMap.put(quotaNativeId, qd);
                        }
                    }
                }
            }
                
            // get all other pages of quota data, process and set quota page by page
            _log.info("NativeGUIDGenerator for storage system {} - complete", storageSystemId);

            // Filter out FS with no Quota associated with them
            discoveredFS = new ArrayList<FileShare>(fsWithQuotaMap.values());
            IsilonApi.IsilonList<FileShare> isilonFSList = new IsilonApi.IsilonList<FileShare>();
            isilonFSList.addList(discoveredFS);

            HashMap<String, Object> discoveredFileDetails = new HashMap<String, Object>();

            discoverdQuotaDirectory.addAll(qdMap.values());

            discoveredFileDetails.put(UMFS_DETAILS, isilonFSList);
            discoveredFileDetails.put(UMFSQD_DETAILS, discoverdQuotaDirectory);

            return discoveredFileDetails;

        } catch (IsilonException ie) {
            _log.error("discoverAllFileSystem failed. Storage system: {}", storageSystemId, ie);
            IsilonCollectionException ice = new IsilonCollectionException("discoverAllFileSystem failed. Storage system: "
                    + storageSystemId);
            ice.initCause(ie);
            throw ice;
        } catch (Exception e) {
            _log.error("discoverAllFileSystem failed. Storage system: {}", storageSystemId, e);
            IsilonCollectionException ice = new IsilonCollectionException("discoverAllFileSystem failed. Storage system: "
                    + storageSystemId);
            ice.initCause(e);
            throw ice;
        }
    }

    private FileShare extractFileShare(String fsNativeId, IsilonSmartQuota quota, StorageSystem storageSystem) {

        _log.debug("extractFileShare for {} and quota {} ", fsNativeId, quota.toString());
        FileShare fs = new FileShare();
        long softLimit = 0;
        int softGrace = 0;
        long notificationLimit = 0;

        String[] splits = fsNativeId.split("/");
        if (splits.length > 0) {
            fs.setName(splits[splits.length - 1]);
        }

        fs.setMountPath(fsNativeId);
        fs.setNativeId(fsNativeId);
        fs.setExtensions(new StringMap());

        String fsNativeGuid = NativeGUIDGenerator.generateNativeGuid(
                storageSystem.getSystemType(),
                storageSystem.getSerialNumber(), fsNativeId);

        fs.setNativeGuid(fsNativeGuid);
        fs.setPath(fsNativeId);
        long capacity = 0;
        if (quota.getThresholds() != null && quota.getThresholds().getHard() != null) {
            capacity = quota.getThresholds().getHard();
        }
        fs.setCapacity(capacity);
        fs.setUsedCapacity(quota.getUsagePhysical());
        if (!quota.getId().equalsIgnoreCase("null")) {
            fs.getExtensions().put(QUOTA, quota.getId());
        }

        if (null != quota.getThresholds().getSoft() && capacity != 0) {
            softLimit = quota.getThresholds().getSoft() * 100 / capacity;
        }
        if (null != quota.getThresholds().getSoftGrace() && capacity != 0) {
            softGrace = new Long(quota.getThresholds().getSoftGrace() / (24 * 60 * 60)).intValue();
        }
        if (null != quota.getThresholds().getAdvisory() && capacity != 0) {
            notificationLimit = quota.getThresholds().getAdvisory() * 100 / capacity;
        }

        fs.setSoftLimit(softLimit);
        fs.setSoftGracePeriod(softGrace);
        fs.setNotificationLimit(notificationLimit);
        
        return fs;
    }
    
    
     
    
    private UnManagedFileQuotaDirectory getUnManagedFileQuotaDirectory(String fsNativeGuid, IsilonSmartQuota quota,
            StorageSystem storageSystem) {
        String qdNativeId = quota.getPath();
        _log.debug("Converting IsilonSmartQuota {} for fileSystem {}", quota.getPath(), fsNativeGuid);

        int softLimit = 0;
        int softGrace = 0;
        int notificationLimit = 0;
        String nativeGuid = null;

        UnManagedFileQuotaDirectory umfsQd = new UnManagedFileQuotaDirectory();
        StringMap extensionsMap = new StringMap();

        String[] tempDirNames = qdNativeId.split("/");
        umfsQd.setParentFSNativeGuid(fsNativeGuid);

        umfsQd.setLabel(tempDirNames[tempDirNames.length - 1]);

        try {
            nativeGuid = NativeGUIDGenerator.generateNativeGuidForUnManagedQuotaDir(storageSystem.getSystemType(),
                    storageSystem.getSerialNumber(), qdNativeId, "");
        } catch (IOException e) {
            _log.error("Exception while generating NativeGuid for UnManagedQuotaDirectory", e);
        }

        umfsQd.setNativeGuid(nativeGuid);
        umfsQd.setNativeId(qdNativeId);

        long capacity = 0;
        if (quota.getThresholds() != null && quota.getThresholds().getHard() != null) {
            capacity = quota.getThresholds().getHard();
        }

        umfsQd.setSize(capacity);
        if (null != quota.getThresholds().getSoft() && capacity != 0) {
            softLimit = new Long(quota.getThresholds().getSoft() * 100 / capacity).intValue();
        }
        if (null != quota.getThresholds().getSoftGrace() && capacity != 0) {
            softGrace = new Long(quota.getThresholds().getSoftGrace() / (24 * 60 * 60)).intValue();
        }
        if (null != quota.getThresholds().getAdvisory() && capacity != 0) {
            notificationLimit = new Long(quota.getThresholds().getAdvisory() * 100 / capacity).intValue();
        }

        if (null != quota.getId()) {
            extensionsMap.put(QUOTA, quota.getId());
        }

        umfsQd.setSoftLimit(softLimit);
        umfsQd.setSoftGrace(softGrace);
        umfsQd.setNotificationLimit(notificationLimit);
        umfsQd.setExtensions(extensionsMap);

        return umfsQd;
    }

    private boolean isUnderUnmanagedDiscoveryPath(String fsNativeId) {
        boolean qualified = false;

        for (String discPath : _discPathsForUnManaged) {
            if (fsNativeId.startsWith(discPath)) {
                qualified = true;
                break;
            }
        }
        return qualified;
    }
    
    private int isQuotaOrFile(String fsNativeId, int accessZoneDiscPathLength) {

        int pathLength = fsNativeId.split("/").length;

        if (pathLength == (accessZoneDiscPathLength + 1)) {
            return PATH_IS_FILE;
        } else if (pathLength == (accessZoneDiscPathLength + 2)) {
            return PATH_IS_QUOTA;
        }
        return PATH_IS_INVALID;
    }

    private HashMap<String, HashSet<Integer>> discoverAllExports(StorageSystem storageSystem,
            final List<IsilonAccessZone> isilonAccessZones)
            throws IsilonCollectionException {

        // Discover All FileSystem
        HashMap<String, HashSet<Integer>> allExports = new HashMap<String, HashSet<Integer>>();

        URI storageSystemId = storageSystem.getId();

        String resumeToken = null;

        try {
            _log.info("discoverAllExports for storage system {} - start", storageSystemId);

            IsilonApi isilonApi = getIsilonDevice(storageSystem);

            for (IsilonAccessZone isilonAccessZone : isilonAccessZones) {
                do {
                    IsilonApi.IsilonList<IsilonExport> isilonExports = isilonApi.listExports(resumeToken,
                            isilonAccessZone.getName());
                    List<IsilonExport> exports = isilonExports.getList();

                    for (IsilonExport exp : exports) {
                        _log.info("Discovered fS export {}", exp.toString());
                        HashSet<Integer> exportIds = new HashSet<Integer>();
                        for (String path : exp.getPaths()) {
                            exportIds = allExports.get(path);
                            if (exportIds == null) {
                                exportIds = new HashSet<Integer>();
                            }
                            exportIds.add(exp.getId());
                            allExports.put(path, exportIds);
                            _log.debug("Discovered fS put export Path {} Export id {}", path, exportIds.size() + ":" + exportIds);
                        }
                    }
                    resumeToken = isilonExports.getToken();
                } while (resumeToken != null);
                _log.info("discoverd All NFS Exports for access zone {} ", isilonAccessZone.getName());
                resumeToken = null;
            }

            return allExports;

        } catch (IsilonException ie) {
            _log.error("discoverAllExports failed. Storage system: {}", storageSystemId, ie);
            IsilonCollectionException ice = new IsilonCollectionException("discoverAllExports failed. Storage system: " + storageSystemId);
            ice.initCause(ie);
            throw ice;
        } catch (Exception e) {
            _log.error("discoverAllExports failed. Storage system: {}", storageSystemId, e);
            IsilonCollectionException ice = new IsilonCollectionException("discoverAllExports failed. Storage system: " + storageSystemId);
            ice.initCause(e);
            throw ice;
        }
    }

    private IsilonExport getIsilonExport(IsilonApi isilonApi, Integer expId, String zoneName) {
        IsilonExport exp = null;
        try {
            _log.debug("call getIsilonExport for {} ", expId);
            if (expId != null) {
                exp = isilonApi.getExport(expId.toString());
                _log.debug("call getIsilonExport {}", exp.toString());
            }
        } catch (Exception e) {
            _log.error("Exception while getting Export for {}", expId);
        }
        return exp;
    }

    /**
     * get UnManaged Cifs Shares and their ACLs
     * 
     * @param unManagedFileSystem
     * @param smbShares
     * @param unManagedCifsShareACLList
     * @param fsPath
     * @param isilonApi
     */
    private void setUnmanagedCifsShareACL(UnManagedFileSystem unManagedFileSystem,
            HashSet<String> smbShares,
            List<UnManagedCifsShareACL> unManagedCifsShareACLList,
            StoragePort storagePort,
            String fsname,
            String zoneName,
            IsilonApi isilonApi) {

        _log.debug("Set CIFS shares and their respective ACL of UMFS: {} from Isilon SMB share details - start", fsname);

        if (null != smbShares && !smbShares.isEmpty()) {
            UnManagedSMBShareMap unManagedSmbShareMap = null;
            if (null == unManagedFileSystem.getUnManagedSmbShareMap()) {
                unManagedSmbShareMap = new UnManagedSMBShareMap();
                unManagedFileSystem.setUnManagedSmbShareMap(unManagedSmbShareMap);
            }
            unManagedSmbShareMap = unManagedFileSystem.getUnManagedSmbShareMap();
            UnManagedSMBFileShare unManagedSMBFileShare = null;

            for (String shareId : smbShares) {
                // get smb share details
                IsilonSMBShare isilonSMBShare = getIsilonSMBShare(isilonApi, shareId, zoneName);
                if (null != isilonSMBShare) {
                    unManagedSMBFileShare = new UnManagedSMBFileShare();
                    unManagedSMBFileShare.setName(isilonSMBShare.getName());
                    unManagedSMBFileShare.setDescription(isilonSMBShare.getDescription());
                    unManagedSMBFileShare.setNativeId(unManagedFileSystem.getNativeGuid());
                    unManagedSMBFileShare.setMountPoint("\\\\" + storagePort.getPortNetworkId() + "\\" + isilonSMBShare.getName());
                    unManagedSMBFileShare.setPath(isilonSMBShare.getPath());
                    unManagedSMBFileShare.setMaxUsers(-1);
                    // setting the dummy permission.This is not used by isilon, but used by other storage system
                    unManagedSMBFileShare.setPermission(FileControllerConstants.CIFS_SHARE_PERMISSION_CHANGE);
                    unManagedSMBFileShare.setPermissionType(FileControllerConstants.CIFS_SHARE_PERMISSION_TYPE_ALLOW);

                    // set Unmanaged SMB Share
                    unManagedSmbShareMap.put(isilonSMBShare.getName(), unManagedSMBFileShare);
                    _log.info("SMB share name {} and fs mount point {} ", unManagedSMBFileShare.getName(),
                            unManagedSMBFileShare.getMountPoint());
                    // process ACL permission
                    UnManagedCifsShareACL unManagedCifsShareACL = null;
                    int aclSize = 0;
                    List<IsilonSMBShare.Permission> permissionList = isilonSMBShare.getPermissions();
                    for (IsilonSMBShare.Permission permission : permissionList) {
                        // Isilon can have deny permission type. Do not ingest the ACL for deny

                        if (FileControllerConstants.CIFS_SHARE_PERMISSION_TYPE_ALLOW
                                .equalsIgnoreCase(permission.getPermissionType())) {

                            aclSize++;
                            _log.debug("IsilonSMBShare: [{}] permission details: {}",
                                    isilonSMBShare.getName(), permission.toString());

                            unManagedCifsShareACL = new UnManagedCifsShareACL();
                            // Set share name
                            unManagedCifsShareACL.setShareName(isilonSMBShare.getName());
                            // Set permission
                            unManagedCifsShareACL.setPermission(permission.getPermission());

                            // We take only username and we can ignore type and id
                            // Set user
                            unManagedCifsShareACL.setUser(permission.getTrustee().getName());

                            // Set filesystem id
                            unManagedCifsShareACL.setFileSystemId(unManagedFileSystem.getId());
                            unManagedCifsShareACL.setId(URIUtil.createId(UnManagedCifsShareACL.class));
                            unManagedCifsShareACLList.add(unManagedCifsShareACL);
                        }
                    }
                    _log.debug("ACL size of share: [{}] is {}", isilonSMBShare.getName(), aclSize);
                }
            }

            if (!unManagedSmbShareMap.isEmpty()) {
                unManagedFileSystem.setHasShares(true);
            }
        }
    }

    /**
     * get UnManaged NFS Shares and their ACLs
     * 
     * @param unManagedFileSystem
     * @param unManagedNfsACLList
     * @param storagePort
     * @param fs
     * @param isilonApi
     */
    private void getUnmanagedNfsShareACL(UnManagedFileSystem unManagedFileSystem,
            List<UnManagedNFSShareACL> unManagedNfsACLList,
            StoragePort storagePort,
            FileShare fs, IsilonApi isilonApi, HashSet<String> fsExportPaths) {

        for (String exportPath : fsExportPaths) {
            _log.info(
                    "getUnmanagedNfsShareACL for UnManagedFileSystem file path{} - start",
                    fs.getName());
            if (exportPath == null || exportPath.isEmpty()) {
                _log.info("Export path is empty");
                continue;
            }
            try {
                IsilonNFSACL isilonNFSAcl = isilonApi.getNFSACL(exportPath);

                for (IsilonNFSACL.Acl tempAcl : isilonNFSAcl.getAcl()) {

                    if (tempAcl.getTrustee() != null) {

                        UnManagedNFSShareACL unmanagedNFSAcl = new UnManagedNFSShareACL();
                        unmanagedNFSAcl.setFileSystemPath(exportPath);

                        String[] tempUname = StringUtils.split(tempAcl.getTrustee().getName(), "\\");

                        if (tempUname.length > 1) {
                            unmanagedNFSAcl.setDomain(tempUname[0]);
                            unmanagedNFSAcl.setUser(tempUname[1]);
                        } else {
                            unmanagedNFSAcl.setUser(tempUname[0]);
                        }

                        unmanagedNFSAcl.setType(tempAcl.getTrustee().getType());
                        unmanagedNFSAcl.setPermissionType(tempAcl.getAccesstype());
                        unmanagedNFSAcl.setPermissions(StringUtils.join(
                                getIsilonAccessList(tempAcl.getAccessrights()), ","));

                        unmanagedNFSAcl.setFileSystemId(unManagedFileSystem.getId());
                        unmanagedNFSAcl.setId(URIUtil.createId(UnManagedNFSShareACL.class));

                        unManagedNfsACLList.add(unmanagedNFSAcl);
                    }
                }
            } catch (Exception ex) {
                _log.warn("Unble to access NFS ACLs for path {}", exportPath);
            }
        }

    }

    @Override
    public void scan(AccessProfile arg0) throws BaseCollectionException {
        // TODO Auto-generated method stub
    }

    /**
     * If discovery fails, then mark the system as unreachable. The
     * discovery framework will remove the storage system from the database.
     * 
     * @param system
     *            the system that failed discovery.
     */
    private void cleanupDiscovery(StorageSystem system) {
        try {
            system.setReachableStatus(false);
            _dbClient.persistObject(system);
        } catch (DatabaseException e) {
            _log.error("discoverStorage failed.  Failed to update discovery status to ERROR.", e);
        }

    }

    /**
     * create StorageFileSystem Info Object
     * 
     * @param unManagedFileSystem
     * @param unManagedFileSystemNativeGuid
     * @param storageSystem
     * @param fileSystem
     * @return UnManagedFileSystem
     * @throws IOException
     * @throws IsilonCollectionException
     */
    private UnManagedFileSystem createUnManagedFileSystem(
            UnManagedFileSystem unManagedFileSystem,
            String unManagedFileSystemNativeGuid, StorageSystem storageSystem,
            StoragePool pool, NASServer nasServer, FileShare fileSystem)
            throws IOException, IsilonCollectionException {

        if (null == unManagedFileSystem) {
            unManagedFileSystem = new UnManagedFileSystem();
            unManagedFileSystem.setId(URIUtil
                    .createId(UnManagedFileSystem.class));
            unManagedFileSystem.setNativeGuid(unManagedFileSystemNativeGuid);
            unManagedFileSystem.setStorageSystemUri(storageSystem.getId());
            if (null != pool) {
                unManagedFileSystem.setStoragePoolUri(pool.getId());
            }
            unManagedFileSystem.setHasExports(false);
            unManagedFileSystem.setHasShares(false);
            unManagedFileSystem.setHasNFSAcl(false);
        }

        if (null == unManagedFileSystem.getExtensions()) {
            unManagedFileSystem.setExtensions(new StringMap());
        }

        Map<String, StringSet> unManagedFileSystemInformation = new HashMap<String, StringSet>();
        StringMap unManagedFileSystemCharacteristics = new StringMap();

        unManagedFileSystemCharacteristics.put(
                UnManagedFileSystem.SupportedFileSystemCharacterstics.IS_SNAP_SHOT.toString(),
                FALSE);

        unManagedFileSystemCharacteristics.put(
                UnManagedFileSystem.SupportedFileSystemCharacterstics.IS_THINLY_PROVISIONED
                        .toString(),
                TRUE);

        unManagedFileSystemCharacteristics.put(
                UnManagedFileSystem.SupportedFileSystemCharacterstics.IS_FILESYSTEM_EXPORTED
                        .toString(),
                FALSE);

        if (null != pool) {
            StringSet pools = new StringSet();
            pools.add(pool.getId().toString());
            unManagedFileSystemInformation.put(
                    UnManagedFileSystem.SupportedFileSystemInformation.STORAGE_POOL.toString(), pools);
            StringSet matchedVPools = DiscoveryUtils.getMatchedVirtualPoolsForPool(_dbClient, pool.getId(),
                    unManagedFileSystemCharacteristics
                            .get(UnManagedFileSystem.SupportedFileSystemCharacterstics.IS_THINLY_PROVISIONED
                                    .toString()));
            _log.debug("Matched Pools : {}", Joiner.on("\t").join(matchedVPools));
            if (null == matchedVPools || matchedVPools.isEmpty()) {
                // clear all existing supported vpools.
                unManagedFileSystem.getSupportedVpoolUris().clear();
            } else {
                // replace with new StringSet
                unManagedFileSystem.getSupportedVpoolUris().replace(matchedVPools);
                _log.info("Replaced Pools :"
                        + Joiner.on("\t").join(unManagedFileSystem.getSupportedVpoolUris()));
            }

        }

        if (null != nasServer) {
            StringSet storagePorts = new StringSet();
            if (nasServer.getStoragePorts() != null && !nasServer.getStoragePorts().isEmpty()) {
                storagePorts.addAll(nasServer.getStoragePorts());
                unManagedFileSystemInformation.put(
                        UnManagedFileSystem.SupportedFileSystemInformation.STORAGE_PORT.toString(), storagePorts);
                _log.info("StoragePorts :"
                        + Joiner.on("\t").join(storagePorts));
            }

            StringSet nasServerSet = new StringSet();
            nasServerSet.add(nasServer.getId().toString());
            unManagedFileSystemInformation.put(UnManagedFileSystem.SupportedFileSystemInformation.NAS.toString(), nasServerSet);
            _log.debug("nasServer uri id {}", nasServer.getId().toString());
        }

        unManagedFileSystemCharacteristics.put(
                UnManagedFileSystem.SupportedFileSystemCharacterstics.IS_INGESTABLE
                        .toString(),
                TRUE);
        if (null != storageSystem) {
            StringSet systemTypes = new StringSet();
            systemTypes.add(storageSystem.getSystemType());
            unManagedFileSystemInformation.put(
                    UnManagedFileSystem.SupportedFileSystemInformation.SYSTEM_TYPE.toString(),
                    systemTypes);
        }

        // Set attributes of FileSystem
        StringSet fsPath = new StringSet();
        fsPath.add(fileSystem.getNativeId());

        StringSet fsMountPath = new StringSet();
        fsMountPath.add(fileSystem.getMountPath());

        StringSet fsName = new StringSet();
        fsName.add(fileSystem.getName());

        StringSet fsId = new StringSet();
        fsId.add(fileSystem.getNativeId());

        unManagedFileSystem.setLabel(fileSystem.getName());

        unManagedFileSystemInformation.put(
                UnManagedFileSystem.SupportedFileSystemInformation.NAME.toString(), fsName);
        unManagedFileSystemInformation.put(
                UnManagedFileSystem.SupportedFileSystemInformation.NATIVE_ID.toString(), fsId);
        unManagedFileSystemInformation.put(
                UnManagedFileSystem.SupportedFileSystemInformation.DEVICE_LABEL.toString(), fsName);
        unManagedFileSystemInformation.put(
                UnManagedFileSystem.SupportedFileSystemInformation.PATH.toString(), fsPath);
        unManagedFileSystemInformation.put(
                UnManagedFileSystem.SupportedFileSystemInformation.MOUNT_PATH.toString(), fsMountPath);

        StringSet provisionedCapacity = new StringSet();
        long capacity = 0;
        if (fileSystem.getCapacity() != null) {
            capacity = fileSystem.getCapacity();
        }
        provisionedCapacity.add(String.valueOf(capacity));
        unManagedFileSystemInformation.put(
                UnManagedFileSystem.SupportedFileSystemInformation.PROVISIONED_CAPACITY
                        .toString(),
                provisionedCapacity);

        StringSet allocatedCapacity = new StringSet();
        long usedCapacity = 0;
        if (fileSystem.getUsedCapacity() != null) {
            usedCapacity = fileSystem.getUsedCapacity();
        }
        allocatedCapacity.add(String.valueOf(usedCapacity));
        unManagedFileSystemInformation.put(
                UnManagedFileSystem.SupportedFileSystemInformation.ALLOCATED_CAPACITY
                        .toString(),
                allocatedCapacity);

        String quotaId = fileSystem.getExtensions().get(QUOTA);
        if (quotaId != null) {
            unManagedFileSystem.getExtensions().put(QUOTA, quotaId);
        }
        _log.debug("Quota : {}  : {}", quotaId, fileSystem.getPath());

        // Add fileSystemInformation and Characteristics.
        unManagedFileSystem
                .addFileSystemInformation(unManagedFileSystemInformation);
        unManagedFileSystem
                .addFileSystemCharacterstcis(unManagedFileSystemCharacteristics);

        // Initialize ExportMap
        unManagedFileSystem.setFsUnManagedExportMap(new UnManagedFSExportMap());

        // Initialize SMBMap
        unManagedFileSystem.setUnManagedSmbShareMap(new UnManagedSMBShareMap());

        return unManagedFileSystem;
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

    /**
     * check Storage fileSystem exists in DB
     * 
     * @param nativeGuid
     * @return
     * @throws java.io.IOException
     */
    protected boolean checkStorageFileSystemExistsInDB(String nativeGuid)
            throws IOException {
        URIQueryResultList result = new URIQueryResultList();
        _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                .getFileSystemNativeGUIdConstraint(nativeGuid), result);
        Iterator<URI> iter = result.iterator();
        while (iter.hasNext()) {
            URI fileSystemtURI = iter.next();
            FileShare fileShare = _dbClient.queryObject(FileShare.class, fileSystemtURI);
            if (fileShare != null && !fileShare.getInactive()) {
                return true;
            }
        }
        return false;
    }
    
    
    /**
     * check QuotaDirectory for given nativeGuid exists in DB
     * 
     * @param nativeGuid
     * @return boolean
     * @throws java.io.IOException
     */
    protected boolean checkStorageQuotaDirectoryExistsInDB(String nativeGuid)
            throws IOException {
        URIQueryResultList result = new URIQueryResultList();

        _dbClient.queryByConstraint(AlternateIdConstraint.Factory.getQuotaDirsByNativeGuid(nativeGuid), result);

        Iterator<URI> iter = result.iterator();
        while (iter.hasNext()) {
            URI storageQDURI = iter.next();

            QuotaDirectory quotaDirectory = _dbClient.queryObject(QuotaDirectory.class, storageQDURI);
            if (quotaDirectory != null && !quotaDirectory.getInactive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * check Pre Existing Storage filesystem exists in DB
     * 
     * @param nativeGuid
     * @return unManageFileSystem
     * @throws IOException
     */
    protected UnManagedFileSystem checkUnManagedFileSystemExistsInDB(
            String nativeGuid) throws IOException {
        UnManagedFileSystem filesystemInfo = null;
        URIQueryResultList result = new URIQueryResultList();
        _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                .getFileSystemInfoNativeGUIdConstraint(nativeGuid), result);
        List<URI> filesystemUris = new ArrayList<URI>();
        Iterator<URI> iter = result.iterator();
        while (iter.hasNext()) {
            URI unFileSystemtURI = iter.next();
            filesystemUris.add(unFileSystemtURI);
        }

        for (URI fileSystemURI : filesystemUris) {
            filesystemInfo = _dbClient.queryObject(UnManagedFileSystem.class,
                    fileSystemURI);
            if (filesystemInfo != null && !filesystemInfo.getInactive()) {
                return filesystemInfo;
            }
        }

        return null;

    }
    
    /**
     * check Pre Existing UnManagedFileSystemQuotaDirectory in DB
     * 
     * @param nativeGuid
     * @return UnManagedFileQuotaDirectory
     * @throws IOException
     */
    protected UnManagedFileQuotaDirectory checkUnManagedFileSystemQuotaDirectoryExistsInDB(
            String nativeGuid) throws IOException {
        UnManagedFileQuotaDirectory quotaDirectoryInfo = null;
        URIQueryResultList result = new URIQueryResultList();
        _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                .getUnManagedFileQuotaDirectoryInfoNativeGUIdConstraint(nativeGuid), result);
        List<URI> umfsQdUris = new ArrayList<URI>();
        Iterator<URI> iter = result.iterator();
        while (iter.hasNext()) {
            URI unFileSystemtURI = iter.next();
            umfsQdUris.add(unFileSystemtURI);
        }

        for (URI umfsQdURI : umfsQdUris) {
            quotaDirectoryInfo = _dbClient.queryObject(UnManagedFileQuotaDirectory.class,
                    umfsQdURI);
            if (quotaDirectoryInfo != null && !quotaDirectoryInfo.getInactive()) {
                return quotaDirectoryInfo;
            }
        }

        return null;

    }

    /**
     * Check if Pool exists in DB.
     * 
     * @param nativeGuid
     * @return
     * @throws IOException
     */
    protected StoragePool checkStoragePoolExistsInDB(String nativeGuid)
            throws IOException {
        StoragePool pool = null;
        // use NativeGuid to lookup Pools in DB
        @SuppressWarnings("deprecation")
        List<URI> poolURIs = _dbClient
                .queryByConstraint(AlternateIdConstraint.Factory
                        .getStoragePoolByNativeGuidConstraint(nativeGuid));
        for (URI poolURI : poolURIs) {
            pool = _dbClient.queryObject(StoragePool.class, poolURI);
            if (pool != null && !pool.getInactive()) {
                return pool;
            }
        }
        return null;
    }

    /*
     * get Storage Pool
     * 
     * @return
     */
    private StoragePool getStoragePool(StorageSystem storageSystem) throws IOException {
        StoragePool storagePool = null;
        // Check if storage pool was already discovered
        URIQueryResultList storagePoolURIs = new URIQueryResultList();
        _dbClient.queryByConstraint(
                ContainmentConstraint.Factory.getStorageDeviceStoragePoolConstraint(storageSystem.getId()),
                storagePoolURIs);
        Iterator<URI> storagePoolIter = storagePoolURIs.iterator();
        while (storagePoolIter.hasNext()) {
            URI storagePoolURI = storagePoolIter.next();
            storagePool = _dbClient.queryObject(StoragePool.class,
                    storagePoolURI);
            if (storagePool != null && !storagePool.getInactive()) {
                _log.debug("found a pool for storage system  {} {}",
                        storageSystem.getSerialNumber(), storagePool);
                return storagePool;
            }
        }
        return null;
    }

    private StoragePort getStoragePortPool(StorageSystem storageSystem)
            throws IOException {
        StoragePort storagePort = null;
        // Check if storage port was already discovered
        URIQueryResultList storagePortURIs = new URIQueryResultList();
        _dbClient.queryByConstraint(
                ContainmentConstraint.Factory.getStorageDeviceStoragePortConstraint(storageSystem.getId()),
                storagePortURIs);
        Iterator<URI> storagePortIter = storagePortURIs.iterator();
        while (storagePortIter.hasNext()) {
            URI storagePortURI = storagePortIter.next();
            storagePort = _dbClient.queryObject(StoragePort.class,
                    storagePortURI);
            if (storagePort != null && !storagePort.getInactive()) {
                _log.debug("found a port for storage system  {} {}",
                        storageSystem.getSerialNumber(), storagePort);
                return storagePort;
            }
        }
        return null;
    }

    /**
     * get the NAS server list
     * 
     * @param storageSystem
     * @param accessZones
     * @return
     */
    private Map<String, NASServer> getNASServer(final StorageSystem storageSystem,
            List<IsilonAccessZone> accessZones) {
        NASServer nasServer = null;
        Map<String, NASServer> accessZonesMap = new HashMap<String, NASServer>();
        if (accessZones != null && !accessZones.isEmpty()) {
            for (IsilonAccessZone isilonAccessZone : accessZones) {
                if (isilonAccessZone.isSystem() == false) {
                    nasServer = findvNasByNativeId(storageSystem, isilonAccessZone.getZone_id().toString());
                    if (nasServer != null) {
                        accessZonesMap.put(isilonAccessZone.getPath() + "/", nasServer);
                    } else {
                        _log.info("Nas server not available for path  {}, hence this filesystem will not be ingested",
                                isilonAccessZone.getPath());
                    }
                } else {
                    nasServer = findPhysicalNasByNativeId(storageSystem, isilonAccessZone.getZone_id().toString());
                    if (nasServer != null) {
                        accessZonesMap.put(isilonAccessZone.getPath() + "/", nasServer);
                    } else {
                        _log.info("Nas server not available for path  {}, hence this filesystem will not be ingested",
                                isilonAccessZone.getPath());
                    }
                }
            }
        }
        return accessZonesMap;
    }

    /**
     * Generate Export Map for UnManagedFileSystem
     * Ignore exports with multiple exports for the same path
     * Ignore exports that have multiple security flavors
     * Ignore exports with multiple paths
     * Ignore exports not found on the array
     * Ignore exports which have the same internal export key ( <sec, perm, root-mapping>)
     * 
     * @param umfs
     * @param expIdMap
     * @param storagePort
     * @param fsPath
     * @param isilonApi
     * @return boolean
     */
    private List<UnManagedFileExportRule> getUnManagedFSExportRules(UnManagedFileSystem umfs, HashMap<String, HashSet<Integer>> expIdMap,
            StoragePort storagePort, String fsPath, String zoneName, IsilonApi isilonApi) {
        List<UnManagedFileExportRule> exportRules = new ArrayList<UnManagedFileExportRule>();
        UnManagedExportVerificationUtility validationUtility = new UnManagedExportVerificationUtility(_dbClient);
        List<UnManagedFileExportRule> exportRulesTemp = null;
        boolean isAllRulesValid = true;
        for (String expMapPath : expIdMap.keySet()) {
            HashSet<Integer> isilonExportIds = new HashSet<>();
            _log.info("getUnManagedFSExportMap {} : export ids : {}",
                    expMapPath, expIdMap.get(expMapPath));
            isilonExportIds = expIdMap.get(expMapPath);
            if (isilonExportIds != null && !isilonExportIds.isEmpty()) {
                exportRulesTemp = getUnManagedFSExportRules(umfs, storagePort,
                        isilonExportIds, expMapPath, zoneName, isilonApi);
                // validate export rules for each path
                if (null != exportRulesTemp && !exportRulesTemp.isEmpty()) {
                    isAllRulesValid = validationUtility.validateUnManagedExportRules(
                            exportRulesTemp, false);
                    if (isAllRulesValid) {
                        _log.info("Validating rules success for export {}", expMapPath);
                        exportRules.addAll(exportRulesTemp);
                    } else {
                        _log.info("Ignroing the rules for export {}", expMapPath);
                        isAllRulesValid = false;
                    }
                }
            }
        }

        if (exportRules.isEmpty() || false == isAllRulesValid) {
            umfs.setHasExports(false);
            _log.info("FileSystem " + fsPath + " does not have valid ViPR exports ");
            exportRules.clear();
        }
        return exportRules;
    }

    /**
     * Generate Export Map for UnManagedFileSystem
     * Ignore exports with multiple exports for the same path
     * Ignore exports that have multiple security flavors
     * Ignore exports with multiple paths
     * Ignore exports not found on the array
     * Ignore exports which have the same internal export key ( <sec, perm, root-mapping>)
     * 
     * @param umfs
     * @param expIdMap
     * @param storagePort
     * @param fsPath
     * @param isilonApi
     * @return boolean
     */
    private boolean getUnManagedFSExportMap(UnManagedFileSystem umfs, HashMap<String, HashSet<Integer>> expIdMap,
            StoragePort storagePort, String fsPath, String zoneName, IsilonApi isilonApi,
            List<UnManagedFileExportRule> exportRules) {

        UnManagedFSExportMap exportMap = new UnManagedFSExportMap();

        boolean validExports = false;

        for (String expMapPath : expIdMap.keySet()) {
            HashSet<Integer> isilonExportIds = new HashSet<>();
            _log.info("getUnManagedFSExportMap {} : export ids : {}", expMapPath, expIdMap.get(expMapPath));
            isilonExportIds = expIdMap.get(expMapPath);
            if (isilonExportIds != null && !isilonExportIds.isEmpty()) {
                validExports = getUnManagedFSExportMap(umfs, isilonExportIds,
                        storagePort, fsPath, zoneName, isilonApi, exportRules);
            } else {
                validExports = false;
            }
            if (!validExports) {
                // perform resetting umfs export map
                umfs.setFsUnManagedExportMap(exportMap);
                return false;
            }
        }
        return true;
    }

    /**
     * Generate Export Map for UnManagedFileSystem
     * Ignore exports with multiple exports for the same path
     * Ignore exports that have multiple security flavors
     * Ignore exports with multiple paths
     * Ignore exports not found on the array
     * Ignore exports which have the same internal export key ( <sec, perm, root-mapping>)
     * 
     * @param umfs
     * @param isilonExportIds
     * @param storagePort
     * @param fsPath
     * @param isilonApi
     * @return boolean
     */
    private boolean getUnManagedFSExportMap(UnManagedFileSystem umfs, HashSet<Integer> isilonExportIds,
            StoragePort storagePort, String fsPath, String zoneName, IsilonApi isilonApi,
            List<UnManagedFileExportRule> expRules) {

        UnManagedFSExportMap exportMap = new UnManagedFSExportMap();

        int generatedExportCount = 0;

        ArrayList<IsilonExport> isilonExports = new ArrayList<IsilonExport>();
        if (expRules == null) {
            expRules = new ArrayList<UnManagedFileExportRule>();
        }

        if (isilonExportIds != null && isilonExportIds.size() > 1) {
            _log.info("Found multiple export rules for file system path {}, {} ", fsPath, isilonExportIds.size());
        }

        for (Integer expId : isilonExportIds) {
            IsilonExport exp = getIsilonExport(isilonApi, expId, zoneName);
            if (exp == null) {
                _log.info("Ignoring file system {}, export {} not found", fsPath, expId);
                return false;
            } else if (exp.getPaths().size() > 1) {
                for (String expPath : exp.getPaths()) {
                    if (!expPath.equalsIgnoreCase(fsPath) && !expPath.startsWith(fsPath + "/")) {
                        _log.warn("Ignoring file system {}, as it contains other export path {}", fsPath, expPath);
                        return false;
                    }

                }
            } else {
                isilonExports.add(exp);
            }
        }

        StringSet secTypes = new StringSet();
        for (IsilonExport exp : isilonExports) {
            String csSecurityTypes = "";
            Set<String> orderedList = new TreeSet<String>();
            // If export has more than one security flavor
            // store all security flavor separated by comma(,)
            for (String sec : exp.getSecurityFlavors()) {
                String securityFlavor = sec;
                // Isilon Maps sys to unix and we do this conversion during export from ViPR
                if (sec.equalsIgnoreCase(UNIXSECURITY)) {
                    securityFlavor = SYSSECURITY;
                }
                orderedList.add(securityFlavor);
            }
            Iterator<String> secIter = orderedList.iterator();
            csSecurityTypes = secIter.next().toString();
            while (secIter.hasNext()) {
                csSecurityTypes += "," + secIter.next().toString();
            }

            if (!csSecurityTypes.isEmpty() && secTypes.contains(csSecurityTypes)) {
                _log.warn("Ignoring file system {}, as it contains multiple export rules with same security {}", fsPath, csSecurityTypes);
                return false;
            }
            secTypes.add(csSecurityTypes);

            String path = exp.getPaths().get(0);

            // Get User
            String rootUserMapping = "";
            String mapAllUserMapping = "";
            if (exp.getMap_root() != null && exp.getMap_root().getUser() != null) {
                rootUserMapping = exp.getMap_root().getUser();
            } else if (exp.getMap_all() != null && exp.getMap_all().getUser() != null) {
                mapAllUserMapping = exp.getMap_all().getUser();
            }

            String resolvedUser = (rootUserMapping != null && (!rootUserMapping.isEmpty())) ? rootUserMapping : mapAllUserMapping;

            // Create Export rule!!
            UnManagedFileExportRule expRule = new UnManagedFileExportRule();
            expRule.setExportPath(path);
            expRule.setSecFlavor(csSecurityTypes);
            expRule.setAnon(resolvedUser);
            expRule.setDeviceExportId(exp.getId().toString());
            expRule.setFileSystemId(umfs.getId());
            expRule.setMountPoint(storagePort.getPortNetworkId() + ":" + path);

            if (exp != null && exp.getReadOnlyClients() != null && !exp.getReadOnlyClients().isEmpty()) {
                UnManagedFSExport unManagedROFSExport = new UnManagedFSExport(
                        exp.getReadOnlyClients(), storagePort.getPortName(), storagePort.getPortName() + ":" + path,
                        csSecurityTypes, RO,
                        resolvedUser, NFS, storagePort.getPortName(), path,
                        exp.getPaths().get(0));
                unManagedROFSExport.setIsilonId(exp.getId().toString());
                exportMap.put(unManagedROFSExport.getFileExportKey(), unManagedROFSExport);
                generatedExportCount++;

                expRule.setReadOnlyHosts(new StringSet(exp.getReadOnlyClients()));
            }

            if (exp != null && exp.getReadWriteClients() != null && !exp.getReadWriteClients().isEmpty()) {
                UnManagedFSExport unManagedRWFSExport = new UnManagedFSExport(
                        exp.getReadWriteClients(), storagePort.getPortName(), storagePort.getPortName() + ":" + path,
                        csSecurityTypes, RW,
                        resolvedUser, NFS, storagePort.getPortName(), path,
                        exp.getPaths().get(0));
                unManagedRWFSExport.setIsilonId(exp.getId().toString());
                exportMap.put(unManagedRWFSExport.getFileExportKey(), unManagedRWFSExport);
                generatedExportCount++;

                expRule.setReadWriteHosts(new StringSet(exp.getReadWriteClients()));
            }

            if (exp != null && exp.getRootClients() != null && !exp.getRootClients().isEmpty()) {
                UnManagedFSExport unManagedROOTFSExport = new UnManagedFSExport(
                        exp.getRootClients(), storagePort.getPortName(), storagePort.getPortName() + ":" + path,
                        csSecurityTypes, ROOT,
                        resolvedUser, NFS, storagePort.getPortName(), path,
                        path);
                unManagedROOTFSExport.setIsilonId(exp.getId().toString());
                exportMap.put(unManagedROOTFSExport.getFileExportKey(), unManagedROOTFSExport);
                generatedExportCount++;

                expRule.setRootHosts(new StringSet(exp.getRootClients()));
            }

            if (exp.getReadOnlyClients() != null && exp.getReadWriteClients() != null && exp.getRootClients() != null) {
                // Check Clients size
                if (exp.getReadOnlyClients().isEmpty() && exp.getReadWriteClients().isEmpty() && exp.getRootClients().isEmpty()) {
                    // All hosts case. Check whether it is RO/RW/ROOT

                    if (exp.getReadOnly()) {
                        // This is a read only export for all hosts
                        UnManagedFSExport unManagedROFSExport = new UnManagedFSExport(
                                exp.getClients(), storagePort.getPortName(), storagePort.getPortName() + ":" + path,
                                csSecurityTypes, RO,
                                rootUserMapping, NFS, storagePort.getPortName(), path,
                                path);
                        unManagedROFSExport.setIsilonId(exp.getId().toString());
                        exportMap.put(unManagedROFSExport.getFileExportKey(), unManagedROFSExport);
                        generatedExportCount++;

                        // This is a read only export for all hosts
                        expRule.setReadOnlyHosts(new StringSet(exp.getClients()));

                    } else {
                        // Not read Only case
                        if (exp.getMap_all() != null && exp.getMap_all().getUser() != null
                                && exp.getMap_all().getUser().equalsIgnoreCase(ROOT)) {
                            // All hosts with root permission
                            UnManagedFSExport unManagedROOTFSExport = new UnManagedFSExport(
                                    exp.getClients(), storagePort.getPortName(), storagePort.getPortName() + ":" + path,
                                    csSecurityTypes, ROOT,
                                    mapAllUserMapping, NFS, storagePort.getPortName(), path,
                                    path);
                            unManagedROOTFSExport.setIsilonId(exp.getId().toString());
                            exportMap.put(unManagedROOTFSExport.getFileExportKey(), unManagedROOTFSExport);
                            generatedExportCount++;

                            // All hosts with root permission
                            expRule.setRootHosts(new StringSet(exp.getClients()));

                        } else if (exp.getMap_all() != null) {
                            // All hosts with RW permission
                            UnManagedFSExport unManagedRWFSExport = new UnManagedFSExport(
                                    exp.getClients(), storagePort.getPortName(), storagePort.getPortName() + ":" + path,
                                    csSecurityTypes, RW,
                                    rootUserMapping, NFS, storagePort.getPortName(), path,
                                    path);
                            unManagedRWFSExport.setIsilonId(exp.getId().toString());
                            exportMap.put(unManagedRWFSExport.getFileExportKey(), unManagedRWFSExport);
                            generatedExportCount++;

                            // All hosts with RW permission
                            expRule.setReadWriteHosts(new StringSet(exp.getClients()));
                        }
                    }
                }
            }
            // Create Export rule for the export!!!
            expRules.add(expRule);
        }

        if (exportMap.values().size() < generatedExportCount) {
            // The keys are not unique and so all the exports are not valid
            _log.info(
                    "Ignoring Exports because they have multiple exports with the same internal export key <sec, perm, root-mapping>. Expected {} got {}",
                    generatedExportCount, exportMap.values().size());
            return false;
        }

        // Return valid
        UnManagedFSExportMap allExportMap = umfs.getFsUnManagedExportMap();
        if (allExportMap == null) {
            allExportMap = new UnManagedFSExportMap();
        }
        allExportMap.putAll(exportMap);
        umfs.setFsUnManagedExportMap(allExportMap);

        return true;
    }

    /**
     * Generate Export Map for UnManagedFileSystem
     * Ignore exports with multiple exports for the same path
     * Ignore exports that have multiple security flavors
     * Ignore exports with multiple paths
     * Ignore exports not found on the array
     * Ignore exports which have the same internal export key ( <sec, perm, root-mapping>)
     * 
     * @param umfs
     * @param isilonExportIds
     * @param storagePort
     * @param fsPath
     * @param isilonApi
     * @return boolean
     */
    private List<UnManagedFileExportRule> getUnManagedFSExportRules(UnManagedFileSystem umfs, StoragePort storagePort,
            HashSet<Integer> isilonExportIds, String fsPath, String zoneName, IsilonApi isilonApi) {

        List<UnManagedFileExportRule> expRules = new ArrayList<UnManagedFileExportRule>();
        ArrayList<IsilonExport> isilonExports = new ArrayList<IsilonExport>();

        if (isilonExportIds != null && isilonExportIds.size() > 1) {
            _log.info("Ignoring file system {}, Multiple export rulues found {} ", fsPath, isilonExportIds.size());
        }

        for (Integer expId : isilonExportIds) {
            IsilonExport exp = getIsilonExport(isilonApi, expId, zoneName);
            if (exp == null) {
                _log.info("Ignoring file system {}, export {} not found", fsPath, expId);
            } else if (exp.getSecurityFlavors().size() > 1) {
                _log.info("Ignoring file system {}, multiple security flavors {} found", fsPath, exp.getSecurityFlavors().toString());
            } else if (exp.getPaths().size() > 1) {
                _log.info("Ignoring file system {}, multiple paths {} found", fsPath, exp.getPaths().toString());
            } else {
                isilonExports.add(exp);
            }
        }

        for (IsilonExport exp : isilonExports) {
            String securityFlavor = exp.getSecurityFlavors().get(0);
            // Isilon Maps sys to unix and we do this conversion during export from ViPR
            if (securityFlavor.equalsIgnoreCase(UNIXSECURITY)) {
                securityFlavor = SYSSECURITY;
            }

            String path = exp.getPaths().get(0);

            // Get User
            String rootUserMapping = "";
            String mapAllUserMapping = "";
            if (exp.getMap_root() != null && exp.getMap_root().getUser() != null) {
                rootUserMapping = exp.getMap_root().getUser();
            } else if (exp.getMap_all() != null && exp.getMap_all().getUser() != null) {
                mapAllUserMapping = exp.getMap_all().getUser();
            }

            String resolvedUser = (rootUserMapping != null && (!rootUserMapping.isEmpty())) ? rootUserMapping : mapAllUserMapping;

            UnManagedFileExportRule expRule = new UnManagedFileExportRule();
            expRule.setExportPath(path);
            expRule.setSecFlavor(securityFlavor);
            expRule.setAnon(resolvedUser);
            expRule.setDeviceExportId(exp.getId().toString());
            expRule.setFileSystemId(umfs.getId());
            expRule.setMountPoint(storagePort.getPortNetworkId() + ":" + fsPath);

            if (exp != null && exp.getReadOnlyClients() != null && !exp.getReadOnlyClients().isEmpty()) {
                expRule.setReadOnlyHosts(new StringSet(exp.getReadOnlyClients()));
            }

            if (exp != null && exp.getReadWriteClients() != null && !exp.getReadWriteClients().isEmpty()) {
                expRule.setReadWriteHosts(new StringSet(exp.getReadWriteClients()));
            }

            if (exp != null && exp.getRootClients() != null && !exp.getRootClients().isEmpty()) {
                expRule.setRootHosts(new StringSet(exp.getRootClients()));
            }

            if (exp.getReadOnlyClients() != null && exp.getReadWriteClients() != null && exp.getRootClients() != null) {
                // Check Clients size
                if (exp.getReadOnlyClients().isEmpty() && exp.getReadWriteClients().isEmpty() && exp.getRootClients().isEmpty()) {
                    // All hosts case. Check whether it is RO/RW/ROOT

                    if (exp.getReadOnly()) {
                        // This is a read only export for all hosts
                        expRule.setReadOnlyHosts(new StringSet(exp.getClients()));
                    } else {
                        // Not read Only case
                        if (exp.getMap_all() != null && exp.getMap_all().getUser() != null
                                && exp.getMap_all().getUser().equalsIgnoreCase(ROOT)) {
                            // All hosts with root permission
                            expRule.setRootHosts(new StringSet(exp.getClients()));

                        } else if (exp.getMap_all() != null) {
                            // All hosts with RW permission
                            expRule.setReadWriteHosts(new StringSet(exp.getClients()));
                        }
                    }
                }
            }
            expRules.add(expRule);
        }

        return expRules;
    }

    private HashMap<String, HashSet<Integer>> getExportsIncludingSubDir(String fsPath, HashMap<String, HashSet<Integer>> expMap) {
        HashMap<String, HashSet<Integer>> expMapWithIds = new HashMap<>();
        for (String expPath : expMap.keySet()) {
            if (expPath.equalsIgnoreCase(fsPath) || expPath.contains(fsPath + "/")) {
                HashSet<Integer> expIds = expMap.get(expPath);
                if (expIds != null && !expIds.isEmpty()) {
                    expMapWithIds.put(expPath, expIds);
                } else {
                    expMapWithIds.put(expPath, new HashSet<Integer>());
                }
            }
        }
        return expMapWithIds;
    }

    private HashMap<String, HashMap<String, HashSet<Integer>>> getExportsWithSubDirForFS(List<FileShare> discoveredIsilonFS,
            HashMap<String, HashSet<Integer>> expMap) {
        HashMap<String, HashMap<String, HashSet<Integer>>> expMapTree = new HashMap<>();
        for (FileShare fs : discoveredIsilonFS) {
            expMapTree.put(fs.getPath(), getExportsIncludingSubDir(fs.getPath(), expMap));

        }
        return expMapTree;
    }

    /**
     * convert Isilon's access permissions key set to ViPR's NFS permission set
     * 
     * @param permissions
     * @return
     */
    private ArrayList<String> getIsilonAccessList(ArrayList<String> permissions) {

        ArrayList<String> accessRights = new ArrayList<String>();
        for (String per : permissions) {

            if (per != null) {
                if (per.equalsIgnoreCase(IsilonNFSACL.AccessRights.dir_gen_read.toString())) {
                    accessRights.add("Read");
                }
                if (per.equalsIgnoreCase(IsilonNFSACL.AccessRights.dir_gen_write.toString())) {
                    accessRights.add("write");
                }
                if (per.equalsIgnoreCase(IsilonNFSACL.AccessRights.dir_gen_execute.toString())) {
                    accessRights.add("execute");
                }
                if (per.equalsIgnoreCase(IsilonNFSACL.AccessRights.dir_gen_all.toString())) {
                    accessRights.add("FullControl");
                }
            }
        }
        return accessRights;

    }

    /**
     * set the Max limits for static db metrics
     * 
     * @param system
     * @param dbMetrics
     */
    private void setMaxDbMetricsAz(final StorageSystem system, StringMap dbMetrics) {
        // Set the Limit Metric keys!!
        dbMetrics.put(MetricsKeys.maxStorageObjects.name(), String.valueOf(MAX_STORAGE_OBJECTS));

        Long MaxNfsExports = 0L;
        Long MaxCifsShares = 30000L;

        if (VersionChecker.verifyVersionDetails(ONEFS_V7_2, system.getFirmwareVersion()) > 0) {
            MaxNfsExports = MAX_NFS_EXPORTS_V7_2;
            MaxCifsShares = MAX_CIFS_SHARES;
        }

        dbMetrics.put(MetricsKeys.maxNFSExports.name(), String.valueOf(MaxNfsExports));
        dbMetrics.put(MetricsKeys.maxCifsShares.name(), String.valueOf(MaxCifsShares));

        // set the max capacity in GB
        long MaxCapacity = Math.round(getClusterStorageCapacity(system));
        dbMetrics.put(MetricsKeys.maxStorageCapacity.name(), String.valueOf(MaxCapacity * GB_IN_KB));
        return;
    }

    /**
     * get the cluster capacity using ssh cli command
     * 
     * @param storageSystem
     * @return
     */
    private Double getClusterStorageCapacity(final StorageSystem storageSystem) {
        Double cluserCap = 0.0;
        IsilonSshApi sshDmApi = new IsilonSshApi();
        sshDmApi.setConnParams(storageSystem.getIpAddress(), storageSystem.getUsername(),
                storageSystem.getPassword());
        cluserCap = sshDmApi.getClusterSize();
        return cluserCap;
    }

    /**
     * Create Virtual NAS for the specified Isilon cluster storage array
     * 
     * @param system
     * @param isiAccessZone
     * @return Virtual NAS Server
     */
    private VirtualNAS createVirtualNas(final StorageSystem system, final IsilonAccessZone isiAccessZone) {
        VirtualNAS vNas = new VirtualNAS();

        vNas.setStorageDeviceURI(system.getId());
        // set name
        vNas.setNasName(isiAccessZone.getName());
        vNas.setNativeId(isiAccessZone.getId());
        // set base directory path
        vNas.setBaseDirPath(isiAccessZone.getPath());
        vNas.setNasState(VirtualNasState.LOADED.toString());
        vNas.setId(URIUtil.createId(VirtualNAS.class));

        // set native "Guid"
        String nasNativeGuid = NativeGUIDGenerator.generateNativeGuid(
                system, isiAccessZone.getZone_id().toString(), NativeGUIDGenerator.VIRTUAL_NAS);
        vNas.setNativeGuid(nasNativeGuid);

        StringMap dbMetrics = vNas.getMetrics();
        _log.info("new Virtual NAS created with guid {} ", vNas.getNativeGuid());
        if (dbMetrics == null) {
            dbMetrics = new StringMap();
        }
        // set the Limitation Metrics keys
        setMaxDbMetricsAz(system, dbMetrics);
        vNas.setMetrics(dbMetrics);
        return vNas;
    }

    /**
     * Modify Virtual NAS for the specified Isilon cluster storage array
     * 
     * @param system
     *            the StorageSystem object
     * @param isiAccessZone
     *            accessZone object
     * @param vNas
     *            the VirtualNAS object
     * @return VirtualNAS with updated attributes
     */
    private VirtualNAS copyUpdatedPropertiesInVNAS(final StorageSystem system,
            final IsilonAccessZone isiAccessZone, VirtualNAS vNas) {

        vNas.setStorageDeviceURI(system.getId());
        // set name
        vNas.setNasName(isiAccessZone.getName());
        vNas.setNativeId(isiAccessZone.getId());
        // set base directory path
        vNas.setBaseDirPath(isiAccessZone.getPath());
        vNas.setNasState(VirtualNasState.LOADED.toString());

        StringMap dbMetrics = vNas.getMetrics();
        if (dbMetrics == null) {
            dbMetrics = new StringMap();
        }
        // set the Limitation Metrics keys
        setMaxDbMetricsAz(system, dbMetrics);
        vNas.setMetrics(dbMetrics);
        return vNas;
    }

    /**
     * Create Physical NAS for the specified Isilon cluster storage array
     * 
     * @param system
     * @param isiAccessZone
     * @return Physical NAS Server
     */
    private PhysicalNAS createPhysicalNas(final StorageSystem system, IsilonAccessZone isiAccessZone) {
        PhysicalNAS phyNas = new PhysicalNAS();

        phyNas.setStorageDeviceURI(system.getId());
        phyNas.setNasName(isiAccessZone.getName());
        phyNas.setNativeId(isiAccessZone.getId());
        // set base directory path

        phyNas.setNasState(VirtualNasState.LOADED.toString());
        phyNas.setId(URIUtil.createId(PhysicalNAS.class));
        // Set storage port details to vNas
        String physicalNasNativeGuid = NativeGUIDGenerator.generateNativeGuid(
                system, isiAccessZone.getZone_id().toString(), NativeGUIDGenerator.PHYSICAL_NAS);
        phyNas.setNativeGuid(physicalNasNativeGuid);
        _log.info("Physical NAS created with guid {} ", phyNas.getNativeGuid());

        StringMap dbMetrics = phyNas.getMetrics();
        if (dbMetrics == null) {
            dbMetrics = new StringMap();
        }
        // set the Limitation Metrics keys
        setMaxDbMetricsAz(system, dbMetrics);
        phyNas.setMetrics(dbMetrics);

        return phyNas;
    }

    /**
     * Set the cifs servers for accesszone
     * 
     * @param isiAccessZone
     *            the Isilon access zone object
     * @param nasServer
     *            the NAS server in which CIF server map will be set
     */
    private void setCifsServerMapForNASServer(final IsilonAccessZone isiAccessZone, NASServer nasServer) {

        if (nasServer == null) {
            return;
        }

        _log.info("Set the authentication providers for NAS: {}", nasServer.getNasName());
        String providerName = null;
        String domain = null;
        ArrayList<String> authArrayList = isiAccessZone.getAuth_providers();
        CifsServerMap cifsServersMap = nasServer.getCifsServersMap();
        if (cifsServersMap != null) {
            cifsServersMap.clear();
        } else {
            cifsServersMap = new CifsServerMap();
        }
        if (authArrayList != null && !authArrayList.isEmpty()) {
            for (String authProvider : authArrayList) {
                String[] providerArray = authProvider.split(":");
                providerName = providerArray[0];
                domain = providerArray[1];
                NasCifsServer nasCifsServer = new NasCifsServer();
                nasCifsServer.setName(providerName);
                nasCifsServer.setDomain(domain);
                cifsServersMap.put(providerName, nasCifsServer);
                _log.info("Setting provider: {} and domain: {}", providerName, domain);
            }
        }
        if (isiAccessZone.isAll_auth_providers() == true) {
            String[] providerArray = isiAccessZone.getSystem_provider().split(":");
            providerName = providerArray[0];
            domain = providerArray[1];
            NasCifsServer nasCifsServer = new NasCifsServer();
            nasCifsServer.setName(providerName);
            nasCifsServer.setDomain(domain);
            cifsServersMap.put(providerName, nasCifsServer);
            _log.info("Setting provider: {} and domain: {}", providerName, domain);
        }

        nasServer.setCifsServersMap(cifsServersMap);
    }

    /**
     * Find the Virtual NAS by Native ID for Isilon cluster
     * 
     * @param system
     *            storage system information including credentials.
     * @param Native
     *            id of the specified Virtual NAS
     * @return Virtual NAS Server
     */
    private VirtualNAS findvNasByNativeId(StorageSystem system, String nativeId) {
        URIQueryResultList results = new URIQueryResultList();
        VirtualNAS vNas = null;

        // Set storage port details to vNas
        String nasNativeGuid = NativeGUIDGenerator.generateNativeGuid(
                system, nativeId, NativeGUIDGenerator.VIRTUAL_NAS);

        _dbClient.queryByConstraint(
                AlternateIdConstraint.Factory.getVirtualNASByNativeGuidConstraint(nasNativeGuid),
                results);
        Iterator<URI> iter = results.iterator();
        VirtualNAS tmpVnas = null;
        while (iter.hasNext()) {
            tmpVnas = _dbClient.queryObject(VirtualNAS.class, iter.next());

            if (tmpVnas != null && !tmpVnas.getInactive()) {
                vNas = tmpVnas;
                _log.info("found virtual NAS {}", tmpVnas.getNativeGuid() + ":" + tmpVnas.getNasName());
                break;
            }
        }

        return vNas;
    }

    /**
     * Find the Physical NAS by Native ID for Isilon cluster
     * 
     * @param system
     *            storage system information including credentials.
     * @param Native
     *            id of the specified Physical NAS
     * @return Physical NAS Server
     */
    private PhysicalNAS findPhysicalNasByNativeId(StorageSystem system, String nativeId) {
        PhysicalNAS physicalNas = null;
        URIQueryResultList results = new URIQueryResultList();
        // Set storage port details to vNas
        String nasNativeGuid = NativeGUIDGenerator.generateNativeGuid(
                system, nativeId, NativeGUIDGenerator.PHYSICAL_NAS);

        _dbClient.queryByConstraint(
                AlternateIdConstraint.Factory.getPhysicalNasByNativeGuidConstraint(nasNativeGuid),
                results);
        PhysicalNAS tmpNas = null;
        Iterator<URI> iter = results.iterator();
        while (iter.hasNext()) {
            tmpNas = _dbClient.queryObject(PhysicalNAS.class, iter.next());

            if (tmpNas != null && !tmpNas.getInactive()) {
                physicalNas = tmpNas;
                _log.info("found physical NAS {}", physicalNas.getNativeGuid() + ":" + physicalNas.getNasName());
                break;
            }
        }

        return physicalNas;
    }
    
    private String getCustomConfigPath() {
        URIQueryResultList results = new URIQueryResultList();

        _dbClient.queryByConstraint(AlternateIdConstraint.Factory.getCustomConfigByConfigType(ISILON_PATH_CUSTOMIZATION), results);

        Iterator<URI> iter = results.iterator();

        CustomConfig tempConfig = null;

        while (iter.hasNext()) {
            tempConfig = _dbClient.queryObject(CustomConfig.class, iter.next());
            if (tempConfig != null && !tempConfig.getInactive()) {
                _log.info("Getting custom Config {}  ", tempConfig.getLabel());
                break;
            }
        }
        return tempConfig.getValue();
    }
    
    /**
     * Compute the path length for discovering a file system for a give AccessZone path
     * its
     * CustomConfigPath + 2
     * where path would be like
     * /<access-zone-path>/<vpool_name>/<tenant_name>/<project_name>
     * <access-zone-path> = /ifs/vipr
     */
    private int computeCustomConfigPathLengths(String accessZonePath) {
        String tempCustomConfigPathLength = getCustomConfigPath();
        String initialPath = accessZonePath;
        int discPathLength=0;
        if (StringUtils.isNotEmpty(tempCustomConfigPathLength)) {
            discPathLength = (initialPath + tempCustomConfigPathLength).split("/").length;
        } else {
            _log.error("CustomConfig path {} has not been set ", tempCustomConfigPathLength);
            discPathLength = (initialPath).split("/").length;
        }
        
        return discPathLength;
    }

    /**
     * Find the Storageport by Native ID for given Isilon Cluster
     * 
     * @param system
     * @param nativeId
     * @return storageport object
     */
    private StoragePort findStoragePortByNativeId(StorageSystem system, String nativeId) {
        StoragePort storagePort = null;
        String portNativeGuid = NativeGUIDGenerator.generateNativeGuid(
                system, nativeId,
                NativeGUIDGenerator.PORT);
        // Check if storage port was already discovered
        URIQueryResultList resultSetList = new URIQueryResultList();
        _dbClient.queryByConstraint(AlternateIdConstraint.Factory.getStoragePortByNativeGuidConstraint(portNativeGuid), resultSetList);
        StoragePort port = null;
        for (URI portUri : resultSetList) {
            port = _dbClient.queryObject(StoragePort.class, portUri);
            if (port != null) {
                if (port.getStorageDevice().equals(system.getId()) && !port.getInactive()) {
                    storagePort = port;
                    break;
                }
            }
        }
        return storagePort;
    }

    private StoragePort getStoragePortFromNasServer(NASServer nasServer) {

        if (nasServer.getStoragePorts() != null && !nasServer.getStoragePorts().isEmpty()) {
            for (String strPort : nasServer.getStoragePorts()) {
                StoragePort sp = _dbClient.queryObject(StoragePort.class, URI.create(strPort));
                if (sp != null) {
                    if (sp.getInactive()
                            || !RegistrationStatus.REGISTERED.toString().equalsIgnoreCase(
                                    sp.getRegistrationStatus())
                            || !DiscoveredDataObject.CompatibilityStatus.COMPATIBLE.name()
                                    .equals(sp.getCompatibilityStatus())
                            || !DiscoveryStatus.VISIBLE.name().equals(
                                    sp.getDiscoveryStatus())) {
                        continue;
                    }
                    _log.info("found storage port {} for NAS server {} ", sp.getPortName(), nasServer.getNasName());
                    return sp;
                }
            }

        }
        return null;
    }
}
