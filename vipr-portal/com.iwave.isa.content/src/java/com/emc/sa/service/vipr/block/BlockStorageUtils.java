/*
 * Copyright (c) 2012-2015 iWave Software LLC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.block;

import static com.emc.sa.service.ServiceParams.CONSISTENCY_GROUP;
import static com.emc.sa.service.ServiceParams.HLU;
import static com.emc.sa.service.ServiceParams.HOST;
import static com.emc.sa.service.ServiceParams.MAX_PATHS;
import static com.emc.sa.service.ServiceParams.MIN_PATHS;
import static com.emc.sa.service.ServiceParams.NAME;
import static com.emc.sa.service.ServiceParams.NUMBER_OF_VOLUMES;
import static com.emc.sa.service.ServiceParams.PATHS_PER_INITIATOR;
import static com.emc.sa.service.ServiceParams.PROJECT;
import static com.emc.sa.service.ServiceParams.SIZE_IN_GB;
import static com.emc.sa.service.ServiceParams.VIRTUAL_ARRAY;
import static com.emc.sa.service.ServiceParams.VIRTUAL_POOL;
import static com.emc.sa.service.vipr.ViPRExecutionUtils.addAffectedResource;
import static com.emc.sa.service.vipr.ViPRExecutionUtils.addAffectedResources;
import static com.emc.sa.service.vipr.ViPRExecutionUtils.addRollback;
import static com.emc.sa.service.vipr.ViPRExecutionUtils.execute;
import static com.emc.sa.util.ResourceType.BLOCK_SNAPSHOT;
import static com.emc.sa.util.ResourceType.VOLUME;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import com.emc.sa.engine.ExecutionContext;
import com.emc.sa.engine.ExecutionException;
import com.emc.sa.engine.ExecutionUtils;
import com.emc.sa.engine.bind.Param;
import com.emc.sa.machinetags.KnownMachineTags;
import com.emc.sa.machinetags.MachineTagUtils;
import com.emc.sa.service.vipr.ViPRExecutionUtils;
import com.emc.sa.service.vipr.application.tasks.GetBlockSnapshotSession;
import com.emc.sa.service.vipr.application.tasks.GetBlockSnapshotSessionList;
import com.emc.sa.service.vipr.application.tasks.GetBlockSnapshotSet;
import com.emc.sa.service.vipr.application.tasks.GetFullCopyList;
import com.emc.sa.service.vipr.block.tasks.AddClusterToExport;
import com.emc.sa.service.vipr.block.tasks.AddHostToExport;
import com.emc.sa.service.vipr.block.tasks.AddJournalCapacity;
import com.emc.sa.service.vipr.block.tasks.AddVolumesToConsistencyGroup;
import com.emc.sa.service.vipr.block.tasks.AddVolumesToExport;
import com.emc.sa.service.vipr.block.tasks.AdjustExportPaths;
import com.emc.sa.service.vipr.block.tasks.CreateBlockVolume;
import com.emc.sa.service.vipr.block.tasks.CreateBlockVolumeByName;
import com.emc.sa.service.vipr.block.tasks.CreateContinuousCopy;
import com.emc.sa.service.vipr.block.tasks.CreateExport;
import com.emc.sa.service.vipr.block.tasks.CreateExportNoWait;
import com.emc.sa.service.vipr.block.tasks.CreateFullCopy;
import com.emc.sa.service.vipr.block.tasks.CreateMultipleBlockVolumes;
import com.emc.sa.service.vipr.block.tasks.CreateSnapshotFullCopy;
import com.emc.sa.service.vipr.block.tasks.DeactivateBlockExport;
import com.emc.sa.service.vipr.block.tasks.DeactivateBlockSnapshot;
import com.emc.sa.service.vipr.block.tasks.DeactivateBlockSnapshotSession;
import com.emc.sa.service.vipr.block.tasks.DeactivateContinuousCopy;
import com.emc.sa.service.vipr.block.tasks.DeactivateVolume;
import com.emc.sa.service.vipr.block.tasks.DeactivateVolumes;
import com.emc.sa.service.vipr.block.tasks.DetachFullCopy;
import com.emc.sa.service.vipr.block.tasks.ExpandVolume;
import com.emc.sa.service.vipr.block.tasks.FindBlockVolumeHlus;
import com.emc.sa.service.vipr.block.tasks.FindEmptyExportByName;
import com.emc.sa.service.vipr.block.tasks.FindExportByCluster;
import com.emc.sa.service.vipr.block.tasks.FindExportByHost;
import com.emc.sa.service.vipr.block.tasks.FindExportsContainingCluster;
import com.emc.sa.service.vipr.block.tasks.FindExportsContainingHost;
import com.emc.sa.service.vipr.block.tasks.FindVirtualArrayInitiators;
import com.emc.sa.service.vipr.block.tasks.GetActiveContinuousCopiesForVolume;
import com.emc.sa.service.vipr.block.tasks.GetActiveFullCopiesForVolume;
import com.emc.sa.service.vipr.block.tasks.GetActiveSnapshotSessionsForVolume;
import com.emc.sa.service.vipr.block.tasks.GetActiveSnapshotsForVolume;
import com.emc.sa.service.vipr.block.tasks.GetBlockConsistencyGroup;
import com.emc.sa.service.vipr.block.tasks.GetBlockContinuousCopies;
import com.emc.sa.service.vipr.block.tasks.GetBlockExport;
import com.emc.sa.service.vipr.block.tasks.GetBlockExports;
import com.emc.sa.service.vipr.block.tasks.GetBlockResource;
import com.emc.sa.service.vipr.block.tasks.GetBlockSnapshot;
import com.emc.sa.service.vipr.block.tasks.GetBlockSnapshots;
import com.emc.sa.service.vipr.block.tasks.GetBlockVolume;
import com.emc.sa.service.vipr.block.tasks.GetBlockVolumeByWWN;
import com.emc.sa.service.vipr.block.tasks.GetBlockVolumes;
import com.emc.sa.service.vipr.block.tasks.GetExportsForBlockObject;
import com.emc.sa.service.vipr.block.tasks.GetVolumeByName;
import com.emc.sa.service.vipr.block.tasks.PauseContinuousCopy;
import com.emc.sa.service.vipr.block.tasks.RemoveBlockResourcesFromExport;
import com.emc.sa.service.vipr.block.tasks.RestoreFromFullCopy;
import com.emc.sa.service.vipr.block.tasks.ResynchronizeBlockSnapshot;
import com.emc.sa.service.vipr.block.tasks.ResynchronizeFullCopy;
import com.emc.sa.service.vipr.block.tasks.StartBlockSnapshot;
import com.emc.sa.service.vipr.block.tasks.StartFullCopy;
import com.emc.sa.service.vipr.block.tasks.SwapCGContinuousCopies;
import com.emc.sa.service.vipr.block.tasks.SwapContinuousCopies;
import com.emc.sa.service.vipr.block.tasks.VerifyVolumeDependencies;
import com.emc.sa.service.vipr.tasks.GetActionableEvents;
import com.emc.sa.service.vipr.tasks.GetCluster;
import com.emc.sa.service.vipr.tasks.GetHost;
import com.emc.sa.service.vipr.tasks.GetStorageSystem;
import com.emc.sa.service.vipr.tasks.GetVirtualArray;
import com.emc.sa.util.DiskSizeConversionUtils;
import com.emc.sa.util.ResourceType;
import com.emc.storageos.coordinator.client.model.Constants;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.Cluster;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.Host;
import com.emc.storageos.db.client.model.HostInterface.Protocol;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.StorageProvider;
import com.emc.storageos.db.client.model.Volume.ReplicationState;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.model.NamedRelatedResourceRep;
import com.emc.storageos.model.RelatedResourceRep;
import com.emc.storageos.model.VirtualArrayRelatedResourceRep;
import com.emc.storageos.model.block.BlockConsistencyGroupRestRep;
import com.emc.storageos.model.block.BlockMirrorRestRep;
import com.emc.storageos.model.block.BlockObjectRestRep;
import com.emc.storageos.model.block.BlockSnapshotRestRep;
import com.emc.storageos.model.block.BlockSnapshotSessionRestRep;
import com.emc.storageos.model.block.NamedVolumesList;
import com.emc.storageos.model.block.VolumeDeleteTypeEnum;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.storageos.model.block.VolumeRestRep.FullCopyRestRep;
import com.emc.storageos.model.block.export.ExportBlockParam;
import com.emc.storageos.model.block.export.ExportGroupRestRep;
import com.emc.storageos.model.block.export.ITLRestRep;
import com.emc.storageos.model.block.export.InitiatorPathParam;
import com.emc.storageos.model.systems.StorageSystemRestRep;
import com.emc.storageos.model.varray.VirtualArrayRestRep;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCode;
import com.emc.vipr.client.Task;
import com.emc.vipr.client.Tasks;
import com.emc.vipr.client.ViPRCoreClient;
import com.emc.vipr.client.core.filters.ExportClusterFilter;
import com.emc.vipr.client.core.filters.ExportHostFilter;
import com.emc.vipr.client.core.util.ResourceUtils;
import com.emc.vipr.client.exceptions.ServiceErrorException;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;

public class BlockStorageUtils {
    private static final Logger log = Logger.getLogger(BlockStorageUtils.class);
    public static final String COPY_NATIVE = "native";
    public static final String COPY_RP = "rp";
    public static final String COPY_SRDF = "srdf";

    public static boolean isHost(URI id) {
        return StringUtils.startsWith(id.toString(), "urn:storageos:Host");
    }

    public static boolean isCluster(URI id) {
        return StringUtils.startsWith(id.toString(), "urn:storageos:Cluster");
    }

    public static Host getHost(URI hostId) {
        if (NullColumnValueGetter.isNullURI(hostId)) {
            return null;
        }
        return execute(new GetHost(hostId));
    }

    public static void checkEvents(DataObject resource) {
        execute(new GetActionableEvents(resource));
    }

    public static Cluster getCluster(URI clusterId) {
        if (NullColumnValueGetter.isNullURI(clusterId)) {
            return null;
        }
        return execute(new GetCluster(clusterId));
    }

    public static String getHostOrClusterId(URI hostOrClusterId) {
        String id = null;
        if (hostOrClusterId != null) {
            id = hostOrClusterId.toString();
            if (BlockStorageUtils.isHost(hostOrClusterId)) {
                Host host = BlockStorageUtils.getHost(hostOrClusterId);
                if (host.getCluster() != null) {
                    Cluster cluster = BlockStorageUtils.getCluster(host.getCluster());
                    if (cluster != null) {
                        id = cluster.getId().toString();
                    }
                }
            }
        }
        return id;
    }

    public static BlockObjectRestRep getVolume(URI volumeId) {
        return getBlockResource(volumeId);
    }

    public static StorageSystemRestRep getStorageSystem(URI storageSystemId) {
        return execute(new GetStorageSystem(storageSystemId));
    }

    public static BlockSnapshotRestRep getSnapshot(URI snapshotId) {
        return execute(new GetBlockSnapshot(snapshotId));
    }

    public static BlockObjectRestRep getBlockResource(URI resourceId) {
        return execute(new GetBlockResource(resourceId));
    }

    public static BlockConsistencyGroupRestRep getBlockConsistencyGroup(URI resourceId) {
        return execute(new GetBlockConsistencyGroup(resourceId));
    }

    public static VirtualArrayRestRep getVirtualArray(URI id) {
        return execute(new GetVirtualArray(id));
    }

    private static List<VolumeRestRep> getVolumes(List<URI> volumeIds) {
        return execute(new GetBlockVolumes(volumeIds));
    }

    private static List<BlockSnapshotRestRep> getBlockSnapshots(List<URI> uris) {
        return execute(new GetBlockSnapshots(uris));
    }

    private static List<BlockMirrorRestRep> getBlockContinuousCopies(List<URI> uris, URI parentId) {
        return execute(new GetBlockContinuousCopies(uris, parentId));
    }

    /**
     * Verify that list of volume doesn't contain any dependencies (snapshot, full copies, continuous copy)
     *
     * @param volumeIds of the volumes to validate dependencies
     */
    public static void verifyVolumeDependencies(List<URI> volumeIds, URI projectId) {
        List<URI> allBlockResources = Lists.newArrayList(volumeIds);
        for (URI volumeId : volumeIds) {
            BlockObjectRestRep volume = getVolume(volumeId);
            allBlockResources.addAll(getSrdfTargetVolumes(volume));
            allBlockResources.addAll(getRpTargetVolumes(volume));
        }

        execute(new VerifyVolumeDependencies(allBlockResources, projectId));
    }

    /**
     * Return true of false if a given volume is a VMFS datastore.
     *
     * @param blockObject to validate
     * @return true or false if the volume is a VMFS Datastore
     */
    public static boolean isVolumeVMFSDatastore(BlockObjectRestRep blockObject) {
        if (blockObject != null) {
            Set<String> volumeTags = blockObject.getTags();
            if (volumeTags != null) {
                Map<String, String> parsedTags = MachineTagUtils.parseMachineTags(volumeTags);

                for (String tag : parsedTags.keySet()) {
                    if (tag != null && tag.startsWith(KnownMachineTags.getVmfsDatastoreTagName())) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Return true of false if a given volume is mounted.
     *
     * @param blockObject to validate
     * @return true or false if the volume is mounted
     */
    public static boolean isVolumeMounted(BlockObjectRestRep blockObject) {
        if (blockObject != null) {
            Set<String> volumeTags = blockObject.getTags();
            if (volumeTags != null) {
                Map<String, String> parsedTags = MachineTagUtils.parseMachineTags(volumeTags);

                for (String tag : parsedTags.keySet()) {
                    if (tag != null && tag.startsWith(KnownMachineTags.getHostMountPointTagName())) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Retrieve a list of block resources based on the resource ids provided. This will gather
     * the appropriate resources based on the resource type of the ids provided.
     *
     * @param resourceIds of the resources to retrieve.
     * @return list of block resources
     */
    public static List<BlockObjectRestRep> getBlockResources(List<URI> resourceIds) {
        return getBlockResources(resourceIds, null);
    }

    /**
     * Retrieve a list of block resources based on the resource ids provided. This will gather
     * the appropriate resources based on the resource type of the ids provided.
     *
     * @param resourceIds of the resources to retrieve.
     * @param parentId of a continuous copy. This will be null for all other resource types.
     * @return list of block resources
     */

    public static List<BlockObjectRestRep> getBlockResources(List<URI> resourceIds, URI parentId) {
        List<BlockObjectRestRep> blockResources = Lists.newArrayList();
        List<URI> blockVolumes = new ArrayList<URI>();
        List<URI> blockSnapshots = new ArrayList<URI>();
        List<URI> blockContinuousCopies = new ArrayList<URI>();
        for (URI resourceId : resourceIds) {
            ResourceType volumeType = ResourceType.fromResourceId(resourceId.toString());
            switch (volumeType) {
                case VOLUME:
                    blockVolumes.add(resourceId);
                    break;
                case BLOCK_SNAPSHOT:
                    blockSnapshots.add(resourceId);
                    break;
                case BLOCK_CONTINUOUS_COPY:
                    blockContinuousCopies.add(resourceId);
                    break;
                default:
                    break;
            }
        }
        if (!blockVolumes.isEmpty()) {
            blockResources.addAll(getVolumes(blockVolumes));
        }
        if (!blockSnapshots.isEmpty()) {
            blockResources.addAll(getBlockSnapshots(blockSnapshots));
        }
        if (!blockContinuousCopies.isEmpty()) {
            blockResources.addAll(getBlockContinuousCopies(blockContinuousCopies, parentId));
        }
        return blockResources;
    }

    public static VolumeRestRep getVolumeByWWN(String volumeWWN) {
        return execute(new GetBlockVolumeByWWN(volumeWWN));
    }

    public static List<VolumeRestRep> getVolumeByName(String name) {
        return execute(new GetVolumeByName(name));
    }

    public static ExportGroupRestRep getExport(URI exportId) {
        return execute(new GetBlockExport(exportId));
    }

    public static List<ExportGroupRestRep> getExports(List<URI> exportIds) {
        return execute(new GetBlockExports(exportIds));
    }

    public static ExportGroupRestRep findExportByHost(Host host, URI projectId, URI varrayId, URI volume) {
        return execute(new FindExportByHost(host.getId(), projectId, varrayId, volume));
    }

    public static ExportGroupRestRep findExportByCluster(Cluster cluster, URI projectId, URI varrayId, URI volume) {
        return execute(new FindExportByCluster(cluster.getId(), projectId, varrayId, volume));
    }

    public static List<ExportGroupRestRep> findExportsContainingCluster(URI cluster, URI projectId, URI varrayId) {
        return execute(new FindExportsContainingCluster(cluster, projectId, varrayId));
    }

    public static List<ExportGroupRestRep> findExportsContainingHost(URI host, URI projectId, URI varrayId) {
        return execute(new FindExportsContainingHost(host, projectId, varrayId));
    }

    public static ExportGroupRestRep findEmptyExportsByName(String name, URI projectId, URI varrayId) {
        return execute(new FindEmptyExportByName(name, projectId, varrayId));
    }
    
    public static URI adjustExportPaths(URI vArray, Integer minPaths, Integer maxPaths, Integer pathsPerInitiator,
            URI storageSystemId, URI id, List<InitiatorPathParam> addedPaths, List<InitiatorPathParam> removedPaths,
            boolean suspendWait) {
        
        Task<ExportGroupRestRep> task = execute(new AdjustExportPaths(vArray, minPaths, maxPaths, pathsPerInitiator,
                storageSystemId, id, addedPaths, removedPaths, suspendWait));
        URI exportId = task.getResourceId();
        addAffectedResource(exportId);
        return exportId;
    }

    public static List<URI> addJournalCapacity(URI projectId, URI virtualArrayId, URI virtualPoolId, double sizeInGb, Integer count,
            URI consistencyGroupId, String copyName) {
        String volumeSize = gbToVolumeSize(sizeInGb);
        Tasks<VolumeRestRep> tasks = execute(new AddJournalCapacity(virtualPoolId, virtualArrayId, projectId, volumeSize,
                count, consistencyGroupId, copyName));
        List<URI> volumeIds = Lists.newArrayList();
        for (Task<VolumeRestRep> task : tasks.getTasks()) {
            URI volumeId = task.getResourceId();
            addAffectedResource(volumeId);
            volumeIds.add(volumeId);
        }
        return volumeIds;
    }

    public static List<URI> createMultipleVolumes(List<? extends CreateBlockVolumeHelper> helpers) {
        Tasks<VolumeRestRep> tasks = execute(new CreateMultipleBlockVolumes(helpers));
        List<URI> volumeIds = Lists.newArrayList();
        for (Task<VolumeRestRep> task : tasks.getTasks()) {
            URI volumeId = task.getResourceId();
            addAffectedResource(volumeId);
            volumeIds.add(volumeId);
        }
        addRollback(new DeactivateVolumes(volumeIds, VolumeDeleteTypeEnum.FULL));
        return volumeIds;
    }

    public static List<URI> createVolumes(URI projectId, URI virtualArrayId, URI virtualPoolId,
            String baseVolumeName, double sizeInGb, Integer count, URI consistencyGroupId, URI computeResource) {
        String volumeSize = gbToVolumeSize(sizeInGb);
        Tasks<VolumeRestRep> tasks = execute(new CreateBlockVolume(virtualPoolId, virtualArrayId, projectId, volumeSize,
                count, baseVolumeName, consistencyGroupId, computeResource));
        List<URI> volumeIds = Lists.newArrayList();
        for (Task<VolumeRestRep> task : tasks.getTasks()) {
            URI volumeId = task.getResourceId();
            addRollback(new DeactivateVolume(volumeId, VolumeDeleteTypeEnum.FULL));
            addAffectedResource(volumeId);
            volumeIds.add(volumeId);
        }
        return volumeIds;
    }

    public static Task<VolumeRestRep> createVolumesByName(URI projectId, URI virtualArrayId, URI virtualPoolId,
            double sizeInGb, URI consistencyGroupId, String volumeName) {
        String volumeSize = gbToVolumeSize(sizeInGb);
        return execute(new CreateBlockVolumeByName(projectId, virtualArrayId,
                virtualPoolId, volumeSize, consistencyGroupId, volumeName));
    }

    public static void expandVolumes(Collection<URI> volumeIds, double newSizeInGB) {
        for (URI volumeId : volumeIds) {
            expandVolume(volumeId, newSizeInGB);
        }
    }

    public static void expandVolume(URI volumeId, double newSizeInGB) {
        String newSize = gbToVolumeSize(newSizeInGB);
        Task<VolumeRestRep> task = execute(new ExpandVolume(volumeId, newSize));
        addAffectedResource(task);
    }

    public static URI createHostExport(URI projectId, URI virtualArrayId, List<URI> volumeIds, Integer hlu, Host host,
            Map<URI, Integer> volumeHlus, Integer minPaths, Integer maxPaths, Integer pathsPerInitiator) {
        String exportName = host.getHostName();
        Task<ExportGroupRestRep> task = execute(new CreateExport(exportName, virtualArrayId, projectId, volumeIds, hlu,
                host.getHostName(), host.getId(), null, volumeHlus, minPaths, maxPaths, pathsPerInitiator));
        URI exportId = task.getResourceId();
        addRollback(new DeactivateBlockExport(exportId));
        addAffectedResource(exportId);
        return exportId;
    }

    public static Task<ExportGroupRestRep> createHostExportNoWait(URI projectId, URI virtualArrayId,
            List<URI> volumeIds, Integer hlu, Host host) {
        String exportName = host.getHostName();
        return execute(new CreateExportNoWait(exportName, virtualArrayId, projectId,
                volumeIds, hlu, host.getHostName(), host.getId(), null));
    }

    public static URI createClusterExport(URI projectId, URI virtualArrayId, List<URI> volumeIds, Integer hlu, Cluster cluster,
            Map<URI, Integer> volumeHlus, Integer minPaths, Integer maxPaths, Integer pathsPerInitiator) {
        String exportName = cluster.getLabel();
        Task<ExportGroupRestRep> task = execute(new CreateExport(exportName, virtualArrayId, projectId, volumeIds, hlu,
                cluster.getLabel(), null, cluster.getId(), volumeHlus, minPaths, maxPaths, pathsPerInitiator));
        URI exportId = task.getResourceId();
        addRollback(new DeactivateBlockExport(exportId));
        addAffectedResource(exportId);
        return exportId;
    }

    public static void addVolumesToExport(Collection<URI> volumeIds, Integer hlu, URI exportId, Map<URI, Integer> volumeHlus,
            Integer minPaths, Integer maxPaths, Integer pathsPerInitiator) {
        Task<ExportGroupRestRep> task = execute(new AddVolumesToExport(exportId, volumeIds, hlu, volumeHlus, minPaths, maxPaths,
                pathsPerInitiator));
        addRollback(new RemoveBlockResourcesFromExport(exportId, volumeIds));
        addAffectedResource(task);
    }

    public static void addHostToExport(URI exportId, URI host, Integer minPaths, Integer maxPaths, Integer pathsPerInitiator) {
        Task<ExportGroupRestRep> task = execute(new AddHostToExport(exportId, host, minPaths, maxPaths, pathsPerInitiator));
        addRollback(new DeactivateBlockExport(exportId));
        addAffectedResource(task);
    }

    public static void addClusterToExport(URI exportId, URI cluster, Integer minPaths, Integer maxPaths, Integer pathsPerInitiator) {
        Task<ExportGroupRestRep> task = execute(new AddClusterToExport(exportId, cluster, minPaths, maxPaths, pathsPerInitiator));
        addRollback(new DeactivateBlockExport(exportId));
        addAffectedResource(task);
    }

    public static List<ITLRestRep> getExportsForBlockObject(URI blockObjectId) {
        return execute(new GetExportsForBlockObject(blockObjectId));
    }

    /** build map of export id to set of volumes in that export */
    protected static Map<URI, Set<URI>> getExportToVolumesMap(List<URI> volumeIds) {
        Map<URI, Set<URI>> exportToVolumesMap = Maps.newHashMap();
        for (URI volumeId : volumeIds) {
            for (ITLRestRep export : getExportsForBlockObject(volumeId)) {
                Set<URI> volumesInExport = exportToVolumesMap.get(export.getExport().getId());
                if (volumesInExport == null) {
                    volumesInExport = Sets.newHashSet(volumeId);
                }
                else {
                    volumesInExport.add(volumeId);
                }
                exportToVolumesMap.put(export.getExport().getId(), volumesInExport);
            }
        }
        return exportToVolumesMap;
    }

    public static void removeBlockResourcesFromExports(Map<URI, Set<URI>> exportToVolumesMap) {
        for (Map.Entry<URI, Set<URI>> entry : exportToVolumesMap.entrySet()) {
            // Check to see if the export returned is an internal export; one used by internal orchestrations only.
            ExportGroupRestRep export = getExport(entry.getKey());
            if (ResourceUtils.isNotInternal(export)) {
                removeBlockResourcesFromExport(entry.getValue(), entry.getKey());
            }
        }
    }

    public static void removeBlockResourcesFromExports(Collection<URI> blockResourceIds) {
        Map<URI, Set<URI>> resourcesInExport = Maps.newHashMap();
        for (URI blockResourceId : blockResourceIds) {
            List<ITLRestRep> exports = getExportsForBlockObject(blockResourceId);
            for (ITLRestRep export : exports) {
                URI exportId = export.getExport().getId();
                if (resourcesInExport.containsKey(exportId)) {
                    resourcesInExport.get(exportId).add(blockResourceId);
                }
                else {
                    resourcesInExport.put(exportId, Sets.newHashSet(blockResourceId));
                }
            }
        }

        removeBlockResourcesFromExports(resourcesInExport);
    }

    public static void removeBlockResourceFromExport(URI resourceId, URI exportId) {
        removeBlockResourcesFromExport(Collections.singletonList(resourceId), exportId);
    }

    public static void removeBlockResourcesFromExport(Collection<URI> resourceId, URI exportId) {
        Task<ExportGroupRestRep> task = execute(new RemoveBlockResourcesFromExport(exportId, resourceId));
        addAffectedResource(task);

        removeExportIfEmpty(exportId);
    }

    static final int MAX_RETRY_COUNT = 30;
    static final int RETRY_DELAY_MSEC = 60000;

    public static void removeExportIfEmpty(URI exportId) {
        boolean retryNeeded = false;
        int retryCount = 0;
        do {
            retryNeeded = false;
            ExportGroupRestRep export = getExport(exportId);
            if (ResourceUtils.isActive(export) && export.getVolumes().isEmpty()) {
                try {
                    log.info(String.format("Attampting deletion of ExportGroup %s (%s)", export.getGeneratedName(), export.getId()));
                    Task<ExportGroupRestRep> response = execute(new DeactivateBlockExport(exportId));
                    addAffectedResource(response);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof ServiceErrorException) {
                        ServiceErrorException svcexp = (ServiceErrorException) e.getCause();
                        if (retryCount++ < MAX_RETRY_COUNT
                                && ServiceCode.toServiceCode(svcexp.getCode()) == ServiceCode.API_TASK_EXECUTION_IN_PROGRESS) {
                            log.info(String.format("ExportGroup %s deletion waiting on pending task execution", export.getId()));
                            retryNeeded = true;
                            try {
                                Thread.sleep(RETRY_DELAY_MSEC);
                            } catch (InterruptedException ex) {
                                log.debug("Sleep interrupted");
                            }
                        } else {
                            throw e;
                        }
                    }
                }
            }
        } while (retryNeeded);
    }

    public static List<URI> getActiveSnapshots(URI volumeId) {
        if (ResourceType.isType(BLOCK_SNAPSHOT, volumeId)) {
            return Collections.emptyList();
        }
        return ResourceUtils.ids(execute(new GetActiveSnapshotsForVolume(volumeId)));
    }

    public static List<URI> getActiveSnapshotSessions(URI volumeId) {
        if (ResourceType.isType(BLOCK_SNAPSHOT, volumeId)) {
            return Collections.emptyList();
        }
        return ResourceUtils.ids(execute(new GetActiveSnapshotSessionsForVolume(volumeId)));
    }

    public static void removeSnapshotsForVolume(URI volumeId, VolumeDeleteTypeEnum type) {
        List<URI> snapshotIds = getActiveSnapshots(volumeId);
        // For ViPR-only delete of exported snapshots, we don't want to
        // try to unexport the snapshots. The controller will clean up any
        // export groups/masks if the snapshot is exported.
        if (VolumeDeleteTypeEnum.VIPR_ONLY != type) {
            removeBlockResourcesFromExports(snapshotIds);
        }
        removeSnapshots(snapshotIds, type);
    }

    public static void removeSnapshots(Collection<URI> snapshotIds, VolumeDeleteTypeEnum type) {
        for (URI snapshotId : snapshotIds) {
            removeSnapshot(snapshotId, type);
        }
    }

    public static void removeSnapshot(URI snapshotId, VolumeDeleteTypeEnum type) {
        Tasks<BlockSnapshotRestRep> task = execute(new DeactivateBlockSnapshot(snapshotId, type));
        addAffectedResources(task);
    }

    public static void removeSnapshotSessionsForVolume(URI volumeId, VolumeDeleteTypeEnum type) {
        List<URI> snapshotSessionIds = getActiveSnapshotSessions(volumeId);
        removeSnapshotSessions(snapshotSessionIds, type);
    }

    public static void removeSnapshotSessions(Collection<URI> snapshotSessionIds, VolumeDeleteTypeEnum type) {
        for (URI snapshotSessionId : snapshotSessionIds) {
            removeSnapshotSession(snapshotSessionId, type);
        }
    }

    public static void removeSnapshotSession(URI snapshotSessionId, VolumeDeleteTypeEnum type) {
        Tasks<BlockSnapshotSessionRestRep> task = execute(new DeactivateBlockSnapshotSession(snapshotSessionId, type));
        addAffectedResources(task);
    }

    public static void resynchronizeBlockSnapshots(Collection<URI> fullCopyIds) {
        for (URI fullCopyId : fullCopyIds) {
            resynchronizeBlockSnaptshot(fullCopyId);
        }
    }

    public static void resynchronizeBlockSnaptshot(URI fullCopyId) {
        execute(new ResynchronizeBlockSnapshot(fullCopyId));
    }

    public static List<URI> getActiveContinuousCopies(URI volumeId) {
        return ResourceUtils.ids(execute(new GetActiveContinuousCopiesForVolume(volumeId)));
    }

    public static void removeContinuousCopiesForVolume(URI volumeId, VolumeDeleteTypeEnum type) {
        if (!ResourceType.isType(BLOCK_SNAPSHOT, volumeId)) {
            Collection<URI> continuousCopyIds = getActiveContinuousCopies(volumeId);
            removeContinuousCopiesForVolume(volumeId, continuousCopyIds, type);
        }
    }

    public static void removeContinuousCopiesForVolume(URI volumeId, Collection<URI> continuousCopyIds, VolumeDeleteTypeEnum type) {
        if (VolumeDeleteTypeEnum.VIPR_ONLY != type) {
            removeBlockResourcesFromExports(continuousCopyIds);
        }
        for (URI continuousCopyId : continuousCopyIds) {
            removeContinuousCopy(volumeId, continuousCopyId, type);
        }
    }

    private static void removeContinuousCopy(URI volumeId, URI continuousCopyId, VolumeDeleteTypeEnum type) {
        if (VolumeDeleteTypeEnum.VIPR_ONLY != type) {
            BlockObjectRestRep obj = getVolume(volumeId);
            if (obj instanceof VolumeRestRep) {
                VolumeRestRep volume = (VolumeRestRep) obj;
                if (!StringUtils.equalsIgnoreCase(volume.getSystemType(), DiscoveredDataObject.Type.vplex.name())) {
                    execute(new PauseContinuousCopy(volumeId, continuousCopyId, COPY_NATIVE));
                }
            }
        }
        Tasks<VolumeRestRep> tasks = execute(new DeactivateContinuousCopy(volumeId, continuousCopyId, COPY_NATIVE, type));
        addAffectedResources(tasks);
    }

    public static List<URI> getActiveFullCopies(URI volumeId) {
        return ResourceUtils.ids(execute(new GetActiveFullCopiesForVolume(volumeId)));
    }

    public static void removeFullCopiesForVolume(URI volumeId, Collection<URI> vols, VolumeDeleteTypeEnum type) {
        List<URI> fullCopiesIds = getActiveFullCopies(volumeId);
        vols.removeAll(fullCopiesIds);
        removeFullCopies(fullCopiesIds, type);
    }

    public static void removeFullCopies(Collection<URI> fullCopyIds, VolumeDeleteTypeEnum type) {
        for (URI fullCopyId : fullCopyIds) {
            removeFullCopy(fullCopyId, type);
        }
    }

    public static void removeFullCopy(URI fullCopyId, VolumeDeleteTypeEnum type) {
        if (VolumeDeleteTypeEnum.VIPR_ONLY != type) {
            detachFullCopy(fullCopyId);
        }
        removeBlockResources(Collections.singletonList(fullCopyId), type);
    }

    public static void detachFullCopies(Collection<URI> fullCopyIds) {
        for (URI fullCopyId : fullCopyIds) {
            detachFullCopy(fullCopyId);
        }
    }

    public static void detachFullCopy(URI fullCopyId) {
        execute(new DetachFullCopy(fullCopyId));
    }

    public static void restoreFromFullCopy(URI fullCopyId) {
        execute(new RestoreFromFullCopy(fullCopyId));
    }

    public static Map<URI, Integer> findBlockVolumeHLUs(Collection<URI> volumeIds) {
        List<ITLRestRep> bulkResponse = execute(new FindBlockVolumeHlus(volumeIds));
        Map<URI, Integer> volumeHLUs = Maps.newHashMap();
        for (ITLRestRep export : bulkResponse) {
            ExportGroupRestRep exportGroup = getExport(export.getExport().getId());
            if (!exportGroup.getInternal()) {
                volumeHLUs.put(export.getBlockObject().getId(), export.getHlu());
            }
        }
        return volumeHLUs;
    }

    public static void resynchronizeFullCopies(Collection<URI> fullCopyIds) {
        for (URI fullCopyId : fullCopyIds) {
            resynchronizeFullCopy(fullCopyId);
        }
    }

    public static void resynchronizeFullCopy(URI fullCopyId) {
        execute(new ResynchronizeFullCopy(fullCopyId));
    }

    public static void removeBlockResources(Collection<URI> blockResourceIds, VolumeDeleteTypeEnum type) {

        List<URI> allBlockResources = Lists.newArrayList(blockResourceIds);
        for (URI volumeId : blockResourceIds) {
            BlockObjectRestRep volume = getVolume(volumeId);
            allBlockResources.addAll(getSrdfTargetVolumes(volume));
        }

        // For ViPR-only delete of exported volumes, we don't want to
        // try to unexport the volumes. The controller will clean up any
        // export groups/masks if the volume is exported.
        if (VolumeDeleteTypeEnum.VIPR_ONLY != type) {
            removeBlockResourcesFromExports(allBlockResources);
        }

        for (URI volumeId : allBlockResources) {
            if (canRemoveReplicas(volumeId)) {
                removeSnapshotsForVolume(volumeId, type);
                removeSnapshotSessionsForVolume(volumeId, type);
                removeContinuousCopiesForVolume(volumeId, type);
                removeFullCopiesForVolume(volumeId, blockResourceIds, type);
            } else {
                unexportReplicas(volumeId, blockResourceIds, type);
            }
        }
        deactivateBlockResources(blockResourceIds, type);
    }

    public static void unexportReplicas(URI volumeId, Collection<URI> blockResourceIds, VolumeDeleteTypeEnum type) {
        if (VolumeDeleteTypeEnum.VIPR_ONLY != type) {
            List<URI> snapshotIds = getActiveSnapshots(volumeId);
            removeBlockResourcesFromExports(snapshotIds);

            if (!ResourceType.isType(BLOCK_SNAPSHOT, volumeId)) {
                Collection<URI> continuousCopyIds = getActiveContinuousCopies(volumeId);
                removeBlockResourcesFromExports(continuousCopyIds);
            }

            List<URI> fullCopyIds = getActiveFullCopies(volumeId);
            removeBlockResourcesFromExports(fullCopyIds);
        }
    }

    public static boolean canRemoveReplicas(URI blockResourceId) {
        ResourceType volumeType = ResourceType.fromResourceId(blockResourceId.toString());
        if (volumeType == ResourceType.VOLUME) {
            VolumeRestRep volume = (VolumeRestRep) getVolume(blockResourceId);
            return !(isVmaxConsistencyVolume(volume) ||
                    (isVplexVolume(volume, volume.getSystemType()) && isSrdfConsistencyVolume(volume)));
        }
        return true;
    }

    private static void deactivateBlockResources(Collection<URI> blockResourceIds, VolumeDeleteTypeEnum type) {
        List<URI> volumes = Lists.newArrayList();
        List<URI> fullCopies = Lists.newArrayList();
        for (URI blockResourceId : blockResourceIds) {
            if (ResourceType.isType(VOLUME, blockResourceId)) {
                if (isFullCopyAttached(blockResourceId)) {
                    fullCopies.add(blockResourceId);
                }
                volumes.add(blockResourceId);
            }
            else if (ResourceType.isType(BLOCK_SNAPSHOT, blockResourceId)) {
                deactivateSnapshot(blockResourceId, type);
            }
        }
        if (VolumeDeleteTypeEnum.VIPR_ONLY != type) {
            detachFullCopies(fullCopies);
        }
        deactivateVolumes(volumes, type);
    }

    public static boolean isFullCopyAttached(URI id) {
        BlockObjectRestRep obj = getVolume(id);
        if (obj instanceof VolumeRestRep) {
            VolumeRestRep volume = (VolumeRestRep) obj;
            if (volume.getProtection() != null) {
                FullCopyRestRep fullCopy = volume.getProtection().getFullCopyRep();
                if (fullCopy != null &&
                        fullCopy.getAssociatedSourceVolume() != null &&
                        fullCopy.getReplicaState() != null &&
                        !fullCopy.getReplicaState().equals(ReplicationState.DETACHED.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void deactivateVolumes(List<URI> volumeIds, VolumeDeleteTypeEnum type) {
        if (CollectionUtils.isNotEmpty(volumeIds)) {
            Tasks<VolumeRestRep> tasks = execute(new DeactivateVolumes(volumeIds, type));
            addAffectedResources(tasks);
        }
    }

    private static void deactivateSnapshot(URI snapshotId, VolumeDeleteTypeEnum type) {
        Tasks<BlockSnapshotRestRep> tasks = execute(new DeactivateBlockSnapshot(snapshotId, type));
        addAffectedResources(tasks);
    }

    public static List<URI> getSrdfTargetVolumes(BlockObjectRestRep blockObject) {
        List<URI> targetVolumes = Lists.newArrayList();
        if (blockObject instanceof VolumeRestRep) {
            VolumeRestRep volume = (VolumeRestRep) blockObject;
            if (volume.getProtection() != null && volume.getProtection().getSrdfRep() != null) {
                for (VirtualArrayRelatedResourceRep targetVolume : volume.getProtection().getSrdfRep().getSRDFTargetVolumes()) {
                    targetVolumes.add(targetVolume.getId());
                }
            }
        }

        return targetVolumes;
    }

    /**
     * Return a list of target RP volume for a given block object
     *
     * @param blockObject to retrieve target from
     * @return a list of RP target volumes for specified block object
     */
    public static List<URI> getRpTargetVolumes(BlockObjectRestRep blockObject) {
        List<URI> targetVolumes = Lists.newArrayList();
        if (blockObject instanceof VolumeRestRep) {
            VolumeRestRep volume = (VolumeRestRep) blockObject;
            if (volume.getProtection() != null && volume.getProtection().getRpRep() != null) {
                for (VirtualArrayRelatedResourceRep targetVolume : volume.getProtection().getRpRep().getRpTargets()) {
                    targetVolumes.add(targetVolume.getId());
                }
            }
        }

        return targetVolumes;
    }

    public static void removeVolumes(List<URI> volumeIds) {
        removeBlockResources(volumeIds, VolumeDeleteTypeEnum.FULL);
    }

    public static void unexportVolumes(List<URI> volumeIds) {
        removeBlockResourcesFromExports(volumeIds);
    }

    public static Set<Initiator> findInitiatorsInVirtualArray(URI virtualArray, Collection<Initiator> initiators,
            Protocol protocol) {
        return findInitiatorsInVirtualArrays(Arrays.asList(virtualArray), initiators, protocol);
    }

    public static Set<Initiator> findInitiatorsInVirtualArrays(Collection<URI> virtualArrays,
            Collection<Initiator> initiators, Protocol protocol) {
        Set<Initiator> results = Sets.newHashSet();
        Collection<Initiator> filteredInitiators = filterInitiatorsByType(initiators, protocol);
        if (!filteredInitiators.isEmpty()) {
            for (URI virtualArray : virtualArrays) {
                results.addAll(execute(new FindVirtualArrayInitiators(virtualArray, filteredInitiators)));
            }
        }
        return results;
    }

    public static Set<URI> getVolumeVirtualArrays(Collection<? extends BlockObjectRestRep> volumes) {
        Set<URI> virtualArrays = Sets.newHashSet();
        virtualArrays.addAll(Collections2.transform(volumes, new Function<BlockObjectRestRep, URI>() {
            @Override
            public URI apply(BlockObjectRestRep input) {
                return input.getVirtualArray().getId();
            }
        }));
        return virtualArrays;
    }

    public static Collection<ExportGroupRestRep> filterExportsByType(Collection<ExportGroupRestRep> exportGroups, final URI hostOrCluster) {
        return Collections2.filter(exportGroups, new Predicate<ExportGroupRestRep>() {
            @Override
            public boolean apply(ExportGroupRestRep input) {
                if (BlockStorageUtils.isCluster(hostOrCluster)) {
                    return input.getType().equals(ExportClusterFilter.CLUSTER_EXPORT_TYPE);
                } else {
                    return input.getType().equals(ExportHostFilter.EXCLUSIVE_EXPORT_TYPE) ||
                            (input.getType().equals(ExportHostFilter.HOST_EXPORT_TYPE));
                }
            }
        });
    }

    public static Collection<Initiator> filterInitiatorsByType(Collection<Initiator> initiators, final Protocol protocol) {
        return Collections2.filter(initiators, new Predicate<Initiator>() {
            @Override
            public boolean apply(Initiator input) {
                return StringUtils.equals(protocol.name(), input.getProtocol());
            }
        });
    }

    public static Collection<String> getPortNames(Collection<Initiator> initiators) {
        return Collections2.transform(initiators, new Function<Initiator, String>() {
            @Override
            public String apply(Initiator input) {
                return input.getInitiatorPort();
            }
        });
    }

    public static String gbToVolumeSize(double sizeInGB) {
        // Always use size in bytes, VMAX does not like size in GB
        return String.valueOf(DiskSizeConversionUtils.gbToBytes(sizeInGB));
    }

    public static Tasks<VolumeRestRep> createFullCopy(URI volumeId, String name, Integer count) {
        int countValue = (count != null) ? count : 1;
        Tasks<VolumeRestRep> copies = execute(new CreateFullCopy(volumeId, name, countValue));
        addAffectedResources(copies);
        return copies;
    }

    public static Tasks<BlockSnapshotRestRep> createSnapshotFullCopy(URI snapshotId, String name, Integer count) {
        int countValue = (count != null) ? count : 1;
        Tasks<BlockSnapshotRestRep> copyTasks = ViPRExecutionUtils.execute(new CreateSnapshotFullCopy(snapshotId, name, countValue));
        addAffectedResources(copyTasks);
        return copyTasks;
    }

    public static Tasks<VolumeRestRep> createContinuousCopy(URI volumeId, String name, Integer count) {
        int countValue = (count != null) ? count : 1;
        Tasks<VolumeRestRep> copies = execute(new CreateContinuousCopy(volumeId, name, countValue, COPY_NATIVE));
        addAffectedResources(copies);
        return copies;
    }

    public static Tasks<VolumeRestRep> createContinuousCopy(URI volumeId, String name, Integer count, String type, URI copyId) {
        int countValue = (count != null) ? count : 1;
        Tasks<VolumeRestRep> copies = execute(new CreateContinuousCopy(volumeId, name, countValue, type, copyId));
        addAffectedResources(copies);
        return copies;
    }

    public static void startSnapshot(URI snapshotId) {
        Task<BlockSnapshotRestRep> task = execute(new StartBlockSnapshot(snapshotId));
        addAffectedResource(task);
    }

    public static void startFullCopy(URI fullCopyId) {
        Tasks<VolumeRestRep> task = execute(new StartFullCopy(fullCopyId));
        addAffectedResources(task);
    }

    public static Tasks<VolumeRestRep> swapContinuousCopy(URI targetVolumeId, String type) {
        Tasks<VolumeRestRep> copies = execute(new SwapContinuousCopies(targetVolumeId, type));
        addAffectedResources(copies);
        return copies;
    }

    public static Tasks<BlockConsistencyGroupRestRep> swapCGContinuousCopy(URI protectionSource, URI protectionTarget, String type) {
        Tasks<BlockConsistencyGroupRestRep> copies = execute(new SwapCGContinuousCopies(protectionSource, protectionTarget, type));
        addAffectedResources(copies);
        return copies;
    }

    public static Task<BlockConsistencyGroupRestRep> addVolumesToConsistencyGroup(URI consistencyGroupId, List<URI> volumeIds) {
        Task<BlockConsistencyGroupRestRep> task = execute(new AddVolumesToConsistencyGroup(consistencyGroupId, volumeIds));
        addAffectedResource(task);
        return task;
    }

    /**
     * Finds the exports (itl) for the given initiators.
     *
     * @param exports
     *            the list of all exports (itl)
     * @param initiators
     *            the initiators.
     * @return the exports for the initiators.
     */
    public static List<ITLRestRep> getExportsForInitiators(Collection<ITLRestRep> exports,
            Collection<Initiator> initiators) {
        Set<String> initiatorPorts = Sets.newHashSet(getPortNames(initiators));
        List<ITLRestRep> results = Lists.newArrayList();
        for (ITLRestRep export : exports) {
            if ((export.getInitiator() != null) && initiatorPorts.contains(export.getInitiator().getPort())) {
                results.add(export);
            }
        }
        return results;
    }

    public static Set<String> getTargetPortsForExports(Collection<ITLRestRep> exports) {
        Set<String> targetPorts = Sets.newTreeSet();
        for (ITLRestRep export : exports) {
            if (export.getStoragePort() != null && StringUtils.isNotBlank(export.getStoragePort().getPort())) {
                targetPorts.add(export.getStoragePort().getPort());
            }
        }
        return targetPorts;
    }

    public static boolean isVolumeInExportGroup(ExportGroupRestRep exportGroup, URI volumeId) {
        if (volumeId == null) {
            return false;
        }

        for (ExportBlockParam param : exportGroup.getVolumes()) {
            if (param.getId().equals(volumeId)) {
                return true;
            }
        }

        return false;
    }

    public static boolean isEmptyExport(ExportGroupRestRep exportGroup) {
        boolean hasVolumes = exportGroup.getVolumes() != null && !exportGroup.getVolumes().isEmpty();
        boolean hasInitiators = exportGroup.getInitiators() != null && !exportGroup.getInitiators().isEmpty();
        return !hasInitiators && !hasVolumes;
    }

    /**
     * Get the project id off a {@link BlockObjectRestRep}
     */
    public static <T extends BlockObjectRestRep> URI getProjectId(T resource) {
        if (resource instanceof BlockSnapshotRestRep) {
            return ((BlockSnapshotRestRep) resource).getProject().getId();
        }
        else if (resource instanceof VolumeRestRep) {
            return ((VolumeRestRep) resource).getProject().getId();
        }
        else if (resource instanceof BlockMirrorRestRep) {
            return ((BlockMirrorRestRep) resource).getProject().getId();
        }
        throw new IllegalStateException(ExecutionUtils.getMessage("illegalState.projectNotFound", resource.getId()));
    }

    /**
     * Get the virtual array id off a {@link BlockObjectRestRep}
     */
    public static URI getVirtualArrayId(BlockObjectRestRep resource) {
        if (resource instanceof VolumeRestRep) {
            return ((VolumeRestRep) resource).getVirtualArray().getId();
        }
        else if (resource instanceof BlockSnapshotRestRep) {
            return ((BlockSnapshotRestRep) resource).getVirtualArray().getId();
        }
        else if (resource instanceof BlockMirrorRestRep) {
            return ((BlockMirrorRestRep) resource).getVirtualArray().getId();
        }
        throw new IllegalStateException(ExecutionUtils.getMessage("illegalState.varrayNotFound", resource.getId()));
    }

    public static String getFailoverType(BlockObjectRestRep blockObject) {
        if (blockObject instanceof VolumeRestRep) {
            VolumeRestRep volume = (VolumeRestRep) blockObject;
            if (volume.getProtection() != null && volume.getProtection().getRpRep() != null) {
                VolumeRestRep.RecoverPointRestRep rp = volume.getProtection().getRpRep();
                if (StringUtils.equals("TARGET", rp.getPersonality())) {
                    return "rp";
                }
            }
            else if (volume.getProtection() != null && volume.getProtection().getSrdfRep() != null) {
                VolumeRestRep.SRDFRestRep srdf = volume.getProtection().getSrdfRep();
                if (srdf.getAssociatedSourceVolume() != null ||
                        (srdf.getSRDFTargetVolumes() != null && !srdf.getSRDFTargetVolumes().isEmpty())) {
                    return "srdf";
                }
            }
        }

        return null;
    }

    public interface Params {
        @Override
        public String toString();

        public Map<String, Object> getParams();
    }

    /**
     * Stores the virtual pool, virtual array, project and consistency group,
     * values for volume create services.
     */
    public static class VolumeParams implements Params {
        @Param(VIRTUAL_POOL)
        public URI virtualPool;
        @Param(VIRTUAL_ARRAY)
        public URI virtualArray;
        @Param(PROJECT)
        public URI project;
        @Param(value = CONSISTENCY_GROUP, required = false)
        public URI consistencyGroup;

        @Override
        public String toString() {
            return "Virtual Pool=" + virtualPool + ", Virtual Array=" + virtualArray + ", Project=" + project
                    + ", Consistency Group=" + consistencyGroup;
        }

        @Override
        public Map<String, Object> getParams() {
            Map<String, Object> map = new HashMap<String, Object>();
            map.put(VIRTUAL_POOL, virtualPool);
            map.put(VIRTUAL_ARRAY, virtualArray);
            map.put(PROJECT, project);
            map.put(CONSISTENCY_GROUP, consistencyGroup);
            return map;
        }
    }

    /**
     * Stores the host and HLU values for volume create for host services.
     */
    public static class HostVolumeParams extends VolumeParams {
        @Param(HOST)
        public URI hostId;
        @Param(value = HLU, required = false)
        public Integer hlu;

        @Param(value = MIN_PATHS, required = false)
        protected Integer minPaths;

        @Param(value = MAX_PATHS, required = false)
        protected Integer maxPaths;

        @Param(value = PATHS_PER_INITIATOR, required = false)
        protected Integer pathsPerInitiator;

        @Override
        public String toString() {
            String parent = super.toString();
            return parent + ", Host Id=" + hostId + ", HLU=" + hlu + ", MIN_PATHS=" + minPaths + ", MAX_PATHS="
            + maxPaths + ", PATHS_PER_INITIATOR=" + pathsPerInitiator;
        }

        @Override
        public Map<String, Object> getParams() {
            Map<String, Object> map = new HashMap<String, Object>();
            map.putAll(super.getParams());
            map.put(HOST, hostId);
            map.put(HLU, hlu);
            map.put(MIN_PATHS, minPaths);
            map.put(MAX_PATHS, maxPaths);
            map.put(PATHS_PER_INITIATOR, pathsPerInitiator);
            return map;
        }
    }

    /**
     * Stores the name, size, and count of volumes for multi-volume create services.
     */
    public static class VolumeTable {
        @Param(NAME)
        protected String nameParam;
        @Param(SIZE_IN_GB)
        protected Double sizeInGb;
        @Param(value = NUMBER_OF_VOLUMES, required = false)
        protected Integer count;

        @Override
        public String toString() {
            return "Volume=" + nameParam + ", size=" + sizeInGb + ", count=" + count;
        }

        public Map<String, Object> getParams() {
            Map<String, Object> map = new HashMap<String, Object>();
            map.put(NAME, nameParam);
            map.put(SIZE_IN_GB, sizeInGb);
            map.put(NUMBER_OF_VOLUMES, count);
            return map;
        }
    }

    /**
     * Helper method for creating a list of all the params for the createBlockVolumesHelper.
     *
     * @param table volume table
     * @param params for volume creation
     * @return map of all params
     */
    public static Map<String, Object> createParam(VolumeTable table, Params params) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.putAll(table.getParams());
        map.putAll(params.getParams());
        return map;
    }

    /**
     * Get source volume for vplexVolume by checking HA volumes with matching varrays
     *
     * @param vplexVolume vplex volume to use
     * @return source volume
     */
    private static VolumeRestRep getSourceVolume(VolumeRestRep vplexVolume) {
        if (vplexVolume.getHaVolumes() != null && !vplexVolume.getHaVolumes().isEmpty()) {
            URI vplexVolumeVarray = vplexVolume.getVirtualArray().getId();
            for (RelatedResourceRep haVolume : vplexVolume.getHaVolumes()) {
                VolumeRestRep volume = execute(new GetBlockVolume(haVolume.getId()));
                if (volume != null && volume.getVirtualArray().getId().equals(vplexVolumeVarray)) {
                    return volume;
                }
            }
        }
        return null;
    }

    public static Table<URI, String, VolumeRestRep> getReplicationGroupVolumes(List<NamedRelatedResourceRep> volumeUris) {
        // Group volumes by storage system and replication group
        Table<URI, String, VolumeRestRep> storageRgToVolumes = HashBasedTable.create();
        for (NamedRelatedResourceRep volumeUri : volumeUris) {
            VolumeRestRep vplexVolume = null;
            VolumeRestRep volume = execute(new GetBlockVolume(volumeUri.getId()));
            boolean isVPlex = volume.getSystemType().equals("vplex");
            if (isVPlex) {
                vplexVolume = volume;
                volume = getSourceVolume(volume);
            }
            String rgName = BlockStorageUtils.stripRPTargetFromReplicationGroup(volume.getReplicationGroupInstance());
            URI storage = volume.getStorageController();
            if (!storageRgToVolumes.contains(storage, rgName)) {
                if (isVPlex) {
                    storageRgToVolumes.put(storage, rgName, vplexVolume);
                } else {
                    storageRgToVolumes.put(storage, rgName, volume);
                }
            }
        }
        return storageRgToVolumes;
    }

    public static Table<URI, String, BlockSnapshotRestRep> getReplicationGroupSnapshots(List<NamedRelatedResourceRep> volumeUris) {
        Table<URI, String, BlockSnapshotRestRep> storageRgToVolumes = HashBasedTable.create();
        for (NamedRelatedResourceRep volumeUri : volumeUris) {
            BlockSnapshotRestRep snapshot = execute(new GetBlockSnapshot(volumeUri.getId()));
            VolumeRestRep volume = execute(new GetBlockVolume(snapshot.getParent().getId()));
            String rgName = volume.getReplicationGroupInstance();
            URI storage = volume.getStorageController();
            if (!storageRgToVolumes.contains(storage, rgName)) {
                storageRgToVolumes.put(storage, rgName, snapshot);
            }
        }
        return storageRgToVolumes;
    }

    public static Table<URI, String, BlockSnapshotSessionRestRep> getReplicationGroupSnapshotSessions(
            List<NamedRelatedResourceRep> volumeUris) {
        Table<URI, String, BlockSnapshotSessionRestRep> storageRgToVolumes = HashBasedTable.create();
        for (NamedRelatedResourceRep volumeUri : volumeUris) {
            BlockSnapshotSessionRestRep snapshotSession = execute(new GetBlockSnapshotSession(volumeUri.getId()));
            String rgName = snapshotSession.getReplicationGroupInstance();
            URI storage = snapshotSession.getStorageController();
            if (!storageRgToVolumes.contains(storage, rgName)) {
                storageRgToVolumes.put(storage, rgName, snapshotSession);
            }
        }
        return storageRgToVolumes;
    }

    public static List<URI> getSingleVolumePerSubGroupAndStorageSystem(NamedVolumesList volList, List<String> subGroups) {
        List<URI> volumeIds = Lists.newArrayList();
        Table<URI, String, VolumeRestRep> results = getReplicationGroupVolumes(volList.getVolumes());
        for (Cell<URI, String, VolumeRestRep> cell : results.cellSet()) {
            if (subGroups.contains(cell.getColumnKey())) {
                volumeIds.add(cell.getValue().getId());
            }
        }
        return volumeIds;
    }

    public static List<URI> getSingleSnapshotPerSubGroupAndStorageSystem(URI applicationId, String copySet, List<String> subGroups) {
        List<URI> snapshotIds = Lists.newArrayList();

        Table<URI, String, BlockSnapshotRestRep> results = getReplicationGroupSnapshots(execute(
                new GetBlockSnapshotSet(applicationId, copySet)).getSnapList());
        for (Cell<URI, String, BlockSnapshotRestRep> cell : results.cellSet()) {
            if (subGroups.contains(BlockStorageUtils.stripRPTargetFromReplicationGroup(cell.getColumnKey()))) {
                snapshotIds.add(cell.getValue().getId());
            }
        }
        return snapshotIds;
    }

    public static List<URI> getSingleSnapshotSessionPerSubGroupAndStorageSystem(URI applicationId, String copySet, List<String> subGroups) {
        List<URI> snapshotSessionIds = Lists.newArrayList();
        Table<URI, String, BlockSnapshotSessionRestRep> results = getReplicationGroupSnapshotSessions(execute(
                new GetBlockSnapshotSessionList(applicationId, copySet)).getSnapSessionRelatedResourceList());
        for (Cell<URI, String, BlockSnapshotSessionRestRep> cell : results.cellSet()) {
            String stripped = BlockStorageUtils.stripRPTargetFromReplicationGroup(cell.getColumnKey());
            if (subGroups.contains(stripped)) {
                snapshotSessionIds.add(cell.getValue().getId());
            }
        }
        return snapshotSessionIds;
    }

    public static Table<URI, String, VolumeRestRep> getReplicationGroupFullCopies(
            List<NamedRelatedResourceRep> volumeUris) {
        // Group volumes by storage system and replication group
        Table<URI, String, VolumeRestRep> storageRgToVolumes = HashBasedTable.create();
        List<URI> parentVolIds = Lists.newArrayList();
        for (NamedRelatedResourceRep volumeUri : volumeUris) {
            VolumeRestRep volume = execute(new GetBlockVolume(volumeUri.getId()));

            if (volume != null && volume.getProtection() != null && volume.getProtection().getFullCopyRep() != null
                    && volume.getProtection().getFullCopyRep().getAssociatedSourceVolume() != null) {
                parentVolIds.add(volume.getProtection().getFullCopyRep().getAssociatedSourceVolume().getId());
            }

            List<VolumeRestRep> parentVolumes = execute(new GetBlockVolumes(parentVolIds));
            if (parentVolumes != null && !parentVolumes.isEmpty()) {
                for (VolumeRestRep parentVolume : parentVolumes) {
                    String rgName = stripRPTargetFromReplicationGroup(parentVolume.getReplicationGroupInstance());
                    URI storage = parentVolume.getStorageController();
                    if (!storageRgToVolumes.contains(storage, rgName)) {
                        storageRgToVolumes.put(storage, rgName, volume);
                    }
                }
            }
        }
        return storageRgToVolumes;
    }

    public static List<URI> getSingleFullCopyPerSubGroupAndStorageSystem(URI applicationId, String copySet,
            List<String> subGroups) {
        List<URI> fullCopyIds = Lists.newArrayList();
        Table<URI, String, VolumeRestRep> results = getReplicationGroupFullCopies(
                execute(new GetFullCopyList(applicationId, copySet)).getVolumes());
        for (Cell<URI, String, VolumeRestRep> cell : results.cellSet()) {
            if (subGroups.contains(cell.getColumnKey())) {
                fullCopyIds.add(cell.getValue().getId());
            }
        }
        return fullCopyIds;
    }

    public static List<URI> getAllFullCopyVolumes(URI applicationId, String copySet, List<String> subGroups) {
        List<URI> fullCopyIds = Lists.newArrayList();

        List<NamedRelatedResourceRep> fullCopies = execute(new GetFullCopyList(applicationId, copySet)).getVolumes();
        for (NamedRelatedResourceRep fullCopy : fullCopies) {
            fullCopyIds.add(fullCopy.getId());
        }

        return fullCopyIds;
    }

    public static boolean isVplexVolume(VolumeRestRep volume, String storageSystemType) {
        return (volume.getHaVolumes() != null && !volume.getHaVolumes().isEmpty())
                || (storageSystemType != null && storageSystemType.equals(StorageProvider.InterfaceType.vplex.name()));
    }

    public static boolean isVplexOrRPVolume(String volumeId) {
        if (volumeId == null) {
            return false;
        }
        VolumeRestRep volume = execute(new GetBlockVolume(volumeId));
        if (volume == null) {
            return false;
        }
        if (volume.getProtection() != null && volume.getProtection().getRpRep() != null) {
            return true;
        }

        return isVplexVolume(volume, volume.getSystemType());
    }

    public static String stripRPTargetFromReplicationGroup(String group) {
        String[] parts = StringUtils.split(group, '-');
        if (parts.length > 1 && parts[parts.length - 1].equals("RPTARGET")) {
            return StringUtils.join(parts, '-', 0, parts.length - 1);
        } else {
            return group;
        }
    }

    public static Set<String> stripRPTargetFromReplicationGroup(Collection<String> groups) {
        Set<String> stripped = new HashSet<String>();

        for (String group : groups) {
            stripped.add(stripRPTargetFromReplicationGroup(group));
        }

        return stripped;
    }

    public static boolean isRPVolume(VolumeRestRep volume) {
        return (volume.getProtection() != null && volume.getProtection().getRpRep() != null);
    }

    public static boolean isRPSourceVolume(VolumeRestRep volume) {
        if (isRPVolume(volume)
                && volume.getProtection().getRpRep().getPersonality() != null
                && volume.getProtection().getRpRep().getPersonality().equalsIgnoreCase("SOURCE")) {
            return true;
        }
        return false;
    }

    /**
     * returns true if the passed in volume is a RP target volume
     *
     * @param vol
     * @return
     */
    public static boolean isRPTargetVolume(VolumeRestRep volume) {
        if (isRPVolume(volume)
                && volume.getProtection().getRpRep().getPersonality() != null
                && volume.getProtection().getRpRep().getPersonality().equalsIgnoreCase("TARGET")) {
            return true;
        }
        return false;
    }

    /**
     * gets the vplex primary/source backing volume for a vplex virtual volume
     *
     * @param client
     * @param vplexVolume
     * @return
     */
    public static VolumeRestRep getVPlexSourceVolume(ViPRCoreClient client, VolumeRestRep vplexVolume) {
        if (vplexVolume.getHaVolumes() != null && !vplexVolume.getHaVolumes().isEmpty()) {
            URI vplexVolumeVarray = vplexVolume.getVirtualArray().getId();
            for (RelatedResourceRep haVolume : vplexVolume.getHaVolumes()) {
                VolumeRestRep volume = client.blockVolumes().get(haVolume.getId());
                if (volume != null && volume.getVirtualArray().getId().equals(vplexVolumeVarray)) {
                    return volume;
                }
            }
        }
        return null;
    }

    public static NamedVolumesList getVolumesBySite(ViPRCoreClient client, String virtualArrayId, URI applicationId) {
        boolean isTarget = false;
        URI virtualArray = null;
        if (virtualArrayId != null && StringUtils.split(virtualArrayId, ':')[0].equals("tgt")) {
            virtualArray = URI.create(StringUtils.substringAfter(virtualArrayId, ":"));
            isTarget = true;
        } else {
            isTarget = false;
        }

        NamedVolumesList applicationVolumes = client.application().getVolumeByApplication(applicationId);
        NamedVolumesList volumesToUse = new NamedVolumesList();
        for (NamedRelatedResourceRep volumeId : applicationVolumes.getVolumes()) {
            VolumeRestRep parentVolume = client.blockVolumes().get(volumeId);
            if (isTarget) {
                if (isRPTargetVolume(parentVolume) && parentVolume.getVirtualArray().getId().equals(virtualArray)) {
                    volumesToUse.getVolumes().add(volumeId);
                }
            } else {
                if (!BlockStorageUtils.isRPVolume(parentVolume) || BlockStorageUtils.isRPSourceVolume(parentVolume)) {
                    volumesToUse.getVolumes().add(volumeId);
                }
            }
        }
        return volumesToUse;
    }

    public static boolean isVmaxConsistencyVolume(VolumeRestRep volume ) {
        return volume.getConsistencyGroup() != null &&
                (volume.getSystemType().equalsIgnoreCase(DiscoveredDataObject.Type.vmax.name()) ||
                        volume.getSystemType().equalsIgnoreCase(DiscoveredDataObject.Type.vmax3.name()));
    }

    public static boolean isSrdfConsistencyVolume(VolumeRestRep volume) {
        if (volume.getConsistencyGroup() == null) {
            return false;
        }

        BlockConsistencyGroupRestRep group = getBlockConsistencyGroup(volume.getConsistencyGroup().getId());
        return group != null && group.getTypes().contains(BlockConsistencyGroup.Types.SRDF.name());
    }

    public static void checkVolumeLimit(ViPRCoreClient client, URI project) {
        ExecutionContext context = ExecutionUtils.currentContext();
        long limit = context.getResourceLimit(Constants.RESOURCE_LIMIT_PROJECT_VOLUMES);
        int numOfVolumes = client.blockVolumes().countByProject(project);
        // Show alert in case of approaching 90% of the limit
        if (numOfVolumes >= limit * Constants.RESOURCE_LIMIT_ALERT_RATE) {
            context.logWarn("alert.createVolume.exceedingResourceLimit", numOfVolumes, limit);
        }
    }
}
