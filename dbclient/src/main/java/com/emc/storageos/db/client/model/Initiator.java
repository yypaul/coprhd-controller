/*
 * Copyright (c) 2012 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.db.client.model;

import com.emc.storageos.db.client.util.EndpointUtility;
import com.emc.storageos.db.client.util.WWNUtility;
import com.emc.storageos.db.client.util.iSCSIUtility;

/**
 * SCSI initiator in either a Fiber Channel or iSCSI SAN.
 */
@Cf("Initiator")
public class Initiator extends HostInterface implements Comparable<Initiator> {

    private String _port;
    private String _node;
    // to do - This is temporary until initiator service is remove
    private String _hostName;
    // to do - This is temporary until initiator service is remove
    private String _clusterName;
    // Lazily initialized, cached hashCode
    private volatile int hashCode;
    // COP-18937: Initiator may be registered to multiple storage systems using different names. XIO Arrays
    // COP-18551: Initiator may be registered to multiple storage systems using different Aliases. VMAX Arrays
    private StringMap initiatorNames;

    /**
     * Default Constructor. This is the constructor used by the API.
     */
    public Initiator() {
        setIsManualCreation(true);
    }

    /**
     * Constructor.
     *
     * @param protocol The initiator port protocol.
     * @param port The initiator port identifier.
     * @param node The initiator node identifier.
     * @param hostName The FQDN of the initiator host.
     * @param isManualCreation the flag that indicates if the initiator is user or system created
     */
    public Initiator(String protocol, String port, String node, String hostName, boolean isManualCreation) {
        super(null, protocol);
        setInitiatorPort(port);
        setInitiatorNode(node == null ? "" : node);
        setHostName(hostName == null ? "" : hostName);
        setIsManualCreation(isManualCreation);
    }

    /**
     * Constructor supports setting of optional cluster name.
     *
     * @param protocol The initiator port protocol.
     * @param port The initiator port identifier.
     * @param node The initiator node identifier.
     * @param hostName The FQDN of the initiator host.
     * @param clusterName The FQDN of the cluster.
     * @param isManualCreation the flag that indicates if the initiator is user or system created
     */
    public Initiator(String protocol, String port, String node, String hostName,
            String clusterName, boolean isManualCreation) {
        super(null, protocol);
        setInitiatorPort(port);
        setInitiatorNode(node == null ? "" : node);
        setHostName(hostName == null ? "" : hostName);
        setClusterName(clusterName == null ? "" : clusterName);
        setIsManualCreation(isManualCreation);
    }

    /**
     * Getter for the initiator port identifier. For FC, this is the port WWN.
     * For iSCSI, this is port name in IQN or EUI format.
     *
     * @return The initiator port identifier.
     */
    @Name("iniport")
    @AlternateId("InitiatorPortIndex")
    public String getInitiatorPort() {
        return _port;
    }

    /**
     * Setter for the initiator port identifier.
     *
     * @param port The initiator port identifier.
     */
    public void setInitiatorPort(String port) {
        _port = EndpointUtility.changeCase(port);
        setChanged("iniport");
    }

    /**
     * Getter for the initiator node identifier. For FC, this is the node WWN.
     * For iSCSI, this field is optional.
     *
     * @return The initiator node identifier.
     */
    @Name("ininode")
    public String getInitiatorNode() {
        return _node;
    }

    /**
     * Setter for the initiator node identifier.
     *
     * @param node The initiator node identifier.
     */
    public void setInitiatorNode(String node) {
        _node = EndpointUtility.changeCase(node);
        setChanged("ininode");
    }

    /**
     * Getter for the FQDN of the initiator host.
     * to do - This is temporary until initiator service is remove
     *
     * @return The FQDN of the initiator host.
     */
    @AlternateId("AltIdIndex")
    @Name("hostname")
    public String getHostName() {
        return _hostName;
    }

    /**
     * Setter for the FQDN of the initiator host.
     * to do - This is temporary until initiator service is remove
     *
     * @param hostName The FQDN of the initiator host.
     */
    public void setHostName(String hostName) {
        _hostName = hostName;
        setChanged("hostname");
    }

    /**
     * Getter for the FQDN of the initiator cluster.
     * to do - This is temporary until initiator service is remove
     *
     * @return The FQDN of the initiator cluster or null if not applicable.
     */
    @Name("clustername")
    public String getClusterName() {
        return _clusterName;
    }

    /**
     * Setter for the FQDN of the initiator cluster.
     * to do - This is temporary until initiator service is remove
     *
     * @param clusterName The FQDN of the initiator cluster.
     */
    public void setClusterName(String clusterName) {
        _clusterName = clusterName;
        setChanged("clustername");
    }

    /**
     * Getter for the initiator names
     *
     * @return Map of storage system serial number to initiator name
     */
    @Name("initiatorNames")
    public StringMap getInitiatorNames() {
        if (initiatorNames == null) {
            initiatorNames = new StringMap();
        }
        return initiatorNames;
    }

    /**
     * Setter for the initiatorNames
     *
     * @param initiatorNames - map of storage system to initiator name
     */
    public void setInitiatorNames(StringMap initiatorNames) {
        this.initiatorNames = initiatorNames;
    }

    /**
     * Map the initiator name to the storage system
     *
     * @param storageSystemSerialNumber storage system serial number
     * @param initiatorName initiator name for the storage system
     */
    public void mapInitiatorName(String storageSystemSerialNumber, String initiatorName) {
        if (storageSystemSerialNumber != null && initiatorName != null && !initiatorName.isEmpty()) {
            getInitiatorNames().put(storageSystemSerialNumber, initiatorName);
        }
    }

    /**
     * Unmap the initiator name for the storage system (remove it from initiator names).
     *
     * @param storageSystemSerialNumber storage system serial number
     */
    public void unmapInitiatorName(String storageSystemSerialNumber) {
        if (storageSystemSerialNumber != null && !storageSystemSerialNumber.isEmpty()) {
            getInitiatorNames().remove(storageSystemSerialNumber);
        }
    }

    /**
     * Get the initiator name for the given storage system if present.
     * If there is no mapping for the storage system, return the initiator label.
     *
     * @param storageSystemSerialNumber
     * @return initiator name for the storage system if present or the label
     */
    public String getMappedInitiatorName(String storageSystemSerialNumber) {
        String initiatorName = getInitiatorNames().get(storageSystemSerialNumber);
        return initiatorName != null ? initiatorName : getLabel();
    }

    @Override
    public final String toString() {
        return String.format(
                "Initiator(Protocol:%s, Node:%s, Port:%s, Host Name: %s, Cluster Name: %s)",
                getProtocol(), getInitiatorNode(), getInitiatorPort(), getHostName(),
                getClusterName());
    }

    static public String normalizePort(String port) {
        String normalizedPort = port;
        if (WWNUtility.isValidWWN(port)) {
            normalizedPort = WWNUtility.getUpperWWNWithNoColons(port);
        } else if (iSCSIUtility.isValidIQNPortName(port)) {
            normalizedPort = normalizedPort.toLowerCase();
        }
        return normalizedPort;
    }

    static public String toPortNetworkId(String port) {
        String portNetworkId = port;
        if (port.startsWith("iqn")) {
            // iSCSI port may have some other values after the port name (e.g.,
            // iqn.1992-04.com.emc:cx.apm00121500018.b9,t,0x0001).
            // Exclude the extraneous parts to arrive at
            // iqn.1992-04.com.emc:cx.apm00121500018.b9
            int firstComma = port.indexOf(',');
            if (firstComma != -1) {
                portNetworkId = port.substring(0, firstComma).toLowerCase();
            }
        } else if (!WWNUtility.isValidWWN(port)) {
            portNetworkId = WWNUtility.getWWNWithColons(port);
        }
        return portNetworkId;
    }

    @Override
    public Object[] auditParameters() {
        return new Object[] { getInitiatorPort(), getInitiatorNode(),
                getHost(), getId() };
    }

    @Override
    public int compareTo(Initiator that) {
        if (this == that) {
            return 0;
        }

        if (this.equals(that)) {
            return 0;
        }

        return this.getInitiatorPort().compareTo(that.getInitiatorPort());
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Initiator)) {
            return false;
        }

        if (this == object) {
            return true;
        }

        Initiator that = (Initiator) object;
        if (!this._id.equals(that._id)) {
            return false;
        }

        String thisPort = this.getInitiatorPort();
        String thatPort = that.getInitiatorPort();
        return thisPort.equals(thatPort);
    }

    @Override
    public int hashCode() {
        int result = hashCode;
        if (result == 0) {
            result = 17;
            result = 31 * result + _id.hashCode();
            result = 31 * result + this.getInitiatorPort().hashCode();
            hashCode = result;
        }
        return result;
    }

    @Override
    public String forDisplay() {
        return this.toString();
    }
}
