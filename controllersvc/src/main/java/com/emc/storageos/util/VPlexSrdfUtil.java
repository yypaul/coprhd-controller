/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.util;


import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.util.NullColumnValueGetter;

/**
 * Performs conversions from Vplex Volumes to corresponding SRDF Volumes and vice-versa.
 */
public class VPlexSrdfUtil {
    private VPlexSrdfUtil() { 
        // All methods are static, no need to make an instance.
    }
    
    /**
     * Returns the srdf underlying volume if there is one, otherwise null.
     * Assumes both legs of vplex cannot be srdf protected.
     * @param dbClient -- dbClient handle
     * @param vplexVolume -- Vplex volume object.
     * @return -- Srdf volume if present, or null
     */
    static public Volume getSrdfVolumeFromVplexVolume(DbClient dbClient, Volume vplexVolume) {
        if (null != vplexVolume.getAssociatedVolumes()) {
            List<URI> volumeUriList = new ArrayList<>();
            for (String associatedVolumeId : vplexVolume.getAssociatedVolumes()) {
                volumeUriList.add(URI.create(associatedVolumeId));
            }
            Iterator<Volume> volumes = dbClient.queryIterativeObjects(Volume.class, volumeUriList, true);
            while (volumes.hasNext()) {
                Volume assocVolume = volumes.next();
                if (assocVolume == null || assocVolume.getInactive()) {
                    continue;
                }
                if (assocVolume.checkForSRDF()) {
                    return assocVolume;
                }
            }
        }

        return null;
    }
    
    /**
     * Returns the Vplex volume that is covering an SRDF volume.
     * @param dbClient - dbclient handle
     * @param srdfVolume - SRDF Volume object
     * @return - Vplex volume fronting SRDF volume, or null
     */
    static public Volume getVplexVolumeFromSrdfVolume(DbClient dbClient, Volume srdfVolume) {
        return Volume.fetchVplexVolume(dbClient, srdfVolume);
    }
    
    /**
     * Given an SRDF or VPLEX volume, will always return the SRDF volume.
     * If vplex it's the corresponding srdf volume.
     * @param dbClient
     * @param id - URI of volume
     * @return - URI of SRDF volume
     */
    static public URI getSrdfIdFromVolumeId(DbClient dbClient, URI id) {
        if (id == null) {
            return null;
        }
        Volume volume = dbClient.queryObject(Volume.class, id);
        if (volume == null || !volume.isVPlexVolume(dbClient)) {
            return id;
        }
        Volume srdfVolume = getSrdfVolumeFromVplexVolume(dbClient, volume);
        return (srdfVolume == null ? id : srdfVolume.getId());
    }
    
    /**
     * Returns the vplex front volume ids for the srdf targets if they have a vplex volume,
     * otherwise returns the original srdf target id
     * @param dbClient - DbClient handle
     * @param srdfVolume - An Srdf volume having targets
     * @return String set of volume ids for either vplex/srdf volume or srdf volume in target list
     */
    static public StringSet getSrdfOrVplexTargets(DbClient dbClient, Volume srdfVolume) {
        StringSet targets = new StringSet();
        
        for (String targetVolumeId : srdfVolume.getSrdfTargets()) {
            URI srdfTargetUri = URI.create(targetVolumeId);
            URI vplexTargetUri = Volume.fetchVplexVolume(dbClient, srdfTargetUri);
            if (vplexTargetUri != null) {
                targets.add(vplexTargetUri.toString());
            } else {
                targets.add(srdfTargetUri.toString());
            }
        }
        return targets;
    }
    
    /**
     * Given a list of Vplex Volume URIs, will filter out any that front SRDF targets.
     * @param dbClient -- database client
     * @param vplexVolumeUris  -- list of URIS for Vplex volumes
     * @return -- filtered list of Vplex volume URIs, or empty list if all filtered away
     */
    public static List<URI> filterOutVplexSrdfTargets(DbClient dbClient, List<URI> vplexVolumeUris) {
        List<URI> returnedVolumes = new ArrayList<URI>();
        returnedVolumes.addAll(vplexVolumeUris);
        List<URI> vplexSrdfTargets = returnVplexSrdfTargets(dbClient, vplexVolumeUris);
        returnedVolumes.removeAll(vplexSrdfTargets);
        return returnedVolumes;
    }
    
    /**
     * Given a list of Vplex Volume URIs, return any that front SRDF targets.
     * @param dbClient -- database client
     * @param vplexVolumeURIs  -- list of URIS for Vplex volumes
     * @param setCGOnVplexVolume -- if true, sets the consistency group of the Vplex volumes
     *    to the CG of the underlying RDF volume, and only returns volumes that have CG set.
     *    It also makes sure that VPLEX is in the cg Requested Types.
     * @return -- filtered list of Vplex volume URIs, or empty list if all filtered away
     */
    public static List<URI> returnVplexSrdfTargets(DbClient dbClient, 
            List<URI> vplexVolumeURIs) {
        List<URI> returnedVolumes = new ArrayList<URI>();
        for (URI vplexURI : vplexVolumeURIs) {
            Volume vplexVolume = dbClient.queryObject(Volume.class, vplexURI);
            if (vplexVolume == null) {
                continue;
            }
            Volume srdfVolume = getSrdfVolumeFromVplexVolume(dbClient, vplexVolume);
            // See if SRDF target
            if (srdfVolume != null && !NullColumnValueGetter.isNullNamedURI(srdfVolume.getSrdfParent())) {
                // Virtual volume in front of SRDF target to return list
                returnedVolumes.add(vplexVolume.getId());
            }
        }
        return returnedVolumes;
    }
    
    /**
     * Given a list of Vplex volume URIs, returns a map of VPlexVolume to corresonding SRDF backend volume.
     * @param dbClient database client handle
     * @param vplexVolumeURIs -- List of vplex volume URIs
     * @return Map<Volume, Volume> of vplex to srdf volumes
     */
    public static Map<Volume, Volume> makeVplexToSrdfVolumeMap(DbClient dbClient, List<URI> vplexVolumeURIs) {
        Map<Volume, Volume> vplexToSrdfVolumeMap = new HashMap<Volume, Volume>();
        for (URI vplexVolumeURI : vplexVolumeURIs) {
            Volume vplexVolume = dbClient.queryObject(Volume.class, vplexVolumeURI);
            if (vplexVolume != null) {
                Volume srdfVolume = getSrdfVolumeFromVplexVolume(dbClient, vplexVolume);
                if (srdfVolume != null) {
                    vplexToSrdfVolumeMap.put(vplexVolume, srdfVolume);
                }
            }
        }
        return vplexToSrdfVolumeMap;
    }
    
}
