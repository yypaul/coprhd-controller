/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.mapper;

import static com.emc.storageos.api.mapper.DbObjectMapper.mapDataObjectFields;
import static com.emc.storageos.api.mapper.DbObjectMapper.mapDiscoveredDataObjectFields;
import static com.emc.storageos.api.mapper.DbObjectMapper.toLink;
import static com.emc.storageos.api.mapper.DbObjectMapper.toNamedRelatedResource;
import static com.emc.storageos.api.mapper.DbObjectMapper.toRelatedResource;
import static com.emc.storageos.db.client.constraint.ContainmentConstraint.Factory.getVolumesByConsistencyGroup;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.service.impl.resource.utils.CapacityUtils;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.PrefixConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.AutoTieringPolicy;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockMirror;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.BlockSnapshotSession;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.Migration;
import com.emc.storageos.db.client.model.NamedURI;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.ProtectionSet;
import com.emc.storageos.db.client.model.RemoteDirectorGroup;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StorageTier;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.SynchronizationState;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.Volume.PersonalityTypes;
import com.emc.storageos.db.client.model.VplexMirror;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedExportMask;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.model.RelatedResourceRep;
import com.emc.storageos.model.ResourceTypeEnum;
import com.emc.storageos.model.VirtualArrayRelatedResourceRep;
import com.emc.storageos.model.adapters.StringMapAdapter;
import com.emc.storageos.model.adapters.StringSetMapAdapter;
import com.emc.storageos.model.block.BlockConsistencyGroupRestRep;
import com.emc.storageos.model.block.BlockMirrorRestRep;
import com.emc.storageos.model.block.BlockObjectRestRep;
import com.emc.storageos.model.block.BlockSnapshotRestRep;
import com.emc.storageos.model.block.BlockSnapshotSessionRestRep;
import com.emc.storageos.model.block.MigrationRestRep;
import com.emc.storageos.model.block.UnManagedExportMaskRestRep;
import com.emc.storageos.model.block.UnManagedVolumeRestRep;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.storageos.model.block.VolumeRestRep.FullCopyRestRep;
import com.emc.storageos.model.block.VolumeRestRep.MirrorRestRep;
import com.emc.storageos.model.block.VolumeRestRep.ProtectionRestRep;
import com.emc.storageos.model.block.VolumeRestRep.RecoverPointRestRep;
import com.emc.storageos.model.block.VolumeRestRep.SRDFRestRep;
import com.emc.storageos.model.block.VplexMirrorRestRep;
import com.emc.storageos.model.block.tier.AutoTierPolicyList;
import com.emc.storageos.model.block.tier.AutoTieringPolicyRestRep;
import com.emc.storageos.model.block.tier.StorageTierRestRep;
import com.emc.storageos.model.vpool.NamedRelatedVirtualPoolRep;
import com.emc.storageos.model.vpool.VirtualPoolChangeOperationEnum;
import com.emc.storageos.model.vpool.VirtualPoolChangeRep;
import com.emc.storageos.protectioncontroller.impl.recoverpoint.RPHelper;
import com.emc.storageos.util.VPlexSrdfUtil;
import com.emc.storageos.util.VPlexUtil;
import com.emc.storageos.volumecontroller.impl.smis.srdf.SRDFUtils;

public class BlockMapper {

    private static final Logger logger = LoggerFactory.getLogger(BlockMapper.class);

    public static void mapBlockObjectFields(BlockObject from, BlockObjectRestRep to) {
        mapDataObjectFields(from, to);
        to.setWwn(from.getWWN());
        to.setStorageController(from.getStorageController());
        to.setProtocols(from.getProtocol());
        to.setVirtualArray(toRelatedResource(ResourceTypeEnum.VARRAY, from.getVirtualArray()));
        to.setDeviceLabel(from.getDeviceLabel() != null ? from.getDeviceLabel() : "");
        to.setNativeId(from.getNativeId() != null ? from.getNativeId() : "");
        to.setConsistencyGroup(toRelatedResource(ResourceTypeEnum.BLOCK_CONSISTENCY_GROUP, from.getConsistencyGroup()));
        to.setReplicationGroupInstance(from.getReplicationGroupInstance() != null ? from.getReplicationGroupInstance() : "");
    }

    public static VolumeRestRep map(Volume from) {
        return map(null, from, null);
    }

    public static VolumeRestRep map(DbClient dbClient, Volume from) {
        return map(dbClient, from, null);
    }
    
    public static VolumeRestRep map(DbClient dbClient, Volume from,
    		Map<URI, Boolean> projectSrdfCapableCache) {
        if (from == null) {
            return null;
        }
        VolumeRestRep to = new VolumeRestRep();
        mapBlockObjectFields(from, to);

        if (from.getProject() != null) {
            to.setProject(toRelatedResource(ResourceTypeEnum.PROJECT, from.getProject().getURI()));
        }
        if (from.getTenant() != null) {
            to.setTenant(toRelatedResource(ResourceTypeEnum.TENANT, from.getTenant().getURI()));
        }
        to.setProvisionedCapacity(CapacityUtils.convertBytesToGBInStr(from.getProvisionedCapacity()));
        // For VPLEX virtual volumes return allocated capacity as provisioned capacity (cop-18608)
        to.setAllocatedCapacity(CapacityUtils.convertBytesToGBInStr(from.getAllocatedCapacity()));
        boolean isVplexVolume = DiscoveredDataObject.Type.vplex.name().equalsIgnoreCase(from.getSystemType());
        if (isVplexVolume) {
            to.setAllocatedCapacity(CapacityUtils.convertBytesToGBInStr(from.getProvisionedCapacity()));
        }
        
        to.setCapacity(CapacityUtils.convertBytesToGBInStr(from.getCapacity()));
        if (from.getThinlyProvisioned()) {
            to.setPreAllocationSize(CapacityUtils.convertBytesToGBInStr(from.getThinVolumePreAllocationSize()));
        }
        to.setVirtualPool(toRelatedResource(ResourceTypeEnum.BLOCK_VPOOL, from.getVirtualPool()));
        to.setIsComposite(from.getIsComposite());
        to.setAutoTierPolicyUri(toRelatedResource(ResourceTypeEnum.AUTO_TIERING_POLICY, from.getAutoTieringPolicyUri(), from.getId()));
        to.setThinlyProvisioned(from.getThinlyProvisioned());
        to.setAccessState(from.getAccessState());
        to.setLinkStatus(from.getLinkStatus());
        // Default snapshot session support to false
        to.setSupportsSnapshotSessions(Boolean.FALSE);
        
        // set compression ratio
        to.setCompressionRatio(from.getCompressionRatio());

        if (DiscoveredDataObject.Type.vmax3.name().equalsIgnoreCase(from.getSystemType())) { 
            to.setSupportsSnapshotSessions(Boolean.TRUE);  
        }
        to.setSystemType(from.getSystemType());

        // Extra checks for VPLEX volumes
        Volume srdfVolume = from;
        Volume sourceSideBackingVolume = null;
        if (null != dbClient && null != from.getAssociatedVolumes() && !from.getAssociatedVolumes().isEmpty()) {
            // For snapshot session support of a VPLEX volume, we only need to check the SOURCE side of the
            // volume.
            sourceSideBackingVolume = VPlexUtil.getVPLEXBackendVolume(from, true, dbClient);
            // Check for null in case the VPlex vol was ingested w/o the backend volumes
            if ((sourceSideBackingVolume != null) 
                    && DiscoveredDataObject.Type.vmax3.name().equalsIgnoreCase(sourceSideBackingVolume.getSystemType())) {
                to.setSupportsSnapshotSessions(Boolean.TRUE);
            }
            // Get the SRDF underlying volume if present. That will be used to fill on the
            // SrdfRestRep below.
            if (projectSrdfCapable(dbClient, from.getProject().getURI(), projectSrdfCapableCache)) {
                srdfVolume = VPlexSrdfUtil.getSrdfVolumeFromVplexVolume(dbClient, from);
                srdfVolume = (srdfVolume != null ? srdfVolume : from);
            }
            to.setAccessState(srdfVolume.getAccessState());
            to.setLinkStatus(srdfVolume.getLinkStatus());
        }

        if (from.getPool() != null) {
            to.setPool(toRelatedResource(ResourceTypeEnum.STORAGE_POOL, from.getPool()));
        }

        // RecoverPoint specific section
        RecoverPointRestRep toRp = null;

        if (from.checkForRp()) {
            toRp = new RecoverPointRestRep();
            toRp.setProtectionSystem(toRelatedResource(ResourceTypeEnum.PROTECTION_SYSTEM, from.getProtectionController()));
            toRp.setPersonality(from.getPersonality());
            toRp.setInternalSiteName(from.getInternalSiteName());
            toRp.setCopyName(from.getRpCopyName());
            toRp.setRsetName(from.getRSetName());
            if ((from.getRpTargets() != null) && (!from.getRpTargets().isEmpty())) {
                List<VirtualArrayRelatedResourceRep> rpTargets = new ArrayList<VirtualArrayRelatedResourceRep>();
                for (String target : from.getRpTargets()) {
                    rpTargets.add(toTargetVolumeRelatedResource(ResourceTypeEnum.VOLUME, URI.create(target), getVarray(dbClient, target)));
                }
                toRp.setRpTargets(rpTargets);
            }

            if (from.getProtectionSet() != null) {
                toRp.setProtectionSet(toRelatedResource(ResourceTypeEnum.PROTECTION_SET, from.getProtectionSet().getURI(), from.getId()));
            }
        }

        // Mirror specific section
        MirrorRestRep toMirror = null;
        if ((from.getMirrors() != null) && (!from.getMirrors().isEmpty())) {
            toMirror = new MirrorRestRep();
            List<VirtualArrayRelatedResourceRep> mirrors = new ArrayList<VirtualArrayRelatedResourceRep>();
            for (String mirror : from.getMirrors()) {
                mirrors.add(toTargetVolumeRelatedResource(ResourceTypeEnum.BLOCK_MIRROR, URI.create(mirror), from.getId(),
                        getVarray(dbClient, mirror)));
            }
            toMirror.setMirrors(mirrors);
        }

        // Full copy specific section
        FullCopyRestRep toFullCopy = null;
        URI fullCopySourceVolumeURI = from.getAssociatedSourceVolume();
        StringSet fromFullCopies = from.getFullCopies();
        if (!NullColumnValueGetter.isNullURI(fullCopySourceVolumeURI) || (fromFullCopies != null && !fromFullCopies.isEmpty())) {
            toFullCopy = new FullCopyRestRep();
            if (fullCopySourceVolumeURI != null) {
                toFullCopy.setAssociatedSourceVolume(toRelatedResource(ResourceTypeEnum.VOLUME, fullCopySourceVolumeURI));
            }
            if (fromFullCopies != null) {
                List<VirtualArrayRelatedResourceRep> fullCopies = new ArrayList<VirtualArrayRelatedResourceRep>();
                for (String fullCopy : fromFullCopies) {
                    fullCopies.add(toTargetVolumeRelatedResource(ResourceTypeEnum.VOLUME, URI.create(fullCopy),
                            getVarray(dbClient, fullCopy)));
                }
                toFullCopy.setFullCopyVolumes(fullCopies);
            }
            if (from.getSyncActive() != null) {
                toFullCopy.setSyncActive(from.getSyncActive());
            }
            if (from.getReplicaState() != null) {
                toFullCopy.setReplicaState(from.getReplicaState());
            }
            if (from.getFullCopySetName() != null) {
                toFullCopy.setFullCopySetName(from.getFullCopySetName());
            }
        }

        // SRDF specific section; uses srdfVolume which is a copy of from unless
        // it's a vplex underlying srdf volume
        SRDFRestRep toSRDF = null;
        if ((srdfVolume.getSrdfTargets() != null) && (!srdfVolume.getSrdfTargets().isEmpty())) {
            toSRDF = new SRDFRestRep();
            List<VirtualArrayRelatedResourceRep> targets = new ArrayList<VirtualArrayRelatedResourceRep>();
            if (srdfVolume != from) {
            	// VPLEX; translate targets to corresponding VPLEX volume. if any
                for (String target : VPlexSrdfUtil.getSrdfOrVplexTargets(dbClient, srdfVolume)) {
                    targets.add(toTargetVolumeRelatedResource(ResourceTypeEnum.VOLUME, URI.create(target), getVarray(dbClient, target)));
                }
            } else {
            	// Non-VPLEX
            	for (String target : from.getSrdfTargets()) {
            		targets.add(toTargetVolumeRelatedResource(ResourceTypeEnum.VOLUME, URI.create(target), getVarray(dbClient, target)));
            	}
            }
            toSRDF.setPersonality(srdfVolume.getPersonality());
            toSRDF.setSRDFTargetVolumes(targets);
        } else if (!NullColumnValueGetter.isNullNamedURI(srdfVolume.getSrdfParent())) {
            toSRDF = new SRDFRestRep();
            toSRDF.setPersonality(srdfVolume.getPersonality());
            toSRDF.setAssociatedSourceVolume(toRelatedResource(ResourceTypeEnum.VOLUME, srdfVolume.getSrdfParent().getURI()));
            toSRDF.setSrdfCopyMode(srdfVolume.getSrdfCopyMode());
            toSRDF.setSrdfGroup(srdfVolume.getSrdfGroup());
        }

        // Protection object encapsulates mirrors and RP
        if (toMirror != null || toRp != null || toFullCopy != null || toSRDF != null) {
            ProtectionRestRep toProtection = new ProtectionRestRep();
            toProtection.setMirrorRep(toMirror);
            toProtection.setRpRep(toRp);
            toProtection.setFullCopyRep(toFullCopy);
            toProtection.setSrdfRep(toSRDF);
            to.setProtection(toProtection);
        }

        String replicationGroupInstance = isVplexVolume ? 
                from.getBackingReplicationGroupInstance() : from.getReplicationGroupInstance();
        if (NullColumnValueGetter.isNotNullValue(replicationGroupInstance)) {
            to.setReplicationGroupInstance(replicationGroupInstance);
        }

        if ((from.getAssociatedVolumes() != null) && (!from.getAssociatedVolumes().isEmpty())) {
            List<RelatedResourceRep> backingVolumes = new ArrayList<RelatedResourceRep>();
            for (String backingVolume : from.getAssociatedVolumes()) {
                backingVolumes.add(toRelatedResource(ResourceTypeEnum.VOLUME, URI.create(backingVolume)));
            }
            to.setHaVolumes(backingVolumes);
        }
        if ((from.getVolumeGroupIds() != null) && (!from.getVolumeGroupIds().isEmpty())) {
            List<RelatedResourceRep> volumeGroups = new ArrayList<RelatedResourceRep>();
            for (String volumeGroup : from.getVolumeGroupIds()) {
                volumeGroups.add(toRelatedResource(ResourceTypeEnum.VOLUME_GROUP, URI.create(volumeGroup)));
            }
            to.setVolumeGroups(volumeGroups);
        }

        return to;
    }

    /**
     * Maintains a cache that maps projectId to whether the project is SRDF capable.
     * @param dbClient
     * @param projectId
     * @param projectSrdfCapableCache -- can be null
     * @return
     */
    private static boolean projectSrdfCapable(DbClient dbClient, URI projectId, Map<URI, Boolean> projectSrdfCapableCache) {
    	if (projectSrdfCapableCache == null) {
    		// No cache is maintained - have to assume could be SRDF capable
    		return true;
    	}
    	boolean srdfCapable = false;
    	try {
    		if (projectSrdfCapableCache.containsKey(projectId)) {
    			// Return cached value
    			return projectSrdfCapableCache.get(projectId);
    		}

    		// Lookup project, the project name determines possible RDF Group names
    		Project project = dbClient.queryObject(Project.class, projectId);
    		if (project != null && !project.getInactive()) {
    			// There are potentially several RDF group names based on the project
    			StringSet rdfGroupNames = SRDFUtils.getQualifyingRDFGroupNames(project);
    			// Look for a remote director group matching one of the names.
    			for (String rdfGroupName : rdfGroupNames) {
    				URIQueryResultList uris = new URIQueryResultList();
    				dbClient.queryByConstraint(
    						PrefixConstraint.Factory.getLabelPrefixConstraint(RemoteDirectorGroup.class,
    								rdfGroupName), uris);
    				Iterator<URI> uriIterator = uris.iterator();
    				if (uriIterator.hasNext()) {
    					srdfCapable = true;
    					break;
    				}
    			}
    		}
    	} catch (Exception ex) {
    		logger.info("Exception: " + ex.getMessage(), ex);
    	}
    	projectSrdfCapableCache.put(projectId, srdfCapable);
    	return srdfCapable;
    }

    private static URI getVarray(DbClient dbClient, String target) {
        if (dbClient != null) {
            if (URIUtil.isType(URI.create(target), VplexMirror.class)) {
                VplexMirror mirror = dbClient.queryObject(VplexMirror.class, URI.create(target));
                return mirror.getVirtualArray();
            }
            BlockObject volume = BlockObject.fetch(dbClient, URI.create(target));
            return volume == null ? null : volume.getVirtualArray();
        }
        return null;
    }

    private static VirtualArrayRelatedResourceRep toTargetVolumeRelatedResource(
            ResourceTypeEnum type, URI id, URI varray) {
        VirtualArrayRelatedResourceRep resourceRep = new VirtualArrayRelatedResourceRep();
        if (NullColumnValueGetter.isNullURI(id)) {
            return null;
        }
        resourceRep.setId(id);
        resourceRep.setLink(toLink(type, id));
        resourceRep.setVirtualArray(toRelatedResource(ResourceTypeEnum.VARRAY, varray));
        return resourceRep;
    }

    private static VirtualArrayRelatedResourceRep toTargetVolumeRelatedResource(
            ResourceTypeEnum type, URI id, URI parentId, URI varray) {
        VirtualArrayRelatedResourceRep resourceRep = new VirtualArrayRelatedResourceRep();
        if (NullColumnValueGetter.isNullURI(id)) {
            return null;
        }
        resourceRep.setId(id);
        resourceRep.setLink(toLink(type, id, parentId));
        resourceRep.setVirtualArray(toRelatedResource(ResourceTypeEnum.VARRAY, varray));
        return resourceRep;
    }

    public static BlockSnapshotRestRep map(DbClient dbClient, BlockSnapshot from) {
        if (from == null) {
            return null;
        }
        BlockSnapshotRestRep to = new BlockSnapshotRestRep();
        mapBlockObjectFields(from, to);
        
        // Map the consistency group
        to.setConsistencyGroup(toRelatedResource(ResourceTypeEnum.BLOCK_CONSISTENCY_GROUP, from.getConsistencyGroup()));

        if (from.getParent() != null) {
            URI parentURI = from.getParent().getURI();
            URIQueryResultList results = new URIQueryResultList();
            dbClient.queryByConstraint(AlternateIdConstraint.Factory.getVolumeByAssociatedVolumesConstraint(parentURI.toString()), results);
            Iterator<URI> resultsIter = results.iterator();
            if (resultsIter.hasNext()) {
                parentURI = resultsIter.next();
            }
            to.setParent(toRelatedResource(ResourceTypeEnum.VOLUME, parentURI));
        }
        if (from.getProject() != null) {
            to.setProject(toRelatedResource(ResourceTypeEnum.PROJECT, from.getProject().getURI()));
        }
        to.setNewVolumeNativeId(from.getNewVolumeNativeId());
        to.setSourceNativeId(from.getSourceNativeId());
        to.setSyncActive(from.getIsSyncActive());
        to.setReplicaState(getReplicaState(from));
        to.setReadOnly(from.getIsReadOnly());
        to.setSnapsetLabel(from.getSnapsetLabel() != null ? from.getSnapsetLabel() : "");
        to.setProvisionedCapacity(CapacityUtils.convertBytesToGBInStr(from.getProvisionedCapacity()));
        to.setAllocatedCapacity(CapacityUtils.convertBytesToGBInStr(from.getAllocatedCapacity()));
        to.setTechnologyType(from.getTechnologyType());
        return to;
    }

    public static String getReplicaState(BlockSnapshot snapshot) {
        if (snapshot.getIsSyncActive()) {
            return SynchronizationState.SYNCHRONIZED.name();
        } else {
            return SynchronizationState.PREPARED.name();
        }
    }

    /**
     * Maps a BlockSnapshotSession instance to its Rest representation.
     *
     * @param dbClient A reference to a database client.
     * @param from An instance of BlockSnapshotSession.
     *
     * @return An instance of BlockSnapshotSessionRestRep
     */
    public static BlockSnapshotSessionRestRep map(DbClient dbClient, BlockSnapshotSession from) {
        if (from == null) {
            return null;
        }
        BlockSnapshotSessionRestRep to = new BlockSnapshotSessionRestRep();

        // Map base class fields.
        mapDataObjectFields(from, to);

        // Map snapshot session consistency group.
        URI consistencyGroup = from.getConsistencyGroup();
        if (consistencyGroup != null) {
            to.setConsistencyGroup(toRelatedResource(ResourceTypeEnum.BLOCK_CONSISTENCY_GROUP, consistencyGroup));
        }

        // Map snapshot session parent i.e., the snapshot session source.
        NamedURI parentNamedURI = from.getParent();
        if (parentNamedURI != null) {
            URI parentURI = parentNamedURI.getURI();

            // It may be possible that the source for the snapshot
            // session is a backend source volume for a VPLEX volume
            // if we support creating snapshot sessions for VPLEX
            // volumes backed by storage that supports snapshot sessions.
            // In this case, the parent we want to reflect in the response
            // is the VPLEX volume.
            URIQueryResultList results = new URIQueryResultList();
            dbClient.queryByConstraint(AlternateIdConstraint.Factory.getVolumeByAssociatedVolumesConstraint(parentURI.toString()), results);
            Iterator<URI> resultsIter = results.iterator();
            if (resultsIter.hasNext()) {
                parentURI = resultsIter.next();
            }

            // This theoretically could be a Volume or BlockSnspshot instance
            // when we support creating a snapshot session of a linked target
            // volume for an array snapshot, which is a BlockSnapshot.
            if (URIUtil.isType(parentURI, Volume.class)) {
                to.setParent(toRelatedResource(ResourceTypeEnum.VOLUME, parentURI));
            }
            else {
                to.setParent(toRelatedResource(ResourceTypeEnum.BLOCK_SNAPSHOT, parentURI));
            }
        }

        // Map project
        NamedURI projectURI = from.getProject();
        if (projectURI != null) {
            to.setProject(toRelatedResource(ResourceTypeEnum.PROJECT, projectURI.getURI()));
        }

        // Map storage controller
        URI storageURI = from.getStorageController();
        if (storageURI != null) {
            to.setStorageController(storageURI);
        }

        // Map linked targets.
        StringSet linkedTargetIds = from.getLinkedTargets();
        if ((linkedTargetIds != null) && (!linkedTargetIds.isEmpty())) {
            List<RelatedResourceRep> linkedTargetReps = new ArrayList<RelatedResourceRep>();
            for (String linkedTargetId : linkedTargetIds) {
                URI linkedTargetURI = URI.create(linkedTargetId);
                // Linked targets are instances of BlockSnapshot.
                linkedTargetReps.add(toRelatedResource(ResourceTypeEnum.BLOCK_SNAPSHOT, linkedTargetURI));
            }
            to.setLinkedTargets(linkedTargetReps);
        }

        // Map session label.
        to.setSessionLabel(from.getSessionLabel());

        // Map replication group name.
        to.setReplicationGroupInstance(from.getReplicationGroupInstance());

        // Map session set name.
        to.setSessionSetName(from.getSessionSetName());

        return to;
    }

    public static BlockMirrorRestRep map(DbClient dbClient, BlockMirror from) {
        if (from == null) {
            return null;
        }
        BlockMirrorRestRep to = new BlockMirrorRestRep();
        mapBlockObjectFields(from, to);

        if (from.getSource() != null) {
            to.setSource(toNamedRelatedResource(ResourceTypeEnum.VOLUME, from.getSource().getURI(), from.getSource().getName()));
        }
        to.setSyncState(from.getSyncState());
        to.setSyncType(from.getSyncType());
        to.setReplicaState(SynchronizationState.fromState(from.getSyncState()).name());
        to.setVirtualPool(toRelatedResource(ResourceTypeEnum.BLOCK_VPOOL, from.getVirtualPool()));
        if (from.getPool() != null) {
            to.setPool(toRelatedResource(ResourceTypeEnum.STORAGE_POOL, from.getPool()));
        }
        return to;
    }

    public static VplexMirrorRestRep map(VplexMirror from) {
        if (from == null) {
            return null;
        }
        VplexMirrorRestRep to = new VplexMirrorRestRep();
        mapDataObjectFields(from, to);

        if (from.getSource() != null) {
            to.setSource(toNamedRelatedResource(ResourceTypeEnum.VOLUME, from.getSource().getURI(), from.getSource().getName()));
        }
        to.setStorageController(from.getStorageController());
        to.setVirtualArray(toRelatedResource(ResourceTypeEnum.VARRAY, from.getVirtualArray()));
        to.setDeviceLabel(from.getDeviceLabel() != null ? from.getDeviceLabel() : "");
        to.setNativeId(from.getNativeId() != null ? from.getNativeId() : "");
        to.setVirtualPool(toRelatedResource(ResourceTypeEnum.BLOCK_VPOOL, from.getVirtualPool()));
        return to;
    }

    public static BlockConsistencyGroupRestRep map(BlockConsistencyGroup from, Set<URI> volumes, DbClient dbClient) {
        if (from == null) {
            return null;
        }

        BlockConsistencyGroupRestRep to = new BlockConsistencyGroupRestRep();
        mapDataObjectFields(from, to);
        to.setVirtualArray(toRelatedResource(ResourceTypeEnum.VARRAY, from.getVirtualArray()));
        to.setProject(toRelatedResource(ResourceTypeEnum.PROJECT, from.getProject().getURI()));
        if (!NullColumnValueGetter.isNullURI(from.getStorageController())) {
            to.setStorageController(toRelatedResource(ResourceTypeEnum.STORAGE_SYSTEM, from.getStorageController()));
        }
        to.setArrayConsistency(from.getArrayConsistency());

        // Default snapshot session support to false
        to.setSupportsSnapshotSessions(Boolean.FALSE);
        if (dbClient != null && from.getSystemConsistencyGroups() != null) {
            for (String systemId : from.getSystemConsistencyGroups().keySet()) {
                StorageSystem system = dbClient.queryObject(StorageSystem.class, URI.create(systemId));
                if (system != null && system.checkIfVmax3()) {
                    to.setSupportsSnapshotSessions(Boolean.TRUE);
                }
            }
        }

        try {
            if (from.getSystemConsistencyGroups() != null) {
                to.setSystemConsistencyGroups(new StringSetMapAdapter().marshal(from.getSystemConsistencyGroups()));

                if (!to.getSupportsSnapshotSessions()) {
                    // If we haven't already determined that we can support snapshot sessions,
                    // loop through all the system cg's to find any storage systems that this
                    // cg resides on. If any of those entries supports snapshot sessions then
                    // we can flag this as true.
                    if (dbClient != null && to.getSystemConsistencyGroups() != null) {
                        for (StringSetMapAdapter.Entry entry : to.getSystemConsistencyGroups()) {
                            String storageSystemId = entry.getKey();
                            if (storageSystemId != null) {
                                StorageSystem system = dbClient.queryObject(StorageSystem.class, URI.create(storageSystemId));
                                if (system != null && system.checkIfVmax3()) {
                                    to.setSupportsSnapshotSessions(Boolean.TRUE);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // internally ignored
            logger.debug(e.getMessage(), e);
        }

        if (from.getTypes() != null) {
            to.setTypes(from.getTypes());
        }
        
        if (dbClient != null) {
            List<RelatedResourceRep> volumesResourceRep = new ArrayList<RelatedResourceRep>();
            final URIQueryResultList cgVolumesResults = new URIQueryResultList();
            dbClient.queryByConstraint(getVolumesByConsistencyGroup(from.getId()), cgVolumesResults);
            Iterator<Volume> volumeIterator = dbClient.queryIterativeObjects(Volume.class, cgVolumesResults);
            boolean first = true;
            while(volumeIterator.hasNext()) {
                // Get the first RP or SRDF volume. From this we are able to obtain the
                // link status and protection set (RP) information for all volumes in the
                // CG.
                Volume volume = volumeIterator.next();

                if (first) {
                    if (from.getTypes().contains(BlockConsistencyGroup.Types.RP.toString())
                            && !NullColumnValueGetter.isNullNamedURI(volume.getProtectionSet())) {
                        // Get the protection set from the first volume and set the appropriate fields
                        ProtectionSet protectionSet = dbClient.queryObject(ProtectionSet.class, volume.getProtectionSet());
                        to.setRpConsistenyGroupId(protectionSet.getProtectionId());
                        to.setLinkStatus(protectionSet.getProtectionStatus());
                        to.setRpProtectionSystem(protectionSet.getProtectionSystem());
                    } else if (from.getTypes().contains(BlockConsistencyGroup.Types.SRDF.toString())) {
                        // Operations cannot be performed individually on volumes within an SRDF CG, hence
                        // we can take any one of the volume's link status and update the CG link status.
                        to.setLinkStatus(volume.getLinkStatus());
                    }
                }
                // Only display CG volumes that are non-RP or RP source volumes. Exclude RP+VPlex backing volumes.
                if ((!volume.checkForRp() && !RPHelper.isAssociatedToAnyRpVplexTypes(volume, dbClient))
                        || (volume.checkForRp() && PersonalityTypes.SOURCE.name().equals(volume.getPersonality()))) {
                    volumesResourceRep.add(toRelatedResource(ResourceTypeEnum.VOLUME, volume.getId()));
                }
                first = false;
            }
            to.setVolumes(volumesResourceRep);
        }

        return to;
    }

    public static MigrationRestRep map(Migration from) {
        if (from == null) {
            return null;
        }
        MigrationRestRep to = new MigrationRestRep();
        to.setVolume(toRelatedResource(ResourceTypeEnum.VOLUME, from.getVolume()));
        to.setSource(toRelatedResource(ResourceTypeEnum.VOLUME, from.getSource()));
        to.setTarget(toRelatedResource(ResourceTypeEnum.VOLUME, from.getTarget()));
        to.setStartTime(from.getStartTime());
        to.setStatus(from.getMigrationStatus());
        to.setPercentageDone(from.getPercentDone());
        return to;
    }

    public static AutoTieringPolicyRestRep map(AutoTieringPolicy from) {
        if (from == null) {
            return null;
        }
        AutoTieringPolicyRestRep to = new AutoTieringPolicyRestRep();
        mapDiscoveredDataObjectFields(from, to);
        to.setSystemType(from.getSystemType());
        to.setStorageGroupName(from.getStorageGroupName());
        to.setStorageDevice(toRelatedResource(ResourceTypeEnum.STORAGE_SYSTEM, from.getStorageSystem()));
        to.setStoragePools(from.getPools());
        to.setPolicyName(from.getPolicyName());
        to.setPolicyEnabled(from.getPolicyEnabled());
        to.setProvisioningType(from.getProvisioningType());
        return to;
    }

    public static void addAutoTierPolicy(AutoTieringPolicy policy, AutoTierPolicyList list, boolean uniquePolicyNames) {
        if (DiscoveredDataObject.Type.vnxblock.toString().equalsIgnoreCase(policy.getSystemType())
                || uniquePolicyNames) {
            if (!list.containsPolicy(policy.getPolicyName())) {
                list.getAutoTierPolicies().add(
                        toNamedRelatedResource(policy, policy.getPolicyName()));
            }
        } else if (!uniquePolicyNames) {
            list.getAutoTierPolicies().add(
                    toNamedRelatedResource(policy, policy.getNativeGuid()));
        }
    }

    public static StorageTierRestRep map(StorageTier from) {
        if (from == null) {
            return null;
        }
        StorageTierRestRep to = new StorageTierRestRep();
        mapDiscoveredDataObjectFields(from, to);
        to.setEnabledState(from.getEnabledState());
        to.setPercentage(from.getPercentage());
        to.setTotalCapacity(from.getTotalCapacity());
        to.setDiskDriveTechnology(from.getDiskDriveTechnology());
        to.setAutoTieringPolicies(from.getAutoTieringPolicies());
        return to;
    }

    public static UnManagedVolumeRestRep map(UnManagedVolume from) {
        if (from == null) {
            return null;
        }
        UnManagedVolumeRestRep to = new UnManagedVolumeRestRep();
        mapDataObjectFields(from, to);
        to.setNativeGuid(from.getNativeGuid());
        try {
            to.setVolumeInformation(new StringSetMapAdapter().marshal(from.getVolumeInformation()));
        } catch (Exception e) {
            // Intentionally ignored
        }
        to.setVolumeCharacteristics(new StringMapAdapter().marshal(from.getVolumeCharacterstics()));
        to.setStorageSystem(toRelatedResource(ResourceTypeEnum.STORAGE_SYSTEM, from.getStorageSystemUri()));
        to.setStoragePool(toRelatedResource(ResourceTypeEnum.STORAGE_POOL, from.getStoragePoolUri()));

        List<String> uems = new ArrayList<String>();
        for (String uem : from.getUnmanagedExportMasks()) {
            uems.add(uem);
        }
        to.setUnManagedExportMasks(uems);

        List<String> initiatorUris = new ArrayList<String>();
        for (String uri : from.getInitiatorUris()) {
            initiatorUris.add(uri);
        }
        to.setInitiatorUris(initiatorUris);

        List<String> initiatorNetworkIds = new ArrayList<String>();
        for (String id : from.getInitiatorNetworkIds()) {
            initiatorNetworkIds.add(id);
        }
        to.setInitiatorNetworkIds(initiatorNetworkIds);

        List<String> storagePortUris = new ArrayList<String>();
        for (String uri : from.getStoragePortUris()) {
            storagePortUris.add(uri);
        }
        to.setStoragePortUris(storagePortUris);

        List<String> supportedVPoolUris = new ArrayList<String>();
        for (String uri : from.getSupportedVpoolUris()) {
            supportedVPoolUris.add(uri);
        }
        to.setSupportedVPoolUris(supportedVPoolUris);

        to.setWWN(from.getWwn());

        return to;
    }

    public static UnManagedExportMaskRestRep map(UnManagedExportMask from) {
        if (from == null) {
            return null;
        }
        UnManagedExportMaskRestRep to = new UnManagedExportMaskRestRep();
        mapDataObjectFields(from, to);
        to.setNativeId(from.getNativeId());
        to.setMaskName(from.getMaskName());
        to.setStorageSystem(toRelatedResource(ResourceTypeEnum.STORAGE_SYSTEM, from.getStorageSystemUri()));

        if ((from.getKnownInitiatorUris() != null) && (!from.getKnownInitiatorUris().isEmpty())) {
            List<RelatedResourceRep> reps = new ArrayList<RelatedResourceRep>();
            for (String uri : from.getKnownInitiatorUris()) {
                reps.add(toRelatedResource(ResourceTypeEnum.INITIATOR, URI.create(uri)));
            }
            to.setKnownInitiatorUris(reps);
        }

        if ((from.getKnownStoragePortUris() != null) && (!from.getKnownStoragePortUris().isEmpty())) {
            List<RelatedResourceRep> reps = new ArrayList<RelatedResourceRep>();
            for (String uri : from.getKnownStoragePortUris()) {
                reps.add(toRelatedResource(ResourceTypeEnum.STORAGE_PORT, URI.create(uri)));
            }
            to.setKnownStoragePortUris(reps);
        }

        if ((from.getKnownVolumeUris() != null) && (!from.getKnownVolumeUris().isEmpty())) {
            List<RelatedResourceRep> reps = new ArrayList<RelatedResourceRep>();
            for (String uri : from.getKnownVolumeUris()) {
                reps.add(toRelatedResource(ResourceTypeEnum.VOLUME, URI.create(uri)));
            }
            to.setKnownStorageVolumeUris(reps);
        }

        if ((from.getUnmanagedVolumeUris() != null) && (!from.getUnmanagedVolumeUris().isEmpty())) {
            List<RelatedResourceRep> reps = new ArrayList<RelatedResourceRep>();
            for (String uri : from.getUnmanagedVolumeUris()) {
                reps.add(toRelatedResource(ResourceTypeEnum.UNMANAGED_VOLUMES, URI.create(uri)));
            }
            to.setUnmanagedVolumeUris(reps);
        }

        to.setUnmanagedInitiatorNetworkIds(from.getUnmanagedInitiatorNetworkIds());
        to.setUnmanagedStoragePortNetworkIds(from.getUnmanagedStoragePortNetworkIds());

        return to;
    }

    public static NamedRelatedVirtualPoolRep toVirtualPoolResource(VirtualPool vpool) {
        ResourceTypeEnum type = BlockMapper.getResourceType(VirtualPool.Type.valueOf(vpool.getType()));
        return new NamedRelatedVirtualPoolRep(vpool.getId(), toLink(type, vpool.getId()), vpool.getLabel(), vpool.getType());
    }

    public static VirtualPoolChangeRep toVirtualPoolChangeRep(VirtualPool vpool, List<VirtualPoolChangeOperationEnum> allowedOpertions,
            String notAllowedReason) {
        ResourceTypeEnum type = BlockMapper.getResourceType(VirtualPool.Type.valueOf(vpool.getType()));
        return new VirtualPoolChangeRep(vpool.getId(), toLink(type, vpool.getId()), vpool.getLabel(), vpool.getType(), notAllowedReason,
                allowedOpertions);
    }

    public static ResourceTypeEnum getResourceType(VirtualPool.Type cosType) {
        if (VirtualPool.Type.block == cosType) {
            return ResourceTypeEnum.BLOCK_VPOOL;
        } else if (VirtualPool.Type.file == cosType) {
            return ResourceTypeEnum.FILE_VPOOL;
        } else if (VirtualPool.Type.object == cosType) {
            return ResourceTypeEnum.OBJECT_VPOOL;
        } else {
            // impossible;
            return ResourceTypeEnum.BLOCK_VPOOL;
        }
    }
}
