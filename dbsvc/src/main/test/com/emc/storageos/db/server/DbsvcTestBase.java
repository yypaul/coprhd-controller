/*
 * Copyright (c) 2008-2013 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.db.server;

import static org.junit.Assert.fail;

import java.beans.Introspector;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.apache.cassandra.config.Schema;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.service.StorageServiceMBean;

import com.emc.storageos.coordinator.client.model.Constants;
import com.emc.storageos.coordinator.client.model.DbVersionInfo;
import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.coordinator.client.service.DrUtil;
import com.emc.storageos.coordinator.client.service.impl.CoordinatorClientInetAddressMap;
import com.emc.storageos.coordinator.common.impl.ConfigurationImpl;
import com.emc.storageos.coordinator.common.impl.ServiceImpl;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.impl.DbClientContext;
import com.emc.storageos.db.client.impl.DbClientImpl;
import com.emc.storageos.db.client.impl.EncryptionProviderImpl;
import com.emc.storageos.db.client.impl.TypeMap;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.upgrade.BaseCustomMigrationCallback;
import com.emc.storageos.db.client.upgrade.InternalDbClient;
import com.emc.storageos.db.common.DataObjectScanner;
import com.emc.storageos.db.common.DbServiceStatusChecker;
import com.emc.storageos.db.common.DependencyChecker;
import com.emc.storageos.db.common.VdcUtil;
import com.emc.storageos.db.server.impl.DbManager;
import com.emc.storageos.db.server.impl.DbServiceImpl;
import com.emc.storageos.db.server.impl.MigrationHandlerImpl;
import com.emc.storageos.db.server.impl.SchemaUtil;
import com.emc.storageos.db.server.upgrade.MockMigrationHandler;
import com.emc.storageos.db.server.util.StubBeaconImpl;
import com.emc.storageos.db.server.util.StubCoordinatorClientImpl;
import com.emc.storageos.security.geo.GeoDependencyChecker;
import com.emc.storageos.security.password.PasswordUtils;
import com.emc.storageos.services.util.JmxServerWrapper;
import com.emc.storageos.services.util.LoggingUtils;


/**
 * Dbsvc unit test base
 */
// Suppress Sonar violation of Lazy initialization of static fields should be synchronized
// Junit test will be called in single thread by default, it's safe to ignore this violation
@SuppressWarnings("squid:S2444")
public class DbsvcTestBase {
    static {
        LoggingUtils.configureIfNecessary("dbtest-log4j.properties");
    }

    private static final Logger _log = LoggerFactory.getLogger(DbsvcTestBase.class);
    protected static TestMockDbServiceImpl _dbsvc;
    protected static ServiceImpl service;
    protected static DbClientImpl _dbClient;
    protected static boolean isDbStarted = false;
    protected static DbVersionInfo sourceVersion;
    protected static File _dataDir;
    protected static CoordinatorClient _coordinator = new StubCoordinatorClientImpl(
            URI.create("thrift://localhost:9160"));
    protected static EncryptionProviderImpl _encryptionProvider = new EncryptionProviderImpl();
    protected static Map<String, List<BaseCustomMigrationCallback>> customMigrationCallbacks = new HashMap<>();
    protected static DbServiceStatusChecker statusChecker = null;
    protected static GeoDependencyChecker _geoDependencyChecker;
    protected static SchemaUtil schemaUtil;
    protected  static final String dataDir="./dbtest";

    // This controls whether the JMX server is started with DBSVC or not. JMX server is used to snapshot Cassandra
    // DB files and dump SSTables to JSON files. However, current JmxServerWrapper.start() implementation blindly
    // calls LocateRegistry.createRegistry() to start a new RMI Registry server on port e.g. 7199. In test cases
    // like DbUpgradeTest which stops the DBSVC then start it again in same OS process, the previous RMI Registry
    // server is still alive and listening on the port, so the 2nd call to LocateRegistry.createRegistry() will
    // throw an exception. There's no standard way to shutdown a RMI Registry server.
    protected static boolean _startJmx;

    private static final String args[] = {
            "dbversion-info.xml",
    };
    /**
     * Deletes given directory
     * 
     * @param dir
     */
    protected static void cleanDirectory(File dir) {
        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                cleanDirectory(file);
            } else {
                file.delete();
            }
        }
        dir.delete();
    }

    @BeforeClass
    public static void setup() throws IOException {
        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext("dbversion-info.xml");

        sourceVersion = (DbVersionInfo)ctx.getBean("dbVersionInfo");
        _dataDir = new File(dataDir);
        if (_dataDir.exists() && _dataDir.isDirectory()) {
            cleanDirectory(_dataDir);
        }
        _dataDir.mkdir();
        
        startDb(sourceVersion.getSchemaVersion(), sourceVersion.getSchemaVersion(), null);
    }

    @AfterClass
    public static void stop() {
        if (isDbStarted) {
            stopAll();
        }

        if (_dataDir != null) {
            cleanDirectory(_dataDir);
            _dataDir = null;
        }

        _log.info("The Dbsvc is stopped");
    }

    protected static void stopAll() {
        TypeMap.clear();
        Introspector.flushCaches();

        isDbStarted = false;
        _dbsvc.stop();
        StorageServiceMBean svc = StorageService.instance;

        if (svc.isInitialized()) {
            svc.stopGossiping();
        }

        if (svc.isRPCServerRunning()) {
            svc.stopRPCServer();
        }

        Schema.instance.clear();

        _log.info("The dbsvc is stopped");

    }

    /**
     * Start embedded DB
     */
    protected static void startDb(String currentVersion, String targetVersion,  String extraModelsPkg) throws IOException {
        startDb(currentVersion, targetVersion, extraModelsPkg, null, false);
    }

    /**
     * Start embedded DB
     */
    protected static void startDb(String currentVersion, String targetVersion, String extraModelsPkg, DataObjectScanner scanner,
    		boolean createMockHandler) throws IOException {
        sourceVersion = new DbVersionInfo();
        sourceVersion.setSchemaVersion(currentVersion);
        
        DbVersionInfo targetVersionInfo = new DbVersionInfo();
        targetVersionInfo.setSchemaVersion(targetVersion);
        
        List<String> packages = new ArrayList<String>();
        packages.add("com.emc.storageos.db.client.model");
        packages.add("com.emc.sa.model");
        if (extraModelsPkg != null) {
            packages.add(extraModelsPkg);
        }
        String[] pkgsArray = packages.toArray(new String[packages.size()]);

        service = new ServiceImpl();
        service.setName("dbsvc");
        service.setVersion(targetVersion);
        service.setEndpoint(URI.create("thrift://localhost:9160"));
        service.setId("db-standalone");
        StubBeaconImpl beacon = new StubBeaconImpl(service);
        if (scanner == null) {
            scanner = new DataObjectScanner();
            scanner.setPackages(pkgsArray);
            scanner.init();
        }

        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext("nodeaddrmap-var.xml");

        CoordinatorClientInetAddressMap inetAddressMap = (CoordinatorClientInetAddressMap) ctx.getBean("inetAddessLookupMap");

        if (inetAddressMap == null) {
            _log.error("CoordinatorClientInetAddressMap is not initialized. Node address lookup will fail.");
        }

        _coordinator.setInetAddessLookupMap(inetAddressMap);
        _coordinator.setDbVersionInfo(sourceVersion);
        
        ConfigurationImpl cfg = new ConfigurationImpl();
        cfg.setKind(Constants.DB_CONFIG);
        cfg.setId(Constants.GLOBAL_ID);
        cfg.setConfig(Constants.SCHEMA_VERSION, currentVersion);
        _coordinator.persistServiceConfiguration(cfg);
        
        statusChecker = new DbServiceStatusChecker();
        statusChecker.setCoordinator(_coordinator);
        statusChecker.setClusterNodeCount(1);
        statusChecker.setDbVersionInfo(sourceVersion);
        statusChecker.setServiceName(service.getName());
        
        SecretKey key = null;
        try {
            KeyGenerator keyGenerator = null;
            keyGenerator = KeyGenerator.getInstance("HmacSHA256");
            key = keyGenerator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            fail("generate key fail");
        }
        
        schemaUtil  = new MockSchemaUtil();
        schemaUtil.setKeyspaceName("Test");
        schemaUtil.setClusterName("Test");
        schemaUtil.setDataObjectScanner(scanner);
        schemaUtil.setService(service);
        schemaUtil.setStatusChecker(statusChecker);
        schemaUtil.setCoordinator(_coordinator);
        schemaUtil.setVdcShortId("datacenter1");
        schemaUtil.setDrUtil(new DrUtil(_coordinator));

        DbClientContext dbctx = new MockDbClientContext();
        dbctx.setClusterName("Test");
        dbctx.setKeyspaceName("Test");
        schemaUtil.setClientContext(dbctx);

        Properties props = new Properties();
        props.put(DbClientImpl.DB_STAT_OPTIMIZE_DISK_SPACE, "false");
        schemaUtil.setDbCommonInfo(props);
        List<String> vdcHosts = new ArrayList();
        vdcHosts.add("127.0.0.1");
        schemaUtil.setVdcNodeList(vdcHosts);
        schemaUtil.setDbCommonInfo(new java.util.Properties());

        JmxServerWrapper jmx = new JmxServerWrapper();
        if (_startJmx) {
            jmx.setEnabled(true);
            jmx.setServiceUrl("service:jmx:rmi://localhost:%d/jndi/rmi://%s:%d/jmxrmi");
            jmx.setHost("127.0.0.1");
            jmx.setPort(7199);
            jmx.setExportPort(7200);
        } else {
            jmx.setEnabled(false);
        }

        _encryptionProvider.setCoordinator(_coordinator);

        _dbClient = getDbClientBase();
        _dbClient.setDbVersionInfo(targetVersionInfo);
        PasswordUtils passwordUtils = new PasswordUtils();
        passwordUtils.setCoordinator(_coordinator);
        passwordUtils.setEncryptionProvider(_encryptionProvider);
        passwordUtils.setDbClient(_dbClient);
        schemaUtil.setPasswordUtils(passwordUtils);


        
        DependencyChecker localDependencyChecker = new DependencyChecker(_dbClient, scanner);
        _geoDependencyChecker = new GeoDependencyChecker(_dbClient, _coordinator, localDependencyChecker);

        _dbsvc = new TestMockDbServiceImpl();
        _dbsvc.setConfig("db-test.yaml");
        _dbsvc.setSchemaUtil(schemaUtil);
        _dbsvc.setCoordinator(_coordinator);
        _dbsvc.setStatusChecker(statusChecker);
        _dbsvc.setService(service);
        _dbsvc.setJmxServerWrapper(jmx);
        _dbsvc.setDbClient(_dbClient);
        _dbsvc.setBeacon(beacon);
        _dbsvc.setDbDir(dataDir);
        _dbsvc.setDisableScheduledDbRepair(true);
        
        _dbsvc.setMigrationHandler(getMigrationHandler(createMockHandler, pkgsArray));
        _dbsvc.setDbMgr(new MockDbManager());
        _dbsvc.start();

        isDbStarted = true;
    }

    private static MigrationHandlerImpl getMigrationHandler(boolean createMockHandler, String[] pkgsArray) {
        MigrationHandlerImpl handler = null;
        if (createMockHandler) {
        	handler = new MockMigrationHandler();
        } else {
        	handler = new MigrationHandlerImpl();
        }
        handler.setPackages(pkgsArray);
        handler.setService(service);
        handler.setStatusChecker(statusChecker);
        handler.setCoordinator(_coordinator);
        handler.setDbClient(_dbClient);
        handler.setSchemaUtil(schemaUtil);
        handler.setPackages(pkgsArray);

        handler.setCustomMigrationCallbacks(customMigrationCallbacks);
        return handler;
    }
    /**
     * Create DbClient to embedded DB
     * 
     * @return
     */
    protected static DbClient getDbClient() {
        return getDbClient(new InternalDbClient());
    }

    protected static DbClient getDbClient(InternalDbClient dbClient) {
        dbClient = getDbClientBase(dbClient);
        dbClient.setBypassMigrationLock(false);
        dbClient.start();
        return dbClient;
    }

    protected static DbClientImpl getDbClientBase() {
        return getDbClientBase(new InternalDbClient());
    }

    protected static InternalDbClient getDbClientBase(InternalDbClient dbClient) {
        dbClient.setCoordinatorClient(_coordinator);
        dbClient.setDbVersionInfo(sourceVersion);
        dbClient.setBypassMigrationLock(true);
        _encryptionProvider.setCoordinator(_coordinator);
        dbClient.setEncryptionProvider(_encryptionProvider);

        DbClientContext localCtx = new MockDbClientContext();
        localCtx.setClusterName("Test");
        localCtx.setKeyspaceName("Test");
        dbClient.setLocalContext(localCtx);
        dbClient.setDrUtil(new DrUtil(_coordinator));
        VdcUtil.setDbClient(dbClient);

        return dbClient;
    }

    protected static CoordinatorClient getCoordinator() {
        return _coordinator;
    }

    static class MockSchemaUtil extends SchemaUtil {
        @Override
        public void insertVdcVersion(final DbClient dbClient) {
            // Do nothing
        }
    }
    
    static class MockDbClientContext extends DbClientContext {
        @Override
        public int getThriftPort() {
            return 9160;
        }
    }
    
    static class MockDbManager extends DbManager {
    	@Override
		public void init() {
    		//do nothing
		}
    	@Override
    	public void start() { 
    		//do nothing
    	}
    }
    
    protected static class TestMockDbServiceImpl extends DbServiceImpl {
        @Override
        public void setDbInitializedFlag() {
            String os = System.getProperty("os.name").toLowerCase();
            if( os.indexOf("windows") >= 0) {
                String currentPath = System.getProperty("user.dir");
                _log.info("CurrentPath is {}", currentPath);
                File dbInitializedFlag = new File(".");
                try {
                    if (!dbInitializedFlag.exists())
                        new FileOutputStream(dbInitializedFlag).close();
                }catch (Exception e) {
                    _log.error("Failed to create file {} e", dbInitializedFlag.getName(), e);
                }
            }else{
                super.setDbInitializedFlag();
            }
            
        }
    }
    
    protected void cleanupDataObjectCF(Class<? extends DataObject> clazz) {
        List<URI> uriList = _dbClient.queryByType(Volume.class, false);
        List<DataObject> dataObjects = new ArrayList<DataObject>();
        for (URI uri : uriList) {
            try {
                DataObject dataObject = clazz.newInstance();
                dataObject.setId(uri);
                dataObjects.add(dataObject);
            } catch (Exception e) {
                _log.error("Failed to create instance of Class {} e", clazz, e);
            }
        }
        _dbClient.internalRemoveObjects(dataObjects.toArray(new DataObject[0]));
    }
}
