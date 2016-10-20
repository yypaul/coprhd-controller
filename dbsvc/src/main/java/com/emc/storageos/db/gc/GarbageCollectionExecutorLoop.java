/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.db.gc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.services.util.NamedThreadPoolExecutor;

import org.apache.curator.framework.recipes.locks.InterProcessLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.common.DependencyTracker;

/**
 * Class schedules and runs GC job
 */
abstract class GarbageCollectionExecutorLoop implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(GarbageCollectionExecutor.class);

    protected DependencyTracker dependencyTracker;
    /**
     * Duration after marked inactive, before GC can process an item
     * this needs to be enough to cover operations in flight
     */
    private static final int DEFAULT_GC_SWEEP_DELAY_MIN = 10;
    protected int gcDelayMins = DEFAULT_GC_SWEEP_DELAY_MIN;

    protected DbClient dbClient;
    protected CoordinatorClient coordinator;
    protected String dbServiceId;

    private List<Future> futures = new ArrayList();
    private ExecutorService executorPool = new NamedThreadPoolExecutor(GarbageCollectionExecutorLoop.class.getSimpleName(), 5);

    GarbageCollectionExecutorLoop() {
    }

    public void setDependencyTracker(DependencyTracker dependencyTracker) {
        this.dependencyTracker = dependencyTracker;
    }

    public void setGcDelayMins(int gcDelayMins) {
        this.gcDelayMins = gcDelayMins;
    }

    public void setDbClient(DbClient dbClient) {
        this.dbClient = dbClient;
    }

    public void setCoordinator(CoordinatorClient coordinator) {
        this.coordinator = coordinator;
    }

    public void setDbServiceId(String dbServiceId) {
        this.dbServiceId = dbServiceId;
    }

    // return true if this GC thread could be run now
    protected abstract boolean preGC();

    /**
     * 
     * @param clazz the GC will run on
     * @param <T>
     * @return true if we can run GC on this class
     **/
    protected abstract <T extends DataObject> boolean canRunGCOnClass(Class<T> clazz);

    /**
     * 
     * @param clazz the GC will run on
     * @param <T>
     **/
    protected abstract <T extends DataObject> GarbageCollectionRunnable genGCTask(Class<T> clazz);

    protected abstract void postGC();
    
    /**
     * return ZK lock name for GC
     * @return
     */
    protected abstract String getGCZKLockName();

    @Override
    public void run() {
    	InterProcessLock lock = null;
    	try {
    		lock = getLockForGC();
            if (lock == null) {
            	log.info("Can't get GC lock, wait for next run.");
                return;
            }
        } catch (Exception e) {
            log.info("Failed to acquire ZK lock for GC", e);
            return;
        }
    	
        try {
            if (!preGC()) {
                return; // can't run GC now
            }

            int maxLevels = dependencyTracker.getLevels();
            for (int i = 0; i < maxLevels; i++) {
                log.info("Now processing level {}", i);
                List<Class<? extends DataObject>> list = new ArrayList(dependencyTracker.getTypesInLevel(i));

                Collections.shuffle(list);

                for (Class<? extends DataObject> clazz : list) {
                    if (canRunGCOnClass(clazz)) {
                        GarbageCollectionRunnable gc = genGCTask(clazz);
                        futures.add(executorPool.submit(gc));
                    }
                }

                waitTasksToComplete();
            }
        } finally {
        	try {
        		postGC();
        	} finally {
        		releaseLockForGC(lock);
        	}
        }
    }

    /**
     * Post handling after GC
     **/
    private void waitTasksToComplete() {
        log.info("Waiting for {} tasks to finish", futures.size());
        for (Future f : futures) {
            try {
                f.get();
            }catch (InterruptedException ex) {
                log.warn("The GC was interrupted e=", ex);
            } catch (ExecutionException ex) {
                log.error("Exception caught: ", ex);
            }
        }

        futures.clear();
        log.info("GC tasks are done");
    }
    
    private InterProcessLock getLockForGC() {
        InterProcessLock lock = null;
        String lockName = getGCZKLockName();
        try {
            log.debug("try to get ZK lock {}", lockName);

            lock = coordinator.getLock(lockName);
            if (lock.acquire(0, TimeUnit.SECONDS) == false) {// try to get the lock timeout=0
                log.info("Can't get ZK lock for GC");
                return null; // failed to get the lock
            }

            log.debug("Get lock {}", lockName);
        } catch (Exception e) {
            log.info("Failed to acquire lock for GC {} Exception e=", lockName, e);
            lock = null;
        }

        return lock;
    }

    private void releaseLockForGC(InterProcessLock lock) {
    	String lockName = getGCZKLockName();
        try {
            lock.release();
            log.debug("Release the ZK lock of {}", lockName);
        } catch (Exception e) {
            log.error("Failed to release the lock for GC {} e=", lockName, e);
        }
    }
}
