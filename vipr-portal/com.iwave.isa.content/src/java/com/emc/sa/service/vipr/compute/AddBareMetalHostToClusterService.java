/*
 * Copyright (c) 2012-2015 iWave Software LLC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.compute;

import static com.emc.sa.service.ServiceParams.CLUSTER;
import static com.emc.sa.service.ServiceParams.COMPUTE_VIRTUAL_POOL;
import static com.emc.sa.service.ServiceParams.HLU;
import static com.emc.sa.service.ServiceParams.PROJECT;
import static com.emc.sa.service.ServiceParams.SIZE_IN_GB;
import static com.emc.sa.service.ServiceParams.VIRTUAL_ARRAY;
import static com.emc.sa.service.ServiceParams.VIRTUAL_POOL;

import java.net.URI;
import java.util.List;

import com.emc.sa.engine.ExecutionUtils;
import com.emc.sa.engine.bind.Bindable;
import com.emc.sa.engine.bind.Param;
import com.emc.sa.engine.service.Service;
import com.emc.sa.service.vipr.ViPRService;
import com.emc.sa.service.vipr.block.BlockStorageUtils;
import com.emc.sa.service.vipr.compute.ComputeUtils.FqdnTable;
import com.emc.storageos.db.client.model.Cluster;
import com.emc.storageos.db.client.model.Host;
import com.emc.storageos.model.vpool.ComputeVirtualPoolRestRep;
import com.google.common.collect.ImmutableList;

@Service("AddBareMetalHostToCluster")
public class AddBareMetalHostToClusterService extends ViPRService {

    @Param(CLUSTER)
    protected URI clusterId;

    @Param(PROJECT)
    protected URI project;

    @Param(VIRTUAL_ARRAY)
    protected URI virtualArray;

    @Param(VIRTUAL_POOL)
    protected URI virtualPool;

    @Param(COMPUTE_VIRTUAL_POOL)
    protected URI computeVirtualPool;

    @Param(SIZE_IN_GB)
    protected Double size;

    @Param(value = HLU, required = false)
    protected Integer hlu;

    @Bindable(itemType = FqdnTable.class)
    protected FqdnTable[] fqdnValues;

    private Cluster cluster;
    private List<String> hostNames = null;
    private List<String> copyOfHostNames = null;


    @Override
    public void precheck() throws Exception {

        StringBuilder preCheckErrors = new StringBuilder();
        hostNames = ComputeUtils.getHostNamesFromFqdn(fqdnValues);
        copyOfHostNames = ImmutableList.copyOf(hostNames);

        List<String> existingHostNames = ComputeUtils.getHostNamesByName(getClient(), hostNames);
        cluster = BlockStorageUtils.getCluster(clusterId);
        List<String> hostNamesInCluster = ComputeUtils.findHostNamesInCluster(cluster);

        if (cluster == null) {
            preCheckErrors.append(ExecutionUtils.getMessage("compute.cluster.no.cluster.exists"));
        }

        if (hostNames == null || hostNames.isEmpty()) {
            preCheckErrors.append(
                    ExecutionUtils.getMessage("compute.cluster.osinstall.host.required") + "  ");
        }

        // Check for validity of host names and host Ips
        for (String hostName : hostNames) {
            if (!ComputeUtils.isValidHostIdentifier(hostName)) {
                preCheckErrors.append(
                        ExecutionUtils.getMessage("compute.cluster.hostname.invalid", hostName) + "  ");
            }
        }

        // Commenting for COP-26104, this pre-check will not allow order to be resubmitted if
        // only the shared export group fails and all other steps succeeded.
        /*if (hostNamesInCluster != null && !hostNamesInCluster.isEmpty() && !existingHostNames.isEmpty()
                && hostNamesInCluster.containsAll(existingHostNames)) {
            preCheckErrors.append(
                    ExecutionUtils.getMessage("compute.cluster.host.already.in.cluster") + "  ");
        }*/

        if (!ComputeUtils.isCapacityAvailable(getClient(), virtualPool,
                virtualArray, size, (hostNames.size() - existingHostNames.size()))) {
            preCheckErrors.append(
                    ExecutionUtils.getMessage("compute.cluster.insufficient.storage.capacity") + "  ");
        }

        if (!ComputeUtils.isComputePoolCapacityAvailable(getClient(), computeVirtualPool,
                (hostNames.size() - existingHostNames.size()))) {
            preCheckErrors.append(
                    ExecutionUtils.getMessage("compute.cluster.insufficient.compute.capacity") + "  ");
        }

        for (String existingHostName : existingHostNames) {
            if (!hostNamesInCluster.contains(existingHostName)) {
                preCheckErrors.append(
                        ExecutionUtils.getMessage("compute.cluster.hosts.exists.elsewhere",
                                existingHostName) + "  ");
            }
        }

        ComputeVirtualPoolRestRep cvp = ComputeUtils.getComputeVirtualPool(getClient(), computeVirtualPool);
        if (cvp.getServiceProfileTemplates().isEmpty()) {
            preCheckErrors.append(
                    ExecutionUtils.getMessage("compute.cluster.service.profile.templates.null", cvp.getName()) + "  ");
        }

        if (preCheckErrors.length() > 0) {
            throw new IllegalStateException(preCheckErrors.toString());
        }
    }

    @Override
    public void execute() throws Exception {

        hostNames = ComputeUtils.removeExistingHosts(hostNames, cluster);

        List<Host> hosts = ComputeUtils.createHosts(cluster, computeVirtualPool, hostNames, virtualArray);
        logInfo("compute.cluster.hosts.created", ComputeUtils.nonNull(hosts).size());

        List<URI> bootVolumeIds = ComputeUtils.makeBootVolumes(project, virtualArray, virtualPool, size, hosts,
                getClient());
        logInfo("compute.cluster.boot.volumes.created", ComputeUtils.nonNull(bootVolumeIds).size());
        hosts = ComputeUtils.deactivateHostsWithNoBootVolume(hosts, bootVolumeIds, cluster);

        List<URI> exportIds = ComputeUtils.exportBootVols(bootVolumeIds, hosts, project, virtualArray, hlu);
        logInfo("compute.cluster.exports.created", ComputeUtils.nonNull(exportIds).size());
        hosts = ComputeUtils.deactivateHostsWithNoExport(hosts, exportIds, bootVolumeIds, cluster);
        hosts = ComputeUtils.setHostBootVolumes(hosts, bootVolumeIds, true);

        ComputeUtils.addHostsToCluster(hosts, cluster);

        String orderErrors = ComputeUtils.getOrderErrors(cluster, copyOfHostNames, null, null);
        if (orderErrors.length() > 0) { // fail order so user can resubmit
            if (ComputeUtils.nonNull(hosts).isEmpty()) {
                throw new IllegalStateException(
                        ExecutionUtils.getMessage("compute.cluster.order.incomplete", orderErrors));
            } else {
                logError("compute.cluster.order.incomplete", orderErrors);
                setPartialSuccess();
            }
        }
    }

    public URI getClusterId() {
        return clusterId;
    }

    public void setClusterId(URI clusterId) {
        this.clusterId = clusterId;
    }

    public URI getProject() {
        return project;
    }

    public void setProject(URI project) {
        this.project = project;
    }

    public URI getVirtualArray() {
        return virtualArray;
    }

    public void setVirtualArray(URI virtualArray) {
        this.virtualArray = virtualArray;
    }

    public URI getVirtualPool() {
        return virtualPool;
    }

    public void setVirtualPool(URI virtualPool) {
        this.virtualPool = virtualPool;
    }

    public URI getVirtualComputePool() {
        return computeVirtualPool;
    }

    public void setVirtualComputePool(URI virtualComputePool) {
        this.computeVirtualPool = virtualComputePool;
    }

    public Double getSize() {
        return size;
    }

    public void setSize(Double size) {
        this.size = size;
    }

    /**
     * @return the cluster
     */
    public Cluster getCluster() {
        return cluster;
    }

    /**
     * @param cluster the cluster to set
     */
    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    /**
     * @return the hostNames
     */
    public List<String> getHostNames() {
        return hostNames;
    }

    /**
     * @param hostNames the hostNames to set
     */
    public void setHostNames(List<String> hostNames) {
        this.hostNames = hostNames;
    }

    /**
     * @return the copyOfHostNames
     */
    public List<String> getCopyOfHostNames() {
        return copyOfHostNames;
    }

    /**
     * @param copyOfHostNames the copyOfHostNames to set
     */
    public void setCopyOfHostNames(List<String> copyOfHostNames) {
        this.copyOfHostNames = copyOfHostNames;
    }

}
