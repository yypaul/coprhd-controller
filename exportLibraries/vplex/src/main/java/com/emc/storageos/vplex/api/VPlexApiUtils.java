/*
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.vplex.api;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.vplex.api.clientdata.VolumeInfo;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Provides utility methods.
 */
public class VPlexApiUtils {

    // Logger reference.
    private static Logger s_logger = LoggerFactory.getLogger(VPlexApiUtils.class);

    /**
     * Transforms the raw WWN format returned by the VPlex CLI.
     * 
     * 0x1a2b3c4d5e6f7g8h -> 1A2B3C4D5E6F7G8H
     * or
     * REGISTERED_0x1a2b3c4d5e6f7g8h -> 1A2B3C4D5E6F7G8H
     * 
     * @param rawWWN The raw WWN from the VPlex CLI.
     * 
     * @return The formatted WWN.
     */
    static String formatWWN(String rawWWN) {

        if (rawWWN != null) {
            // trim off the REGISTERED_ prefix if it's present
            if (rawWWN.toUpperCase().startsWith(VPlexApiConstants.REGISTERED_INITIATOR_PREFIX)){
                rawWWN = rawWWN.substring(VPlexApiConstants.REGISTERED_INITIATOR_PREFIX.length());
            }

            return rawWWN.substring(2).toUpperCase();
        }

        return rawWWN;
    }

    /**
     * Extracts the attribute data from the passed JSON response and returns
     * the attribute data as a Map of name/value pairs.
     * 
     * @param response The response from a VPlex get request for a resource.
     * 
     * @return A map of string/value pairs containing the attribute info.
     * 
     * @throws VPlexApiException When an error occurs parsing the response.
     */
    static Map<String, Object> getAttributesFromResponse(String response)
            throws VPlexApiException {
        Map<String, Object> attributeMap = new HashMap<String, Object>();

        try {
            JSONObject jsonObj = new JSONObject(response);
            JSONObject respObj = jsonObj
                    .getJSONObject(VPlexApiConstants.RESPONSE_JSON_KEY);
            JSONArray contextArray = respObj
                    .getJSONArray(VPlexApiConstants.CONTEXT_JSON_KEY);
            for (int i = 0; i < contextArray.length(); i++) {
                JSONObject contextObj = contextArray.getJSONObject(i);
                JSONArray attributesArray = contextObj
                        .getJSONArray(VPlexApiConstants.ATTRIBUTES_JSON_KEY);
                for (int j = 0; j < attributesArray.length(); j++) {
                    JSONObject attObj = attributesArray.getJSONObject(j);
                    String attName = attObj
                            .getString(VPlexApiConstants.ATTRIBUTE_NAME_JSON_KEY);
                    Object attValue = attObj
                            .get(VPlexApiConstants.ATTRIBUTE_VALUE_JSON_KEY);
                    attributeMap.put(attName, attValue);
                }
            }
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedExtractingAttributesFromResponse(response, e);
        }

        return attributeMap;
    }

    /**
     * Extracts all children from the context objects in the passed request
     * response.
     * 
     * @param response The response from a VPlex GET request.
     * 
     * @return A list of JSONObject specifying the children.
     * 
     * @throws VPlexApiException When an error occurs processing the response.
     */
    static <T extends VPlexResourceInfo> List<T> getChildrenFromResponse(
            String baseResourcePath, String response, Class<T> clazz)
            throws VPlexApiException {
        List<T> children = new ArrayList<T>();
        try {
            JSONObject jsonObj = new JSONObject(response);
            JSONObject respObj = jsonObj.getJSONObject(VPlexApiConstants.RESPONSE_JSON_KEY);
            JSONArray contextArray = respObj.getJSONArray(VPlexApiConstants.CONTEXT_JSON_KEY);
            for (int i = 0; i < contextArray.length(); i++) {
                JSONObject contextObj = contextArray.getJSONObject(i);
                JSONArray childArray = contextObj.getJSONArray(VPlexApiConstants.CHILDREN_JSON_KEY);
                for (int j = 0; j < childArray.length(); j++) {
                    JSONObject childObj = childArray.getJSONObject(j);
                    T child = new Gson().fromJson(childObj.toString(), clazz);
                    child.setPath(baseResourcePath.substring(VPlexApiConstants.VPLEX_PATH
                            .length()) + child.getName());
                    children.add(child);
                }
            }
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedExtractingChildrenFromResponse(response, e);
        }

        return children;
    }

    /**
     * Extracts VPlexResourceInfo resource objects from a list of JSON
     * context objects returned by the VPLEX API.
     * 
     * @param baseResourcePath the REST API Path to the resource
     * @param response the response from the VPLEX API for processing
     * @param clazz the VPlexResourceInfo resource object class
     * @return a list of VPlexResourceInfo objects
     * 
     * @throws VPlexApiException
     */
    static <T extends VPlexResourceInfo> List<T> getResourcesFromResponseContext(
            String baseResourcePath, String response, Class<T> clazz)
            throws VPlexApiException {

        List<T> resources = new ArrayList<T>();
        try {
            JSONObject jsonObj = new JSONObject(response);
            JSONObject respObj = jsonObj
                    .getJSONObject(VPlexApiConstants.RESPONSE_JSON_KEY);
            JSONArray contextArray = respObj
                    .getJSONArray(VPlexApiConstants.CONTEXT_JSON_KEY);
            for (int i = 0; i < contextArray.length(); i++) {
                JSONObject contextObj = contextArray.getJSONObject(i);

                s_logger.debug("Parsing {}: {}", clazz.getName(), contextObj.toString());
                Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_DASHES).create();
                T resource = gson.fromJson(contextObj.toString(), clazz);
                resource.setPath(contextObj.getString(VPlexApiConstants.PARENT_JSON_KEY) + VPlexApiConstants.SLASH + resource.getName());
                resources.add(resource);
            }
        } catch (Exception e) {
        	s_logger.error(e.getLocalizedMessage(), e);
            throw VPlexApiException.exceptions.failedToDeserializeJsonResponse(e.getLocalizedMessage());
        }

        return resources;
    }

    /**
     * Sets the value of the attributes for the passed resource by extracting
     * the values from the passed resource request response.
     * 
     * @param responseStr The response for a resource GET request.
     * @param resourceInfo The resource whose attribute value is set.
     * 
     * @throws VPlexApiException If an error occurs setting the attribute
     *             values.
     */
    @SuppressWarnings("rawtypes")
    static void setAttributeValues(String responseStr, VPlexResourceInfo resourceInfo)
            throws VPlexApiException {
        try {
            Map<String, Object> resourceAtts = getAttributesFromResponse(responseStr);
            List<String> attributeFilters = resourceInfo.getAttributeFilters();
            Iterator<Entry<String, Object>> attsIter = resourceAtts.entrySet().iterator();
            while (attsIter.hasNext()) {
                Entry<String, Object> entry = attsIter.next();

                // Skip attributes we are not interested in.
                if ((attributeFilters != null) && (!attributeFilters.isEmpty())
                        && (!attributeFilters.contains(entry.getKey()))) {
                    continue;
                }
                Class[] parameterTypes;
                Object attValObj = entry.getValue();
                if (attValObj instanceof JSONArray) {
                    parameterTypes = new Class[] { List.class };
                    JSONArray attValArray = (JSONArray) attValObj;
                    List<String> attValList = new ArrayList<String>();
                    for (int i = 0; i < attValArray.length(); i++) {
                        attValList.add(attValArray.get(i).toString());
                    }
                    attValObj = attValList;
                } else {
                    parameterTypes = new Class[] { String.class };
                    attValObj = attValObj.toString();
                }

                String setterName = resourceInfo.getAttributeSetterMethodName(entry.getKey());
                Method m = resourceInfo.getClass().getMethod(setterName, parameterTypes);
                m.invoke(resourceInfo, attValObj);
            }
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedSettingAttributesForResource(resourceInfo.getName(), e);
        }
    }

    /**
     * Creates JSON formatted post data for passed arguments.
     * 
     * @param argsMap A map of the POST data arguments.
     * @param includeForce true to include the force argument, false otherwise.
     * 
     * @return A JSONOBject containing the POST data.
     * 
     * @throws VPlexApiException When an error occurs forming the post data.
     */
    static <T extends VPlexResourceInfo> JSONObject createPostData(
            Map<String, String> argsMap, boolean includeForce) throws VPlexApiException {
        try {
            StringBuilder argsBuilder = new StringBuilder();
            if (argsMap != null) {
                Iterator<Entry<String, String>> argsIter = argsMap.entrySet().iterator();
                while (argsIter.hasNext()) {
                    Entry<String, String> entry = argsIter.next();
                    if (argsBuilder.length() != 0) {
                        argsBuilder.append(" ");
                    }
                    argsBuilder.append(entry.getKey());
                    argsBuilder.append(" ");
                    argsBuilder.append(entry.getValue());
                }
            }

            if (includeForce) {
                argsBuilder.append(" ");
                argsBuilder.append(VPlexApiConstants.ARG_FORCE);
            }

            JSONObject postDataObject = new JSONObject();
            postDataObject.put(VPlexApiConstants.POST_DATA_ARG_KEY, argsBuilder.toString());
            return postDataObject;
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedCreatingPostDataForRequest(e);
        }
    }

    /**
     * Extracts the "custom-data" from the passed JSON response.
     * 
     * @param response The response from a VPlex request.
     * 
     * @return A string specifying the value for the custom data.
     * 
     * @throws VPlexApiException When an error occurs parsing the response.
     */
    static String getCustomDataFromResponse(String response) throws VPlexApiException {
        String customData = null;
        try {
            JSONObject jsonObj = new JSONObject(response);
            JSONObject respObj = jsonObj
                    .getJSONObject(VPlexApiConstants.RESPONSE_JSON_KEY);
            customData = respObj.get(VPlexApiConstants.CUSTOM_DATA_JSON_KEY).toString();
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedGettingCustomDataFromResponse(response, e);
        }

        return customData;
    }

    /**
     * Extracts the "exception" message from the passed JSON response.
     * 
     * @param response The response from a VPlex request.
     * 
     * @return A string specifying the value for the exception message.
     * 
     * @throws VPlexApiException When an error occurs parsing the response.
     */
    static String getExceptionMessageFromResponse(String response) throws VPlexApiException {
        String exceptionMessage = null;
        try {
            JSONObject jsonObj = new JSONObject(response);
            JSONObject respObj = jsonObj
                    .getJSONObject(VPlexApiConstants.RESPONSE_JSON_KEY);
            exceptionMessage = respObj.get(VPlexApiConstants.EXCEPTION_MSG_JSON_KEY).toString();
        } catch (Exception e) {
            throw VPlexApiException.exceptions.failedGettingExceptionMsgFromResponse(response, e);
        }

        return exceptionMessage;
    }

    /**
     * Extracts the cause returned in a failure response by the VPLEX.
     * 
     * @param response The response from the VPLEX.
     * 
     * @return The cause of the failure.
     */
    static String getCauseOfFailureFromResponse(String response) {

        // There is often multiple "cause:" in the response, and the last
        // is the one that supplies the specific details that are most useful.
        // For example, the first cause might be "Command Failed". The next
        // cause might be "Failed to supply a value for parameter --foo".
        // Then the last cause might be "Foo cannot begin with a numerical
        // value", so the parameter is supplied. It just has an invalid value.
        StringBuilder result = new StringBuilder();
        String exceptionMessage = getExceptionMessageFromResponse(response);
        if (exceptionMessage != null) {
            String[] causes = exceptionMessage.split(VPlexApiConstants.CAUSE_DELIM);
            if (causes != null && causes.length > 0) {
                String cause = causes[causes.length - 1];
                String[] lines = cause.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    result.append(line);
                    if (i < lines.length - 1) {
                        result.append(" ");
                    }
                }
            } else {
                result.append(exceptionMessage);
            }
        }
        return result.toString();
    }

    /**
     * Simple puts the thread to sleep for the passed duration.
     * 
     * @param duration How long to pause in milliseconds.
     */
    static void pauseThread(long duration) {
        try {
            Thread.sleep(duration);
        } catch (Exception e) {
            s_logger.warn("Exception while trying to sleep", e);
        }
    }
    
    /**
     * ITLs fetch is required if the backend
     * array has populated the ITLs in the VolumeInfo
     * 
     * Note : Currently Cinder is using ITLs for volume lookup
     * 
     * @param volInfo
     * @return
     */
    public static boolean isITLBasedSearch(VolumeInfo volumeInfo) {

        return !volumeInfo.getITLs().isEmpty();
    }
    
    /**
     * Determines if the passed volume name conforms to the default naming convention.
     * 
     * @param volumeName The name of the volume.
     * @param supportingDeviceName The name of the supporting device.
     * @param isDistributed true if the volume is distributed, or false for local.
     * @param claimedVolumeNames The names of the claimed storage volumes used by the virtual volumes.
     * 
     * @return true if the volume name conforms to the default naming convention, false otherwise.
     */
    public static boolean volumeHasDefaultNamingConvention(String volumeName, String supportingDeviceName,
            boolean isDistributed, List<String> claimedVolumeNames) {
        // First check that the supporting device conforms to the default naming convention.
        if (!deviceHasDefaultNamingConvention(supportingDeviceName, isDistributed, claimedVolumeNames)) {
            s_logger.info("Supporting device {} does conform to default naming convention", supportingDeviceName);
            return false;
}
        
        // The volume name must end with the expected virtual volume suffix.
        if (!volumeName.endsWith(VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX)) {
            s_logger.info("Volume {} does not end with the expected suffix", volumeName);
            return false;
        }
        
        // Verify the passed volume name is not simply the volume suffix.
        if (volumeName.length() == VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX.length()) {
            s_logger.info("Volume name {} consists only of the volume suffix", volumeName);
            return false;
        }

        // Extract the value after before the suffix, which in the default naming convention
        // is the supporting device name.
        int endIndex = volumeName.length() - VPlexApiConstants.VIRTUAL_VOLUME_SUFFIX.length();
        if (!supportingDeviceName.equals(volumeName.substring(0, endIndex))) {
            s_logger.info("Volume name {} does not conform to default naming convention", volumeName);
            return false;            
        }

        return true;
    }
    
    /**
     * Determines if the passed supporting device name conforms to the default naming convention.
     * 
     * @param supportingDeviceName The name of the supporting device.
     * @param isDistributed true if the volume is distributed, or false for local.
     * @param claimedVolumeNames The names of the claimed storage volumes used by the device.
     * 
     * @return true if the device name conforms to the default naming convention, false otherwise.
     */
    public static boolean deviceHasDefaultNamingConvention(String supportingDeviceName, boolean isDistributed, List<String> claimedVolumeNames) {
        // Verify the passed supporting device name based on whether it is distributed or local.
        if (isDistributed) {
            return distributedDeviceHasDefaultNamingConvention(supportingDeviceName, claimedVolumeNames);
        } else {
            return localDeviceHasDefaultNamingConvention(supportingDeviceName, claimedVolumeNames);
        }
    }

    /**
     * Determines if the passed local device name conforms to the default naming convention.
     * 
     * @param supportingDeviceName The name of the supporting device.
     * @param claimedVolumeNames The names of the storage volumes used by the device.
     * 
     * @return true if the device name conforms to the default naming convention, false otherwise.
     */
    public static boolean localDeviceHasDefaultNamingConvention(String deviceName, List<String> claimedVolumeNames) {
        // The local device name must start with the local device prefix.
        if (!deviceName.startsWith(VPlexApiConstants.DEVICE_PREFIX)) {
            s_logger.info("Local device {} does not start with the expected prefix", deviceName);
            return false;
        }
        
        // Verify the passed device name is not simply the device prefix.
        if (deviceName.equals(VPlexApiConstants.DEVICE_PREFIX.length())) {
            s_logger.info("Local device name {} consists only of the device prefix", deviceName);
            return false;
        }
        
        // There should only be a single claimed volume.
        if (claimedVolumeNames.size() != 1) {
            s_logger.info("Too many claimed volumes {} for local device {}", claimedVolumeNames, deviceName);
            return false;            
        }
        String claimedVolumeName = claimedVolumeNames.get(0);

        // Extract the value after the prefix, which in the default naming convention
        // is the claimed storage volume name.
        int startIndex = VPlexApiConstants.DEVICE_PREFIX.length();
        if (!claimedVolumeName.equals(deviceName.substring(startIndex))) {
            s_logger.info("Local device name {} does not conform to default naming convention", deviceName);
            return false;            
        }
        
        return true;
    }
    
    /**
     * Determines if the passed distributed device name conforms to the default naming convention.
     * 
     * @param supportingDeviceName The name of the supporting device.
     * @param claimedVolumeNames The names of the storage volumes used by the device.
     * 
     * @return true if the device name conforms to the default naming convention, false otherwise.
     */
    public static boolean distributedDeviceHasDefaultNamingConvention(String deviceName, List<String> claimedVolumeNames) {
        // The distributed device must start with the expected prefix.
        String distDevicePrefix = VPlexApiConstants.DIST_DEVICE_PREFIX + VPlexApiConstants.DIST_DEVICE_NAME_DELIM;
        if (!deviceName.startsWith(distDevicePrefix)) {
            s_logger.info("Distributed device {} does not start with the expected prefix", deviceName);
            return false;
        }

        // Verify the device name is not exactly the prefix.
        if (deviceName.equals(distDevicePrefix)) {
            s_logger.info("Distributed device {} consists only of the expected prefix", deviceName);
            return false;                
        }

        // Get the distributed device name without the prefix. This must consist of 
        // exactly two components separated by a delimiter.
        String deviceNameNoPrefix = deviceName.substring(distDevicePrefix.length());
        String[] deviceNameComponents = deviceNameNoPrefix.split(VPlexApiConstants.DIST_DEVICE_NAME_DELIM);
        if (deviceNameComponents.length != 2) {
            s_logger.info("Distributed device {} does not consist of exactly 2 components", deviceName);
            return false;             
        }
        
        // These two components must be the passed claimed storage volume names.
        boolean allComponentsMatched = true;
        for (String deviceNameComponent : deviceNameComponents) {
            boolean match = false;
            for (String claimedVolumeName : claimedVolumeNames)
                if (deviceNameComponent.equals(claimedVolumeName)) {
                    match = true;
                    break;
            }
            if (!match) {
                allComponentsMatched = false;
                break;
            }
        }
        if (!allComponentsMatched) {
            s_logger.info("Distributed device name {} does not contain claimed volumes {}", deviceName, claimedVolumeNames);
            return false;                
        }
        
        return true;
    }
}
