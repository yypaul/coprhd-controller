package com.emc.storageos.api.service.impl.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.service.impl.resource.utils.StorageSystemTypeServiceUtils;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.StorageSystemType;
import com.emc.storageos.model.ResourceTypeEnum;
import com.emc.storageos.model.storagesystem.type.StorageSystemTypeAddParam;
import com.emc.storageos.model.storagesystem.type.StorageSystemTypeList;
import com.emc.storageos.model.storagesystem.type.StorageSystemTypeRestRep;
import com.emc.storageos.security.audit.AuditLogManager;
import com.emc.storageos.security.authorization.CheckPermission;
import com.emc.storageos.security.authorization.DefaultPermissions;
import com.emc.storageos.security.authorization.Role;
import com.emc.storageos.services.OperationTypeEnum;
import com.google.common.collect.Lists;

//import util.StringOption;

import static java.util.Arrays.asList;
import static com.emc.storageos.api.mapper.SystemsMapper.map;

/**
 * StorageSystemTypes resource implementation
 */
@Path("/storagesystemtype")
@DefaultPermissions(readRoles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR }, writeRoles = { Role.SYSTEM_ADMIN,
		Role.RESTRICTED_SYSTEM_ADMIN })

public class StorageSystemTypeService extends TaskResourceService {

	private static final Logger log = LoggerFactory.getLogger(StorageSystemTypeService.class);
	private static final String EVENT_SERVICE_TYPE = "StorageSystemTypeService";

//	private static final String ISILON = "isilon";
//	private static final String VNX_BLOCK = "vnxblock";
//	private static final String VNXe = "vnxe";
//	private static final String VNX_FILE = "vnxfile";
//	private static final String VMAX = "smis";
//	private static final String NETAPP = "netapp";
//	private static final String NETAPPC = "netappc";
//	private static final String HITACHI = "hds";
//	private static final String IBMXIV = "ibmxiv";
//	private static final String VPLEX = "vplex";
//	private static final String OPENSTACK = "openstack";
//	private static final String SCALEIO = "scaleio";
//	private static final String SCALEIOAPI = "scaleioapi";
//	private static final String XTREMIO = "xtremio";
//	private static final String DATA_DOMAIN = "datadomain";
//	private static final String ECS = "ecs";

	/**
	 * Show compute image attribute.
	 *
	 * @param id
	 *            the URN of compute image
	 * @brief Show compute image
	 * @return Compute image details
	 */
	@GET
	@Path("/{id}")
	@Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
	public StorageSystemTypeRestRep getStorageSystemType(@PathParam("id") URI id) {
		if (!checkForStorageSystemType()) {
			StorageSystemTypeServiceUtils.InitializeStorageSystemTypes(_dbClient); // addDefaultStorageSystemTypes();
		}
		ArgValidator.checkFieldUriType(id, StorageSystemType.class, "id");
		StorageSystemType storageType = queryResource(id);
		ArgValidator.checkEntity(storageType, id, isIdEmbeddedInURL(id));
		StorageSystemTypeRestRep storageTypeRest = new StorageSystemTypeRestRep();
		return map(storageType, storageTypeRest);
	}

	/**
	 * Returns a list of all compute images.
	 *
	 * @brief Show compute images
	 * @return List of all compute images.
	 */
	@GET
	@Path("/type/{type_name}")
	@Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
	@CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR })
	public StorageSystemTypeList getStorageSystemTypeType(@PathParam("storageType") String storageType) {
		log.info("GET getStorageSystemType on type: " + storageType);
		if (!checkForStorageSystemType()) {
			StorageSystemTypeServiceUtils.InitializeStorageSystemTypes(_dbClient);
		}
		// validate query param
		if (storageType != null) {
			ArgValidator.checkFieldValueFromEnum(storageType, "storageType", StorageSystemType.StorageType.class);
		}

		List<URI> ids = _dbClient.queryByType(StorageSystemType.class, true);

		StorageSystemTypeList list = new StorageSystemTypeList();

		Iterator<StorageSystemType> iter = _dbClient.queryIterativeObjects(StorageSystemType.class, ids);
		while (iter.hasNext()) {
			StorageSystemType ssType = iter.next();
			if (ssType.getStorageTypeId() == null) {
				ssType.setStorageTypeId(ssType.getId().toString());
			}
			if (storageType == null || storageType.equals(ssType.getStorageTypeType())) {
				list.getStorageSystemTypes().add(map(ssType));
			}
		}
		return list;
	}

	/**
	 * Create compute image from image URL or existing installable image URN.
	 *
	 * @param param
	 *            The ComputeImageCreate object contains all the parameters for
	 *            creation.
	 * @brief Create compute image
	 * @return Creation task REST representation.
	 */
	@POST
	@Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
	@Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
	@CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
	public StorageSystemTypeRestRep addStorageSystemType(StorageSystemTypeAddParam addparam) {
		log.info("addStorageSystemType");
		if (!checkForStorageSystemType()) {
			StorageSystemTypeServiceUtils.InitializeStorageSystemTypes(_dbClient);
		}
		// unique name required
		ArgValidator.checkFieldNotEmpty(addparam.getStorageTypeName(), "name");
		checkDuplicateLabel(StorageSystemType.class, addparam.getStorageTypeName());

		ArgValidator.checkFieldNotEmpty(addparam.getStorageTypeId(), "id");
		ArgValidator.checkUrl(addparam.getStorageTypeId(), "id");

		StorageSystemType ssType = new StorageSystemType();
		ssType.setId(URIUtil.createId(StorageSystemType.class));

		ssType.setStorageTypeName(addparam.getStorageTypeName());
		ssType.setStorageTypeType(addparam.getStorageTypeType());
		ssType.setIsSmiProvider(addparam.getIsSmiProvider());
		
		// ALIK 
		//Need to add all other paramaetrs here
		
		_dbClient.createObject(ssType);

		auditOp(OperationTypeEnum.ADD_STORAGE_SYSTEM_TYPE, true, AuditLogManager.AUDITOP_BEGIN,
				ssType.getId().toString(), ssType.getStorageTypeName(), ssType.getStorageTypeType());
		return map(ssType);
	}

	/**
	 * Delete existing Storage System Type.
	 *
	 * @param id
	 * 
	 * @brief Delete Storage System Type.
	 * @return No data returned in response body
	 */
	@POST
	@Path("/{id}/deactivate")
	@Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
	@Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
	@CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
	public Response deleteStorageSystemType(@PathParam("id") URI id) {
		log.info("deleteStorageSystemType: {}", id);

		StorageSystemType sstype = queryObject(StorageSystemType.class, id, true);
		ArgValidator.checkEntity(sstype, id, isIdEmbeddedInURL(id));

		_dbClient.markForDeletion(sstype);

		auditOp(OperationTypeEnum.REMOVE_STORAGE_SYSTEM_TYPE, true, AuditLogManager.AUDITOP_BEGIN,
				sstype.getId().toString(), sstype.getStorageTypeName(), sstype.getStorageTypeType());
		return Response.ok().build();

	}

	@Override
	public String getServiceType() {
		return EVENT_SERVICE_TYPE;
	}

	@Override
	protected StorageSystemType queryResource(URI id) {
		ArgValidator.checkUri(id);
		StorageSystemType storageType = _dbClient.queryObject(StorageSystemType.class, id);
		ArgValidator.checkEntity(storageType, id, isIdEmbeddedInURL(id));
		return storageType;
	}

	/**
	 * Check if there are Storage System Type, if not we should initialized
	 */
	private boolean checkForStorageSystemType() {
		boolean storageTypeExist = true;
		List<URI> storageTypes = _dbClient.queryByType(StorageSystemType.class, true);

		ArrayList<URI> tempList = Lists.newArrayList(storageTypes.iterator());

		if (tempList.isEmpty()) {
			storageTypeExist = false;
		}
		return storageTypeExist;
	}

	@Override
	protected ResourceTypeEnum getResourceType() {
		// TODO Auto-generated method stub
		return ResourceTypeEnum.STORAGE_SYSTEM_TYPE;
	}

	@Override
	protected URI getTenantOwner(URI id) {
		// TODO Auto-generated method stub
		return null;
	}

	private void addDefaultStorageSystemTypes() {
		// // Default File arrays
		// List<String> storageArrayFile = asList(VNX_FILE, ISILON, NETAPP,
		// NETAPPC);
		//
		// // Default Provider for File
		// List<String> storageProviderFile = asList(SCALEIOAPI);
		//
		// // Default block arrays
		// List<String> storageArrayBlock = asList(VNX_BLOCK, VNXe);
		//
		// // Default Storage provider for Block
		// List<String> storageProviderBlock = asList(VMAX, HITACHI, VPLEX,
		// OPENSTACK, SCALEIO, DATA_DOMAIN, IBMXIV,
		// XTREMIO);
		//
		// // Default object arrays
		// List<String> storageArrayObject = asList(ECS);
		//
		// // Default SSL providers
		// HashMap<String, Boolean> defaultSSL = new HashMap<String, Boolean>();
		// // VNX_BLOCK,
		// // VMAX,
		// // SCALEIOAPI,
		// // VPLEX,
		// // VNX_FILE,
		// // VNXe,
		// // IBMXIV
		// defaultSSL.put(VNX_BLOCK, true);
		// defaultSSL.put(VMAX, true);
		// defaultSSL.put(SCALEIOAPI, true);
		// defaultSSL.put(VPLEX, true);
		// defaultSSL.put(VNX_FILE, true);
		// defaultSSL.put(VNXe, true);
		// defaultSSL.put(IBMXIV, true);
		//
		// // MDM_DEFAULT_OPTIONS = StringOption.options(new String[] { SCALEIO,
		// // SCALEIOAPI });
		// HashMap<String, Boolean> defaultMDM = new HashMap<String, Boolean>();
		// defaultMDM.put(SCALEIO, true);
		// defaultMDM.put(SCALEIOAPI, true);
		//
		// // MDM_ONLY_OPTIONS = StringOption.options(new String[]
		// {SCALEIOAPI});
		// HashMap<String, Boolean> onlyMDM = new HashMap<String, Boolean>();
		// onlyMDM.put(SCALEIOAPI, true);
		//
		// // ELEMENT_MANAGER_OPTIONS = StringOption.options(new String[] {
		// SCALEIO
		// // });
		// HashMap<String, Boolean> elementManager = new HashMap<String,
		// Boolean>();
		// elementManager.put(SCALEIO, true);
		//
		// // Name of Array and its Display Name mapping
		// HashMap<String, String> nameDisplayNameMap = new HashMap<String,
		// String>();
		// nameDisplayNameMap.put(VNX_FILE, "EMC VNX File");
		// nameDisplayNameMap.put(ISILON, "EMC Isilon");
		// nameDisplayNameMap.put(NETAPP, "NetApp 7-mode");
		// nameDisplayNameMap.put(NETAPPC, "NetApp Cluster-mode");
		// nameDisplayNameMap.put(SCALEIOAPI, "ScaleIO Gateway");
		// nameDisplayNameMap.put(VNX_BLOCK, "EMC VNX Block");
		// nameDisplayNameMap.put(VNXe, "EMC VNXe");
		// nameDisplayNameMap.put(VMAX, "Storage Provider for EMC VMAX or VNX
		// Block");
		// nameDisplayNameMap.put(HITACHI, "Storage Provider for Hitachi storage
		// systems");
		// nameDisplayNameMap.put(VPLEX, "Storage Provider for EMC VPLEX");
		// nameDisplayNameMap.put(OPENSTACK, "Storage Provider for Third-party
		// block storage systems");
		// nameDisplayNameMap.put(SCALEIO, "Block Storage Powered by ScaleIO");
		// nameDisplayNameMap.put(DATA_DOMAIN, "Storage Provider for Data Domain
		// Management Center");
		// nameDisplayNameMap.put(IBMXIV, "Storage Provider for IBM XIV");
		// nameDisplayNameMap.put(XTREMIO, "Storage Provider for EMC XtremIO");
		// nameDisplayNameMap.put(ECS, "EMC Elastic Cloud Storage");
		//
		// for (String file : storageArrayFile) {
		// StorageSystemType ssType = new StorageSystemType();
		// URI ssTyeUri = URIUtil.createId(StorageSystemType.class);
		// ssType.setId(ssTyeUri);
		// ssType.setStorageTypeId(ssTyeUri.toString());
		// ssType.setStorageTypeName(file);
		// ssType.setStorageTypeDispName(nameDisplayNameMap.get(file));
		// ssType.setStorageTypeType("file");
		// ssType.setIsSmiProvider(false);
		//
		// if (defaultSSL.get(file) != null ) {
		// ssType.setIsDefaultSsl(true);
		// }
		//
		// if (defaultMDM.get(file) != null ) {
		// ssType.setIsDefaultMDM(true);
		// }
		// if (onlyMDM.get(file) != null ) {
		// ssType.setIsOnlyMDM(true);
		// }
		// if (elementManager.get(file) != null ) {
		// ssType.setIsElementMgr(true);
		// }
		//
		// _dbClient.createObject(ssType);
		// }
		//
		// for (String file : storageProviderFile) {
		// StorageSystemType ssType = new StorageSystemType();
		// URI ssTyeUri = URIUtil.createId(StorageSystemType.class);
		// ssType.setId(ssTyeUri);
		// ssType.setStorageTypeId(ssTyeUri.toString());
		// ssType.setStorageTypeName(file);
		// ssType.setStorageTypeDispName(nameDisplayNameMap.get(file));
		// ssType.setStorageTypeType("file");
		// ssType.setIsSmiProvider(true);
		// if (defaultSSL.get(file) != null ) {
		// ssType.setIsDefaultSsl(true);
		// }
		// if (defaultMDM.get(file) != null ) {
		// ssType.setIsDefaultMDM(true);
		// }
		// if (onlyMDM.get(file) != null ) {
		// ssType.setIsOnlyMDM(true);
		// }
		// if (elementManager.get(file) != null ) {
		// ssType.setIsElementMgr(true);
		// }
		//
		// _dbClient.createObject(ssType);
		// }
		//
		// for (String block : storageArrayBlock) {
		// StorageSystemType ssType = new StorageSystemType();
		// URI ssTyeUri = URIUtil.createId(StorageSystemType.class);
		// ssType.setId(ssTyeUri);
		// ssType.setStorageTypeId(ssTyeUri.toString());
		// ssType.setStorageTypeName(block);
		// ssType.setStorageTypeDispName(nameDisplayNameMap.get(block));
		// ssType.setStorageTypeType("block");
		// ssType.setIsSmiProvider(false);
		// if (defaultSSL.get(block) != null ) {
		// ssType.setIsDefaultSsl(true);
		// }
		// if (defaultMDM.get(block) != null ) {
		// ssType.setIsDefaultMDM(true);
		// }
		// if (onlyMDM.get(block) != null ) {
		// ssType.setIsOnlyMDM(true);
		// }
		// if (elementManager.get(block) != null ) {
		// ssType.setIsElementMgr(true);
		// }
		//
		// _dbClient.createObject(ssType);
		// }
		//
		// for (String block : storageProviderBlock) {
		// StorageSystemType ssType = new StorageSystemType();
		// URI ssTyeUri = URIUtil.createId(StorageSystemType.class);
		// ssType.setId(ssTyeUri);
		// ssType.setStorageTypeId(ssTyeUri.toString());
		// ssType.setStorageTypeName(block);
		// ssType.setStorageTypeDispName(nameDisplayNameMap.get(block));
		// ssType.setStorageTypeType("block");
		// ssType.setIsSmiProvider(true);
		// if (defaultSSL.get(block) != null ) {
		// ssType.setIsDefaultSsl(true);
		// }
		// if (defaultMDM.get(block) != null ) {
		// ssType.setIsDefaultMDM(true);
		// }
		// if (onlyMDM.get(block) != null ) {
		// ssType.setIsOnlyMDM(true);
		// }
		// if (elementManager.get(block) != null ) {
		// ssType.setIsElementMgr(true);
		// }
		//
		// _dbClient.createObject(ssType);
		// }
		//
		// for (String object : storageArrayObject) {
		// StorageSystemType ssType = new StorageSystemType();
		// URI ssTyeUri = URIUtil.createId(StorageSystemType.class);
		// ssType.setId(ssTyeUri);
		// ssType.setStorageTypeId(ssTyeUri.toString());
		// ssType.setStorageTypeName(object);
		// ssType.setStorageTypeDispName(nameDisplayNameMap.get(object));
		// ssType.setStorageTypeType("object");
		// ssType.setIsSmiProvider(false);
		// if (defaultSSL.get(object) != null ) {
		// ssType.setIsDefaultSsl(true);
		// }
		// if (defaultMDM.get(object) != null ) {
		// ssType.setIsDefaultMDM(true);
		// }
		// if (onlyMDM.get(object) != null ) {
		// ssType.setIsOnlyMDM(true);
		// }
		// if (elementManager.get(object) != null ) {
		// ssType.setIsElementMgr(true);
		// }
		//
		// _dbClient.createObject(ssType);
		// }
	}
}
