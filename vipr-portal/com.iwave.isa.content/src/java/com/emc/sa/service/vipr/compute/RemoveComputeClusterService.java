/*
 * Copyright (c) 2012-2015 iWave Software LLC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.compute;

import static com.emc.sa.service.ServiceParams.CLUSTER;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;

import com.emc.sa.engine.ExecutionUtils;
import com.emc.sa.engine.bind.Param;
import com.emc.sa.engine.service.Service;
import com.emc.sa.service.vipr.ViPRService;
import com.emc.sa.service.vipr.block.BlockStorageUtils;
import com.emc.sa.service.vipr.compute.tasks.DeactivateCluster;
import com.emc.storageos.db.client.model.Cluster;
import com.emc.storageos.model.block.BlockObjectRestRep;
import com.emc.storageos.model.host.HostRestRep;
import com.google.common.collect.Lists;

@Service("RemoveComputeCluster")
public class RemoveComputeClusterService extends ViPRService {

    @Param(CLUSTER)
    protected URI clusterId;

    private Cluster cluster;

    private List<URI> vblockHostURIs = null;
    private Map<URI, String> vblockHostMap = null;
    private List<URI> hostURIs = null;
    @Override
    public void precheck() throws Exception {
        StringBuilder preCheckErrors = new StringBuilder();
        cluster = BlockStorageUtils.getCluster(clusterId);
        if (cluster == null) {
            preCheckErrors.append("Cluster doesn't exist for ID " + clusterId);
        }
        hostURIs = ComputeUtils.getHostURIsByCluster(getClient(), clusterId);
        vblockHostMap = ComputeUtils.getVblockHostURIsByCluster(clusterId);
        vblockHostURIs = Lists.newArrayList(vblockHostMap.keySet());
        //Additional check to verify if cluster is vblock cluster
        if (!CollectionUtils.isEmpty(hostURIs) && CollectionUtils.isEmpty(vblockHostURIs)) {
            logError("computeutils.deactivatecluster.deactivate.notpossible.nonvblockcluster", cluster.getLabel());
            preCheckErrors.append("Cluster ").append(cluster.getLabel())
            .append(" is a non-Vblock cluster, cannot decommission a non-Vblock cluster.");
        } // verify if cluster is a mixed cluster, if so do not proceed further, only pure vblock clusters should be decommissioned.
        else if (!CollectionUtils.isEmpty(hostURIs) && !CollectionUtils.isEmpty(vblockHostURIs)
                && (hostURIs.size() > vblockHostURIs.size() || !vblockHostURIs.containsAll(hostURIs))) {
            logError("computeutils.deactivatecluster.deactivate.notpossible", cluster.getLabel());
            preCheckErrors.append("Cluster ").append(cluster.getLabel())
            .append(" is a mixed cluster, cannot decommission a mixed cluster.");
        }

        // Validate all of the boot volumes are still valid.
        if (!validateBootVolumes()) {
            logError("computeutils.deactivatecluster.deactivate.bootvolumes", cluster.getLabel());
            preCheckErrors.append("Cluster ").append(cluster.getLabel())
            .append(" has different boot volumes than what controller provisioned.  Cannot delete original boot volume in case it was re-purposed.");
        }

        if (preCheckErrors.length() > 0) {
            throw new IllegalStateException(preCheckErrors.toString());
        }
    }

    @Override
    public void execute() throws Exception {

        // removing cluster checks for running VMs first for ESX hosts
        addAffectedResource(clusterId);

        // VBDU [DONE] COP-28400: Looks like this will decommission an entire cluster if there are hosts in it that we are
        // not managing.
        // ClusterService has a precheck to verify the matching environments before deactivating
        if (vblockHostURIs.isEmpty() && hostURIs.isEmpty()) {
            execute(new DeactivateCluster(cluster));
            return;
        }

        // get boot vols to be deleted (so we can check afterwards)
        List<URI> bootVolsToBeDeleted = Lists.newArrayList();
        for (URI hostURI : vblockHostURIs) {
            // VBDU TODO: COP-28447, We're assuming the volume we're deleting is still the boot volume, but it could
            // have been manually dd'd (migrated) to another volume and this volume could be re-purposed elsewhere.
            // We should verify this is the boot volume on the server before attempting to delete it.
            URI bootVolURI = BlockStorageUtils.getHost(hostURI).getBootVolumeId();
            if (bootVolURI != null) {
                BlockObjectRestRep bootVolRep = null;
                try{
                    bootVolRep = BlockStorageUtils.getBlockResource(bootVolURI);
                } catch(Exception e){
                    //Invalid boot volume reference. Ignore
                }
                if (bootVolRep!=null && !bootVolRep.getInactive()) {
                    bootVolsToBeDeleted.add(bootVolURI);
                }
            }
        }

        // removing hosts also removes associated boot volumes and exports
        List<URI> successfulHostIds = ComputeUtils.deactivateHostURIs(vblockHostMap);

        // fail order if no hosts removed
        if (successfulHostIds.isEmpty()) {
            throw new IllegalStateException(ExecutionUtils.getMessage("computeutils.deactivatehost.deactivate.failure", ""));
        }
        // Check if all hosts are deactivated successfully and only then issue the deactivateCluster call
        if (successfulHostIds.size() == vblockHostURIs.size() && successfulHostIds.containsAll(vblockHostURIs)) {
            execute(new DeactivateCluster(cluster));
        }

        // check all hosts were removed
        if (successfulHostIds.size() < vblockHostURIs.size()) {
            for (URI hostURI : vblockHostURIs) {
                if (!successfulHostIds.contains(hostURI)) {
                    logError("computeutils.deactivatehost.failure", hostURI, clusterId);
                }
            }
            setPartialSuccess();
        }
        else {  // check all boot vols were removed
            for (URI bootVolURI : bootVolsToBeDeleted) {
                BlockObjectRestRep bootVolRep = BlockStorageUtils.getBlockResource(bootVolURI);
                if ((bootVolRep != null) && !bootVolRep.getInactive()) {
                    logError("computeutils.removebootvolumes.failure", bootVolRep.getDeviceLabel());
                    setPartialSuccess();
                }
            }
        }

    }

    /**
     * Validate that the boot volume for this host is still on the server.
     * This prevents us from deleting a re-purposed volume that was originally
     * a boot volume.
     *
     * @return false if we can reach the server and determine the boot volume is no longer there.
     */
    private boolean validateBootVolumes() {
        // If the cluster isn't returned properly, not found in DB, do not delete the boot volume until
        // the references are fixed.
        if (cluster == null || cluster.getInactive()) {
            logError("computeutils.removebootvolumes.failure.cluster");
            return false;
        }

        List<HostRestRep> clusterHosts = ComputeUtils.getHostsInCluster(cluster.getId());
        return ComputeUtils.validateBootVolumes(cluster, clusterHosts);
    }

    /**
     * @return the vblockHostURIs
     */
    public List<URI> getVblockHostURIs() {
        return vblockHostURIs;
    }

    /**
     * @param vblockHostURIs the vblockHostURIs to set
     */
    public void setVblockHostURIs(List<URI> vblockHostURIs) {
        this.vblockHostURIs = vblockHostURIs;
    }

    /**
     * @return the hostURIs
     */
    public List<URI> getHostURIs() {
        return hostURIs;
    }

    /**
     * @param hostURIs the hostURIs to set
     */
    public void setHostURIs(List<URI> hostURIs) {
        this.hostURIs = hostURIs;
    }

    /**
     * @return the vblockHostMap
     */
    public Map<URI, String> getVblockHostMap() {
        return vblockHostMap;
    }

    /**
     * @param vblockHostMap the vblockHostMap to set
     */
    public void setVblockHostMap(Map<URI, String> vblockHostMap) {
        this.vblockHostMap = vblockHostMap;
    }
}