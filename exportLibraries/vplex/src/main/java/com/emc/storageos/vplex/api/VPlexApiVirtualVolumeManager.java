/*
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.vplex.api;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.StringTokenizer;

import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.vplex.api.VPlexCacheStatusInfo.InvalidateStatus;
import com.emc.storageos.vplex.api.VPlexVirtualVolumeInfo.WaitOnRebuildResult;
import com.emc.storageos.vplex.api.clientdata.VolumeInfo;
import com.sun.jersey.api.client.ClientResponse;

/**
 * VPlexApiVirtualVolumeManager provides methods creating and destroying virtual
 * volumes.
 */
public class VPlexApiVirtualVolumeManager {

    // Logger reference.
    private static Logger s_logger = LoggerFactory.getLogger(VPlexApiVirtualVolumeManager.class);

    // A reference to the API client.
    private final VPlexApiClient _vplexApiClient;

    /**
     * Package protected constructor.
     *
     * @param client A reference to the API client.
     */
    VPlexApiVirtualVolumeManager(VPlexApiClient client) {
        _vplexApiClient = client;
    }

    /**
     * Creates a VPlex virtual volume using the passed native volume
     * information. The passed native volume information should identify a
     * single volume on a backend storage array connected to a VPlex cluster
     * when a simple, non-distributed virtual volume is desired. When a
     * distributed virtual volume is desired the native volume info should
     * identify two volumes. One volume should reside on a backend storage array
     * connected to one VPlex cluster in a VPlex metro configuration. The other
     * volumes should reside on a backend storage array connected to the other
     * VPlex cluster in a VPlex Metro configuration.
     *
     * NOTE: Currently, backend volumes newly exported to the VPlex must be
     * discovered prior to creating a virtual volume using them by invoking the
     * rediscoverStorageSystems API.
     *
     * @param nativeVolumeInfoList The native volume information.
     * @param isDistributed true for a distributed virtual volume, false
     *            otherwise.
     * @param discoveryRequired true if the passed native volumes are newly
     *            exported and need to be discovered by the VPlex.
     * @param preserveData true if the native volume data should be preserved
     *            during virtual volume creation.
     * @param winningClusterId Used to set detach rules for distributed volumes.
     * @param clusterInfoList A list of VPlexClusterInfo specifying the info for the VPlex
     *            clusters.
     * @param findVirtualVolume If true findVirtualVolume method is called after virtual volume is created.
     * @param thinEnabled If true, the virtual volume should be created as a thin-enabled virtual volume.
     * @param clusterName The clusterName the volume is on. if non-null, backend volume 
     *                    search will be restricted to the named cluster.
     *
     * @return The information for the created virtual volume.
     *
     * @throws VPlexApiException When an error occurs creating the virtual
     *             volume.
     */
    VPlexVirtualVolumeInfo createVirtualVolume(List<VolumeInfo> nativeVolumeInfoList,
            boolean isDistributed, boolean discoveryRequired, boolean preserveData,
            String winningClusterId, List<VPlexClusterInfo> clusterInfoList, 
            boolean findVirtualVolume, boolean thinEnabled, String clusterName)
            throws VPlexApiException {

        s_logger.info("Request to create {} virtual volume.",
                (isDistributed ? "distributed" : "local"));

        if ((isDistributed) && (nativeVolumeInfoList.size() != 2)) {
            throw VPlexApiException.exceptions.twoDevicesRequiredForDistVolume();
        } else if ((!isDistributed) && (nativeVolumeInfoList.size() != 1)) {
            throw VPlexApiException.exceptions.oneDevicesRequiredForLocalVolume();
        }
        
        if (null == clusterInfoList) {
            clusterInfoList = new ArrayList<VPlexClusterInfo>();
        }

        // Find the storage volumes corresponding to the passed native
        // volume information, discover them if required.
        Map<VolumeInfo, VPlexStorageVolumeInfo> storageVolumeInfoMap = findStorageVolumes(nativeVolumeInfoList,
                discoveryRequired, clusterInfoList, clusterName);

        // For a distributed virtual volume, verify logging volumes
        // have been configured on each cluster.
        if (isDistributed) {
            for (VPlexClusterInfo clusterInfo : clusterInfoList) {
                if (!clusterInfo.hasLoggingVolume()) {
                    throw VPlexApiException.exceptions.clusterHasNoLoggingVolumes(clusterInfo.getName());
                }
            }
            s_logger.info("Verified logging volumes");
        }

        // Claim the storage volumes
        claimStorageVolumes(storageVolumeInfoMap, preserveData);
        s_logger.info("Claimed storage volumes");

        // Try and build up the VPLEX artifacts from the claimed storage
        // volumes and create the new virtual volume. If we get an error,
        // clean up the VPLEX artifacts and unclaim the storage volumes.
        try {
            // Create extents
            List<VPlexStorageVolumeInfo> storageVolumeInfoList = new ArrayList<VPlexStorageVolumeInfo>();
            for (VolumeInfo nativeVolumeInfo : nativeVolumeInfoList) {
                storageVolumeInfoList.add(storageVolumeInfoMap.get(nativeVolumeInfo));
            }
            createExtents(storageVolumeInfoList);
            s_logger.info("Created extents on storage volumes");

            // Find the extents just created and create local devices on
            // those extents.
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
            List<VPlexExtentInfo> extentInfoList = discoveryMgr.findExtents(storageVolumeInfoList);
            createLocalDevices(extentInfoList);
            s_logger.info("Created local devices on extents");

            // Find the local devices just created. If the virtual volume is
            // to be distributed, first create a distributed device from the
            // local devices, then create a virtual volumes from the distributed
            // device. Otherwise, create a virtual volume from the local device.
            String clusterId;
            String deviceName;
            String devicePath;
            List<VPlexDeviceInfo> localDevices = discoveryMgr.findLocalDevices(extentInfoList);
            if (isDistributed) {
                // Create and find the distributed device using the local devices.
                String distributedDeviceName = createDistributedDevice(localDevices, winningClusterId);
                s_logger.info("Created distributed device on local devices");
                VPlexDistributedDeviceInfo distDeviceInfo = discoveryMgr
                        .findDistributedDevice(distributedDeviceName, true);
                if (distDeviceInfo == null) {
                    s_logger.error("Distributed device {} was successfully created but not returned by the VPLEX system", distributedDeviceName); 
                    throw VPlexApiException.exceptions.failedGettingDistributedDevice(distributedDeviceName);
                }
                distDeviceInfo.setLocalDeviceInfo(localDevices);
                clusterId = distDeviceInfo.getClusterId();
                deviceName = distDeviceInfo.getName();
                devicePath = distDeviceInfo.getPath();
            } else {
                // Should only be a single local device.
                VPlexDeviceInfo deviceInfo = localDevices.get(0);
                clusterId = deviceInfo.getCluster();
                deviceName = deviceInfo.getName();
                devicePath = deviceInfo.getPath();
            }

            // Create virtual volume
            createVirtualVolume(devicePath, thinEnabled);
            s_logger.info("Created virtual volume on device {}", devicePath);

            VPlexVirtualVolumeInfo virtualVolumeInfo = new VPlexVirtualVolumeInfo();
            StringBuilder volumeNameBuilder = new StringBuilder();
            volumeNameBuilder.append(deviceName);
            volumeNameBuilder.append(VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX);
            if (findVirtualVolume) {
                // For bulk volume creation we shouldn't use findVirtualVolume as true, rather findVirtualVolumes should be called
                // separately after createVirtualVolumes.
                virtualVolumeInfo = discoveryMgr.findVirtualVolume(clusterId, volumeNameBuilder.toString(), true, true);
            } else {
                virtualVolumeInfo.setName(volumeNameBuilder.toString());
                virtualVolumeInfo.addCluster(clusterId);
            }

            return virtualVolumeInfo;
        } catch (Exception e) {
            // An error occurred. Clean up any VPLEX artifacts created for
            // virtual volume and unclaim the storage volumes.
            s_logger.info("Exception occurred creating virtual volume, attempting to cleanup VPLEX artifacts");
            try {
                // This will look for any artifacts, starting with a virtual
                // volume, that use the passed native volume info and destroy
                // them and then unclaim the volume.
                deleteVirtualVolume(nativeVolumeInfoList);
            } catch (Exception ex) {
                s_logger.error("Failed attempting to cleanup VPLEX after failed attempt " +
                        "to create a new virtual volume", ex);
            }
            throw e;
        }
    }

    /**
     * Creates and attaches mirror device to the source device.
     *
     * @param virtualVolume The virtual volume information to which mirror will be attached
     * @param nativeVolumeInfoList The native volume information.
     * @param discoveryRequired true if the passed native volumes are newly
     *            exported and need to be discovered by the VPlex.
     * @param preserveData true if the native volume data should be preserved
     *            during mirror device creation.
     *
     * @return The VPlex device info that is attached as a mirror.
     *
     * @throws VPlexApiException When an error occurs creating and attaching device as a mirror
     */
    VPlexDeviceInfo createDeviceAndAttachAsMirror(VPlexVirtualVolumeInfo virtualVolume,
            List<VolumeInfo> nativeVolumeInfoList, boolean discoveryRequired,
            boolean preserveData) throws VPlexApiException {

        if (nativeVolumeInfoList.size() != 1) {
            throw VPlexApiException.exceptions.oneDeviceRequiredForMirror();
        }

        // Find the storage volumes corresponding to the passed native
        // volume information, discovery them if required.
        List<VPlexClusterInfo> clusterInfoList = new ArrayList<VPlexClusterInfo>();
        Map<VolumeInfo, VPlexStorageVolumeInfo> storageVolumeInfoMap = findStorageVolumes(
                nativeVolumeInfoList, discoveryRequired, clusterInfoList, null);

        // Claim the storage volumes
        claimStorageVolumes(storageVolumeInfoMap, preserveData);
        s_logger.info("Claimed storage volumes");

        // Try and build up the VPLEX local device from the claimed storage
        // volume and attach the local device to the local device of the
        // passed virtual volume to form the local mirror. If we get an error,
        // clean up the VPLEX artifacts and unclaim the storage volume.
        try {
            // Create extents
            List<VPlexStorageVolumeInfo> storageVolumeInfoList = new ArrayList<VPlexStorageVolumeInfo>();
            for (VolumeInfo nativeVolumeInfo : nativeVolumeInfoList) {
                storageVolumeInfoList.add(storageVolumeInfoMap.get(nativeVolumeInfo));
            }
            createExtents(storageVolumeInfoList);
            s_logger.info("Created extents on storage volumes");

            // Find the extents just created and create local devices on
            // those extents.
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
            List<VPlexExtentInfo> extentInfoList = discoveryMgr.findExtents(storageVolumeInfoList);
            createLocalDevices(extentInfoList);
            s_logger.info("Created local devices on extents");

            // Find the local devices just created.There will be only one local device
            List<VPlexDeviceInfo> localDevices = discoveryMgr.findLocalDevices(extentInfoList);

            VPlexVirtualVolumeInfo vplexVolumeInfo = findVirtualVolumeAndUpdateInfo(virtualVolume.getName(), discoveryMgr);

            String sourceDeviceName = vplexVolumeInfo.getSupportingDevice();

            if (virtualVolume.getLocality().equals(VPlexApiConstants.LOCAL_VIRTUAL_VOLUME)) {
                // Find the source local device.
                VPlexDeviceInfo sourceLocalDevice = discoveryMgr.findLocalDevice(sourceDeviceName);
                if (sourceLocalDevice == null) {
                    throw VPlexApiException.exceptions.cantFindLocalDevice(sourceDeviceName);
                }
                s_logger.info("Found the local device {}", sourceLocalDevice.getPath());

                // Attach mirror device to the source volume device
                deviceAttachMirror(sourceLocalDevice.getPath(), localDevices.get(0).getPath(), null);

                s_logger.info("Added {} as a mirror to the source device {}", localDevices
                        .get(0).getPath(), sourceLocalDevice.getPath());
            } else {
                // Find the distributed device
                VPlexDistributedDeviceInfo distributedDeviceInfo = discoveryMgr.findDistributedDevice(sourceDeviceName);
                if (distributedDeviceInfo == null) {
                    throw VPlexApiException.exceptions.cantFindDistDevice(sourceDeviceName);
                }

                String sourceDevicePath = null;
                List<VPlexDistributedDeviceComponentInfo> ddComponents = discoveryMgr
                        .getDistributedDeviceComponents(distributedDeviceInfo);
                for (VPlexDistributedDeviceComponentInfo ddComponent : ddComponents) {
                    discoveryMgr.updateDistributedDeviceComponent(ddComponent);
                    if (ddComponent.getCluster().equals(localDevices.get(0).getCluster())) {
                        sourceDevicePath = ddComponent.getPath();
                        break;
                    }
                }
                if (sourceDevicePath == null) {
                    throw VPlexApiException.exceptions.couldNotFindComponentForDistDevice(
                            distributedDeviceInfo.getName(), localDevices.get(0)
                                    .getCluster());
                }

                // Attach mirror device to one of the device in the distributed device where
                // mirror device and the source device are in the same cluster
                deviceAttachMirror(sourceDevicePath, localDevices.get(0).getPath(), null);

                s_logger.info("Added {} as a mirror to the device {}", localDevices
                        .get(0).getPath(), sourceDevicePath);
            }

            // update the vplexVolumeInfo object so we can tell if thin-capability changed
            vplexVolumeInfo = findVirtualVolumeAndUpdateInfo(virtualVolume.getName(), discoveryMgr);
            virtualVolume.setThinCapable(vplexVolumeInfo.getThinCapable());
            virtualVolume.setThinEnabled(vplexVolumeInfo.getThinEnabled());

            // return mirror device
            return localDevices.get(0);
        } catch (Exception e) {
            s_logger.error("Exception occurred creating mirror device");
            throw e;
        }
    }

    /**
     * Attaches mirror device to the source device
     *
     * @param locality The constant that tells if its local or distributed volume
     * @param sourceVirtualVolumeName Name of the virtual volume
     * @param mirrorDeviceName Name of the mirror device
     *
     * @throws VPlexApiException When an error occurs attaching mirror device to the
     *             source volume device
     */
    public void attachMirror(String locality, String sourceVirtualVolumeName, String mirrorDeviceName)
            throws VPlexApiException {
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        VPlexVirtualVolumeInfo vplexVirtualVolumeInfo = findVirtualVolumeAndUpdateInfo(sourceVirtualVolumeName, discoveryMgr);
        String sourceDeviceName = vplexVirtualVolumeInfo.getSupportingDevice();

        // Find mirror device
        VPlexDeviceInfo mirrorLocalDevice = discoveryMgr.findLocalDevice(mirrorDeviceName);
        if (mirrorLocalDevice == null) {
            throw VPlexApiException.exceptions.cantFindLocalDevice(mirrorDeviceName);
        }

        String sourceDevicePath = null;

        if (locality.equals(VPlexApiConstants.LOCAL_VIRTUAL_VOLUME)) {
            // Find local source device
            VPlexDeviceInfo sourceLocalDevice = discoveryMgr.findLocalDevice(sourceDeviceName);
            if (sourceLocalDevice == null) {
                throw VPlexApiException.exceptions.cantFindLocalDevice(sourceDeviceName);
            }
            sourceDevicePath = sourceLocalDevice.getPath();
        } else {
            // Find the distributed device
            VPlexDistributedDeviceInfo distributedDeviceInfo = discoveryMgr.findDistributedDevice(sourceDeviceName);
            if (distributedDeviceInfo == null) {
                throw VPlexApiException.exceptions.cantFindDistDevice(sourceDeviceName);
            }

            List<VPlexDistributedDeviceComponentInfo> ddComponents = discoveryMgr
                    .getDistributedDeviceComponents(distributedDeviceInfo);
            for (VPlexDistributedDeviceComponentInfo ddComponent : ddComponents) {
                discoveryMgr.updateDistributedDeviceComponent(ddComponent);
                if (ddComponent.getCluster().equals(mirrorLocalDevice.getCluster())) {
                    sourceDevicePath = ddComponent.getPath();
                    break;
                }
            }
            if (sourceDevicePath == null) {
                throw VPlexApiException.exceptions.couldNotFindComponentForDistDevice(
                        distributedDeviceInfo.getName(), mirrorLocalDevice.getCluster());
            }
        }

        // Attach mirror device to the source volume device
        deviceAttachMirror(sourceDevicePath, mirrorLocalDevice.getPath(), null);

        s_logger.info("Added ", mirrorLocalDevice.getPath() + " as a mirror to the source device " + sourceDevicePath);
    }

    /**
     * Deletes the virtual volume by destroying all the components (i.e,
     * extents, devices) that were created in the process of creating the
     * virtual and unclaiming the storage volume(s).
     *
     * @param nativeVolumeInfoList The same native volume info that was passed
     *            when the virtual volume was created.
     *
     * @throws VPlexApiException When an error occurs deleted the virtual
     *             volume.
     */
    void deleteVirtualVolume(List<VolumeInfo> nativeVolumeInfoList)
            throws VPlexApiException {

        s_logger.info("Deleting virtual volume using native volume info");

        // Get the name(s) of the volume(s) that were used to create
        // the virtual volume.
        List<String> nativeVolumeNames = new ArrayList<String>();
        for (VolumeInfo nativeVolumeInfo : nativeVolumeInfoList) {
            nativeVolumeNames.add(nativeVolumeInfo.getVolumeName());
        }

        // Build the virtual volume name from the names of the
        // passed volumes and delete it.
        deleteVirtualVolume(buildVirtualVolumeName(nativeVolumeNames), true, false);
    }

    /**
     * Deletes the virtual volume by destroying all the components (i.e,
     * extents, devices) that were created in the process of creating the
     * virtual and unclaiming the storage volume(s).
     *
     * @param virtualVolumeName The name of the virtual volume to be deleted.
     * @param unclaimVolumes true if the storage volumes should be unclaimed.
     * @param retryOnDismantleFailure When true, retries the delete if the
     *            dismantle of the virtual volume fails, which can occur if the
     *            volume was distributed and had previously been migrated. This is
     *            due to a bug in the VPLEX management software (zeph-q24801)
     *
     * @throws VPlexApiException When an error occurs deleted the virtual
     *             volume.
     */
    void deleteVirtualVolume(String virtualVolumeName,
            boolean unclaimVolumes, boolean retryOnDismantleFailure) throws VPlexApiException {

        s_logger.info("Deleting virtual volume {}", virtualVolumeName);

        // Find the virtual volume. If it exists, we should be able to find it
        // on either cluster.
        VPlexVirtualVolumeInfo virtualVolumeInfo = null;
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        List<VPlexClusterInfo> clusterInfoList = discoveryMgr.getClusterInfoLite();
        for (VPlexClusterInfo clusterInfo : clusterInfoList) {
            virtualVolumeInfo = discoveryMgr.findVirtualVolume(clusterInfo.getName(),
                    virtualVolumeName, false);
            if (virtualVolumeInfo != null) {
                break;
            }
        }

        // Tear down the virtual volume if it is found. This will tear down
        // the entire virtual volume stack and unclaim the storage volumes.
        if (virtualVolumeInfo != null) {
            s_logger.info("Tearing down virtual volume {}", virtualVolumeName);
            try {
                dismantleResource(virtualVolumeInfo.getPath(), unclaimVolumes, false);
            } catch (VPlexApiException vae) {
                if (retryOnDismantleFailure) {
                    s_logger.info("Tear down of virtual volume {} failed: {}",
                            virtualVolumeName, vae.getMessage());
                    // If the issue is the bug referenced in the javadoc, the
                    // virtual volume will actually be gone, so in the next
                    // call the volume will not be found, and the else path
                    // will be taken. Note that the distributed device will
                    // also be deleted and so will not be found, so what ends
                    // up happening is the local devices are dismantled. This
                    // should work successfully and in the end, the virtual
                    // volume will be successfully cleanup, just as if the
                    // original called succeeded.
                    deleteVirtualVolume(virtualVolumeName, unclaimVolumes, false);
                } else {
                    throw vae;
                }
            }
        } else if (virtualVolumeName.endsWith(VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX)) {
            // Otherwise, perhaps the virtual volume creation failed and
            // the delete is being called to cleanup any intermediate
            // resources in the virtual volume stack.
            // Build the distributed device name.
            String deviceName = virtualVolumeName.substring(0,
                    virtualVolumeName.indexOf(VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX));
            if (deviceName.startsWith(VPlexApiConstants.DIST_DEVICE_PREFIX)) {
                s_logger.info("Tearing down distributed device for virtual volume {}",
                        virtualVolumeName);
                deleteDistributedDevice(deviceName);
            } else {
                s_logger.info("Tearing down local device for virtual volume {}",
                        virtualVolumeName);
                deleteLocalDevice(deviceName);
            }
        }
    }

    /**
     * Deletes the virtual volume and leaves the underlying structure intact.
     *
     * @param virtualVolumeName The name of the virtual volume to be destroyed.
     *
     * @throws VPlexApiException When an error occurs destroying the virtual
     *             volume.
     */
    void destroyVirtualVolume(String virtualVolumeName) throws VPlexApiException {
        s_logger.info("Destroying virtual volume {}", virtualVolumeName);

        // Find the virtual volume. If it exists, we should be able to find it
        // on either cluster.
        VPlexVirtualVolumeInfo virtualVolumeInfo = null;
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        List<VPlexClusterInfo> clusterInfoList = discoveryMgr.getClusterInfoLite();
        for (VPlexClusterInfo clusterInfo : clusterInfoList) {
            virtualVolumeInfo = discoveryMgr.findVirtualVolume(clusterInfo.getName(),
                    virtualVolumeName, false);
            if (virtualVolumeInfo != null) {
                break;
            }
        }

        if (virtualVolumeInfo != null) {
            ClientResponse response = null;
            try {
                URI requestURI = _vplexApiClient.getBaseURI().resolve(
                        VPlexApiConstants.URI_DESTROY_VIRTUAL_VOLUME);
                s_logger.info("Destroy virtual volume URI is {}", requestURI.toString());
                Map<String, String> argsMap = new HashMap<String, String>();
                argsMap.put(VPlexApiConstants.ARG_DASH_V, virtualVolumeInfo.getPath());

                JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
                s_logger.info("Destroy POST data is {}", postDataObject.toString());
                response = _vplexApiClient.post(requestURI,
                        postDataObject.toString());
                String responseStr = response.getEntity(String.class);
                s_logger.info("Destroy virtual volume response is {}", responseStr);
                if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                    if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                        s_logger.info("Destroying of resource is completing asynchronously");
                        _vplexApiClient.waitForCompletion(response);
                    } else {
                        String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                        throw VPlexApiException.exceptions.deleteVolumeFailureStatus(
                                virtualVolumeName, String.valueOf(response.getStatus()), cause);
                    }
                }
                s_logger.info("Resource {} successfully destroyed.", virtualVolumeInfo.getPath());
            } catch (VPlexApiException vae) {
                throw vae;
            } catch (Exception e) {
                throw VPlexApiException.exceptions.failedDeleteVolume(virtualVolumeName, e);
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
    }

    /**
     * Expands the virtual volume with the passed name to its full expandable
     * capacity. This API would be invoked after natively expanding the backend
     * volume(s) of the virtual volume to provide additional capacity or say
     * migrating the backend volume(s) to volume(s) with a larger capacity.
     *
     * @param virtualVolumeName The name of the virtual volume.
     * @param expansionStatusRetryCount Retry count to check virtual volume's expansion status.
     * @param expansionStatusSleepTime Sleep time in between expansion status check retries.
     *
     * @throws VPlexApiException When an exception occurs expanding the volume.
     */
    VPlexVirtualVolumeInfo expandVirtualVolume(String virtualVolumeName,
            int expansionStatusRetryCount, long expansionStatusSleepTime)
            throws VPlexApiException {
        s_logger.info("Expanding virtual volume {}", virtualVolumeName);

        // Find the virtual volume. If it exists, we should be able to find it
        // on either cluster.
        String clusterName = null;
        VPlexVirtualVolumeInfo virtualVolumeInfo = null;
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        List<VPlexClusterInfo> clusterInfoList = discoveryMgr.getClusterInfoLite();
        for (VPlexClusterInfo clusterInfo : clusterInfoList) {
            virtualVolumeInfo = discoveryMgr.findVirtualVolume(clusterInfo.getName(),
                    virtualVolumeName, false);
            if (virtualVolumeInfo != null) {
                clusterName = clusterInfo.getName();
                break;
            }
        }

        if (virtualVolumeInfo == null) {
            throw VPlexApiException.exceptions.cantFindRequestedVolume(virtualVolumeName);
        }
        s_logger.info("Found virtual volume");

        ClientResponse response = null;
        try {
            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    VPlexApiConstants.URI_EXPAND_VIRTUAL_VOLUME);
            s_logger.info("Expand virtual volume URI is {}", requestURI.toString());
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_V, virtualVolumeInfo.getPath());
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Expand virtual volume POST data is {}",
                    postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Expand virtual volume response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Virtual volume expansion completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.expandVolumeFailureStatus(
                            virtualVolumeName, String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully expanded virtual volume");
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedExpandVolume(virtualVolumeName, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }

        // Update the virtual volume info with the new capacity.
        updateVirtualVolumeInfoAfterExpansion(clusterName, virtualVolumeInfo,
                expansionStatusRetryCount, expansionStatusSleepTime);
        s_logger.info("Updated virtual volume info");

        return virtualVolumeInfo;
    }

    /**
     * Updates the virtual volume information after an expansion is executed for
     * the purpose of updating the block count so that the volume specifies the
     * newly expanded capacity. Waits for a specified period of time for the
     * expansion to complete, which should happen quickly.
     *
     * @param clusterName The cluster for the virtual volume.
     * @param virtualVolumeInfo A reference to the virtual volume info.
     * @param expansionStatusRetryCount Retry count to check virtual volume's expansion status.
     * @param expansionStatusSleepTime Sleep time in between expansion status check retries.
     *
     * @throws VPlexApiException When an exception occurs attempting to update
     *             the virtual volume info.
     */
    private void updateVirtualVolumeInfoAfterExpansion(String clusterName,
            VPlexVirtualVolumeInfo virtualVolumeInfo, int expansionStatusRetryCount,
            long expansionStatusSleepTime) throws VPlexApiException {

        int retryCount = 0;
        String expansionStatus = null;
        String expandableCapacity = null;
        boolean expansionCompleted = false;
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        while (++retryCount <= expansionStatusRetryCount) {
            try {
                // Pause before obtaining the volume info.
                VPlexApiUtils.pauseThread(expansionStatusSleepTime);
                discoveryMgr.updateVirtualVolumeInfo(clusterName, virtualVolumeInfo);
                
                // Get the expansion status and the expandable capacity. We need
                // to check both. We want the updated virtual volume info to specify
                // the new block count after the expansion has completed. In this 
                // way we properly reflect the new provisioned capacity in ViPR.
                // However due to VPLEX issue (zeph-q40729), at the time the expansion
                // status becomes "null" indicating the expansion has completed, it
                // may be the case that the block count and expandable capacity
                // do not reflect the updated values resulting form the expansion.
                // Therefore, we check both that the expansion has completed and 
                // also that the expandable capacity is 0. At this point the block
                // count should also be updated, and the proper capacity can be
                // set in ViPR.
                expansionStatus = virtualVolumeInfo.getExpansionStatus();
                s_logger.info("Expansion status is {}", expansionStatus);
                expandableCapacity = virtualVolumeInfo.getExpandableCapacity();
                s_logger.info("Expandable capacity is {}", expandableCapacity);
                
                // Now check if the expansion has completed by checking the expansion
                // status and expandable capacity.
                if (((expansionStatus == null) || (VPlexApiConstants.NULL_ATT_VAL.equals(expansionStatus))) &&
                        (VPlexApiConstants.NO_EXPANDABLE_CAPACITY.equals(expandableCapacity))) {
                    // The expansion has completed.
                    expansionCompleted = true;
                    break;
                } else if (!VPlexVirtualVolumeInfo.ExpansionStatus.FAILED.equals(expansionStatus)) {
                    // The expansion status is null indicating the expansion has completed but
                    // the expandable capacity has yet to be updated, or the expansion status
                    // is in-progress or unknown. In this case we just continue and retry.
                    continue;
                } else {
                    // The expansion status indicates the expansion has failed.
                    break;
                }               
            } catch (Exception e) {
                s_logger.error("An error occurred updating the virtual volume info: {}", e.getMessage());
                if (retryCount < expansionStatusRetryCount) {
                    s_logger.info("Trying again to get virtual volume info");
                } else {
                    throw VPlexApiException.exceptions.exceptionGettingVolumeExpansionStatus(virtualVolumeInfo.getName(), e);
                }
            }
        }
        
        // If the VPLEX volume expansion is not completed, throw an error indicating why.
        if (!expansionCompleted) {
            s_logger.info(String.format("After %s retries with wait of %s ms between each retry volume %s "
                    + "expansion status has not completed", String.valueOf(expansionStatusRetryCount),
                    String.valueOf(expansionStatusSleepTime), virtualVolumeInfo.getName()));
            if (VPlexVirtualVolumeInfo.ExpansionStatus.FAILED.equals(expansionStatus)) {
                throw VPlexApiException.exceptions.vplexVolumeExpansionFailed(virtualVolumeInfo.getName());
            } else if (VPlexVirtualVolumeInfo.ExpansionStatus.INPROGRESS.equals(expansionStatus)) {
                throw VPlexApiException.exceptions.vplexVolumeExpansionIsStillInProgress(virtualVolumeInfo.getName());
            } else if (VPlexVirtualVolumeInfo.ExpansionStatus.UNKNOWN.equals(expansionStatus)) {
                throw VPlexApiException.exceptions.vplexVolumeExpansionIsInUnknownState(virtualVolumeInfo.getName());
            } else {
                throw VPlexApiException.exceptions.vplexVolumeExpansionBlockCountNotUpdated(virtualVolumeInfo.getName());
            }
        }
    }

    /**
     * Find the storage volumes identified by the passed native volume info,
     * discovering the storage volumes if required.
     *
     * @param nativeVolumeInfoList The native volume information.
     * @param discoveryRequired true if the passed native volumes are newly
     *            exported and need to be discovered by the VPlex.
     * @param clusterInfoList [OUT] param set to the cluster information.
     * @param clusterName if non-null, search will be restricted to the named cluster
     *
     * @throws VPlexApiException When an error occurs finding the storage
     *             volumes or the storage volumes are not all found.
     */
    Map<VolumeInfo, VPlexStorageVolumeInfo> findStorageVolumes(List<VolumeInfo> nativeVolumeInfoList, boolean discoveryRequired,
            List<VPlexClusterInfo> clusterInfoList, String clusterName) throws VPlexApiException {

        // If the volume(s) passed are newly exported to the VPlex, they may
        // need to be discovered before they can be used. If the discovery
        // required flag is true, we execute a discovery step so that the
        // volumes are available to the VPlex. Note that we will try for a while
        // to give the newly exported volumes some time to be accessible by
        // the VPlex.
        Map<VolumeInfo, VPlexStorageVolumeInfo> storageVolumeInfoMap = null;
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();

        if (discoveryRequired) {
            s_logger.info("Storage volume discovery is required.");
            int retryCount = 0;
            while (++retryCount <= VPlexApiConstants.FIND_STORAGE_VOLUME_RETRY_COUNT) {
                try {
                    // Execute the re-discover command.
                    s_logger.info("Executing storage volume discovery try {} of {}",
                            retryCount, VPlexApiConstants.FIND_STORAGE_VOLUME_RETRY_COUNT);
                    List<String> storageSystemGuids = new ArrayList<String>();
                    for (VolumeInfo nativeVolumeInfo : nativeVolumeInfoList) {
                        String storageSystemGuid = nativeVolumeInfo.getStorageSystemNativeGuid();
                        if (!storageSystemGuids.contains(storageSystemGuid)) {

                            s_logger.info("Discover storage volumes on array {}", storageSystemGuid);
                            storageSystemGuids.add(storageSystemGuid);
                        }
                    }

                    discoveryMgr.rediscoverStorageSystems(storageSystemGuids);
                    s_logger.info("Discovery completed");

                    // Get the cluster information.
                    clusterInfoList.addAll(discoveryMgr.getClusterInfo(false, true, clusterName));
                    s_logger.info("Retrieved storage volume info for VPlex clusters");

                    // Find the back-end storage volumes. If a volume cannot be
                    // found, an exception is thrown.
                    storageVolumeInfoMap = discoveryMgr.findStorageVolumes(nativeVolumeInfoList, clusterInfoList);
                    s_logger.info("Found storage volumes to use for virtual volume");

                    // Exit, no exceptions means all volumes found.
                    break;
                } catch (VPlexApiException vae) {
                    // If we reached the maximum retry count, then rethrow
                    // the exception.
                    if (retryCount == VPlexApiConstants.FIND_STORAGE_VOLUME_RETRY_COUNT) {
                        throw vae;
                    }

                    // Otherwise, if an exception occurs, it is likely because a
                    // storage volume was not found. Wait for a bit and execute
                    // the the discovery again.
                    clusterInfoList.clear();
                    VPlexApiUtils.pauseThread(VPlexApiConstants.FIND_STORAGE_VOLUME_SLEEP_TIME_MS);
                }
            }
        } else {
            s_logger.info("Storage volume discovery is not required.");

            // Get the cluster information.
            if (clusterInfoList.isEmpty()) {
                clusterInfoList.addAll(discoveryMgr.getClusterInfo(false, true, clusterName));
                s_logger.info("Retrieved storage volume info for VPlex clusters");
            }

            // Find the backend storage volumes. If a volume cannot be
            // found, then an exception will be thrown.
            storageVolumeInfoMap = discoveryMgr.findStorageVolumes(nativeVolumeInfoList, clusterInfoList);
            s_logger.info("Found storage volumes");
        }

        return storageVolumeInfoMap;
    }

    /**
     * Claims the VPlex volumes in the passed map.
     *
     * @param storageVolumeInfoMap The VPlex volumes to claim.
     * @param preserveData true if the native volume data should be preserved
     *            during virtual volume creation.
     *
     * @throws VPlexApiException When an error occurs claiming the volumes.
     */
    void claimStorageVolumes(
            Map<VolumeInfo, VPlexStorageVolumeInfo> storageVolumeInfoMap, boolean preserveData)
            throws VPlexApiException {
        URI requestURI = _vplexApiClient.getBaseURI().resolve(VPlexApiConstants.URI_CLAIM_VOLUME);
        s_logger.info("Claim storage volumes URI is {}", requestURI.toString());
        Iterator<Entry<VolumeInfo, VPlexStorageVolumeInfo>> volumeIter = storageVolumeInfoMap.entrySet().iterator();
        List<String> storageVolumeContextPaths = new ArrayList<String>();
        while (volumeIter.hasNext()) {
            ClientResponse response = null;
            Entry<VolumeInfo, VPlexStorageVolumeInfo> entry = volumeIter.next();
            VolumeInfo volumeInfo = entry.getKey();
            String volumeName = volumeInfo.getVolumeName();
            s_logger.info("Claiming volume {}", volumeInfo.getVolumeWWN());
            try {
                VPlexStorageVolumeInfo storageVolumeInfo = entry.getValue();
                Map<String, String> argsMap = new HashMap<String, String>();
                argsMap.put(VPlexApiConstants.ARG_DASH_D, storageVolumeInfo.getPath());
                argsMap.put(VPlexApiConstants.ARG_DASH_N, volumeName);
                if (preserveData) {
                    argsMap.put(VPlexApiConstants.ARG_APPC, "");
                }
                if (volumeInfo.getIsThinProvisioned()) {
                    argsMap.put(VPlexApiConstants.ARG_THIN_REBUILD, "");
                }
                JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
                s_logger.info("Claim storage volumes POST data is {}",
                        postDataObject.toString());
                response = _vplexApiClient.post(requestURI,
                        postDataObject.toString());
                String responseStr = response.getEntity(String.class);
                s_logger.info("Claim storage volume response is {}", responseStr);
                if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                    if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                        s_logger.info("Claiming storage volume is completing asynchronously");
                        _vplexApiClient.waitForCompletion(response);
                    } else {
                        String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                        throw VPlexApiException.exceptions.claimVolumeFailureStatus(
                                volumeInfo.getVolumeWWN(),
                                String.valueOf(response.getStatus()), cause);
                    }
                }

                // If successfully claimed, update the VPlex storage volume
                // info with the name assigned when the volume was claimed.
                storageVolumeInfo.setName(volumeName);

                // Also, update the context path.
                String currentPath = storageVolumeInfo.getPath();
                int endIndex = currentPath.lastIndexOf("/");
                String newPath = currentPath.substring(0, endIndex + 1) + volumeName;
                storageVolumeInfo.setPath(newPath);
                s_logger.info("Successfully claimed storage volume {}",
                        volumeInfo.getVolumeWWN());

                // Update storage volumes contexts to be refreshed.
                String contextPath = currentPath.substring(0, endIndex);
                if (!storageVolumeContextPaths.contains(contextPath)) {
                    storageVolumeContextPaths.add(contextPath);
                }
            } catch (VPlexApiException vae) {
                throw vae;
            } catch (Exception e) {
                throw VPlexApiException.exceptions.failedClaimVolume(
                        volumeInfo.getVolumeWWN(), e);
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }

        // Workaround for VPLEX issue zeph-q34217 as seen in Jira 9121
        _vplexApiClient.getDiscoveryManager().refreshContexts(storageVolumeContextPaths);
    }

    /**
     * Creates extents for the passed VPlex storage volumes. The extents so
     * created consume the entire volume.
     *
     * @param storageVolumeInfoList The VPlex storage volumes on which to create
     *            extents.
     *
     * @throws VPlexApiException When an error occurs creating the extents,
     */
    void createExtents(List<VPlexStorageVolumeInfo> storageVolumeInfoList)
            throws VPlexApiException {
        ClientResponse response = null;
        try {
            URI requestURI = _vplexApiClient.getBaseURI().resolve(VPlexApiConstants.URI_CREATE_EXTENT);
            s_logger.info("Create extent URI is {}", requestURI.toString());
            StringBuilder volumePathsBuilder = new StringBuilder();
            Iterator<VPlexStorageVolumeInfo> volumeInfoIter = storageVolumeInfoList.iterator();
            while (volumeInfoIter.hasNext()) {
                volumePathsBuilder.append(volumeInfoIter.next().getPath());
                if (volumeInfoIter.hasNext()) {
                    volumePathsBuilder.append(",");
                }
            }
            s_logger.info("Creating extents on storage volumes {}",
                    volumePathsBuilder.toString());
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_D, volumePathsBuilder.toString());
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, false);
            s_logger.info("Create extent POST data is {}", postDataObject.toString());
            response = _vplexApiClient.post(requestURI, postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Create extent response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Extent creation completing asyncronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.createExtentFailureStatus(
                            String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Extent creation successful");
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedCreateExtent(e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Create local devices on the passed extents. The local devices so created
     * consume the entire extent.
     *
     * @param extentInfoList The list of extents on which to create local
     *            devices.
     *
     * @throws VPlexApiException When an error occurs creating the local
     *             devices.
     */
    void createLocalDevices(List<VPlexExtentInfo> extentInfoList)
            throws VPlexApiException {
        URI requestURI = _vplexApiClient.getBaseURI().resolve(
                VPlexApiConstants.URI_CREATE_LOCAL_DEVICE);
        s_logger.info("Create local device URI is {}", requestURI.toString());
        Iterator<VPlexExtentInfo> extentInfoIter = extentInfoList.iterator();
        while (extentInfoIter.hasNext()) {
            ClientResponse response = null;
            try {
                VPlexExtentInfo extentInfo = extentInfoIter.next();
                s_logger.info("Create local device for extent {}", extentInfo.getName());
                VPlexStorageVolumeInfo storageVolumeInfo = extentInfo.getStorageVolumeInfo();
                String storageVolumeName = storageVolumeInfo.getName();
                Map<String, String> argsMap = new HashMap<String, String>();
                argsMap.put(VPlexApiConstants.ARG_DASH_E, extentInfo.getPath());
                argsMap.put(VPlexApiConstants.ARG_DASH_N, VPlexApiConstants.DEVICE_PREFIX + storageVolumeName);
                argsMap.put(VPlexApiConstants.ARG_DASH_G, VPlexApiConstants.ARG_GEOMETRY_RAID0);
                JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
                s_logger.info("Create local device POST data is {}", postDataObject.toString());
                response = _vplexApiClient.post(requestURI, postDataObject.toString());
                String responseStr = response.getEntity(String.class);
                s_logger.info("Create local device response is {}", responseStr);
                if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                    if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                        s_logger.info("Local device creation completing asynchronously");
                        _vplexApiClient.waitForCompletion(response);
                    } else {
                        String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                        throw VPlexApiException.exceptions.createLocalDeviceFailureStatus(
                                String.valueOf(response.getStatus()), cause);
                    }
                }
                s_logger.info("Successfully created local device");
            } catch (VPlexApiException vae) {
                throw vae;
            } catch (Exception e) {
                throw VPlexApiException.exceptions.failedCreateLocalDevice(e);
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
    }

    /**
     * Creates a distributed device from the passed local devices.
     *
     * @param localDeviceInfoList The local devices to be used to construct the
     *            distributed device.
     * @param winningClusterId Used to set detach rules for distributed volumes.
     *
     * @return The name of the distributed device.
     *
     * @throws VPlexApiException When an error occurs creating the distributed
     *             device.
     */
    private String createDistributedDevice(List<VPlexDeviceInfo> localDeviceInfoList,
            String winningClusterId) throws VPlexApiException {
        ClientResponse response = null;
        try {
            URI requestURI = _vplexApiClient.getBaseURI().resolve(VPlexApiConstants.URI_CREATE_DIST_DEVICE);
            s_logger.info("Create distributed device URI is {}", requestURI.toString());
            Map<String, String> argsMap = new HashMap<String, String>();
            StringBuilder pathBuilder = new StringBuilder();
            StringBuilder nameBuilder = new StringBuilder(VPlexApiConstants.DIST_DEVICE_PREFIX);
            for (VPlexDeviceInfo localDeviceInfo : localDeviceInfoList) {
                if (pathBuilder.length() != 0) {
                    pathBuilder.append(",");
                }
                pathBuilder.append(localDeviceInfo.getPath());
                nameBuilder.append(VPlexApiConstants.DIST_DEVICE_NAME_DELIM);
                nameBuilder.append(localDeviceInfo.getName().substring(
                        VPlexApiConstants.DEVICE_PREFIX.length()));
            }

            String rulesetName = VPlexApiConstants.CLUSTER_1_DETACHES;
            if (winningClusterId.equals(VPlexApiConstants.CLUSTER_2_ID)) {
                rulesetName = VPlexApiConstants.CLUSTER_2_DETACHES;
            }
            s_logger.info("Creating distributed device from local devices {}",
                    pathBuilder.toString());
            argsMap.put(VPlexApiConstants.ARG_DASH_N, nameBuilder.toString());
            argsMap.put(VPlexApiConstants.ARG_DASH_D, pathBuilder.toString());
            argsMap.put(VPlexApiConstants.ARG_DASH_R, rulesetName);
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Create distributed device POST data is {}", postDataObject.toString());
            response = _vplexApiClient.post(requestURI, postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Create distributed device response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Distributed device creation completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.createDistDeviceFailureStatus(
                            String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully created distributed device {}",
                    nameBuilder.toString());
            return nameBuilder.toString();
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedCreateDistDevice(e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Creates a virtual volume for the device with the passed context path.
     *
     * @param devicePath The context path of a local or distributed device.
     * @param thinEnabled If true, request VPLEX to create the volume as thin-enabled.
     *
     * @throws VPlexApiException When an error occurs creating the virtual volume.
     */
    private void createVirtualVolume(String devicePath, Boolean thinEnabled)
            throws VPlexApiException {
        ClientResponse response = null;
        try {
            s_logger.info("Create virtual volume for device {}", devicePath);
            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    VPlexApiConstants.URI_CREATE_VIRTUAL_VOLUME);
            s_logger.info("Create virtual volume URI is {}", requestURI.toString());
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_R, devicePath);
            if (thinEnabled) {
                argsMap.put(VPlexApiConstants.ARG_THIN_ENABLED, "");
            }
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, false);
            s_logger.info("Create virtual volume POST data is {}", postDataObject.toString());
            response = _vplexApiClient.post(requestURI, postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Create virtual volume response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Virtual volume creation completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.createVolumeFailureStatus(
                            String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully created virtual volume for device {}", devicePath);
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedCreateVolume(e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Creates the name given a virtual volume when it is created.
     *
     * @param nativeVolumeNames The names of the volume(s) passed when the
     *            virtual volume is created.
     *
     * @return The name of the virtual volume.
     */
    private String buildVirtualVolumeName(List<String> nativeVolumeNames) {
        s_logger.info("Building virtual volume name from native volume info");
        StringBuilder nameBuilder = new StringBuilder();
        if (nativeVolumeNames.size() == 1) {
            // Simple virtual volume.
            nameBuilder.append(VPlexApiConstants.DEVICE_PREFIX);
            nameBuilder.append(nativeVolumeNames.get(0));
            nameBuilder.append(VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX);
        } else {
            // Distributed virtual volume.
            nameBuilder.append(VPlexApiConstants.DIST_DEVICE_PREFIX);
            for (String nativeVolumeName : nativeVolumeNames) {
                nameBuilder.append(VPlexApiConstants.DIST_DEVICE_NAME_DELIM);
                nameBuilder.append(nativeVolumeName);
            }
            nameBuilder.append(VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX);
        }
        s_logger.info("Virtual volume name is {}", nameBuilder.toString());
        return nameBuilder.toString();
    }

    /**
     * Tears down a virtual volume, distributed device, or local device.
     *
     * @param resourcePath The context path of the virtual volume or device.
     * @param unclaimVolumes true if the storage volumes should be unclaimed.
     * @param isDevice True if the resource is a device, false if it's a virtual volume.
     *
     * @throws VPlexApiException When an error occurs tearing down the resource.
     */
    private void dismantleResource(String resourcePath, boolean unclaimVolumes, boolean isDevice)
            throws VPlexApiException {
        ClientResponse response = null;
        try {
            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    VPlexApiConstants.URI_DISMANTLE);
            s_logger.info("Dismantle resource URI is {}", requestURI.toString());
            Map<String, String> argsMap = new HashMap<String, String>();
            if (isDevice) {
                argsMap.put(VPlexApiConstants.ARG_DASH_R, resourcePath);
            } else {
                argsMap.put(VPlexApiConstants.ARG_DASH_V, resourcePath);
            }
            if (unclaimVolumes) {
                argsMap.put(VPlexApiConstants.ARG_UNCLAIM, "");
            }
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Dismantle POST data is {}", postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Delete virtual volume response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Dismantling of resource is completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.dismantleResourceFailureStatus(
                            resourcePath, String.valueOf(response.getStatus()), cause);
                }
            }
            // It has been observed that sometimes the VPlex reports SUCCESS but doesn't actually
            // do the dismantle, reporting a response containing "will not be dismantled because".
            // To avoid subsequent silent/unexplained failures, we test for that message and
            // if found throw an Exception.
            if (responseStr.contains(VPlexApiConstants.DISMANTLE_ERROR_MSG)) {
                s_logger.info("SUCCESS Response string contains DISMANTLE_ERROR_MSG string: " + responseStr);
                throw VPlexApiException.exceptions.dismantleResourceFailureStatus(
                        resourcePath, String.valueOf(response.getStatus()), responseStr);
            }
            s_logger.info("Resource {} successfully dismantled.", resourcePath);
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedDismantleResource(resourcePath, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Attempts to find and delete the passed distributed device. Will attempt
     * to dismantle the distributed device by destroying all components used to
     * build up the device (i.e, extents and local volumes).
     *
     * @param deviceName The name of the distributed device.
     *
     * @throws VPlexApiException When an error occurs deleting the distributed
     *             device.
     */
    private void deleteDistributedDevice(String deviceName)
            throws VPlexApiException {

        s_logger.info("Deleting distributed device {}", deviceName);

        // Find the distributed device.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        VPlexDistributedDeviceInfo distDeviceInfo = discoveryMgr
                .findDistributedDevice(deviceName);

        if (distDeviceInfo != null) {
            // If the distributed device is found, dismantle it.
            s_logger.info("Tearing down distributed device {}", deviceName);
            dismantleResource(distDeviceInfo.getPath(), true, true);
        } else {
            // Otherwise, delete any locals devices that might exist
            // for the distributed device with the passed name.
            s_logger.info("Tearing down local devices for distributed device {}",
                    deviceName);
            StringTokenizer tokenizer = new StringTokenizer(
                    deviceName.substring(VPlexApiConstants.DIST_DEVICE_PREFIX.length()),
                    VPlexApiConstants.DIST_DEVICE_NAME_DELIM);
            while (tokenizer.hasMoreTokens()) {
                deleteLocalDevice(VPlexApiConstants.DEVICE_PREFIX + tokenizer.nextToken());
            }
        }
    }

    /**
     * Attempts to find and delete the local device with the passed name.Will
     * attempt to dismantle the local device by destroying all components used
     * to build up the device (i.e, extents).
     *
     * @param deviceInfo the backend storage volume info for the device.
     *
     * @throws VPlexApiException When an error occurs deleting the local
     *             device.
     */
    void deleteLocalDevice(VolumeInfo deviceInfo) throws VPlexApiException {
        String deviceName = VPlexApiConstants.DEVICE_PREFIX + deviceInfo.getVolumeName();
        deleteLocalDevice(deviceName);
    }

    /**
     * Attempts to find and delete the local device with the passed name. Will
     * attempt to dismantle the local device by destroying all components used
     * to build up the device (i.e, extents).
     *
     * @param deviceName The name of the local device.
     *
     * @throws VPlexApiException When an error occurs deleting the distributed
     *             device.
     */
    void deleteLocalDevice(String deviceName) throws VPlexApiException {

        s_logger.info("Deleting local device {}", deviceName);

        // Find the local device.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        VPlexDeviceInfo deviceInfo = discoveryMgr.findLocalDevice(deviceName);

        if (deviceInfo != null) {
            // If the local device is found, dismantle it.
            s_logger.info("Tearing down local device {}", deviceName);
            dismantleResource(deviceInfo.getPath(), true, true);
        } else if (deviceName.startsWith(VPlexApiConstants.DEVICE_PREFIX)) {
            // Otherwise delete the extent that may exist for
            // this local device.
            s_logger.info("Destroying extents for local device {}", deviceName);
            StringBuilder nameBuilder = new StringBuilder(VPlexApiConstants.EXTENT_PREFIX);
            nameBuilder.append(deviceName.substring(VPlexApiConstants.DEVICE_PREFIX.length()));
            nameBuilder.append(VPlexApiConstants.EXTENT_SUFFIX);
            deleteExtent(nameBuilder.toString());
        }
    }

    /**
     * Attempts to find and delete the extent with the passed name. If
     * successfully delete, the method will also unclaim the storage volume from
     * which the extent was built.
     *
     * @param extentName The name of the extent.
     *
     * @throws When an error occurs deleting the extent or subsequently
     *             unclaiming the storage volume.
     */
    void deleteExtent(String extentName)
            throws VPlexApiException {

        s_logger.info("Deleting extent {}", extentName);

        // Find the extent.
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        VPlexExtentInfo extentInfo = discoveryMgr.findExtent(extentName);

        // Get the storage volume name.
        String storageVolumeName = extentName.substring(
                VPlexApiConstants.EXTENT_PREFIX.length(),
                extentName.indexOf(VPlexApiConstants.EXTENT_SUFFIX));

        // If the extent is found, destroy it.
        if (extentInfo != null) {
            ClientResponse response = null;
            try {
                s_logger.info("Destroying extent {}", extentName);
                URI requestURI = _vplexApiClient.getBaseURI().resolve(
                        VPlexApiConstants.URI_DESTROY_EXTENT);
                Map<String, String> argsMap = new HashMap<String, String>();
                argsMap.put(VPlexApiConstants.ARG_DASH_S, extentInfo.getPath());
                JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
                s_logger.info("Destroy extent POST data is {}", postDataObject.toString());
                response = _vplexApiClient.post(requestURI,
                        postDataObject.toString());
                String responseStr = response.getEntity(String.class);
                s_logger.info("Destroy extent response is {}", responseStr);
                if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                    if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                        s_logger.info("Destroy extent completing asynchronously");
                        _vplexApiClient.waitForCompletion(response);
                    } else {
                        String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                        throw VPlexApiException.exceptions.deleteExtentFailureStatus(
                                extentName, String.valueOf(response.getStatus()), cause);
                    }
                }

                s_logger.info("Successfully destroyed extent {}", extentName);

                // Now unclaim the volume.
                unclaimStorageVolume(storageVolumeName);
            } catch (VPlexApiException vae) {
                throw vae;
            } catch (Exception e) {
                throw VPlexApiException.exceptions.failedDeleteExtent(extentName, e);
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        } else {
            // Otherwise, unclaim the storage volume that may have been
            // claimed for the extent.
            unclaimStorageVolume(storageVolumeName);
        }
    }

    /**
     * Unclaim the storage volume with the passed name.
     *
     * @param storageVolumeName The storage volume name.
     *
     * @throws VPlexApiException When an error occurs unclaiming the storage
     *             volume.
     */
    private void unclaimStorageVolume(String storageVolumeName) throws VPlexApiException {
        ClientResponse response = null;
        try {
            s_logger.info("Unclaim storage volume {}", storageVolumeName);
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
            VPlexStorageVolumeInfo storageVolumeInfo = discoveryMgr
                    .findStorageVolume(storageVolumeName);
            if (storageVolumeInfo != null) {
                s_logger.info("Found storage volume {} to be unclaimed", storageVolumeName);
                URI requestURI = _vplexApiClient.getBaseURI().resolve(
                        VPlexApiConstants.URI_UNCLAIM_VOLUME);
                Map<String, String> argsMap = new HashMap<String, String>();
                argsMap.put(VPlexApiConstants.ARG_DASH_D, storageVolumeInfo.getPath());
                JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, false);
                s_logger.info("Unclaim storage volume POST data is {}",
                        postDataObject.toString());
                response = _vplexApiClient.post(requestURI,
                        postDataObject.toString());
                String responseStr = response.getEntity(String.class);
                s_logger.info("Unclaim storage volume response is {}", responseStr);
                if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                    if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                        s_logger.info("Unclaim storage volume completing asynchrounously");
                        _vplexApiClient.waitForCompletion(response);
                    } else {
                        String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                        throw VPlexApiException.exceptions.unclaimVolumeFailureStatus(
                                storageVolumeName, String.valueOf(response.getStatus()), cause);
                    }
                }

                s_logger.info("Successfully unclaimed storage volume {}",
                        storageVolumeName);
            }
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedUnclaimVolume(storageVolumeName, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Creates a distributed virtual volume from a non-distributed one plus a new unclaimed
     * remote storage volume.
     *
     * @param virtualVolume - Existing non-distributed virtual volume.
     * @param newRemoteVolume - Unclaimed storage volume in remote cluster.
     * @param discoveryRequired - Set if discovery required.
     * @param clusterId - Used to set the detach rule for the distributed volume.
     * @return - VPlexVirtualVolumeInfo representing the distributed virtual volume.
     * @throws VPlexApiException
     */
    VPlexVirtualVolumeInfo createDistributedVirtualVolume(VPlexVirtualVolumeInfo virtualVolume,
            VolumeInfo newRemoteVolume, boolean discoveryRequired, String clusterId,
            String transferSize) throws VPlexApiException {
        // Determine the "local" device
        String virtualVolumeName = virtualVolume.getName();
        String localDeviceName = virtualVolume.getSupportingDevice();

        // Find the storage volumes corresponding to the passed remote
        // volume information, discovery them if required.
        s_logger.info("Find remote storage volume");
        List<VolumeInfo> remoteVolumeInfoList = new ArrayList<VolumeInfo>();
        remoteVolumeInfoList.add(newRemoteVolume);
        List<VPlexClusterInfo> clusterInfoList = new ArrayList<VPlexClusterInfo>();
        Map<VolumeInfo, VPlexStorageVolumeInfo> storageVolumeInfoMap = findStorageVolumes(
                remoteVolumeInfoList, discoveryRequired, clusterInfoList, null);
        if (storageVolumeInfoMap.isEmpty()) {
            throw VPlexApiException.exceptions.cantDiscoverStorageVolume(newRemoteVolume.getVolumeWWN());
        }

        // Claim the storage volumes
        claimStorageVolumes(storageVolumeInfoMap, false);
        s_logger.info("Claimed remote storage volume");

        // Try and build up the VPLEX local device from the claimed storage
        // volume and attach the local device to the local device of the
        // passed virtual volume to form the local mirror. If we get an error,
        // clean up the VPLEX artifacts and unclaim the storage volume.
        VPlexDeviceInfo remoteDevice, localDevice;
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        try {
            // Create extents
            List<VPlexStorageVolumeInfo> storageVolumeInfoList = new ArrayList<VPlexStorageVolumeInfo>();
            for (VolumeInfo nativeVolumeInfo : remoteVolumeInfoList) {
                storageVolumeInfoList.add(storageVolumeInfoMap.get(nativeVolumeInfo));
            }
            createExtents(storageVolumeInfoList);
            s_logger.info("Created extent on remote storage volume");

            // Find the extents just created and create local devices on
            // those extents.
            List<VPlexExtentInfo> extentInfoList = discoveryMgr.findExtents(storageVolumeInfoList);
            if (extentInfoList.isEmpty()) {
                throw VPlexApiException.exceptions
                        .cantFindExtentForClaimedVolume(storageVolumeInfoList.get(0).getName());
            }
            createLocalDevices(extentInfoList);
            s_logger.info("Created local devices on extents");

            // Find the local device for the extent just created.
            List<VPlexDeviceInfo> remoteDevices = discoveryMgr.findLocalDevices(extentInfoList);
            if (remoteDevices.isEmpty()) {
                throw VPlexApiException.exceptions.cantFindLocalDeviceForExtent(extentInfoList.get(0).getName());
            }
            remoteDevice = remoteDevices.get(0);
            s_logger.info("Found the remote device {}", remoteDevice.getPath());

            // Find the local device.
            localDevice = discoveryMgr.findLocalDevice(localDeviceName);
            if (localDevice == null) {
                throw VPlexApiException.exceptions.cantFindLocalDevice(localDeviceName);
            }
            s_logger.info("Found the local device {}", localDevice.getPath());

            // Now use "device attach-mirror" to create a distributed device from an already exported volume.
            String rulesetName = VPlexApiConstants.CLUSTER_1_DETACHES;
            if (clusterId.equals(VPlexApiConstants.CLUSTER_2_ID)) {
                rulesetName = VPlexApiConstants.CLUSTER_2_DETACHES;
            }
            deviceAttachMirror(localDevice.getPath(), remoteDevice.getPath(), rulesetName);
            s_logger.info("Finished device attach-mirror on device {}", localDevice.getPath());
        } catch (Exception e) {
            // An error occurred. Clean up the VPLEX artifacts created for
            // the mirror and unclaim the storage volume.
            s_logger.info("Exception occurred creating and attaching remote mirror " +
                    " to local VPLEX volume, attempting to cleanup VPLEX artifacts");
            throw e;
        }

        try {
            // Find virtual volume.
            VPlexVirtualVolumeInfo vvInfo = discoveryMgr.findVirtualVolume(
                    localDevice.getCluster(), virtualVolumeName, false);

            // If transferSize is set, set the rebuild size for the distributed device created by
            // device-attach mirror.
            VPlexDistributedDeviceInfo distDeviceInfo = discoveryMgr
                    .findDistributedDevice(localDevice.getName());

            if (transferSize != null) {
                String deviceName = distDeviceInfo.getName();
                s_logger.info("Rebuild transfer size of {} will be set for device {}", transferSize, deviceName);
                setRebuildTransferSize(deviceName, transferSize);
            }

            // If the volume is using the default naming convention then when want to update the 
            // name of the volume and the distributed device to reflect both backend volumes 
            // used by the newly formed distributed volume. However, if the volume does not conform
            // to the default naming convention, such as an ingested volume or a volume that has 
            // it name set via the custom naming configurations, then we want to leave the volume name
            // alone and simply update the device name, again assuming the previous device named 
            // conforms to the standard naming convention.
            if (localDeviceName.length() > VPlexApiConstants.DEVICE_PREFIX.length()) {
                List<String> claimedVolumeNames = Arrays.asList(localDeviceName.substring(VPlexApiConstants.DEVICE_PREFIX.length()));
                if (VPlexApiUtils.volumeHasDefaultNamingConvention(virtualVolumeName, localDeviceName, false, claimedVolumeNames)) {
                    // Update both the volume and supporting device names.
                    String remoteName = remoteDevice.getName().replaceAll(VPlexApiConstants.DEVICE_PREFIX, "");
                    String newVvName = vvInfo.getName();
                    newVvName = newVvName.replaceFirst(VPlexApiConstants.DEVICE_PREFIX,
                            VPlexApiConstants.DIST_DEVICE_PREFIX + VPlexApiConstants.DIST_DEVICE_NAME_DELIM);
                    newVvName = newVvName.replaceFirst(VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX, "");
                    newVvName = newVvName + VPlexApiConstants.DIST_DEVICE_NAME_DELIM + remoteName
                            + VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX;
                    vvInfo = renameVPlexResource(vvInfo, newVvName);
    
                    String newDdName = distDeviceInfo.getName();
                    newDdName = newDdName.replaceFirst(VPlexApiConstants.DEVICE_PREFIX,
                            VPlexApiConstants.DIST_DEVICE_PREFIX + VPlexApiConstants.DIST_DEVICE_NAME_DELIM);
                    newDdName = newDdName + VPlexApiConstants.DIST_DEVICE_NAME_DELIM + remoteName;
                    distDeviceInfo = renameVPlexResource(distDeviceInfo, newDdName);
                } else if (VPlexApiUtils.localDeviceHasDefaultNamingConvention(localDeviceName, claimedVolumeNames)) {
                    // The volume name does not conform, but then supporting device does, so 
                    // update just the device name.
                    String newDdName = distDeviceInfo.getName();
                    newDdName = newDdName.replaceFirst(VPlexApiConstants.DEVICE_PREFIX,
                            VPlexApiConstants.DIST_DEVICE_PREFIX + VPlexApiConstants.DIST_DEVICE_NAME_DELIM);
                    String remoteName = remoteDevice.getName().replaceAll(VPlexApiConstants.DEVICE_PREFIX, "");
                    newDdName = newDdName + VPlexApiConstants.DIST_DEVICE_NAME_DELIM + remoteName;
                    distDeviceInfo = renameVPlexResource(distDeviceInfo, newDdName);
                }
            }
            return vvInfo;
        } catch (Exception e) {
            // An error occurred. Detach the mirror and clean up the VPLEX artifacts
            // created for the remote mirror and unclaim the storage volume.
            try {
                // Detach the mirror as the attach was successful.
                detachMirrorFromLocalVirtualVolume(virtualVolumeName, remoteDevice.getName(), true);
                // This will look for any artifacts, starting with a virtual
                // volume, that use the passed native volume info and destroy
                // them and then unclaim the volume.
                deleteVirtualVolume(Collections.singletonList(newRemoteVolume));
            } catch (Exception ex) {
                s_logger.error("Failed attempting to cleanup VPLEX after failed attempt " +
                        "to find and rename local virtual volume {} after remote mirror attached.", virtualVolume.getPath(), ex);
            }
            throw e;
        }
    }

    /**
     * Execute the "device attach-mirror" command.
     *
     * @param sourceDevicePath -- Path for the device in the existing virtual volume
     * @param mirrorDevicePath -- Path for the new device that will be attached as a mirror.It can be
     *            in the local cluster of the source device or in the remote cluster.
     * @param rulesetName -- The rule set name apply when attaching a remote mirror to form a
     *            distributed volume or null when attaching local mirrors.
     * @throws VPlexApiException
     */
    private void deviceAttachMirror(String sourceDevicePath, String mirrorDevicePath,
            String rulesetName) throws VPlexApiException {
        ClientResponse response = null;
        try {
            s_logger.info("Device Attach Mirror for devices {} {}", sourceDevicePath, mirrorDevicePath);
            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    VPlexApiConstants.URI_DEVICE_ATTACH_MIRROR);
            s_logger.info("Device Attach Mirror URI is {}", requestURI.toString());
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_D, sourceDevicePath);
            argsMap.put(VPlexApiConstants.ARG_DASH_M, mirrorDevicePath);
            if (rulesetName != null) {
                argsMap.put(VPlexApiConstants.ARG_DASH_R, rulesetName);
            }

            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Device Attach Mirror POST data is {}", postDataObject.toString());
            response = _vplexApiClient.post(requestURI, postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Device Attach Mirror response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Virtual volume creation completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.attachMirrorFailureStatus(
                            String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully attached mirror for device {}", sourceDevicePath);
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedAttachMirror(e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Execute the "rebuild set-transfer-size" command.
     *
     * @param deviceName -- Distributed device on which we need to set the rebuild size
     * @param transferSize -- The transfer size that needs to be set in VPLEX
     * @throws VPlexApiException
     */

    public void setRebuildTransferSize(String deviceName, String transferSize) {
        ClientResponse response = null;
        try {
            s_logger.info("Setting transfer size");
            URI requestURI = _vplexApiClient.getBaseURI().resolve(VPlexApiConstants.URI_REBUILD_SET_TRANSFER_SIZE);
            s_logger.info("Rebuild Transfer size URI is {}", requestURI.toString());
            Map<String, String> argsMap = new HashMap<String, String>();
            StringBuilder deviceAndSize = new StringBuilder();
            deviceAndSize.append(deviceName);
            deviceAndSize.append(" ");
            deviceAndSize.append(transferSize);
            argsMap.put(VPlexApiConstants.ARG_DEVICES, deviceAndSize.toString());

            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, false);
            s_logger.info("Rebuild Set Transfer Size POST data is {}", postDataObject.toString());
            response = _vplexApiClient.post(requestURI, postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Rebuild Set Transfer Size response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Rebuild Set Transfer Size command completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.setRebuildSetTransferSpeeFailureStatus(
                            String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully executed rebuild set-transfer-size command");
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedSetTransferSize(e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Waits for the rebuild of the passed distributed volume to complete.
     * Will wait for up to 4 hours before it stops waiting and returns a
     * timeout result.
     *
     * @param virtualVolumeName The name of the virtual volume.
     *
     * @return A WaitOnRebuildResult indicating if the rebuild completed successfully,
     *         failed, or we timed out waiting for it to complete.
     *
     * @throws VPlexApiException
     */
    public WaitOnRebuildResult waitOnRebuildCompletion(String virtualVolumeName)
            throws VPlexApiException {
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        for (int retryCount = 0; retryCount < VPlexApiConstants.REBUILD_WAIT_RETRY_COUNT; retryCount++) {
            try {
                VPlexVirtualVolumeInfo virtualVolume = discoveryMgr.findVirtualVolume(virtualVolumeName, true);
                if (!VPlexVirtualVolumeInfo.Locality.distributed.name().equals(virtualVolume.getLocality())) {
                    s_logger.error("Not a distributed device: {}", virtualVolumeName);
                    return WaitOnRebuildResult.INVALID_REQUEST;
                }
                Map<String, Object> ddProperties = getDistributedDeviceProperties(virtualVolume.getSupportingDevice());
                String rebuildStatus = ddProperties.get(VPlexApiConstants.REBUILD_STATUS_ATT_KEY).toString();
                if (VPlexApiConstants.REBUILD_STATUS_DONE.equalsIgnoreCase(rebuildStatus)) {
                    s_logger.info("Rebuild complete for volume: {}", virtualVolumeName);
                    return WaitOnRebuildResult.SUCCESS;
                }
                if (rebuildStatus.equalsIgnoreCase(VPlexApiConstants.REBUILD_STATUS_ERROR)) {
                    s_logger.info("Rebuild failed for volume: {}", virtualVolumeName);
                    return WaitOnRebuildResult.FAILED;
                }

                s_logger.info("Waiting on rebuild to complete for volume: {}", virtualVolumeName);
                VPlexApiUtils.pauseThread(VPlexApiConstants.REBUILD_WAIT_SLEEP_TIME_MS);
            } catch (Exception e) {
                s_logger.warn("An exception occured checking rebuild status: {}. Retrying", e.getMessage(), e);
                VPlexApiUtils.pauseThread(VPlexApiConstants.REBUILD_WAIT_SLEEP_TIME_MS);
            }
        }
        s_logger.error("Rebuild timed out for volume: {}", virtualVolumeName);
        return WaitOnRebuildResult.TIMED_OUT;
    }

    /**
     * Returns the properties for a Distributed Device as name/value pairs.
     *
     * @param deviceName String
     * @return Map of property name to value
     * @throws VPlexApiException
     */
    Map<String, Object> getDistributedDeviceProperties(String deviceName)
            throws VPlexApiException {
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        VPlexDistributedDeviceInfo ddInfo = discoveryMgr.findDistributedDevice(deviceName);
        URI requestURI = _vplexApiClient.getBaseURI().resolve(
                URI.create(VPlexApiConstants.VPLEX_PATH + ddInfo.getPath()));
        s_logger.info("getDistributedDeviceProperties URI is {}", requestURI.toString());
        ClientResponse response = _vplexApiClient.get(requestURI);
        String responseStr = response.getEntity(String.class);
        response.close();
        s_logger.info("getDistributedDeviceProperties name response is {}", responseStr);
        Map<String, Object> resultMap = VPlexApiUtils.getAttributesFromResponse(responseStr);
        return resultMap;
    }

    /**
     * Get the device components of a Distributed Device
     *
     * @param deviceName -- Distributed Device Name
     * @return -- List<VPlexResourceInfo>
     * @throws VPlexApiException
     */
    List<VPlexDistributedDeviceComponentInfo> getDistributedDeviceComponents(
            String deviceName) throws VPlexApiException {
        VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
        VPlexDistributedDeviceInfo ddInfo = discoveryMgr.findDistributedDevice(deviceName);
        return discoveryMgr.getDistributedDeviceComponents(ddInfo);
    }

    /**
     * Get the device components of a Distributed Device.
     *
     * @param ddInfo - The VPlex distributed device info for the device.
     *
     * @return A list of VPlexResourceInfo representing the components of the
     *         distributed device.
     *
     * @throws VPlexApiException When an error occurs finding the components for
     *             the distributed device.
     */
    List<VPlexResourceInfo> getDistributedDeviceComponents(
            VPlexDistributedDeviceInfo ddInfo) throws VPlexApiException {
        URI requestURI = _vplexApiClient.getBaseURI().resolve(
                URI.create(VPlexApiConstants.VPLEX_PATH + ddInfo.getPath()
                        + VPlexApiConstants.URI_DISTRIBUTED_DEVICE_COMP));
        s_logger.info("getDistributedDeviceComponents URI is {}", requestURI.toString());
        ClientResponse response = _vplexApiClient.get(requestURI);
        String responseStr = response.getEntity(String.class);
        response.close();
        s_logger.info("getDistributedDeviceComponents name response is {}", responseStr);
        List<VPlexResourceInfo> componentList =
                VPlexApiUtils.getChildrenFromResponse(
                        VPlexApiConstants.URI_DISTRIBUTED_DEVICES
                                + ddInfo.getName()
                                + VPlexApiConstants.URI_DISTRIBUTED_DEVICE_COMP,
                        responseStr, VPlexResourceInfo.class);
        return componentList;
    }

    /**
     * Rename a VPlex resource that subclasses VPlexResourceInfo.
     * Adjusts the path and name in the resourceInfo and returns the original object.
     *
     * @param resourceInfo - VPlexVirtualVolumeInfo, VPlexDistributedDeviceInfo, etc.
     * @param newName String -- the desired new name.
     * @return -- The original VPlexResourceInfo with updated path and name
     * @throws VPlexApiException
     */
    <T extends VPlexResourceInfo> T renameVPlexResource(T resourceInfo, String newName)
            throws VPlexApiException {
        StringBuilder pathBuilder = new StringBuilder();
        pathBuilder.append(VPlexApiConstants.VPLEX_PATH);
        pathBuilder.append(resourceInfo.getPath());
        pathBuilder.append("?");
        pathBuilder.append(VPlexApiConstants.ATTRIBUTE_NAME_JSON_KEY);
        pathBuilder.append("=");
        pathBuilder.append(newName);
        URI requestURI = _vplexApiClient.getBaseURI().resolve(
                URI.create(pathBuilder.toString()));
        s_logger.info("Update name URI is {}", requestURI.toString());
        
        // Try and rename the resource. For newly created resources, the VPLEX
        // database may not be consistent and the VPLEX may return an unexpected 
        // failure (See COP-24456). A retry of the request is likely to succeed. 
        int retryCount = 0;
        while (++retryCount <= VPlexApiConstants.RENAME_RESOURCE_MAX_TRIES) {
            ClientResponse response = _vplexApiClient.put(requestURI);
            String responseStr = response.getEntity(String.class);
            s_logger.info("Update name response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Update name is completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                    response.close();
                } else {
                    response.close();
                    if (retryCount == VPlexApiConstants.RENAME_RESOURCE_MAX_TRIES) {
                        String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                        throw VPlexApiException.exceptions.renameResourceFailureStatus(
                                String.valueOf(response.getStatus()), cause);
                    } else {
                        s_logger.info(String.format("Update name for resource %s failed on attempt %d of %d, retrying...",
                                resourceInfo.getName(), retryCount, VPlexApiConstants.RENAME_RESOURCE_MAX_TRIES));
                        VPlexApiUtils.pauseThread(VPlexApiConstants.RENAME_RESOURCE_SLEEP_TIME_MS);
                        continue;
                    } 
                }
            }
            String newPath = resourceInfo.getPath().replaceFirst(resourceInfo.getName(), newName);
            resourceInfo.setPath(newPath);
            resourceInfo.setName(newName);
            break;
        }
        return resourceInfo;
    }

    /**
     * All cached data associated with the virtual volume with the passed name
     * is invalidated on all directors of the VPLEX cluster. Subsequent reads
     * from host applications will fetch data from storage volume due to cache
     * miss.
     *
     * NOTE: According to VPLEX engineering, the execution of the cache-invalidate
     * command will wait 5 minutes for the invalidation to complete. If it does
     * not complete successfully or in error within this time, the command will
     * return a failure status, but return a message in the response "exception"
     * field indicating that the invalidate is still in progress. In this case,
     * the cache-invalidate-status command must be executed to monitor the
     * progress until it completes.
     *
     * @param virtualVolumeName The name of the virtual volume.
     *
     * @return true if the invalidate cache is still in progress when the
     *         call returns and the status must be monitored until it completes,
     *         false otherwise.
     *
     * @throws VPlexApiException When an error occurs invalidating the cache.
     */
    public boolean invalidateVirtualVolumeCache(String virtualVolumeName)
            throws VPlexApiException {
        s_logger.info("Request to invalidate virtual volume cache for volume {}",
                virtualVolumeName);

        ClientResponse response = null;
        try {
            // Find the virtual volume.
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
            VPlexVirtualVolumeInfo virtualVolumeInfo = discoveryMgr
                    .findVirtualVolume(virtualVolumeName, false);

            // Invalidate the read cache for the virtual volume.
            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    VPlexApiConstants.URI_INVALIDATE_VOLUME_CACHE);
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_V, virtualVolumeInfo.getPath());
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Invalidate cache POST data is {}",
                    postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Invalidate cache response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Invalidate cache completing asynchrounously");
                    _vplexApiClient.waitForCompletion(response);
                    return false;
                } else {
                    // For a failure status, the exception message will indicate
                    // if failure is because the cache invalidation timed out and
                    // is still in progress. If it is not, then it failed.
                    String exceptionMessage = VPlexApiUtils.getExceptionMessageFromResponse(responseStr);
                    if ((exceptionMessage != null) &&
                            (exceptionMessage.contains(VPlexApiConstants.CACHE_INVALIDATE_IN_PROGRESS_MSG))) {
                        return true;
                    } else {
                        String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                        throw VPlexApiException.exceptions.invalidateCacheFailureStatus(
                                virtualVolumeName, String.valueOf(response.getStatus()), cause);
                    }
                }
            }
            return false;
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedInvalidatingVolumeCache(virtualVolumeName, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Get the cache invalidate status information for the virtual volume with
     * the passed name.
     *
     * @param virtualVolumeName The name of the virtual volume.
     *
     * @return A reference to a VPlexCacheStatusInfo specifying the cache status
     *         information.
     */
    public VPlexCacheStatusInfo getCacheStatus(String virtualVolumeName)
            throws VPlexApiException {
        s_logger.info("Request to get the cache status for for volume {}",
                virtualVolumeName);

        VPlexCacheStatusInfo cacheStatusInfo = new VPlexCacheStatusInfo();
        ClientResponse response = null;
        try {
            // Find the virtual volume.
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
            VPlexVirtualVolumeInfo virtualVolumeInfo = discoveryMgr
                    .findVirtualVolume(virtualVolumeName, false);

            // Get the cache invalidate status.
            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    VPlexApiConstants.URI_INVALIDATE_VOLUME_CACHE_STATUS);
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_V, virtualVolumeInfo.getPath());
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, false);
            s_logger.info("Get cache status POST data is {}", postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Get cache status response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Get cache status completing asynchrounously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.getCacheStatusFailureStatus(
                            virtualVolumeName, String.valueOf(response.getStatus()), cause);
                }
            }

            // Successful response. Parse the custom-data from the response, which
            // contains the information to determine the progress of the cache
            // invalidation. Update the cache status info invalidation status and if
            // it is determined that the cache invalidation failed, set the failure
            // error message.
            //
            // NOTE: TBD once I know how to parse this info.
            cacheStatusInfo.setCacheInvalidateStatus(InvalidateStatus.SUCCESS);
            return cacheStatusInfo;
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedGettingCacheStatus(virtualVolumeName, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Detaches the mirror specified by the passed mirror info from the
     * local VPLEX volume with the passed name.
     *
     * @param virtualVolumeName The name of the VPLEX Local volume.
     * @param mirrorDeviceName The name of the mirror to be detached.
     * @param discard The boolean value for discard
     *
     * @throws VPlexApiException When an error occurs detaching the mirror from the volume.
     */
    public void detachMirrorFromLocalVirtualVolume(String virtualVolumeName,
            String mirrorDeviceName, boolean discard) throws VPlexApiException {
        s_logger.info("Request to detach mirror {} from a virtual volume {}",
                mirrorDeviceName, virtualVolumeName);

        ClientResponse response = null;
        try {
            // Find the virtual volume to make sure it exists.
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();

            // Find the virtual volume.
            VPlexVirtualVolumeInfo virtualVolumeInfo = findVirtualVolumeAndUpdateInfo(virtualVolumeName, discoveryMgr);

            // Find the source device for the virtual volume.
            String sourceDeviceName = virtualVolumeInfo.getSupportingDevice();

            VPlexDeviceInfo sourceDeviceInfo = discoveryMgr.findLocalDevice(sourceDeviceName);
            if (sourceDeviceInfo == null) {
                throw VPlexApiException.exceptions
                        .cantFindLocalDeviceForVolume(virtualVolumeName);
            }

            // Get components from the source volume device to check the mirror to be detached
            // exist there.
            String mirrorDevicePath = null;
            List<VPlexLocalDeviceComponentInfo> components = discoveryMgr.getLocalDeviceComponents(sourceDeviceInfo);
            for (VPlexLocalDeviceComponentInfo component : components) {
                if (component.getName().equals(mirrorDeviceName)) {
                    mirrorDevicePath = component.getPath();
                    break;
                }
            }

            // Throw an exception if we can't find device component
            // corresponding to the mirror to be updated.
            if (mirrorDevicePath == null) {
                throw VPlexApiException.exceptions.cantFindMirror(mirrorDeviceName,
                        virtualVolumeName);
            }

            // Detach this mirror device component from the source device.
            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    VPlexApiConstants.URI_DEVICE_DETACH_MIRROR);
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_D, sourceDeviceInfo.getPath());
            argsMap.put(VPlexApiConstants.ARG_DASH_M, mirrorDevicePath);

            if (discard) {
                // If discard is false then without discard option the device will be
                // converted into virtual volume.
                argsMap.put(VPlexApiConstants.ARG_DISCARD, "");
            }

            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Detach mirror for virtual volume POST data is {}", postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Detach mirror for virtual volume response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Detach mirror for virtual volume is completing asynchrounously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.detachMirrorFailureStatus(
                            mirrorDeviceName, virtualVolumeName,
                            String.valueOf(response.getStatus()), cause);
                }
            }

            // if detach was a success, we need to flatten the device 
            // to align with standard ViPR 1-1-1 structure 
            _vplexApiClient.deviceCollapse(sourceDeviceName, VPlexApiConstants.LOCAL_DEVICE);
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedDetachingVPlexVolumeMirror(
                    mirrorDeviceName, virtualVolumeName, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Detaches the mirror specified by the passed mirror info from the
     * Distributed VPLEX volume with the passed name.
     *
     * @param virtualVolumeName The name of the VPLEX Local volume.
     * @param mirrorDeviceName The name of the mirror to be detached.
     * @param discard The boolean value for discard
     *
     * @throws VPlexApiException When an error occurs detaching the mirror from the volume.
     */
    public void detachLocalMirrorFromDistributedVirtualVolume(String virtualVolumeName,
            String mirrorDeviceName, boolean discard) throws VPlexApiException {
        s_logger.info("Request to detach mirror {} from a virtual volume {}",
                mirrorDeviceName, virtualVolumeName);

        try {
            // Find the virtual volume to make sure it exists.
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
            VPlexVirtualVolumeInfo virtualVolumeInfo = findVirtualVolumeAndUpdateInfo(virtualVolumeName, discoveryMgr);

            // Find the source device for the virtual volume.
            String sourceDeviceName = virtualVolumeInfo.getSupportingDevice();
            // Find the distributed device
            VPlexDistributedDeviceInfo distributedDeviceInfo = discoveryMgr.findDistributedDevice(sourceDeviceName);
            if (distributedDeviceInfo == null) {
                throw VPlexApiException.exceptions.cantFindDistDevice(sourceDeviceName);
            }

            String sourceDevicePath = null;
            String mirrorDevicePath = null;
            List<VPlexDistributedDeviceComponentInfo> ddComponents = discoveryMgr
                    .getDistributedDeviceComponents(distributedDeviceInfo);
            for (VPlexDistributedDeviceComponentInfo ddComponent : ddComponents) {
                discoveryMgr.updateDistributedDeviceComponent(ddComponent);
                List<VPlexLocalDeviceComponentInfo> localComponents = discoveryMgr.getLocalDeviceComponents(ddComponent);
                for (VPlexLocalDeviceComponentInfo localComponent : localComponents) {
                    if (localComponent.getName().equals(mirrorDeviceName)) {
                        sourceDevicePath = ddComponent.getPath();
                        mirrorDevicePath = localComponent.getPath();
                        break;
                    }
                }
                if (sourceDevicePath != null && mirrorDevicePath != null) {
                    break;
                }
            }

            // Throw an exception if we can't find device component
            // corresponding to the mirror to be updated.
            if (mirrorDevicePath == null) {
                throw VPlexApiException.exceptions.cantFindMirror(mirrorDeviceName,
                        virtualVolumeName);
            }

            // Detach this mirror device component from the source device.
            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    VPlexApiConstants.URI_DEVICE_DETACH_MIRROR);
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_D, sourceDevicePath);
            argsMap.put(VPlexApiConstants.ARG_DASH_M, mirrorDevicePath);

            if (discard) {
                // If discard is false then without discard option the device will be
                // converted into virtual volume.
                argsMap.put(VPlexApiConstants.ARG_DISCARD, "");
            }

            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Detach mirror for virtual volume POST data is {}", postDataObject.toString());
            ClientResponse response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Detach mirror for virtual volume response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Detach mirror for virtual volume is completing asynchrounously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.detachMirrorFailureStatus(
                            mirrorDeviceName, virtualVolumeName,
                            String.valueOf(response.getStatus()), cause);
                }
            }

            // if detach was a success, we need to flatten the device 
            // to align with standard ViPR 1-1-1 structure 
            _vplexApiClient.deviceCollapse(sourceDevicePath, VPlexApiConstants.COLLAPSE_BY_PATH);
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedDetachingVPlexVolumeMirror(
                    mirrorDeviceName, virtualVolumeName, e);
        }
    }

    /**
     * Detaches the mirror on the specified cluster from the distributed VPLEX
     * volume with the passed name.
     *
     * @param virtualVolumeName The name of the VPLEX distributed volume.
     * @param clusterId The cluster of the mirror to detach.
     *
     * @return The name of the detached mirror for use when reattaching the mirror.
     *
     * @throws VPlexApiException When an error occurs detaching the mirror from the volume.
     */
    public String detachMirrorFromDistributedVolume(String virtualVolumeName,
            String clusterId) throws VPlexApiException {
        s_logger.info("Request to detach mirror on cluster {} from a distributed volume {}",
                clusterId, virtualVolumeName);

        ClientResponse response = null;
        try {
            // Find the virtual volume to make sure it exists.
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
            VPlexVirtualVolumeInfo virtualVolumeInfo = findVirtualVolumeAndUpdateInfo(virtualVolumeName, discoveryMgr);

            // Find the distributed device for the virtual volume.
            String ddName = virtualVolumeInfo.getSupportingDevice();
            VPlexDistributedDeviceInfo ddInfo = discoveryMgr.findDistributedDevice(ddName);
            if (ddInfo == null) {
                throw VPlexApiException.exceptions
                        .cantFindDistributedDeviceForVolume(virtualVolumeName);
            }

            // Get the distributed device components. These are the local devices
            // that were used to build the distributed device. Then find the
            // component corresponding to the mirror to be detached.
            String mirrorDevicePath = null;
            String detachedDeviceName = null;
            List<VPlexDistributedDeviceComponentInfo> ddComponents = discoveryMgr
                    .getDistributedDeviceComponents(ddInfo);
            for (VPlexDistributedDeviceComponentInfo ddComponent : ddComponents) {
                discoveryMgr.updateDistributedDeviceComponent(ddComponent);
                if (ddComponent.getCluster().equals(clusterId)) {
                    mirrorDevicePath = ddComponent.getPath();
                    detachedDeviceName = ddComponent.getName();
                    s_logger.info("Detached device is {}", detachedDeviceName);
                    break;
                }
            }

            // Throw an exception if we can't find distributed device component
            // corresponding to the mirror to be updated.
            if (mirrorDevicePath == null) {
                throw VPlexApiException.exceptions.cantFindMirrorForDetach(clusterId,
                        virtualVolumeName);
            }

            // Detach this component from the distributed device.
            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    VPlexApiConstants.URI_DEVICE_DETACH_MIRROR);
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_D, ddInfo.getPath());
            argsMap.put(VPlexApiConstants.ARG_DASH_M, mirrorDevicePath);
            argsMap.put(VPlexApiConstants.ARG_DISCARD, "");
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Detach mirror for virtual volume POST data is {}", postDataObject.toString());
            response = _vplexApiClient.post(requestURI,
                    postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Detach mirror for virtual volume response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Detach mirror for virtual volume is completing asynchrounously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.detachMirrorFailureStatus(
                            clusterId, virtualVolumeName,
                            String.valueOf(response.getStatus()), cause);
                }
            }

            s_logger.info("Detached device is {}", detachedDeviceName);
            return detachedDeviceName;
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedDetachingVPlexVolumeMirror(
                    clusterId, virtualVolumeName, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Reattach the mirror on the specified cluster to the distributed VPLEX
     * volume with the passed name.
     *
     * @param virtualVolumeName The name of the VPLEX distributed volume.
     * @param detachedDeviceName The local device name of the mirror previously detached.
     *
     * @throws VPlexApiException When an error occurs reattaching the mirror to
     *             the volume.
     */
    public void reattachMirrorToDistributedVolume(String virtualVolumeName,
            String detachedDeviceName) throws VPlexApiException {
        s_logger.info("Request to reattach mirror {} to distributed volume {}",
                detachedDeviceName, virtualVolumeName);

        try {
            // Find the virtual volume to make sure it exists.
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();
            VPlexVirtualVolumeInfo vplexVirtualVolumeInfo = findVirtualVolumeAndUpdateInfo(virtualVolumeName, discoveryMgr);

            // Find the distributed device for the virtual volume.
            String ddName = vplexVirtualVolumeInfo.getSupportingDevice();
            VPlexDistributedDeviceInfo ddInfo = discoveryMgr.findDistributedDevice(ddName);
            if (ddInfo == null) {
                throw VPlexApiException.exceptions
                        .cantFindDistributedDeviceForVolume(virtualVolumeName);
            }

            // Find the local device for the passed mirror. Note that when
            // the mirror is detached from the distributed device, the
            // local devices that comprise the distributed device are no
            // longer listed as components of the distributed device. We
            // must find the local device on the cluster on which it resides.
            VPlexDeviceInfo mirrorDeviceInfo = discoveryMgr.findLocalDevice(detachedDeviceName);
            if (mirrorDeviceInfo == null) {
                throw VPlexApiException.exceptions.cantFindMirrorForAttach(
                        detachedDeviceName, virtualVolumeName);
            }
            String mirrorDevicePath = mirrorDeviceInfo.getPath();

            // Reattach this local device to the distributed device.
            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    VPlexApiConstants.URI_DEVICE_ATTACH_MIRROR);
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_D, ddInfo.getPath());
            argsMap.put(VPlexApiConstants.ARG_DASH_M, mirrorDevicePath);
            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, true);
            s_logger.info("Reattach mirror for virtual volume POST data is {}",
                    postDataObject.toString());

            // We'll retry a failed reattach in case some transitive issue
            // caused the reattach to fail. We want to do our best to recreate
            // the distributed volume.
            int reattachTryCount = 0;
            while (++reattachTryCount <= VPlexApiConstants.REATTACH_HA_MIRROR_RETRY_COUNT) {
                ClientResponse response = null;
                try {
                    response = _vplexApiClient.post(requestURI,
                            postDataObject.toString());
                    String responseStr = response.getEntity(String.class);
                    s_logger.info("Attach mirror for virtual volume response is {}", responseStr);
                    if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                        if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                            s_logger.info("Attach mirror for virtual volume is completing asynchrounously");
                            _vplexApiClient.waitForCompletion(response);
                            // Reattach succeeded upon waiting for completion.
                            break;
                        } else {
                            String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                            throw VPlexApiException.exceptions.reattachMirrorFailureStatus(
                                    detachedDeviceName, virtualVolumeName,
                                    String.valueOf(response.getStatus()), cause);
                        }
                    }

                    // Reattach succeeded.
                    break;
                } catch (Exception e) {
                    if (reattachTryCount == VPlexApiConstants.REATTACH_HA_MIRROR_RETRY_COUNT) {
                        if (e instanceof VPlexApiException) {
                            throw e;
                        } else {
                            throw VPlexApiException.exceptions.failedAttachingVPlexVolumeMirror(
                                    detachedDeviceName, virtualVolumeName, e);
                        }
                    } else {
                        VPlexApiUtils.pauseThread(VPlexApiConstants.REATTACH_HA_MIRROR_SLEEP_TIME_MS);
                    }
                } finally {
                    if (response != null) {
                        response.close();
                    }
                }
            }
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedAttachingVPlexVolumeMirror(
                    detachedDeviceName, virtualVolumeName, e);
        }
    }

    /**
     * This method finds virtual volume on the VPLEX and then updates virtual volume info.
     *
     * @param virtualVolumeName virtual volume name
     * @param discoveryMgr reference to VPlexApiDiscoveryManager
     * @return VPlexVirtualVolumeInfo object with updated info.
     */
    public VPlexVirtualVolumeInfo findVirtualVolumeAndUpdateInfo(String virtualVolumeName, VPlexApiDiscoveryManager discoveryMgr) {
        VPlexVirtualVolumeInfo virtualVolumeInfo = null;
        if (null == discoveryMgr) {
            discoveryMgr = _vplexApiClient.getDiscoveryManager();
        }
        List<VPlexClusterInfo> clusterInfoList = discoveryMgr.getClusterInfoLite();
        for (VPlexClusterInfo clusterInfo : clusterInfoList) {
            String clusterName = clusterInfo.getName();
            virtualVolumeInfo = discoveryMgr.findVirtualVolume(clusterName,
                    virtualVolumeName, false);
            if (virtualVolumeInfo != null) {
                discoveryMgr.updateVirtualVolumeInfo(clusterName, virtualVolumeInfo);
                break;
            }
        }

        if (virtualVolumeInfo == null) {
            throw VPlexApiException.exceptions.cantFindRequestedVolume(virtualVolumeName);
        }
        return virtualVolumeInfo;
    }

    /**
     * This method collapses the one legged device for the passed virtual volume device.
     * After this device will change back to local device.
     *
     * @param sourceDeviceNameOrPath source device name or path
     * @param collapseType "local" or "distributed" or "collapse-by-path"
     */
    public void deviceCollapse(String sourceDeviceNameOrPath, String collapseType) {
        ClientResponse response = null;
        try {
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();

            // Find the source device.
            VPlexResourceInfo sourceDevice = null;
            String devicePath = null;
            if (VPlexApiConstants.DISTRIBUTED_DEVICE.equalsIgnoreCase(collapseType)) {
                sourceDevice = discoveryMgr.findDistributedDevice(sourceDeviceNameOrPath);
                if (sourceDevice != null) {
                    devicePath = sourceDevice.getPath();
                }
            } else if (VPlexApiConstants.LOCAL_DEVICE.equalsIgnoreCase(collapseType)) {
                sourceDevice = discoveryMgr.findLocalDevice(sourceDeviceNameOrPath);
                if (sourceDevice != null) {
                    devicePath = sourceDevice.getPath();
                }
            } else if (VPlexApiConstants.COLLAPSE_BY_PATH.equalsIgnoreCase(collapseType)){
                devicePath = sourceDeviceNameOrPath;
            } else {
                throw new Exception("invalid collapse type: " + collapseType);
            }
            if (devicePath == null) {
                throw VPlexApiException.exceptions.cantFindLocalDevice(sourceDeviceNameOrPath);
            }
            s_logger.info("Found the device path to collapse {}", devicePath);

            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    VPlexApiConstants.URI_DEVICE_COLLAPSE);
            s_logger.info("Device collapse URI is {}", requestURI.toString());
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.ARG_DASH_D, devicePath);

            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, false);
            s_logger.info("Device collapse POST data is {}", postDataObject.toString());

            response = _vplexApiClient.post(requestURI, postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Device collapse response is {}", responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Device collapse completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.failedDeviceCollapseStatus(
                            sourceDeviceNameOrPath, String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully did device collapse for device {}", devicePath);
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedDeviceCollapse(sourceDeviceNameOrPath, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * This method sets device visibility to local.
     *
     * @param sourceDeviceName source device name
     * @throws VPlexApiException
     */
    public void setDeviceVisibility(String sourceDeviceName) {
        ClientResponse response = null;
        try {
            VPlexApiDiscoveryManager discoveryMgr = _vplexApiClient.getDiscoveryManager();

            // Find the source local device.
            VPlexDeviceInfo sourceLocalDevice = discoveryMgr.findLocalDevice(sourceDeviceName);
            if (sourceLocalDevice == null) {
                throw VPlexApiException.exceptions.cantFindLocalDevice(sourceDeviceName);
            }
            String devicePath = sourceLocalDevice.getPath();
            s_logger.info("Found the local device {}", devicePath);

            // Build the request path.
            StringBuilder pathBuilder = new StringBuilder();
            pathBuilder.append(VPlexApiConstants.VPLEX_PATH);
            pathBuilder.append(devicePath);
            pathBuilder.append(VPlexApiConstants.QUESTION_MARK);
            pathBuilder.append(VPlexApiConstants.ATTRIBUTE_DEVICE_VISIBILITY);
            pathBuilder.append(VPlexApiConstants.EQUALS);
            pathBuilder.append(VPlexApiConstants.LOCAL_DEVICE);

            URI requestURI = _vplexApiClient.getBaseURI().resolve(
                    URI.create(pathBuilder.toString()));
            s_logger.info("Set device visibility URI is {}", requestURI.toString());

            response = _vplexApiClient.put(requestURI);
            String responseStr = response.getEntity(String.class);
            s_logger.info("Set device visibility response is {}", responseStr);
            int status = response.getStatus();
            if (status != VPlexApiConstants.SUCCESS_STATUS) {
                if (status == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Set device visibility  is completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                    response.close();
                } else {
                    response.close();
                    String cause = VPlexApiUtils.getCauseOfFailureFromResponse(responseStr);
                    throw VPlexApiException.exceptions.failedSettingDeviceVisibilityStatus(
                            sourceDeviceName, String.valueOf(response.getStatus()), cause);
                }
            }
            s_logger.info("Successfully set {} visibility for device {}", VPlexApiConstants.LOCAL_DEVICE, devicePath);
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedSettingDeviceVisibility(sourceDeviceName, e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Sends a request to the VPLEX to turn on thin provisioning for the given
     * virtual volume. Will return a boolean value indicating whether or not
     * the request was a success.
     * 
     * @param virtualVolumeInfo the virtual volume to update
     * @return true if the thin-enabled request was a success
     */
    public boolean setVirtualVolumeThinEnabled(VPlexVirtualVolumeInfo virtualVolumeInfo) {
        ClientResponse response = null;
        try {
            // set thin enabled to false by default on the out-param until we get a successful response
            virtualVolumeInfo.setThinEnabled(VPlexApiConstants.FALSE);
            s_logger.info("Requesting thin-enabled flag set to true on virtual volume " + virtualVolumeInfo.getName());
            URI requestURI = _vplexApiClient.getBaseURI().resolve(VPlexApiConstants.URI_SET_THIN_ENABLED_VIRTUAL_VOLUME);
            s_logger.info("Set thin-enabled virtual-volume URI is " + requestURI.toString());
            Map<String, String> argsMap = new HashMap<String, String>();
            argsMap.put(VPlexApiConstants.TRUE.toString(), "");
            argsMap.put(VPlexApiConstants.ARG_VIRTUAL_VOLUMES, virtualVolumeInfo.getPath());

            JSONObject postDataObject = VPlexApiUtils.createPostData(argsMap, false);
            s_logger.info("Set thin-enabled virtual-volume POST data is " + postDataObject.toString());
            response = _vplexApiClient.post(requestURI, postDataObject.toString());
            String responseStr = response.getEntity(String.class);
            s_logger.info("Set thin-enabled virtual-volume response is " + responseStr);
            if (response.getStatus() != VPlexApiConstants.SUCCESS_STATUS) {
                if (response.getStatus() == VPlexApiConstants.ASYNC_STATUS) {
                    s_logger.info("Set thin-enabled virtual-volume command completing asynchronously");
                    _vplexApiClient.waitForCompletion(response);
                } else {
                    s_logger.info("set-thin-enabled command was not successful on virtual volume " + virtualVolumeInfo.getName());
                    return false;
                }
            }
            // if it got this far, we can assume thin-enabled and thin-capable are both true
            virtualVolumeInfo.setThinEnabled(VPlexApiConstants.TRUE);
            virtualVolumeInfo.setThinCapable(VPlexApiConstants.TRUE);
            s_logger.info("Successfully executed set-thin-enabled command");
            return true;
        } catch (VPlexApiException vae) {
            throw vae;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedSettingThinEnabled(virtualVolumeInfo.getName(), e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }
}
