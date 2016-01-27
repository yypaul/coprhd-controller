/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.placement;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.emc.storageos.api.service.authorization.PermissionsHelper;
import com.emc.storageos.api.service.impl.placement.FileMirrorRecommendation.Target;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.VpoolRemoteCopyProtectionSettings;
import com.emc.storageos.model.block.VirtualPoolChangeParam;
import com.emc.storageos.volumecontroller.AttributeMatcher;
import com.emc.storageos.volumecontroller.AttributeMatcher.Attributes;
import com.emc.storageos.volumecontroller.Recommendation;
import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;

public class FileMirrorSchedular implements Scheduler {

    public final Logger _log = LoggerFactory
            .getLogger(FileMirrorSchedular.class);

    private DbClient _dbClient;
    private StorageScheduler _storageScheduler;
    private FileStorageScheduler _fileScheduler;

    public void setStorageScheduler(final StorageScheduler storageScheduler) {
        _storageScheduler = storageScheduler;
    }

    public void setDbClient(final DbClient dbClient) {
        _dbClient = dbClient;
    }

    public void setFileScheduler(FileStorageScheduler fileScheduler) {
        _fileScheduler = fileScheduler;
    }

    @Autowired
    protected PermissionsHelper _permissionsHelper = null;

    /**
     * get list mirror recommendation for mirror file shares(local or remote)
     * Select and return one or more storage pools where the filesystem(s) should be created. The
     * placement logic is based on: - varray, only storage devices in the given varray are
     * candidates - destination varrays - vpool, specifies must-meet & best-meet service
     * specifications - access-protocols: storage pools must support all protocols specified in
     * vpool - file replication: if yes, only select storage pools with this capability -
     * best-match, select storage pools which meets desired performance - provision-mode: thick/thin
     * 
     * @param varray
     *            varray requested for source
     * @param project
     *            for the storage
     * @param vpool
     *            vpool requested
     * @param capabilities
     *            vpool capabilities parameters
     * @return list of Recommendation objects to satisfy the request
     */
    @Override
    public List getRecommendationsForResources(VirtualArray varray,
            Project project, VirtualPool vpool,
            VirtualPoolCapabilityValuesWrapper capabilities) {

        List<FileRecommendation> recommendations = null;
        if (vpool.getFileReplicationType().equals(VirtualPool.FileReplicationType.REMOTE.name())) {
            recommendations = getRemoteMirrorRecommendationsForResources(varray, project, vpool, capabilities);

        } else {
            recommendations = getLocalMirrorRecommendationsForResources(varray, project, vpool, capabilities);
        }
        return recommendations;

    }

    /* local mirror related functions */
    /**
     * get list Recommendation for Local Mirror
     * 
     * @param vArray
     * @param project
     * @param vPool
     * @param capabilities
     * @return
     */
    public List getRemoteMirrorRecommendationsForResources(VirtualArray vArray,
            Project project, VirtualPool vPool,
            VirtualPoolCapabilityValuesWrapper capabilities) {

        List<FileRecommendation> targetFileRecommendations = null;
        List<FileMirrorRecommendation> fileMirrorRecommendations = new ArrayList<FileMirrorRecommendation>();
        // Get the source file system recommendations!!!
        capabilities.put(VirtualPoolCapabilityValuesWrapper.PERSONALITY, VirtualPoolCapabilityValuesWrapper.FILE_REPLICATION_SOURCE);

        // Get the recommendation for source!!!
        List<FileRecommendation> sourceFileRecommendations =
                _fileScheduler.getRecommendationsForResources(vArray, project, vPool, capabilities);
        // Process the each recommendations for targets
        for (FileRecommendation sourceFileRecommendation : sourceFileRecommendations) {

            Map<URI, VpoolRemoteCopyProtectionSettings> remoteCopySettings =
                    VirtualPool.getFileRemoteProtectionSettings(vPool, _dbClient);

            String srcSystemType = sourceFileRecommendation.getDeviceType();
            Set<String> systemTypes = new StringSet();
            systemTypes.add(srcSystemType);

            if (remoteCopySettings != null && !remoteCopySettings.isEmpty()) {
                for (Entry<URI, VpoolRemoteCopyProtectionSettings> copy : remoteCopySettings.entrySet()) {
                    // Process each target !!!
                    FileMirrorRecommendation fileMirrorRecommendation = new FileMirrorRecommendation(sourceFileRecommendation);
                    VpoolRemoteCopyProtectionSettings targetCopy = copy.getValue();
                    VirtualPool targetPool = _dbClient.queryObject(VirtualPool.class, targetCopy.getVirtualPool());
                    VirtualArray targetArray = _dbClient.queryObject(VirtualArray.class, targetCopy.getVirtualArray());
                    // Filter the target storage pools!!!
                    Map<String, Object> attributeMap = new HashMap<String, Object>();

                    attributeMap.put(Attributes.system_type.toString(), systemTypes);
                    attributeMap.put(Attributes.remote_copy_mode.toString(), targetCopy.getCopyMode());
                    attributeMap.put(Attributes.source_storage_system.name(), sourceFileRecommendation.getSourceStorageSystem().toString());

                    capabilities.put(VirtualPoolCapabilityValuesWrapper.PERSONALITY,
                            VirtualPoolCapabilityValuesWrapper.FILE_REPLICATION_TARGET);
                    // Get target recommendations!!!
                    targetFileRecommendations = _fileScheduler.placeFileShare(targetArray, targetPool, capabilities, project, attributeMap);

                    String copyMode = vPool.getFileReplicationCopyMode();
                    if (targetFileRecommendations != null && !targetFileRecommendations.isEmpty()) {
                        // prepare the target recommendation
                        FileRecommendation targetRecommendation = targetFileRecommendations.get(0);
                        prepareTargetFileRecommendation(copyMode,
                                vArray, targetRecommendation, fileMirrorRecommendation);

                        fileMirrorRecommendations.add(fileMirrorRecommendation);
                    }
                }
            }
        }
        return fileMirrorRecommendations;
    }

    /* local mirror related functions */
    /**
     * get list Recommendation for Local Mirror
     * 
     * @param vArray
     * @param project
     * @param vPool
     * @param capabilities
     * @return
     */
    public List getLocalMirrorRecommendationsForResources(VirtualArray vArray,
            Project project, VirtualPool vPool,
            VirtualPoolCapabilityValuesWrapper capabilities) {

        List<FileRecommendation> targetFileRecommendations = null;
        List<FileMirrorRecommendation> fileMirrorRecommendations = new ArrayList<FileMirrorRecommendation>();

        // get the recommendation for source -step1
        List<FileRecommendation> sourceFileRecommendations =
                _fileScheduler.getRecommendationsForResources(vArray, project, vPool, capabilities);
        // process the each recommendations for targets
        for (FileRecommendation sourceFileRecommendation : sourceFileRecommendations) {
            // set the source file recommendation
            FileMirrorRecommendation fileMirrorRecommendation = new FileMirrorRecommendation(sourceFileRecommendation);

            // attribute map of target storagesystem and varray
            Map<String, Object> attributeMap = new HashMap<String, Object>();
            Set<String> storageSystemSet = new HashSet<String>();
            storageSystemSet.add(sourceFileRecommendation.getSourceStorageSystem().toString());
            attributeMap.put(AttributeMatcher.Attributes.storage_system.name(), storageSystemSet);

            Set<String> virtualArraySet = new HashSet<String>();
            virtualArraySet.add(vArray.getId().toString());
            attributeMap.put(AttributeMatcher.Attributes.varrays.name(), virtualArraySet);

            // get target recommendations -step2
            targetFileRecommendations = _fileScheduler.placeFileShare(vArray, vPool, capabilities, project, attributeMap);

            String copyMode = vPool.getFileReplicationCopyMode();
            if (targetFileRecommendations != null && !targetFileRecommendations.isEmpty()) {
                // prepare the target recommendation
                FileRecommendation targetRecommendation = targetFileRecommendations.get(0);
                prepareTargetFileRecommendation(copyMode,
                        vArray, targetRecommendation, fileMirrorRecommendation);

                fileMirrorRecommendations.add(fileMirrorRecommendation);
            }

        }
        return fileMirrorRecommendations;
    }

    void prepareTargetFileRecommendation(final String fsCopyMode, final VirtualArray targetVarray,
            FileRecommendation targetFileRecommendation,
            FileMirrorRecommendation fileMirrorRecommendation) {

        // Set target recommendations!!!
        Target target = new Target();
        target.setTargetPool(targetFileRecommendation.getSourceStoragePool());
        target.setTargetStorageDevice(targetFileRecommendation.getSourceStorageSystem());
        if (targetFileRecommendation.getStoragePorts() != null) {
            target.setTargetStoragePortUris(targetFileRecommendation.getStoragePorts());
        }

        if (targetFileRecommendation.getvNAS() != null) {
            target.setTargetvNASURI(targetFileRecommendation.getvNAS());
        }

        if (fileMirrorRecommendation.getVirtualArrayTargetMap() == null) {
            fileMirrorRecommendation.setVirtualArrayTargetMap(new HashMap<URI, FileMirrorRecommendation.Target>());
        }
        fileMirrorRecommendation.getVirtualArrayTargetMap().put(targetVarray.getId(), target);
        // File replication copy mode
        fileMirrorRecommendation.setCopyMode(fsCopyMode);
    }

    /**
     * Gets and verifies that the target varrays passed in the request are accessible to the tenant.
     * 
     * @param project
     *            A reference to the project.
     * @param vpool
     *            class of service, contains target varrays
     * @return A reference to the varrays
     * @throws java.net.URISyntaxException
     * @throws com.emc.storageos.db.exceptions.DatabaseException
     */
     public List<VirtualArray> getTargetVirtualArraysForVirtualPool(final Project project,
            final VirtualPool vpool, final DbClient dbClient,
            final PermissionsHelper permissionHelper) {
        List<VirtualArray> targetVirtualArrays = new ArrayList<VirtualArray>();
        if (VirtualPool.getFileRemoteProtectionSettings(vpool, dbClient) != null) {
            for (URI targetVirtualArray : VirtualPool.getFileRemoteProtectionSettings(vpool, dbClient)
                    .keySet()) {
                VirtualArray nh = dbClient.queryObject(VirtualArray.class, targetVirtualArray);
                targetVirtualArrays.add(nh);
                permissionHelper.checkTenantHasAccessToVirtualArray(
                        project.getTenantOrg().getURI(), nh);
            }
        }
        return targetVirtualArrays;
    }

	@Override
	public List<Recommendation> scheduleStorageForCosChangeUnprotected(
			Volume volume, VirtualPool vpool, List<VirtualArray> targetVarrays,
			VirtualPoolChangeParam param) {
		// TODO Auto-generated method stub
		return null;
	}
}
