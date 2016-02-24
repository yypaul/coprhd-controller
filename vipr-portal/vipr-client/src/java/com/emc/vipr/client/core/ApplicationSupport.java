/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.vipr.client.core;

import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_CLONE_SET_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_CLONE_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_CREATE_APP_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_CREATE_FULL_COPY_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_DELETE_APP_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_DETACH_FULL_COPY_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_FULL_COPY_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_GET_CLUSTERS_APP_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_GET_HOSTS_APP_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_RESTORE_FULL_COPY_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_RESYNCHRONIZE_FULL_COPY_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_UPDATE_APP_URL;
import static com.emc.vipr.client.core.impl.PathConstants.APP_SUPPORT_VOLUME_URL;
import static com.emc.vipr.client.core.util.ResourceUtils.defaultList;

import java.net.URI;
import java.util.List;

import javax.ws.rs.core.UriBuilder;

import com.emc.storageos.model.NamedRelatedResourceRep;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.application.VolumeGroupCopySetList;
import com.emc.storageos.model.application.VolumeGroupCopySetParam;
import com.emc.storageos.model.application.VolumeGroupCreateParam;
import com.emc.storageos.model.application.VolumeGroupFullCopyCreateParam;
import com.emc.storageos.model.application.VolumeGroupFullCopyDetachParam;
import com.emc.storageos.model.application.VolumeGroupFullCopyRestoreParam;
import com.emc.storageos.model.application.VolumeGroupFullCopyResynchronizeParam;
import com.emc.storageos.model.application.VolumeGroupList;
import com.emc.storageos.model.application.VolumeGroupRestRep;
import com.emc.storageos.model.application.VolumeGroupUpdateParam;
import com.emc.storageos.model.block.NamedVolumesList;
import com.emc.storageos.model.host.HostList;
import com.emc.storageos.model.host.cluster.ClusterList;
import com.emc.vipr.client.core.filters.ResourceFilter;
import com.emc.vipr.client.core.impl.PathConstants;
import com.emc.vipr.client.impl.RestClient;

public class ApplicationSupport extends AbstractResources<VolumeGroupRestRep> {

    protected final RestClient client;

    public ApplicationSupport(RestClient client) {
        super(client, VolumeGroupRestRep.class, PathConstants.APP_SUPPORT_CREATE_APP_URL);
        this.client = client;
    }

    /**
     * Creates an application.
     * <p>
     * API Call: POST /volume-groups/block
     * 
     * @return The new state of the cluster
     */
    public VolumeGroupRestRep createApplication(VolumeGroupCreateParam input) {
        return client.post(VolumeGroupRestRep.class, input, APP_SUPPORT_CREATE_APP_URL);
    }

    /**
     * Get List of applications
     * API call: GET /volume-groups/block
     * 
     * @return List of applications
     */

    public VolumeGroupList getApplications() {
        return client.get(VolumeGroupList.class, APP_SUPPORT_CREATE_APP_URL, "");
    }

    public List<VolumeGroupRestRep> getApplications(ResourceFilter<VolumeGroupRestRep> filter) {
        VolumeGroupList groups = getApplications();
        return this.getByRefs(groups.getVolumeGroups(), filter);
    }

    /**
     * Deletes an application
     * API Call: POST /volume-groups/block/{id}/deactivate
     * 
     */
    public void deleteApplication(URI id) {
        client.post(String.class, APP_SUPPORT_DELETE_APP_URL, id);
    }

    /**
     * Update an application
     * API call: PUT /volume-groups/block/{id}
     * 
     */
    public TaskList updateApplication(URI id, VolumeGroupUpdateParam input) {
        UriBuilder uriBuilder = client.uriBuilder(APP_SUPPORT_UPDATE_APP_URL);
        return client.putURI(TaskList.class, input, uriBuilder.build(id));
    }

    /**
     * Get application based on ID
     * 
     */
    public VolumeGroupRestRep getApplication(URI id) {
        return client.get(VolumeGroupRestRep.class, APP_SUPPORT_UPDATE_APP_URL, id);
    }

    public List<NamedRelatedResourceRep> getVolumes(URI id) {
        NamedVolumesList response = client.get(NamedVolumesList.class, APP_SUPPORT_VOLUME_URL, id);
        return response.getVolumes();
    }

    /**
     * Get hosts associated with an application
     * 
     * @param id application id
     * @return list of hosts
     */
    public List<NamedRelatedResourceRep> getHosts(URI id) {
        HostList response = client.get(HostList.class, APP_SUPPORT_GET_HOSTS_APP_URL, id);
        return response.getHosts();
    }

    /**
     * Get clusters associated with an application
     * 
     * @param id application id
     * @return list of clusters
     */
    public List<NamedRelatedResourceRep> getClusters(URI id) {
        ClusterList response = client.get(ClusterList.class, APP_SUPPORT_GET_CLUSTERS_APP_URL, id);
        return response.getClusters();
    }

    /**
     * Get volumes associated with an application
     * 
     * @param id application id
     * @return list of volumes
     */
    public List<NamedRelatedResourceRep> listVolumes(URI id) {
        NamedVolumesList response = getVolumeByApplication(id);
        return defaultList(response.getVolumes());
    }

    /*
     * Get volumes for application
     * GET /volume-groups/block/{id}/volumes
     */
    public NamedVolumesList getVolumeByApplication(URI id) {
        return client.get(NamedVolumesList.class, APP_SUPPORT_VOLUME_URL, id);
    }

    /*
     * Get full copies for application
     * GET /volume-groups/block/{id}/protection/full-copies
     */
    public NamedVolumesList getClonesByApplication(URI id) {
        return client.get(NamedVolumesList.class, APP_SUPPORT_CLONE_URL, id);
    }

    /**
     * Creates a full copy of an application.
     * API Call: POST /volume-groups/block/{id}/protection/full-copies
     * 
     * @param id application id to create full copy of
     * @param input input parameters for create full copy request
     * @return list of tasks
     */
    public TaskList createFullCopyOfApplication(URI id, VolumeGroupFullCopyCreateParam input) {
        UriBuilder uriBuilder = client.uriBuilder(APP_SUPPORT_CREATE_FULL_COPY_URL);
        return client.postURI(TaskList.class, input, uriBuilder.build(id));
    }

    /*
     * Get full copies for application
     * GET /volume-groups/block/{id}/volumes
     */
    public NamedVolumesList getFullCopiesByApplication(URI id) {
        return client.get(NamedVolumesList.class, APP_SUPPORT_FULL_COPY_URL, id);
    }

    /**
     * Get full copy set for an application
     * GET /volume-groups/block/{id}/protection/full-copies/copy-sets
     */

    public VolumeGroupCopySetList getFullCopySetsByApplication(URI id) {
        return client.get(VolumeGroupCopySetList.class, APP_SUPPORT_CLONE_SET_URL, id);
    }

    /**
     * POST /volume-groups/block/{id}/protection/full-copies/copy-sets/{id}
     * 
     */
    public NamedVolumesList getVolumeGroupFullCopiesForSet(URI applicationId, VolumeGroupCopySetParam param) {
        UriBuilder uribuilder = client.uriBuilder(APP_SUPPORT_CLONE_SET_URL);
        return client.postURI(NamedVolumesList.class, param, uribuilder.build(applicationId));
    }

    /**
     * Detaches a full copy of an application.
     * API Call: POST /volume-groups/block/{id}/protection/full-copies/detach
     * 
     * @param id application id with full copy
     * @param input input parameters for application full copy request
     * @return list of tasks
     */
    public TaskList detachApplicationFullCopy(URI id, VolumeGroupFullCopyDetachParam input) {
        UriBuilder uriBuilder = client.uriBuilder(APP_SUPPORT_DETACH_FULL_COPY_URL);
        return client.postURI(TaskList.class, input, uriBuilder.build(id));
    }

    /**
     * Restores a full copy of an application.
     * API Call: POST /volume-groups/block/{id}/protection/full-copies/restore
     * 
     * @param id application id with full copy
     * @param input input parameters for application full copy request
     * @return list of tasks
     */
    public TaskList restoreApplicationFullCopy(URI id, VolumeGroupFullCopyRestoreParam input) {
        UriBuilder uriBuilder = client.uriBuilder(APP_SUPPORT_RESTORE_FULL_COPY_URL);
        return client.postURI(TaskList.class, input, uriBuilder.build(id));
    }

    /**
     * Resynchronizes a full copy of an application.
     * API Call: POST /volume-groups/block/{id}/protection/full-copies/resynchronize
     * 
     * @param id application id with full copy
     * @param input input parameters for application full copy request
     * @return list of tasks
     */
    public TaskList resynchronizeApplicationFullCopy(URI id, VolumeGroupFullCopyResynchronizeParam input) {
        UriBuilder uriBuilder = client.uriBuilder(APP_SUPPORT_RESYNCHRONIZE_FULL_COPY_URL);
        return client.postURI(TaskList.class, input, uriBuilder.build(id));
    }
}
