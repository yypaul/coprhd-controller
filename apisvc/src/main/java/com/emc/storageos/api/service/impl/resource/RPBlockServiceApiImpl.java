/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.api.service.impl.resource;

import static com.emc.storageos.api.mapper.DbObjectMapper.toNamedRelatedResource;
import static com.emc.storageos.api.mapper.TaskMapper.toTask;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import com.emc.storageos.Controller;
import com.emc.storageos.api.service.authorization.PermissionsHelper;
import com.emc.storageos.api.service.impl.placement.RecoverPointScheduler;
import com.emc.storageos.api.service.impl.placement.RecoverPointScheduler.SwapContainer;
import com.emc.storageos.api.service.impl.placement.StorageScheduler;
import com.emc.storageos.api.service.impl.placement.VirtualPoolUtil;
import com.emc.storageos.api.service.impl.placement.VpoolUse;
import com.emc.storageos.api.service.impl.resource.fullcopy.BlockFullCopyManager;
import com.emc.storageos.api.service.impl.resource.utils.BlockServiceUtils;
import com.emc.storageos.api.service.impl.resource.utils.CapacityCalculatorFactory;
import com.emc.storageos.api.service.impl.resource.utils.RPVPlexMigration;
import com.emc.storageos.api.service.impl.resource.utils.VirtualPoolChangeAnalyzer;
import com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationController;
import com.emc.storageos.blockorchestrationcontroller.VolumeDescriptor;
import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.coordinator.client.service.InterProcessLockHolder;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.AutoTieringPolicy;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockConsistencyGroup.Types;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.BlockSnapshot.TechnologyType;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.DataObject.Flag;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.Migration;
import com.emc.storageos.db.client.model.NamedURI;
import com.emc.storageos.db.client.model.OpStatusMap;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.ProtectionSet;
import com.emc.storageos.db.client.model.ProtectionSystem;
import com.emc.storageos.db.client.model.RPSiteArray;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.Task;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.VirtualPool.MetroPointType;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.Volume.PersonalityTypes;
import com.emc.storageos.db.client.model.VolumeGroup;
import com.emc.storageos.db.client.model.VpoolProtectionVarraySettings;
import com.emc.storageos.db.client.model.util.BlockConsistencyGroupUtils;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.client.util.ResourceOnlyNameGenerator;
import com.emc.storageos.db.client.util.SizeUtil;
import com.emc.storageos.db.common.DependencyChecker;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.model.ResourceTypeEnum;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.TaskResourceRep;
import com.emc.storageos.model.application.VolumeGroupUpdateParam.VolumeGroupVolumeList;
import com.emc.storageos.model.block.VirtualPoolChangeParam;
import com.emc.storageos.model.block.VolumeCreate;
import com.emc.storageos.model.block.VolumeDeleteTypeEnum;
import com.emc.storageos.model.systems.StorageSystemConnectivityList;
import com.emc.storageos.model.systems.StorageSystemConnectivityRestRep;
import com.emc.storageos.model.vpool.VirtualPoolChangeOperationEnum;
import com.emc.storageos.protectioncontroller.RPController;
import com.emc.storageos.protectioncontroller.impl.recoverpoint.RPHelper;
import com.emc.storageos.recoverpoint.exceptions.RecoverPointException;
import com.emc.storageos.recoverpoint.impl.RecoverPointClient.RecoverPointCGCopyType;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.svcs.errorhandling.resources.InternalServerErrorException;
import com.emc.storageos.util.ConnectivityUtil;
import com.emc.storageos.util.ConnectivityUtil.StorageSystemType;
import com.emc.storageos.util.VPlexUtil;
import com.emc.storageos.volumecontroller.ApplicationAddVolumeList;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.RPProtectionRecommendation;
import com.emc.storageos.volumecontroller.RPRecommendation;
import com.emc.storageos.volumecontroller.Recommendation;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.smis.SmisConstants;
import com.emc.storageos.volumecontroller.impl.utils.ControllerOperationValuesWrapper;
import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Table;

/**
 * Block Service subtask (parts of larger operations) RecoverPoint implementation.
 */
public class RPBlockServiceApiImpl extends AbstractBlockServiceApiImpl<RecoverPointScheduler> {
    private static final String NEW_LINE = "%n-------------------------------------------------%n";

    private static final Logger _log = LoggerFactory.getLogger(RPBlockServiceApiImpl.class);

    protected final static String CONTROLLER_SVC = "controllersvc";
    protected final static String CONTROLLER_SVC_VER = "1";

    private static final String VIRTUAL_ARRAYS_CONSTRAINT = "virtualArrays";
    private static final String UNKNOWN_VOL_TYPE = "unknown";

    private static final String SRC_COPY_SUFFIX = " - Original Production";
    private static final String MP_ACTIVE_COPY_SUFFIX = " - Active Production";
    private static final String MP_STANDBY_COPY_SUFFIX = " - Standby Production";

    private static final String VOLUME_TYPE_TARGET = "-target-";
    private static final int LOCK_WAIT_MILLISECONDS = 60000;
    private static final String JOURNAL_RSET = "rp_journal";

    // Spring injected
    private RPHelper _rpHelper;

    public void setRpHelper(RPHelper rpHelper) {
        _rpHelper = rpHelper;
    }

    // Spring injected
    protected VPlexBlockServiceApiImpl vplexBlockServiceApiImpl;

    public VPlexBlockServiceApiImpl getVplexBlockServiceApiImpl() {
        return vplexBlockServiceApiImpl;
    }

    public void setVplexBlockServiceApiImpl(VPlexBlockServiceApiImpl vplexBlockServiceApiImpl) {
        this.vplexBlockServiceApiImpl = vplexBlockServiceApiImpl;
    }

    // Spring injected
    protected CoordinatorClient _coordinator;

    @Override
    public void setCoordinator(CoordinatorClient locator) {
        _coordinator = locator;
    }

    // TODO BBB - remove? it's on the parent
    @Autowired
    protected PermissionsHelper _permissionsHelper = null;

    // TODO BBB - remove? it's on the parents
    @Autowired
    protected DependencyChecker _dependencyChecker;

    @Autowired
    protected CapacityCalculatorFactory capacityCalculatorFactory;

    public RPBlockServiceApiImpl() {
        super(DiscoveredDataObject.Type.rp.name());
    }

    /**
     * Looks up controller dependency for given hardware
     *
     * @param clazz
     *            controller interface
     * @param hw
     *            hardware name
     * @param <T>
     * @return
     */
    @Override
    protected <T extends Controller> T getController(Class<T> clazz, String hw) {
        return _coordinator.locateService(clazz, CONTROLLER_SVC, CONTROLLER_SVC_VER, hw, clazz.getSimpleName());
    }

    /**
     * Recommendations for change vpool
     *
     * @param changeVpoolVolume
     *            Volume to be moved
     * @param newVpool
     *            The new vpool
     * @param vpoolChangeParam
     *            The change vpool param
     * @return List of recommendations for change vpool
     */
    private List<Recommendation> getRecommendationsForVirtualPoolChangeRequest(Volume changeVpoolVolume, VirtualPool newVpool,
            VirtualPoolChangeParam vpoolChangeParam, VirtualPoolCapabilityValuesWrapper capabilities) {
        Project project = _dbClient.queryObject(Project.class, changeVpoolVolume.getProject());

        List<Recommendation> recommendations = null;
        if (changeVpoolVolume.checkForRp()) {
            recommendations = getBlockScheduler().scheduleStorageForVpoolChangeProtected(changeVpoolVolume, newVpool, RecoverPointScheduler
                    .getProtectionVirtualArraysForVirtualPool(project, newVpool, _dbClient, super.getPermissionsHelper()));
        } else {
            VirtualArray varray = _dbClient.queryObject(VirtualArray.class, changeVpoolVolume.getVirtualArray());
            recommendations = getBlockScheduler().getRecommendationsForResources(varray, project, newVpool, capabilities);
        }

        return recommendations;
    }

    /**
     * Prepare Recommended Volumes for Protected scenarios only.
     *
     * This method is responsible for acting the same as the unprotected "prepareRecommendedVolumes" call,
     * however it needs to create multiple volumes per single volume requests in order to generate protection.
     *
     * Those most typical scenario is, that for any one volume requested in a CRR configuration, we create:
     * 1. One Source Volume
     * 2. One Source Journal Volume (minimum 10GB, otherwise 2.5X source size)
     * 3. One Target Volume on protection varray
     * 4. One Target Journal Volume on protection varray
     *
     * In a CLR configuration, there are additional volumes created for the Local Target and Local Target Journal.
     *
     * This method will assemble a ProtectionSet object in Cassandra that will describe the Protection that
     * will be created on the Protection System.
     *
     * When other protection mechanisms come on board, the RP-ness of this method will need to be pulled out.
     *
     * @param param volume create request
     * @param task task from request or generated
     * @param taskList task list
     * @param project project from request
     * @param originalVarray varray from request
     * @param originalVpool vpool from request
     * @param numberOfVolumesInRequest volume count from the request
     * @param recommendations list of resulting recommendations from placement
     * @param consistencyGroup consistency group ID
     * @param capabilities Capabilities object
     * @param descriptors List of descriptors to be populated
     * @param volumeURIs List to hold volumes that have been prepared
     */
    private void prepareRecommendedVolumes(VolumeCreate param, String task, TaskList taskList,
            Project project, VirtualArray originalVarray, VirtualPool originalVpool, Integer numberOfVolumesInRequest,
            List<Recommendation> recommendations, String volumeLabel, VirtualPoolCapabilityValuesWrapper capabilities,
            List<VolumeDescriptor> descriptors, List<URI> volumeURIs) throws APIException {
        boolean isChangeVpool = false;
        boolean isChangeVpoolForProtectedVolume = false;
        boolean isSrcAndHaSwapped = VirtualPool.isRPVPlexProtectHASide(originalVpool);
        boolean metroPointEnabled = VirtualPool.vPoolSpecifiesMetroPoint(originalVpool);

        // This copy of capabilities object is meant to be used by all volume prepares that require changing data,
        // which is our case is TARGET and JOURNALS. SOURCE will use always use the main capabilities object.
        VirtualPoolCapabilityValuesWrapper copyOfCapabilities = new VirtualPoolCapabilityValuesWrapper(capabilities);

        // Set the volume name from the param
        String volumeName = volumeLabel;

        // Need to check if we should swap src and ha, call the block scheduler code to
        // find out. Nothing will be changed for MetroPoint.
        VirtualArray haVarray = null;
        VirtualPool haVpool = null;
        SwapContainer container = this.getBlockScheduler().new SwapContainer();
        container.setSrcVarray(originalVarray);
        container.setSrcVpool(originalVpool);
        container.setHaVarray(haVarray);
        container.setHaVpool(haVpool);
        container = RecoverPointScheduler.initializeSwapContainer(container, _dbClient);

        // Use the new references post swap
        VirtualArray varray = container.getSrcVarray();
        VirtualPool vpool = container.getSrcVpool();

        // Save a reference to the CG, we'll need this later
        BlockConsistencyGroup consistencyGroup = capabilities.getBlockConsistencyGroup() == null ? null
                : _dbClient.queryObject(BlockConsistencyGroup.class, capabilities.getBlockConsistencyGroup());

        // Total volumes to be created
        int totalVolumeCount = 0;

        // Create an entire Protection object for each recommendation result.
        Iterator<Recommendation> recommendationsIter = recommendations.iterator();

        while (recommendationsIter.hasNext()) {
            RPProtectionRecommendation rpProtectionRec = (RPProtectionRecommendation) recommendationsIter.next();

            URI protectionSystemURI = rpProtectionRec.getProtectionDevice();
            URI changeVpoolVolumeURI = rpProtectionRec.getVpoolChangeVolume();
            Volume changeVpoolVolume = (changeVpoolVolumeURI == null ? null : _dbClient.queryObject(Volume.class, changeVpoolVolumeURI));
            isChangeVpool = (changeVpoolVolumeURI != null);
            isChangeVpoolForProtectedVolume = rpProtectionRec.isVpoolChangeProtectionAlreadyExists();
            boolean addJournalForStandbySourceCopy = capabilities.getAddJournalCapacity()
                    && (rpProtectionRec.getStandbyJournalRecommendation() != null);

            String newVolumeLabel = volumeName;

            // Find the Source RP Copy Name
            String sourceCopyName = retrieveRpCopyName(originalVpool, varray, consistencyGroup, true);
            String standbySourceCopyName = "";

            if (addJournalForStandbySourceCopy) {
                // Find the Source Standby RP Copy Name - for add journal operation
                standbySourceCopyName = retrieveRpCopyName(originalVpool, varray, consistencyGroup, true);
            }

            if (metroPointEnabled) {
                // Find the Source Standby RP Copy Name - for MetorPoint
                haVarray = _dbClient.queryObject(VirtualArray.class, VPlexUtil.getHAVarray(originalVpool));
                standbySourceCopyName = retrieveRpCopyName(originalVpool, haVarray, consistencyGroup, true);
            }

            StringBuffer volumeInfoBuffer = new StringBuffer();
            volumeInfoBuffer.append(String.format(NEW_LINE));

            // Prepare the Journals first
            try {
                prepareRpJournals(rpProtectionRec, project, consistencyGroup, vpool, originalVpool, param, numberOfVolumesInRequest,
                        newVolumeLabel, isChangeVpoolForProtectedVolume, copyOfCapabilities, protectionSystemURI, taskList, task,
                        descriptors, volumeURIs, volumeInfoBuffer, sourceCopyName, standbySourceCopyName);
            } catch (Exception e) {
                _log.error("Error trying to prepare RP Journal volumes", e);
                throw APIException.badRequests.rpBlockApiImplPrepareVolumeException(newVolumeLabel);
            }

            // Prepare the source and targets
            if (rpProtectionRec.getSourceRecommendations() != null) {
                for (RPRecommendation sourceRec : rpProtectionRec.getSourceRecommendations()) {
                    // Get a reference to all existing VPLEX Source volumes (if any)
                    List<Volume> allSourceVolumesInCG = BlockConsistencyGroupUtils.getActiveVplexVolumesInCG(consistencyGroup, _dbClient,
                            Volume.PersonalityTypes.SOURCE);

                    // We only need to validate the MetroPoint type if this is the
                    // first MP volume of a new CG.
                    if (metroPointEnabled && allSourceVolumesInCG.isEmpty()) {
                        validateMetroPointType(sourceRec.getMetroPointType());
                    }

                    // Get the number of volumes needed to be created for this recommendation.
                    int volumeCountInRec = sourceRec.getResourceCount();

                    // For an upgrade to MetroPoint, even though the user would have chosen 1 volume to update, we need
                    // to update ALL the RSets in the CG. We can't just update one RSet / volume.
                    // So let's get ALL the source volumes in the CG and we will update them all to MetroPoint.
                    // Each source volume will be exported to the HA side of the VPLEX (for MetroPoint visibility).
                    // All source volumes will share the same secondary journal.
                    if (isChangeVpoolForProtectedVolume) {
                        _log.info(String.format("Change Virtual Pool Protected: %d existing source volume(s) in CG [%s](%s) are affected.",
                                allSourceVolumesInCG.size(), consistencyGroup.getLabel(), consistencyGroup.getId()));
                        // Force the count to the number of existing source volumes in the CG.
                        volumeCountInRec = allSourceVolumesInCG.size();
                    }

                    // Grab a handle of the haRec, it could be null which is Ok.
                    RPRecommendation haRec = sourceRec.getHaRecommendation();

                    for (int volumeCount = 0; volumeCount < volumeCountInRec; volumeCount++) {
                        // Let's not get into multiple of multiples, this class will handle multi volume creates.
                        // So force the incoming VolumeCreate param to be set to 1 always from here on.
                        sourceRec.setResourceCount(1);
                        if (haRec != null) {
                            haRec.setResourceCount(1);
                        }

                        newVolumeLabel = generateDefaultVolumeLabel(volumeName, totalVolumeCount, numberOfVolumesInRequest);

                        // Grab the existing volume and task object from the incoming task list
                        Volume preCreatedVolume = StorageScheduler.getPrecreatedVolume(_dbClient, taskList, newVolumeLabel);

                        // Assemble a Replication Set; A Collection of volumes. One production, and any number of
                        // targets.
                        String rsetName = "RSet-" + newVolumeLabel;

                        // Increment total volume count
                        totalVolumeCount++;

                        // This name has to remain unique, especially when the number of volumes requested to be created
                        // is more than 1.
                        param.setName(newVolumeLabel);

                        Volume sourceVolume = null;
                        // /////// SOURCE ///////////
                        if (!isChangeVpoolForProtectedVolume) {
                            if (isChangeVpool) {
                                _log.info(String.format("Change Vpool, use existing Source Volume [%s].", changeVpoolVolume.getLabel()));
                            } else {
                                _log.info("Create RP Source Volume...");
                            }

                            // Create the source
                            sourceVolume = createRecoverPointVolume(sourceRec, newVolumeLabel, project, capabilities, consistencyGroup,
                                    param, protectionSystemURI, Volume.PersonalityTypes.SOURCE, rsetName, preCreatedVolume, null, taskList,
                                    task, sourceCopyName, descriptors, changeVpoolVolume, isChangeVpool, isSrcAndHaSwapped, true);
                        } else {
                            if (metroPointEnabled) {
                                _log.info("Upgrade to MetroPoint operation...");
                                // This is a change vpool to upgrade to Metropoint, we need to update all source volumes
                                // in the CG to reference the newly created stand-by journal.
                                for (Volume sourceVol : allSourceVolumesInCG) {
                                    _log.info(String.format("Update the source volume [%s](%s) with new standby journal.",
                                            sourceVol.getLabel(), sourceVol.getId()));
                                    // All RP+VPLEX Metro volumes in this CG need to have their backing volume
                                    // references updated with the internal site names for exports.
                                    setInternalSitesForSourceBackingVolumes(sourceRec, haRec, sourceVol, true, false,
                                            originalVpool.getHaVarrayConnectedToRp(), sourceCopyName, standbySourceCopyName);
                                    // We need to have all the existing RP+VPLEX Metro volumes from the CG
                                    // added to the volumeURI list so we can properly export the standby
                                    // leg to RP for each volume.
                                    volumeURIs.add(sourceVol.getId());
                                }
                            } else {
                                // NOTE: Upgrade to MetroPoint is (currently) the only supported Change Virtual Pool Protected
                                // operation, so if we have a null standby journal we're in real trouble.
                                _log.error("Error trying to upgrade to MetroPoint. Standby journal is null.");
                                throw APIException.badRequests.rpBlockApiImplPrepareVolumeException(newVolumeLabel);
                            }

                            // There's no reason to continue past this point, we have
                            // the existing source volumes references and we have
                            // the new standby journal.
                            //
                            // NOTE: In the future, if we decide to expand change vpool protected
                            // to include things like adding/removing targets we can continue
                            // past this point.
                            break;
                        }
                        volumeURIs.add(sourceVolume.getId());

                        // NOTE: This is only needed for MetroPoint and Distributed RP+VPLEX(HA as RP source),
                        // nothing will happen for regular RP volumes.
                        //
                        // Source volumes need to have their backing volumes set with the correct internal
                        // site name. The reason for this is so we know later on where to export the volumes to.
                        //
                        // This is very evident with MetroPoint as we need to export BOTH sides of the VPLEX Distributed
                        // Volume.
                        //
                        // This is less evident with Distributed RP+VPLEX that has "HA as RP source" set.
                        // In this case we need to set it on the HA volume as that is the side to export (not the source
                        // side).
                        // To do this we need to pass in a hint...
                        // We need the (unswapped) original vpool and we then check the getHaVarrayConnectedToRp() value
                        // which tells us
                        // which side(varray) to export.
                        // This value will only be used if isSrcAndHaSwapped == true.
                        setInternalSitesForSourceBackingVolumes(sourceRec, haRec, sourceVolume, metroPointEnabled, isSrcAndHaSwapped,
                                originalVpool.getHaVarrayConnectedToRp(), sourceCopyName, standbySourceCopyName);

                        // /////// TARGET(S) ///////////
                        List<URI> protectionTargets = new ArrayList<URI>();

                        for (RPRecommendation targetRec : sourceRec.getTargetRecommendations()) {
                            // Keep track of the targets created
                            protectionTargets.add(targetRec.getVirtualArray());

                            // Grab the target's varray
                            VirtualArray targetVirtualArray = _dbClient.queryObject(VirtualArray.class, targetRec.getVirtualArray());

                            _log.info(String.format("Create Target (%s)...", targetVirtualArray.getLabel()));

                            // Check to see if there is a change vpool of a already protected source, if so, we could
                            // potentially not need
                            // to provision this target.
                            if (isChangeVpoolForProtectedVolume) {
                                Volume alreadyProvisionedTarget = RPHelper.findAlreadyProvisionedTargetVolume(changeVpoolVolume,
                                        targetRec.getVirtualArray(), _dbClient);
                                if (alreadyProvisionedTarget != null) {
                                    _log.info(String.format("Existing target volume [%s] found for varray [%s].",
                                            alreadyProvisionedTarget.getLabel(), targetVirtualArray.getLabel()));
                                    // No need to go further, continue on to the next target varray
                                    continue;
                                }
                            }

                            // Generate target volume name
                            String targetVolumeName = new StringBuilder(newVolumeLabel)
                                    .append(VOLUME_TYPE_TARGET + targetVirtualArray.getLabel()).toString();

                            // Create the target
                            Volume targetVolume = createRecoverPointVolume(targetRec, targetVolumeName, project, copyOfCapabilities,
                                    consistencyGroup, param, protectionSystemURI, Volume.PersonalityTypes.TARGET, rsetName, null,
                                    sourceVolume, taskList, task, targetRec.getRpCopyName(), descriptors, null, false, false, false);

                            volumeInfoBuffer.append(logVolumeInfo(targetVolume));
                            volumeURIs.add(targetVolume.getId());
                        }

                        // /////// METROPOINT LOCAL TARGET(S) ///////////
                        if (metroPointEnabled && haRec.getTargetRecommendations() != null && !haRec.getTargetRecommendations().isEmpty()) {
                            // If metropoint is chosen and two local copies are configured, one copy on each side
                            // then we need to create targets for the second (stand-by) leg.
                            for (RPRecommendation standbyTargetRec : haRec.getTargetRecommendations()) {
                                // Grab the MP target's varray
                                VirtualArray standyTargetVirtualArray = _dbClient.queryObject(VirtualArray.class,
                                        standbyTargetRec.getVirtualArray());

                                _log.info(String.format("Create Standby Target (%s)..", standyTargetVirtualArray.getLabel()));

                                // Skip over protection targets that have already been created as part of the
                                // source recommendation.
                                if (protectionTargets.contains(standbyTargetRec.getVirtualArray())) {
                                    continue;
                                }

                                // Check to see if there is a change vpool of a already protected source, if so, we
                                // could potentially not need to provision this target. This would sit on the source recommendation, not the
                                // standby.
                                if (isChangeVpoolForProtectedVolume) {
                                    Volume alreadyProvisionedTarget = RPHelper.findAlreadyProvisionedTargetVolume(changeVpoolVolume,
                                            standyTargetVirtualArray.getId(), _dbClient);
                                    if (alreadyProvisionedTarget != null) {
                                        _log.info(String.format("Existing target volume [%s] found for varray [%s].",
                                                alreadyProvisionedTarget.getLabel(), standyTargetVirtualArray.getLabel()));
                                        // No need to go further, continue on to the next target varray
                                        continue;
                                    }
                                }

                                // Generate standby target label
                                String standbyTargetVolumeName = new StringBuilder(newVolumeLabel)
                                        .append(VOLUME_TYPE_TARGET + standyTargetVirtualArray.getLabel()).toString();

                                // Create the standby target
                                Volume standbyTargetVolume = createRecoverPointVolume(standbyTargetRec, standbyTargetVolumeName, project,
                                        copyOfCapabilities, consistencyGroup, param, protectionSystemURI, Volume.PersonalityTypes.TARGET,
                                        rsetName, null, sourceVolume, taskList, task, standbyTargetRec.getRpCopyName(), descriptors, null,
                                        false, false, false);
                                volumeInfoBuffer.append(logVolumeInfo(standbyTargetVolume));
                                volumeURIs.add(standbyTargetVolume.getId());
                            }
                        }

                        // Hold off on logging the source volume until we're done creating the targets
                        volumeInfoBuffer.append(logVolumeInfo(sourceVolume));
                    }
                }

                volumeInfoBuffer.append(String.format(NEW_LINE));
                _log.info(volumeInfoBuffer.toString());
            }
        }
    }

    /**
     * Used to create a task and add it to the TaskList
     *
     * @param volume
     *            Volume that the task is for
     * @param capabilities
     * @param taskList
     *            The TaskList to store tasks
     * @param task
     *            Task Id
     */
    private void createTaskForVolume(Volume volume, VirtualPoolCapabilityValuesWrapper capabilities, TaskList taskList, String task) {
        ResourceOperationTypeEnum type = ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME;

        if (capabilities.getAddJournalCapacity()) {
            type = ResourceOperationTypeEnum.ADD_JOURNAL_VOLUME;
        }
        createTaskForVolume(volume, type, taskList, task);
    }

    /**
     * Used to create a task and add it to the TaskList
     *
     * @param volume
     *            Volume that the task is for
     * @param type
     *            type of the task
     * @param taskList
     *            The TaskList to store tasks
     * @param task
     *            Task Id
     */
    private void createTaskForVolume(Volume volume, ResourceOperationTypeEnum type, TaskList taskList, String task) {

        // Create the OP
        Operation op = _dbClient.createTaskOpStatus(Volume.class, volume.getId(), task, type);
        volume.setOpStatus(new OpStatusMap());
        volume.getOpStatus().put(task, op);

        // Persist the volume in the db
        _dbClient.updateObject(volume);

        _log.info(String.format("Created task of type [%s] for volume [%s]", type.name(), volume.getLabel()));

        // Create the task and add it to the task list
        taskList.getTaskList().add(toTask(volume, task, op));
    }

    /**
     * Prepares all the journal volumes and populates the values into the sourceJournals and/or
     * the targetJournals map.
     *
     * @param rpProtectionRec
     *            - The main rec object
     * @param project
     *            - The project
     * @param consistencyGroup
     *            - The CG
     * @param vpool
     *            - The vpool (potentially swapped)
     * @param originalVpool
     *            - The original vpool
     * @param param
     *            - Volume create param
     * @param numberOfVolumesInRequest
     *            - Number of volumes to create
     * @param newVolumeLabel
     *            - Label of volume
     * @param isChangeVpoolForProtectedVolume
     *            - Flag for protected change vpool op
     * @param capabilities
     *            - Capabilities object
     * @param protectionSystemURI
     *            - Protection System being used
     * @param taskList
     *            - Task List
     * @param task
     *            - Task
     * @param descriptors
     *            - List of all descriptors to be added
     * @param volumeURIs
     *            - List to store all the volume URIs
     * @param volumeInfoBuffer
     *            - Buffer for volume info to be printed
     * @param sourceCopyName
     *            - Source Copy Name
     * @param standbySourceCopyName
     *            - Standby Copy Name
     */
    private void prepareRpJournals(RPProtectionRecommendation rpProtectionRec, Project project, BlockConsistencyGroup consistencyGroup,
            VirtualPool vpool, VirtualPool originalVpool, VolumeCreate param, Integer numberOfVolumesInRequest, String newVolumeLabel,
            boolean isChangeVpoolForProtectedVolume, VirtualPoolCapabilityValuesWrapper capabilities, URI protectionSystemURI,
            TaskList taskList, String task, List<VolumeDescriptor> descriptors, List<URI> volumeURIs, StringBuffer volumeInfoBuffer,
            String sourceCopyName, String standbySourceCopyName) throws Exception {

        Volume sourceJournal = null;
        Volume standbyJournal = null;

        List<Volume> cgSourceVolumes = new ArrayList<Volume>();
        List<Volume> cgTargetVolumes = new ArrayList<Volume>();

        // This boolean indicates that the operation is only for adding additional journals.
        // When adding additional journals, there is the option to add multiple new journals,
        // however, for all other creates we are either re-using an existing journal or
        // just creating a single journal.
        // i.e. the majority of the time we are only creating a single journal.
        boolean journalOnlyCreate = capabilities.getAddJournalCapacity();

        // Only check for existing source journals if this is not a direct journal add operation.
        if (!journalOnlyCreate) {
            // If the CG already contains RP volumes, then we need to check if new/additional journal
            // volumes need to be created, based on the journal policy specified.
            cgSourceVolumes = RPHelper.getCgVolumes(_dbClient, consistencyGroup.getId(), Volume.PersonalityTypes.SOURCE.toString());
            cgTargetVolumes = RPHelper.getCgVolumes(_dbClient, consistencyGroup.getId(), Volume.PersonalityTypes.TARGET.toString());

            if (!cgSourceVolumes.isEmpty()) {
                boolean isAdditionalSourceJournalRequired = _rpHelper.isAdditionalJournalRequiredForRPCopy(vpool.getJournalSize(),
                        consistencyGroup, param.getSize(), numberOfVolumesInRequest, sourceCopyName);
                if (!isAdditionalSourceJournalRequired) {
                    _log.info(String.format("Re-use existing Source Journal for copy [%s]", sourceCopyName));
                    // If the CG contains volumes already and no new additional journals are provisioned,
                    // then we simply update the reference on the source for the journal volume.
                    List<Volume> existingSourceJournals = RPHelper.findExistingJournalsForCopy(_dbClient, consistencyGroup.getId(),
                            sourceCopyName);
                    sourceJournal = existingSourceJournals.get(0);
                    _log.info(String.format("Existing Primary Source Journal: [%s] (%s)", sourceJournal.getLabel(), sourceJournal.getId()));

                    if (VirtualPool.vPoolSpecifiesMetroPoint(vpool) && !isChangeVpoolForProtectedVolume) {
                        _log.info(String.format("Re-use existing Standby Journal for copy [%s]", standbySourceCopyName));
                        List<Volume> existingStandbyJournals = RPHelper.findExistingJournalsForCopy(_dbClient, consistencyGroup.getId(),
                                standbySourceCopyName);
                        standbyJournal = existingStandbyJournals.get(0);
                        _log.info(String.format("Existing Standby Source Journal: [%s] (%s)", standbyJournal.getLabel(),
                                standbyJournal.getId()));
                    }
                }
            }
        }

        // /////// ACTIVE SOURCE JOURNAL ///////////
        if (!isChangeVpoolForProtectedVolume
                && (sourceJournal == null)
                && rpProtectionRec.getSourceJournalRecommendation() != null) {
            _log.info("Create Active Source Journal...");

            // varray is used to get unique journal volume names
            VirtualArray varray = _dbClient.queryObject(VirtualArray.class, rpProtectionRec.getSourceJournalRecommendation()
                    .getVirtualArray());

            // Number of journals to create - will only be greater than 1 when doing add journal operation.
            int numberOfJournalVolumesInRequest = rpProtectionRec.getSourceJournalRecommendation().getResourceCount();

            // Let's not get into multiple of multiples, this class will handle multi volume creates.
            // So force the incoming VolumeCreate param to be set to 1 always from here on.
            rpProtectionRec.getSourceJournalRecommendation().setResourceCount(1);

            for (int volumeCount = 0; volumeCount < numberOfJournalVolumesInRequest; volumeCount++) {

                // acquire a lock so it's possible to get a unique name for the volume
                String lockKey = new StringBuilder(consistencyGroup.getLabel()).append("-").append(varray.getLabel()).toString();
                InterProcessLockHolder lock = null;
                try {
                    _log.info("Attempting to acquire lock: " + lockKey);
                    lock = InterProcessLockHolder.acquire(_coordinator, lockKey, _log, LOCK_WAIT_MILLISECONDS);
                    // get a unique journal volume name
                    String journalName = _rpHelper.createJournalVolumeName(varray, consistencyGroup);

                    // Create source journal
                    sourceJournal = createRecoverPointVolume(rpProtectionRec.getSourceJournalRecommendation(), journalName, project,
                            capabilities, consistencyGroup, param, protectionSystemURI, Volume.PersonalityTypes.METADATA,
                            JOURNAL_RSET, null, null, taskList, task, sourceCopyName, descriptors, null, false, false, true);
                } finally {
                    if (lock != null) {
                        lock.close();
                    }
                }
                volumeURIs.add(sourceJournal.getId());
                volumeInfoBuffer.append(logVolumeInfo(sourceJournal));
            }
        }

        // /////// STANDBY SOURCE JOURNAL ///////////
        if (standbyJournal == null && rpProtectionRec.getStandbyJournalRecommendation() != null) {
            _log.info("Create Standby Source Journal...");

            // varray is used to get unique journal volume names
            VirtualArray varray = _dbClient.queryObject(VirtualArray.class,
                    rpProtectionRec.getStandbyJournalRecommendation().getVirtualArray());

            // Number of journals to create - will only be greater than 1 when doing add journal operation.
            int numberOfJournalVolumesInRequest = rpProtectionRec.getStandbyJournalRecommendation().getResourceCount();

            // Let's not get into multiple of multiples, this class will handle multi volume creates.
            // So force the incoming VolumeCreate param to be set to 1 always from here on.
            rpProtectionRec.getStandbyJournalRecommendation().setResourceCount(1);

            for (int volumeCount = 0; volumeCount < numberOfJournalVolumesInRequest; volumeCount++) {

                // acquire a lock so it's possible to get a unique name for the volume
                String lockKey = new StringBuilder(consistencyGroup.getLabel()).append("-").append(varray.getLabel()).toString();
                InterProcessLockHolder lock = null;
                try {
                    _log.info("Attempting to acquire lock: " + lockKey);
                    lock = InterProcessLockHolder.acquire(_coordinator, lockKey, _log, LOCK_WAIT_MILLISECONDS);
                    // get a unique journal volume name
                    String journalName = _rpHelper.createJournalVolumeName(varray, consistencyGroup);

                    // If MetroPoint is enabled we need to create the standby journal volume
                    standbyJournal = createRecoverPointVolume(rpProtectionRec.getStandbyJournalRecommendation(), journalName, project,
                            capabilities, consistencyGroup, param, protectionSystemURI, Volume.PersonalityTypes.METADATA,
                            JOURNAL_RSET, null, null, taskList, task, standbySourceCopyName, descriptors, null, false, false, true);
                } finally {
                    if (lock != null) {
                        lock.close();
                    }
                }
                volumeURIs.add(standbyJournal.getId());
                volumeInfoBuffer.append(logVolumeInfo(standbyJournal));
            }
        }

        // /////// TARGET JOURNAL(s) ///////////
        if (!isChangeVpoolForProtectedVolume && rpProtectionRec.getTargetJournalRecommendations() != null
                && !rpProtectionRec.getTargetJournalRecommendations().isEmpty()) {
            for (RPRecommendation targetJournalRec : rpProtectionRec.getTargetJournalRecommendations()) {

                VirtualArray targetJournalVarray = _dbClient.queryObject(VirtualArray.class, targetJournalRec.getVirtualArray());

                // This is the varray for the target we're associating the journal too. It could be the case
                // that it is the same as the target journal varray set, or the user could have chosen a different
                // varray for their target journal in which case we do need to find which target/copy this journal will
                // be associated to in the RP CG.
                // Ex:
                // Target varray1
                // Target journal varray6
                // The target journal is varray6, however we are adding the journal to the target copy based on the protection
                // settings defined for varray1.
                VirtualArray targetCopyVarray = getProtectionVarray(rpProtectionRec, targetJournalRec.getInternalSiteName());
                if (targetCopyVarray == null) {
                    targetCopyVarray = targetJournalVarray;
                }

                // Only need to enter this block if we already have existing journals in the CG
                // and we want to see if more space is required or if we are performing an add
                // journal volume operation
                if (!cgTargetVolumes.isEmpty() && !capabilities.getAddJournalCapacity()) {
                    VpoolProtectionVarraySettings protectionSettings = _rpHelper.getProtectionSettings(originalVpool, targetCopyVarray);
                    String targetCopyName = targetJournalRec.getRpCopyName();
                    if (targetCopyName == null) {
                        // Target RP copy name was not set on the recommendation, find it from the CG.
                        targetCopyName = RPHelper.getCgCopyName(_dbClient, consistencyGroup, targetCopyVarray.getId(), false);
                    }
                    boolean isAdditionalTargetJournalRequired = _rpHelper.isAdditionalJournalRequiredForRPCopy(
                            protectionSettings.getJournalSize(), consistencyGroup, param.getSize(),
                            numberOfVolumesInRequest,
                            targetCopyName);
                    if (!isAdditionalTargetJournalRequired) {
                        // If the CG contains volumes already and no new additional journals are provisioned,
                        // then we simply update the reference on the source for the journal volume.
                        _log.info(String.format("Re-use existing Target Journal for copy [%s]", targetCopyName));
                        List<Volume> existingTargetJournals = RPHelper.findExistingJournalsForCopy(_dbClient, consistencyGroup.getId(),
                                targetCopyName);
                        Volume existingTargetJournalVolume = existingTargetJournals.get(0);
                        _log.info(String.format("Existing Target Journal: [%s] (%s)", existingTargetJournalVolume.getLabel(),
                                existingTargetJournalVolume.getId()));
                        continue;
                    }
                }

                _log.info(String.format("Create Target Journal (%s)...", targetJournalVarray.getLabel()));
                // Number of journals to create - will only be greater than 1 when doing add journal operation.
                int numberOfJournalVolumesInRequest = targetJournalRec.getResourceCount();

                // Let's not get into multiple of multiples, this class will handle multi volume creates.
                // So force the incoming VolumeCreate param to be set to 1 always from here on.
                targetJournalRec.setResourceCount(1);

                for (int volumeCount = 0; volumeCount < numberOfJournalVolumesInRequest; volumeCount++) {

                    // acquire a lock so it's possible to get a unique name for the volume
                    String lockKey = new StringBuilder(consistencyGroup.getLabel()).append("-").append(targetCopyVarray.getLabel())
                            .toString();
                    InterProcessLockHolder lock = null;
                    try {
                        _log.info("Attempting to acquire lock: " + lockKey);
                        lock = InterProcessLockHolder.acquire(_coordinator, lockKey, _log, LOCK_WAIT_MILLISECONDS);
                        // get a unique journal volume name
                        String journalName = _rpHelper.createJournalVolumeName(targetCopyVarray, consistencyGroup);

                        // Create target journal
                        Volume targetJournalVolume = createRecoverPointVolume(targetJournalRec, journalName, project, capabilities,
                                consistencyGroup, param, protectionSystemURI, Volume.PersonalityTypes.METADATA,
                                JOURNAL_RSET, null, null, taskList, task, targetJournalRec.getRpCopyName(),
                                descriptors, null, false, false, false);
                        volumeURIs.add(targetJournalVolume.getId());
                        volumeInfoBuffer.append(logVolumeInfo(targetJournalVolume));
                    } finally {
                        if (lock != null) {
                            lock.close();
                        }
                    }
                }
            }
        }
    }

    /**
     * There are instances where the varray we're provisioning to and the varray associated to the protection copy
     * are NOT the same.
     *
     * Ex:
     * Protection/Target Copy Varray => varray1
     * Target Journal Varray => varray6
     *
     * We're provisioning the journal volume on varray6 but the journal is for the Protection/Target at varray1.
     *
     * Using the internal site name we are able to find the exact Protection/Copy varray that we
     * need by parsing through the recommendation object.
     *
     * @param protectionRec The RP Protection Recommendation
     * @param internalSiteName The internal site name we're looking for
     * @return Returns the varray for the protection internal site
     */
    private VirtualArray getProtectionVarray(RPProtectionRecommendation protectionRec, String internalSiteName) {
        if (protectionRec != null) {
            if (protectionRec.getSourceRecommendations() != null) {
                for (RPRecommendation sourceRec : protectionRec.getSourceRecommendations()) {
                    if (sourceRec.getTargetRecommendations() != null) {
                        for (RPRecommendation targetRec : sourceRec.getTargetRecommendations()) {
                            if (targetRec.getInternalSiteName().equals(internalSiteName)) {
                                return _dbClient.queryObject(VirtualArray.class, targetRec.getVirtualArray());
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Prepare the volume to be used by the controllers for RP.
     *
     * This includes preparing the volume for any of the leveraged technologies (ex: VPLEX) and
     * lastly for RP.
     *
     * @param rpRec The rec for the RP volume to get most of the info in preparing it
     * @param rpVolumeName Volume name
     * @param project Project for the volume
     * @param capabilities Capabilities for the volume create
     * @param consistencyGroup CG for the volume
     * @param param Volume create param for this volume
     * @param protectionSystemURI URI for the Protection System being used
     * @param personalityType Personality of the volume
     * @param rsetName Replication set name
     * @param preCreatedVolume An optionally pre-created source volume, non-null when calling this method to create a SOURCE volume.
     * @param sourceVolume Source volume. Sent down if we're making a target/metadata that needs to be associated to this volume.
     * @param taskList Tasklist to capture all tasks for the UI
     * @param task Task Id
     * @param copyName RP Copy Name
     * @param descriptors Descriptors to be populated
     * @param changeVpoolVolume Existing volume for change vpool volume if it exist
     * @param isChangeVpool Boolean to indicate if this is a change vpool op
     * @param isSrcAndHaSwapped Boolean to indicate if this is a swapped Src and HA op
     * @param productionCopy whether this is a production copy or not
     * @return Prepared RP Volume
     */
    private Volume
            createRecoverPointVolume(RPRecommendation rpRec, String rpVolumeName, Project project,
                    VirtualPoolCapabilityValuesWrapper capabilities,
                    BlockConsistencyGroup consistencyGroup, VolumeCreate param,
                    URI protectionSystemURI, Volume.PersonalityTypes personalityType,
                    String rsetName, Volume preCreatedVolume, Volume sourceVolume, TaskList taskList,
                    String task, String copyName, List<VolumeDescriptor> descriptors, Volume changeVpoolVolume,
                    boolean isChangeVpool, boolean isSrcAndHaSwapped,
                    boolean productionCopy) {
        boolean isPreCreatedVolume = (preCreatedVolume != null);
        Volume rpVolume = preCreatedVolume;
        VirtualArray varray = _dbClient.queryObject(VirtualArray.class, rpRec.getVirtualArray());
        VirtualPool vpool = rpRec.getVirtualPool();
        String rpInternalSiteName = rpRec.getInternalSiteName();
        URI storagePoolUri = rpRec.getSourceStoragePool();
        URI storageSystemUri = rpRec.getSourceStorageSystem();
        String size = String.valueOf(rpRec.getSize());

        // If the copy name was passed in as null, let's get it from the cg or create it
        if (copyName == null) {
            copyName = retrieveRpCopyName(vpool, varray, consistencyGroup, productionCopy);
        }

        // Determine if we're creating a RP+VPLEX or MetroPoint volume
        boolean vplex = VirtualPool.vPoolSpecifiesHighAvailability(vpool);

        _log.info(String.format("Prepare %s Volume %s %s(%s/%s)%s. It will be placed in CG [%s] and "
                + "will be protected by RecoverPoint (Protection System: %s) with RP Copy Name [%s] on RP Internal Site [%s] "
                + "as part of RP Replication Set [%s].",
                personalityType.toString(), (vplex ? "(VPLEX) -" : "-"),
                rpVolumeName, varray.getLabel(), vpool.getLabel(),
                (isChangeVpool ? ". This is an existing volume that is involved in a change virtual pool operation." : ""),
                consistencyGroup.getLabel(), protectionSystemURI.toString(), copyName,
                rpInternalSiteName, rsetName));

        if (vplex) {
            List<Recommendation> vplexRecs = new ArrayList<Recommendation>();
            // Add VPLEX Source Rec
            vplexRecs.add(0, rpRec.getVirtualVolumeRecommendation());
            // Add VPLEX HA Rec, if it exists
            if (rpRec.getHaRecommendation() != null) {
                vplexRecs.add(1, rpRec.getHaRecommendation().getVirtualVolumeRecommendation());
            }

            // Prepare VPLEX specific volume info
            rpVolume = prepareVPlexVolume(vplexRecs, project, varray, vpool, storagePoolUri, storageSystemUri, capabilities,
                    consistencyGroup, param, rpVolumeName, size, descriptors, taskList, task, personalityType, isChangeVpool,
                    changeVpoolVolume);
        }

        // Prepare RP specific volume info
        rpVolume = prepareVolume(rpVolume, project, varray, vpool, size, rpRec, rpVolumeName, consistencyGroup, protectionSystemURI,
                personalityType, rsetName, rpInternalSiteName, copyName, sourceVolume, vplex, changeVpoolVolume, isPreCreatedVolume);

        boolean createTask = isTaskRequired(rpVolume, capabilities, vplex, taskList);
        if (createTask) {
            // Create a task for this volume
            createTaskForVolume(rpVolume, capabilities, taskList, task);
        }

        return rpVolume;
    }

    /**
     * Determines whether or not we need to generate a task for this operation.
     *
     * @param rpVolume Volume to check
     * @param capabilities Needed for checking if this is a Journal Create
     * @param vplex VPLEX flag
     * @param isChangeVpool Change vpool flag
     * @param taskList task list
     * @return True if task is needed, false otherwise.
     */
    private boolean isTaskRequired(Volume rpVolume, VirtualPoolCapabilityValuesWrapper capabilities, boolean vplex, TaskList taskList) {
        boolean rpNonVplexSourceVolume = (Volume.PersonalityTypes.SOURCE.name().equals(rpVolume.getPersonality()) && !vplex);
        boolean addJournalVolume = capabilities.getAddJournalCapacity();
        boolean notAlreadyInTaskList = (StorageScheduler.getPrecreatedVolume(_dbClient, taskList, rpVolume.getLabel()) == null);
        boolean createTask = addJournalVolume || (rpNonVplexSourceVolume && notAlreadyInTaskList);
        return createTask;
    }

    /**
     * Leverage the vplex block service api to properly prepare volumes in cassandra for later consumption by the controllers
     *
     * @param vplexRecommendations All the VPLEX recs
     * @param project Project for the volume
     * @param varray Varray for the volume
     * @param vpool Vpool for the volume
     * @param storagePoolUri URI of the Storage Pool for the volume
     * @param storageSystemId URI of the Storage System for the volume
     * @param capabilities Capabilities for the volume create
     * @param consistencyGroup CG for the volume
     * @param param Volume create param for the volume
     * @param volumeName Volume name
     * @param size Volume size
     * @param descriptors All volume descriptors, needed to be populated by the VPLEX Block
     * @param taskList Tasklist to add all VPLEX tasks
     * @param task Task Id
     * @param personality Personality of the volume
     * @param isChangeVpool Boolean set to true if it's a change vpool op
     * @param changeVpoolVolume The change vpool volume if needed
     * @return Prepared VPLEX volume
     */
    private Volume prepareVPlexVolume(List<Recommendation> vplexRecommendations, Project project, VirtualArray varray,
            VirtualPool vpool, URI storagePoolUri, URI storageSystemId,
            VirtualPoolCapabilityValuesWrapper capabilities,
            BlockConsistencyGroup consistencyGroup, VolumeCreate param,
            String volumeName, String size, List<VolumeDescriptor> descriptors,
            TaskList taskList, String task, Volume.PersonalityTypes personality,
            boolean isChangeVpool, Volume changeVpoolVolume) {

        Volume vplexVirtualVolume = null;

        if (!isChangeVpool) {
            List<URI> volumes = new ArrayList<URI>();

            // VPLEX needs to be aware of the CG
            capabilities.put(VirtualPoolCapabilityValuesWrapper.BLOCK_CONSISTENCY_GROUP, consistencyGroup.getId());
            capabilities.put(VirtualPoolCapabilityValuesWrapper.PERSONALITY, personality.name());
            capabilities.put(VirtualPoolCapabilityValuesWrapper.RESOURCE_COUNT, 1);

            // Tweak the VolumeCreate slightly for VPLEX
            VolumeCreate volumeCreateParam = new VolumeCreate();
            volumeCreateParam.setConsistencyGroup(consistencyGroup.getId());
            volumeCreateParam.setCount(1);
            volumeCreateParam.setName(volumeName);
            volumeCreateParam.setProject(project.getId());
            volumeCreateParam.setSize(size);
            volumeCreateParam.setVarray(varray.getId());
            volumeCreateParam.setVpool(vpool.getId());

            boolean createTask = Volume.PersonalityTypes.SOURCE.equals(personality);
            List<VolumeDescriptor> vplexVolumeDescriptors = vplexBlockServiceApiImpl
                    .createVPlexVolumeDescriptors(volumeCreateParam, project, varray, vpool,
                            vplexRecommendations, task, capabilities, capabilities.getBlockConsistencyGroup(), taskList, volumes,
                            createTask);
            // Set the compute resource into the VPLEX volume descriptors for RP source
            // volumes so that the compute resource name can be reflected in the volume
            // name if the custom volume naming is configured as such.
            if ((createTask) && (param.getComputeResource() != null)) {
                for (VolumeDescriptor vplexVolumeDescriptor : vplexVolumeDescriptors) {
                    vplexVolumeDescriptor.setComputeResource(param.getComputeResource());
                }
            }
            descriptors.addAll(vplexVolumeDescriptors);
            vplexVirtualVolume = this.getVPlexVirtualVolume(volumes);
        } else {
            if (Volume.PersonalityTypes.SOURCE.equals(personality)) {
                VolumeDescriptor changeVpoolDescriptor = new VolumeDescriptor(
                        VolumeDescriptor.Type.VPLEX_VIRT_VOLUME,
                        changeVpoolVolume.getStorageController(), changeVpoolVolume.getId(),
                        null, consistencyGroup.getId(),
                        capabilities, changeVpoolVolume.getCapacity());
                Map<String, Object> descParams = new HashMap<String, Object>();
                descParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_EXISTING_VOLUME_ID, changeVpoolVolume.getId());
                descParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_NEW_VPOOL_ID, vpool.getId());
                descParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_OLD_VPOOL_ID, changeVpoolVolume.getVirtualPool());
                changeVpoolDescriptor.setParameters(descParams);
                descriptors.add(changeVpoolDescriptor);
                vplexVirtualVolume = changeVpoolVolume;
            }
        }

        return vplexVirtualVolume;
    }

    /**
     * Prep work to call the orchestrator to create the volume descriptors
     *
     * @param recommendation recommendation object from RPRecommendation
     * @param volumeURIs volumes already prepared
     * @param capabilities vpool capabilities
     * @return list of volume descriptors
     * @throws ControllerException
     */
    private List<VolumeDescriptor> createVolumeDescriptors(RPProtectionRecommendation recommendation, List<URI> volumeURIs,
            VirtualPoolCapabilityValuesWrapper capabilities, URI oldVpool, URI computeResource) {

        List<Volume> preparedVolumes = _dbClient.queryObject(Volume.class, volumeURIs);

        List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();

        // Package up the Volume descriptors
        for (Volume volume : preparedVolumes) {
            boolean vplex = RPHelper.isVPlexVolume(volume, _dbClient);

            VolumeDescriptor.Type volumeType = VolumeDescriptor.Type.RP_SOURCE;
            if (vplex) {
                volumeType = VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE;
            }

            // If the volume being provisioned is an xtremio volume, set the max
            // number of snaps to the default value of 128. It is necessary to set
            // the default because xtremio uses snap technology for replication
            // Eventually this value should be configurable and passed as part of the VPool RecoverPoint settings
            // If capabilities is null this is an expand operation no need to set the max number of snaps
            if (RPHelper.protectXtremioVolume(volume, _dbClient) && capabilities != null) {
                capabilities.put(VirtualPoolCapabilityValuesWrapper.RP_MAX_SNAPS, 128);
            }

            VolumeDescriptor desc = null;
            // Vpool Change flow, mark the production volume as already existing, so it doesn't get created
            if (recommendation != null && (recommendation.getVpoolChangeVolume() != null)
                    && Volume.PersonalityTypes.SOURCE.toString().equals(volume.getPersonality())) {
                if (recommendation.isVpoolChangeProtectionAlreadyExists()) {
                    volumeType = VolumeDescriptor.Type.RP_EXISTING_PROTECTED_SOURCE;
                } else {
                    volumeType = VolumeDescriptor.Type.RP_EXISTING_SOURCE;
                }
                desc = new VolumeDescriptor(volumeType, volume.getStorageController(), volume.getId(), volume.getPool(), null, capabilities,
                        volume.getCapacity());
                Map<String, Object> volumeParams = new HashMap<String, Object>();
                volumeParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_EXISTING_VOLUME_ID, recommendation.getVpoolChangeVolume());
                volumeParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_NEW_VPOOL_ID, recommendation.getVpoolChangeNewVpool());
                volumeParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_OLD_VPOOL_ID, oldVpool);

                desc.setParameters(volumeParams);
                descriptors.add(desc);
            } else {
                // Normal create-from-scratch flow
                if (volume.getPersonality() == null) {
                    throw APIException.badRequests.missingPersonalityAttribute(String.valueOf(volume.getId()));
                }

                if (volume.getPersonality().equals(Volume.PersonalityTypes.TARGET.toString())) {
                    if (vplex) {
                        volumeType = VolumeDescriptor.Type.RP_VPLEX_VIRT_TARGET;
                    } else {
                        volumeType = VolumeDescriptor.Type.RP_TARGET;
                    }
                } else if (volume.getPersonality().equals(Volume.PersonalityTypes.METADATA.toString())) {
                    if (vplex) {
                        volumeType = VolumeDescriptor.Type.RP_VPLEX_VIRT_JOURNAL;
                    } else {
                        volumeType = VolumeDescriptor.Type.RP_JOURNAL;
                    }
                }

                desc = new VolumeDescriptor(volumeType, volume.getStorageController(), volume.getId(), volume.getPool(), null, capabilities,
                        volume.getCapacity());

                if (volume.checkPersonality(Volume.PersonalityTypes.SOURCE.name()) && computeResource != null) {
                    _log.info(String.format("Volume %s - will be exported to Host/Cluster: %s", volume.getLabel(),
                            computeResource.toString()));
                    desc.setComputeResource(computeResource);
                }
                descriptors.add(desc);
            }
        }

        return descriptors;
    }

    /**
     * Adds a Volume to StorageSystem mapping entry in the provided Map.
     *
     * @param volumeStorageSystems the Map in which we want to add an entry
     * @param volume the Volume the Volume referencing the StorageSystem we want to Map
     */
    protected void addVolumeStorageSystem(Map<URI, StorageSystem> volumeStorageSystems, Volume volume) {
        if (volumeStorageSystems == null) {
            volumeStorageSystems = new HashMap<URI, StorageSystem>();
        }

        StorageSystem volumeStorageSystem = volumeStorageSystems.get(volume.getStorageController());
        if (volumeStorageSystem == null) {
            volumeStorageSystem = _dbClient.queryObject(StorageSystem.class, volume.getStorageController());
            volumeStorageSystems.put(volumeStorageSystem.getId(), volumeStorageSystem);
        }
    }

    /**
     * This method computes a matching volume allocation capacity across all protection
     * arrays. Some storage systems will allocate a slightly larger capacity than
     * requested so volume sizes can become inconsistent between source and target.
     * <p>
     * If we are protecting between different array types, we need to determine the actual allocation size on each array. Set the capacity
     * of the source and target volumes to be the larger of the actual allocation sizes. This is done to ensure the size of the source and
     * target volumes are identical so RP can create the CG properly.
     *
     * This method returns the size of the volume to be created taking into account the above considerations.
     *
     * @param volumeURIs
     * @param requestedSize
     *            Request size of the volume to be expanded
     * @param isExpand
     *            Expand or Create volume operation
     * @return the final capacity used
     */
    protected Long computeProtectionCapacity(List<URI> volumeURIs, Long requestedSize, boolean isExpand, boolean isChangeVpool,
            List<VolumeDescriptor> volumeDescriptors) {
        List<Volume> volumes = _dbClient.queryObject(Volume.class, volumeURIs);
        _log.info("Performing checks to see if all volumes are of the same System Type and capacity for Protection.");
        Map<URI, StorageSystem> volumeStorageSystemMap = new HashMap<URI, StorageSystem>();
        List<Volume> allVolumesToCompare = new ArrayList<Volume>();
        List<Volume> allVolumesToUpdateCapacity = new ArrayList<Volume>();
        List<Long> currentVolumeSizes = new ArrayList<Long>();
        Map<URI, String> associatedVolumePersonalityMap = new HashMap<URI, String>();
        Long capacity = 0L;

        // We could be performing a change vpool for RP+VPLEX / MetroPoint. This means
        // we could potentially have migrations that need to be done on the backend
        // volumes. If migration info exists we need to collect that ahead of time.
        List<VolumeDescriptor> migrateDescriptors = null;
        List<Migration> migrations = null;
        if (volumeDescriptors != null) {
            migrateDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                    new VolumeDescriptor.Type[] { VolumeDescriptor.Type.VPLEX_MIGRATE_VOLUME }, null);

            if (migrateDescriptors != null && !migrateDescriptors.isEmpty()) {
                _log.info("Data Migration detected, this is due to a change virtual pool operation on RP+VPLEX or MetroPoint.");
                // Load the migration objects for use later
                migrations = new ArrayList<Migration>();
                Iterator<VolumeDescriptor> migrationIter = migrateDescriptors.iterator();
                while (migrationIter.hasNext()) {
                    Migration migration = _dbClient.queryObject(Migration.class, migrationIter.next().getMigrationId());
                    migrations.add(migration);
                }
            }
        }

        for (Volume volume : volumes) {
            // Find the source/target volumes and cache their storage systems.
            // Since these will be VPLEX virtual volumes, we need to grab the associated
            // volumes as those are the real backing volumes.
            if (volume.getPersonality() != null) {
                if (volume.getPersonality().equals(Volume.PersonalityTypes.SOURCE.toString())
                        || volume.getPersonality().equals(Volume.PersonalityTypes.TARGET.toString())) {

                    allVolumesToUpdateCapacity.add(volume);
                    _log.info("Adding Volume [{}] to potentially have capacity adjusted.", volume.getLabel());

                    // If there are associated volumes, this must be a Virtual Volume for RP+VPLEX
                    // and we also need to load those associated/backend volumes for comparison
                    StringSet associatedVolumes = volume.getAssociatedVolumes();
                    if (associatedVolumes != null && !associatedVolumes.isEmpty()) {
                        _log.info("Volume [{}] is a VPLEX virtual volume.", volume.getLabel());
                        Iterator<String> it = associatedVolumes.iterator();
                        while (it.hasNext()) {
                            URI associatedVolumeURI = URI.create(it.next());
                            Volume associatedVolume = _dbClient.queryObject(Volume.class, associatedVolumeURI);

                            // Check to see if there is a migration for this backend volume
                            if (migrations != null && !migrations.isEmpty()) {
                                for (Migration migration : migrations) {
                                    if (migration.getSource().equals(associatedVolume.getId())) {
                                        _log.info("VPLEX backing volume [{}] has a migration, using migration volume instead.",
                                                associatedVolume.getLabel());
                                        // Use the migration volume instead for the capacity adjustment check
                                        associatedVolume = _dbClient.queryObject(Volume.class, migration.getTarget());
                                        break;
                                    }
                                }
                            }
                            // Check sizes, if provisioned is greater than 0 use that to compare sizes
                            // otherwise use the requested capacity
                            if (associatedVolume.getProvisionedCapacity().longValue() > 0) {
                                currentVolumeSizes.add(associatedVolume.getProvisionedCapacity());
                            } else {
                                currentVolumeSizes.add(associatedVolume.getCapacity());
                            }

                            addVolumeStorageSystem(volumeStorageSystemMap, associatedVolume);
                            allVolumesToCompare.add(associatedVolume);
                            allVolumesToUpdateCapacity.add(associatedVolume);
                            associatedVolumePersonalityMap.put(associatedVolume.getId(), volume.getPersonality());
                            _log.info("Adding Volume [{}] to potentially have capacity adjusted.", associatedVolume.getLabel());
                        }
                    } else {
                        // Not a VPLEX Virtual Volume, the volume itself can be used.
                        _log.info("Volume [{}] is not VPLEX virtual volume.", volume.getLabel());

                        // Check sizes, if provisioned is greater than 0 use that to compare sizes
                        // otherwise use the requested capacity
                        if (volume.getProvisionedCapacity().longValue() > 0) {
                            currentVolumeSizes.add(volume.getProvisionedCapacity());
                        } else {
                            currentVolumeSizes.add(volume.getCapacity());
                        }

                        addVolumeStorageSystem(volumeStorageSystemMap, volume);
                        allVolumesToCompare.add(volume);
                    }
                }
            } else {
                _log.warn("Volume [{}] does not have PERSONALITY set. We will not be able to compare this volume.", volume.getLabel());
            }
        }

        // Flag to indicate that there are VMAX2 and VMAX3 storage
        // systems in the request. Special handling will be required.
        boolean vmax2Vmax3StorageCombo = false;

        // There should be at least 2 volumes to compare, Source and Target (if not more)
        if (!allVolumesToCompare.isEmpty() && (allVolumesToCompare.size() >= 2)) {
            StorageSystem storageSystem = null;
            StorageSystem storageSystemToCompare = null;
            boolean storageSystemsMatch = true;

            // Determine if all the source/target storage systems involved are the same based
            // on type, model, and firmware version.
            for (Map.Entry<URI, StorageSystem> volumeStorageSystemEntry : volumeStorageSystemMap.entrySet()) {
                URI volUri = volumeStorageSystemEntry.getKey();
                if (storageSystemToCompare == null) {
                    // Find a base for the comparison, the first element will do.
                    storageSystemToCompare = volumeStorageSystemMap.get(volUri);
                    // set storageSystem to first element if there is only one
                    if (volumeStorageSystemMap.size() == 1) {
                        storageSystem = volumeStorageSystemMap.get(volUri);
                    }
                    continue;
                }

                storageSystem = volumeStorageSystemMap.get(volUri);
                vmax2Vmax3StorageCombo = checkVMAX2toVMAX3(storageSystemToCompare, storageSystem);

                if (!storageSystemToCompare.getSystemType().equals(storageSystem.getSystemType()) || vmax2Vmax3StorageCombo) {
                    // The storage systems do not all match so we need to determine the allocated
                    // capacity on each system.
                    storageSystemsMatch = false;
                    break;
                }
            }

            // XtremIO target is added to a replication set, it must be exactly the same size as the
            // source volume. It cannot be larger or smaller.
            // This is a special case for change vpool to ensure this is true.
            if (storageSystemsMatch && isChangeVpool && DiscoveredDataObject.Type.xtremio.name().equals(storageSystem.getSystemType())) {

                for (Volume volume : allVolumesToUpdateCapacity) {
                    if (NullColumnValueGetter.isNotNullValue(volume.getPersonality())
                            && volume.getPersonality().equals(Volume.PersonalityTypes.SOURCE.toString())) {
                        capacity = volume.getProvisionedCapacity();
                        break;
                    }
                }

                for (Volume volume : allVolumesToUpdateCapacity) {
                    updateVolumeCapacity(volume, capacity, isExpand);
                }

                _log.info(String.format("Capacity adjustments made for XIO change vpool operation."));
                return capacity;
            }

            // If the storage systems do not all match we need to figure out matching volume
            // allocation sizes for all storage systems. CG creation will likely fail if
            // we don't do this.
            if (!storageSystemsMatch || !allVolumeSizesMatch(currentVolumeSizes)) {
                // The storage systems are not all the same so now we must find a volume allocation size
                // that matches between all arrays.
                _log.warn("The storage systems for all volumes do not match or all volume sizes do not match. "
                        + "This could cause RP CG creation to fail. "
                        + "Potentially need to adjust capacity size of volumes to be consistent across all source/targets.");

                List<Volume> tempVolumesList = new ArrayList<Volume>();
                Long currentVolumeCapacity = 0L;
                Long volumeToCompareCapacity = 0L;
                boolean matched = true;

                Long capacityToUseInCalculation = Collections.max(currentVolumeSizes);
                if (isExpand) {
                    capacityToUseInCalculation = requestedSize;
                }

                _log.info(String.format("The capacity to match to is [%s]", capacityToUseInCalculation.toString()));

                // Determine if the provisioning request requires storage systems
                // which cannot allocate storage at the exact same amount
                if (!capacitiesCanMatch(volumeStorageSystemMap) || vmax2Vmax3StorageCombo) {
                    setUnMatchedCapacities(allVolumesToUpdateCapacity, associatedVolumePersonalityMap, isExpand,
                            capacityToUseInCalculation);
                } else {
                    // Storage systems in the provisioning request can allocate storage capacity in equal amounts

                    // Compare the actual capacity of each volume against the calculated capacity of
                    // each of the other volumes. If we end up matching on the same capacity across
                    // all volumes for all storage systems, we have found a winner. If we can't match
                    // across all the storage systems, we just use the original requested volume capacity.
                    // If the capacity of the source volume ends up being bigger than that of
                    // the target, creating the CG will fail.
                    //
                    // TODO: A more complex hand-shaking algorithm should be introduced here that can converge
                    // on appropriate matching volume capacities across all storage systems.

                    for (int index = 0; index < allVolumesToCompare.size(); index++) {
                        matched = true;
                        tempVolumesList.clear();
                        tempVolumesList.addAll(allVolumesToCompare);
                        // Remove the current volume from the list and get a handle on it
                        Volume currentVolume = tempVolumesList.remove(index);
                        StorageSystem currentVolumeStorageSystem = _dbClient.queryObject(StorageSystem.class,
                                currentVolume.getStorageController());

                        // Get the System Type for the current volume
                        String currentVolumeSystemType = volumeStorageSystemMap.get(currentVolume.getStorageController()).getSystemType();
                        // Calculate the capacity for the current volume based on the Storage System type to see if it can be adjusted
                        currentVolumeCapacity = capacityCalculatorFactory.getCapacityCalculator(currentVolumeSystemType)
                                .calculateAllocatedCapacity(capacityToUseInCalculation, currentVolume, _dbClient);

                        _log.info(String.format(
                                "Volume [%s] has a capacity of %s on storage system type %s. "
                                        + "The calculated capacity for this volume is %s.",
                                currentVolume.getLabel(), currentVolume.getCapacity(), currentVolumeSystemType, currentVolumeCapacity));

                        // Compare the volume's capacity to the other volumes to see if the
                        // capacities will match.
                        for (Volume volumeToCompare : tempVolumesList) {
                            // Get the System Type for the volume to compare
                            String volumeToCompareSystemType = volumeStorageSystemMap.get(volumeToCompare.getStorageController())
                                    .getSystemType();
                            // Make sure the volume to compare is not the same storage system type as the one we used to calculate the
                            // currentVolumeCapacity above. We have already used that storage system with the capacity calculator so
                            // we don't want to adjust the capacity again, so just skip it.
                            if (volumeToCompareSystemType.equalsIgnoreCase(currentVolumeSystemType)) {
                                continue;
                            }

                            StorageSystem volumeToCompareStorageSystem = _dbClient.queryObject(StorageSystem.class,
                                    volumeToCompare.getStorageController());

                            // Calculate the capacity for the volume to compare based on the Storage System type to see if it can be
                            // adjusted
                            volumeToCompareCapacity = capacityCalculatorFactory.getCapacityCalculator(volumeToCompareSystemType)
                                    .calculateAllocatedCapacity(currentVolumeCapacity, volumeToCompare, _dbClient);

                            // Check to see if the capacities match
                            if (!currentVolumeCapacity.equals(volumeToCompareCapacity)) {
                                // If the capacities don't match, we can not use this capacity across all volumes
                                // so we will have to check the next volume. Break out of this loop and warn the user.
                                _log.warn(String.format(
                                        "Storage System %s is not capable of allocating exactly %s bytes for volume [%s], keep trying...",
                                        volumeToCompareSystemType, currentVolumeCapacity, volumeToCompare.getLabel()));
                                matched = false;
                                break;
                            } else {
                                _log.info(String.format(
                                        "Volume [%s] is capable of being provisioned at %s bytes on storage system of type %s, continue...",
                                        volumeToCompare.getLabel(), currentVolumeCapacity, volumeToCompareSystemType));
                            }
                        }

                        // If all volume capacities match, we have a winner.
                        if (matched) {
                            break;
                        }
                    }

                    if (matched) {
                        // We have found capacity that is consistent across all Storage Systems
                        capacity = currentVolumeCapacity;
                        _log.info("Found a capacity size that is consistent across all source/target(s) storage systems: " + capacity);
                        for (Volume volume : allVolumesToUpdateCapacity) {
                            if (isChangeVpool && NullColumnValueGetter.isNotNullValue(volume.getPersonality())
                                    && volume.getPersonality().equals(Volume.PersonalityTypes.SOURCE.toString())) {
                                // Don't update the existing source if this is a change vpool
                                continue;
                            }
                            updateVolumeCapacity(volume, capacity, isExpand);
                        }
                    } else {
                        // Unable to determine a matching allocation volume size across all source -> target storage systems.
                        // Circumvent CG creation, which would fail, by throwing an exception here.
                        throw APIException.internalServerErrors.noMatchingAllocationCapacityFound();
                    }
                }
            } else {
                _log.info(String
                        .format("All storage systems match and/or all volume sizes are consistent. No need for any capacity adjustments."));

                capacity = requestedSize;
            }
        } else {
            _log.error("There were no volumes found to compare capacities.");
        }

        return capacity;
    }

    /**
     * Checks to see if the two storage systems are VMAX and if so, then
     * further check to see if one of them is a VMAX2 while the other is a
     * VMAX3.
     *
     * The capacity checks for these systems are slightly different so we
     * need to ensure that they get calculated.
     *
     * @param storageSystem1 First StorageSystem to check
     * @param storageSystem2 Second StorageSystem to check
     * @return true if we do have the case of a VMAX2 to VMAX3
     *         difference between the Storage Systems, false otherwise.
     */
    private boolean checkVMAX2toVMAX3(StorageSystem storageSystem1, StorageSystem storageSystem2) {
        if (storageSystem1 != null && storageSystem2 != null) {
            if (DiscoveredDataObject.Type.vmax.name().equals(storageSystem1.getSystemType())
                    && DiscoveredDataObject.Type.vmax.name().equals(storageSystem2.getSystemType())
                    && ((!storageSystem1.checkIfVmax3() && storageSystem2.checkIfVmax3())
                            || (storageSystem1.checkIfVmax3() && !storageSystem2.checkIfVmax3()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prepare Volume for a RecoverPoint protected volume
     *
     * @param volume Volume to prepare, could be null if brand new vol
     * @param project Project for volume
     * @param varray Varray for volume
     * @param vpool Vpool for volume
     * @param size Size of volume
     * @param recommendation Main rec for this volume
     * @param label Volume label
     * @param consistencyGroup CG for volume
     * @param protectionSystemURI URI for the Protection System
     * @param personality Personality of the volume
     * @param rsetName Replication Set Name
     * @param internalSiteName RP Internal site of the volume
     * @param rpCopyName RP Copy Name
     * @param sourceVolume The source volume
     * @param vplex Boolean that is true if this is a vplex volume
     * @param changeVpoolVolume Existing volume if this is a change vpool
     * @param isPreCreatedVolume
     * @return Fully prepared Volume for RP
     */
    public Volume prepareVolume(Volume volume, Project project, VirtualArray varray, VirtualPool vpool, String size,
            RPRecommendation recommendation, String label, BlockConsistencyGroup consistencyGroup, URI protectionSystemURI,
            Volume.PersonalityTypes personality, String rsetName, String internalSiteName, String rpCopyName, Volume sourceVolume,
            boolean vplex, Volume changeVpoolVolume, boolean isPreCreatedVolume) {
        // Check to see if this is a change vpool volume, if so, use it as the already existing volume.
        volume = (changeVpoolVolume != null) ? changeVpoolVolume : volume;

        // If volume is still null, then it's a brand new volume
        boolean isNewVolume = (volume == null);

        if (isNewVolume || isPreCreatedVolume) {
            if (!isPreCreatedVolume) {
                volume = new Volume();
                volume.setId(URIUtil.createId(Volume.class));
                volume.setOpStatus(new OpStatusMap());
            } else {
                // Reload volume object from DB
                volume = _dbClient.queryObject(Volume.class, volume.getId());
            }

            volume.setSyncActive(true);
            volume.setLabel(label);
            volume.setCapacity(SizeUtil.translateSize(size));
            volume.setThinlyProvisioned(
                    VirtualPool.ProvisioningType.Thin.toString().equalsIgnoreCase(vpool.getSupportedProvisioningType()));
            volume.setVirtualPool(vpool.getId());
            volume.setProject(new NamedURI(project.getId(), volume.getLabel()));
            volume.setTenant(new NamedURI(project.getTenantOrg().getURI(), volume.getLabel()));
            volume.setVirtualArray(varray.getId());

            if (null != recommendation.getSourceStoragePool()) {
                StoragePool pool = _dbClient.queryObject(StoragePool.class, recommendation.getSourceStoragePool());
                if (null != pool) {
                    volume.setProtocol(new StringSet());
                    volume.getProtocol().addAll(VirtualPoolUtil.getMatchingProtocols(vpool.getProtocols(), pool.getProtocols()));

                    if (!vplex) {
                        volume.setPool(pool.getId());
                        volume.setStorageController(pool.getStorageDevice());
                        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, pool.getStorageDevice());
                        String systemType = storageSystem.checkIfVmax3() ? 
                                DiscoveredDataObject.Type.vmax3.name() : storageSystem.getSystemType();
                        volume.setSystemType(systemType);
                    }
                }
            }

            volume.setVirtualArray(varray.getId());
        }

        // Set all Journal Volumes to have the INTERNAL_OBJECT flag.
        if (personality.equals(Volume.PersonalityTypes.METADATA)) {
            volume.addInternalFlags(Flag.INTERNAL_OBJECT);
            volume.addInternalFlags(Flag.SUPPORTS_FORCE);
            volume.setAccessState(Volume.VolumeAccessState.NOT_READY.name());
        } else if (personality.equals(Volume.PersonalityTypes.SOURCE)) {
            volume.setAccessState(Volume.VolumeAccessState.READWRITE.name());
            volume.setLinkStatus(Volume.LinkStatus.OTHER.name());
        } else if (personality.equals(Volume.PersonalityTypes.TARGET)) {
            volume.setAccessState(Volume.VolumeAccessState.NOT_READY.name());
            volume.setLinkStatus(Volume.LinkStatus.OTHER.name());
        }

        if (consistencyGroup != null) {
            volume.setConsistencyGroup(consistencyGroup.getId());

            // If we have a new RP+VPLEX/MP change vpool volume we may need
            // to do some extra work depending on whether we need the volume's
            // backend volumes added to backend CGs.
            if (changeVpoolVolume != null && !changeVpoolVolume.checkForRp() && RPHelper.isVPlexVolume(changeVpoolVolume, _dbClient)) {

                // Only need to set the replicationGroupInstance for an existing VPlex volume
                // if the CG has array consistency enabled and the CG supports LOCAL type.
                if (consistencyGroup.getArrayConsistency()) {
                    if (null == changeVpoolVolume.getAssociatedVolumes() || changeVpoolVolume.getAssociatedVolumes().isEmpty()) {
                        _log.error("VPLEX volume {} has no backend volumes.", changeVpoolVolume.forDisplay());
                        throw InternalServerErrorException.
                            internalServerErrors.noAssociatedVolumesForVPLEXVolume(changeVpoolVolume.forDisplay());
                    }
                    for (String backendVolumeId : changeVpoolVolume.getAssociatedVolumes()) {
                        Volume backingVolume = _dbClient.queryObject(Volume.class, URI.create(backendVolumeId));

                        String rgName = consistencyGroup.getCgNameOnStorageSystem(backingVolume.getStorageController());
                        if (rgName == null) {
                            rgName = consistencyGroup.getLabel(); // for new CG
                        } else {
                            // if other volumes in the same CG are in an application, add this volume to the same application
                            VolumeGroup volumeGroup = ControllerUtils.getApplicationForCG(_dbClient, consistencyGroup, rgName);
                            if (volumeGroup != null) {
                                backingVolume.getVolumeGroupIds().add(volumeGroup.getId().toString());
                            }
                        }
                        _log.info(String.format(
                                "Preparing VPLEX volume [%s](%s) for RP Protection, "
                                        + "backend end volume [%s](%s) updated with replication group name: %s",
                                volume.getLabel(), volume.getId(), backingVolume.getLabel(), backingVolume.getId(), rgName));

                        backingVolume.setReplicationGroupInstance(rgName);
                        changeVpoolVolume.setBackingReplicationGroupInstance(rgName);
                        _dbClient.updateObject(backingVolume);
                    }
                }
            }
        }

        volume.setPersonality(personality.toString());
        volume.setProtectionController(protectionSystemURI);
        volume.setRSetName(rsetName);
        volume.setInternalSiteName(internalSiteName);
        volume.setRpCopyName(rpCopyName);

        if (NullColumnValueGetter.isNotNullValue(vpool.getAutoTierPolicyName())) {
            URI autoTierPolicyUri = StorageScheduler.getAutoTierPolicy(volume.getPool(), vpool.getAutoTierPolicyName(), _dbClient);
            if (null != autoTierPolicyUri) {
                volume.setAutoTieringPolicyUri(autoTierPolicyUri);
            }
        }

        if (isNewVolume && !isPreCreatedVolume) {
            // Create the volume in the db
            _dbClient.createObject(volume);
        } else {
            _dbClient.updateObject(volume);
        }

        // Keep track of target volumes associated with the source volume
        if (sourceVolume != null) {
            if (sourceVolume.getRpTargets() == null) {
                sourceVolume.setRpTargets(new StringSet());
            }
            sourceVolume.getRpTargets().add(volume.getId().toString());
            _dbClient.updateObject(sourceVolume);
        }

        return volume;
    }

    /**
     * Determines if all the passed in volume sizes match
     *
     * @param currentVolumeSizes
     *            all volume sizes
     * @return true if all sizes match, false otherwise
     */
    private boolean allVolumeSizesMatch(List<Long> currentVolumeSizes) {
        // If the storage systems match then check all current sizes of the volumes too, any mismatch
        // will lead to a calculation check
        List<Long> currentVolumeSizesCopy = new ArrayList<Long>();
        currentVolumeSizesCopy.addAll(currentVolumeSizes);
        for (Long currentSize : currentVolumeSizes) {
            for (Long compareSize : currentVolumeSizesCopy) {
                if (currentSize.longValue() != compareSize.longValue()) {
                    return false;
                }
            }
        }

        _log.info("All volumes are of the same size. No need for capacity calculations.");
        return true;
    }

    @Override
    public TaskList createVolumes(VolumeCreate param, Project project, VirtualArray varray, VirtualPool vpool,
            Map<VpoolUse, List<Recommendation>> recommendationMap, TaskList taskList, String task,
            VirtualPoolCapabilityValuesWrapper capabilities) throws InternalException {
        List<Recommendation> recommendations = recommendationMap.get(VpoolUse.ROOT);
        // List of volumes to be prepared
        List<URI> volumeURIs = new ArrayList<URI>();

        // Volume label from the param
        String volumeLabel = param.getName();

        // List to store the volume descriptors for the Block Orchestration
        List<VolumeDescriptor> volumeDescriptors = new ArrayList<VolumeDescriptor>();
        // Store capabilities of the CG, so they make it down to the controller
        if (vpool.getRpCopyMode() != null) {
            capabilities.put(VirtualPoolCapabilityValuesWrapper.RP_COPY_MODE, vpool.getRpCopyMode());
        }
        if (vpool.getRpRpoType() != null && NullColumnValueGetter.isNotNullValue(vpool.getRpRpoType())) {
            capabilities.put(VirtualPoolCapabilityValuesWrapper.RP_RPO_TYPE, vpool.getRpRpoType());
        }
        if (vpool.checkRpRpoValueSet()) {
            capabilities.put(VirtualPoolCapabilityValuesWrapper.RP_RPO_VALUE, vpool.getRpRpoValue());
        }

        // Get the first recommendation, we need to figure out if this is a change vpool
        RPProtectionRecommendation rpProtectionRec = (RPProtectionRecommendation) recommendations.get(0);
        boolean isChangeVpool = (rpProtectionRec.getVpoolChangeVolume() != null);
        boolean isChangeVpoolForProtectedVolume = rpProtectionRec.isVpoolChangeProtectionAlreadyExists();

        // for change vpool, save off the original source volume in case we need to roll back
        URI oldVpoolId = null;
        if (isChangeVpool || isChangeVpoolForProtectedVolume) {
            Volume changeVpoolVolume = _dbClient.queryObject(Volume.class, rpProtectionRec.getVpoolChangeVolume());
            oldVpoolId = changeVpoolVolume.getVirtualPool();
        }

        try {
            // Prepare the volumes
            prepareRecommendedVolumes(param, task, taskList, project, varray, vpool, capabilities.getResourceCount(), recommendations,
                    volumeLabel, capabilities, volumeDescriptors, volumeURIs);

            // Execute the volume creations requests for each recommendation.
            Iterator<Recommendation> recommendationsIter = recommendations.iterator();
            while (recommendationsIter.hasNext()) {
                RPProtectionRecommendation recommendation = (RPProtectionRecommendation) recommendationsIter.next();

                volumeDescriptors
                        .addAll(createVolumeDescriptors(recommendation, volumeURIs, capabilities, oldVpoolId, param.getComputeResource()));
                logDescriptors(volumeDescriptors);

                BlockOrchestrationController controller = getController(BlockOrchestrationController.class,
                        BlockOrchestrationController.BLOCK_ORCHESTRATION_DEVICE);

                // TODO might be able to use param.getSize() instead of the below code to find requestedVolumeCapactity
                Long requestedVolumeCapactity = 0L;
                for (URI volumeURI : volumeURIs) {
                    Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
                    if (Volume.PersonalityTypes.SOURCE.name().equalsIgnoreCase(volume.getPersonality())) {
                        requestedVolumeCapactity = volume.getCapacity();
                        break;
                    }
                }

                computeProtectionCapacity(volumeURIs, requestedVolumeCapactity, false, isChangeVpool, null);

                if (isChangeVpool) {
                    _log.info("Add Recoverpoint Protection to existing volume");
                    controller.changeVirtualPool(volumeDescriptors, task);
                } else {
                    _log.info("Create RP volumes");
                    controller.createVolumes(volumeDescriptors, task);
                }
            }
        } catch (Exception e) {
            _log.error(e.getMessage(), e);

            try {
                // If there is a change vpool volume, we need to ensure that we rollback protection on it.
                // We want to return the volume back to its original state.
                if (isChangeVpool || isChangeVpoolForProtectedVolume) {
                    Volume changeVpoolVolume = _dbClient.queryObject(Volume.class, rpProtectionRec.getVpoolChangeVolume());
                    VirtualPool oldVpool = _dbClient.queryObject(VirtualPool.class, oldVpoolId);
                    RPHelper.rollbackProtectionOnVolume(changeVpoolVolume, oldVpool, _dbClient);
                }

                for (URI volumeURI : volumeURIs) {
                    // Rollback any volumes that were created during prepare, excluding the change vpool volume
                    // which would have already been handled above. Not too mention we of course do not want to
                    // completely rollback an existing volume (which the change vpool volume would be).
                    if (!volumeURI.equals(rpProtectionRec.getVpoolChangeVolume())) {
                        RPHelper.rollbackVolume(volumeURI, _dbClient);
                    }
                }
            } catch (Exception e2) {
                // best effort for rollback; still need to set the tasks to error
                _log.error("rollback create volume or change vpool failed");
                _log.error(e2.getMessage(), e);
            }

            // Let's check to see if there are existing tasks, if so, put them in error.
            if (taskList.getTaskList() != null && !taskList.getTaskList().isEmpty()) {
                for (TaskResourceRep volumeTask : taskList.getTaskList()) {
                    volumeTask.setState(Operation.Status.error.name());
                    volumeTask.setMessage(e.getMessage());
                    Operation statusUpdate = new Operation(Operation.Status.error.name(), e.getMessage());
                    _dbClient.updateTaskOpStatus(Volume.class, volumeTask.getResource().getId(), task, statusUpdate);
                }
            }

            throw APIException.badRequests.rpBlockApiImplPrepareVolumeException(volumeLabel);
        }

        return taskList;
    }

    /**
     * {@inheritDoc}
     *
     * @throws InternalException
     */
    @Override
    public void deleteVolumes(final URI systemURI, final List<URI> volumeURIs, final String deletionType, final String task)
            throws InternalException {
        _log.info("Request to delete {} volume(s) with RP Protection", volumeURIs.size());
        try {
            // check to make sure no target volumes were passed in
            Iterator<Volume> dbVolumeIter = _dbClient.queryIterativeObjects(Volume.class, volumeURIs);
            while (dbVolumeIter.hasNext()) {
                Volume vol = dbVolumeIter.next();
                if (vol.checkPersonality(Volume.PersonalityTypes.TARGET)) {
                    throw APIException.badRequests.deactivateRPTargetNotSupported(vol.getLabel());
                }
            }
            super.deleteVolumes(systemURI, volumeURIs, deletionType, task);
        } catch (Exception e) {
            // Set the task to failed
            for (URI volumeID : volumeURIs) {
                if (e instanceof ServiceCoded) {
                    _dbClient.error(Volume.class, volumeID, task, (ServiceCoded) e);
                } else {
                    _dbClient.error(Volume.class, volumeID, task, RecoverPointException.exceptions.deletingRPVolume(e));
                }
            }
            throw RecoverPointException.exceptions.deletingRPVolume(e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws InternalException
     */
    @Override
    public <T extends DataObject> String checkForDelete(T object, List<Class<? extends DataObject>> excludeTypes) throws InternalException {
        // The standard dependency checker really doesn't fly with RP because we need to determine if we can do
        // a tear-down of the volume, and that tear-down involved cleaning up dependent relationships.
        // So this will be a bit more manual and calculated. In order to determine if we can delete this object,
        // we need to make sure:
        // 1. This device is a SOURCE device if the protection set is happy and healthy (this is done before we get
        // here)
        // 2. This device and all of the other devices don't have any block snapshots
        // 3. If the device isn't part of a healthy protection set, then do a dependency check

        // Generate a list of dependencies, if there are any.
        Map<URI, URI> dependencies = new HashMap<URI, URI>();

        Volume sourceVolume = (Volume) object;
        // Get all of the snapshots associated with the volume
        for (BlockSnapshot snapshot : this.getSnapshotsForVolume(sourceVolume)) {
            if (snapshot != null && !snapshot.getInactive()) {
                dependencies.put(sourceVolume.getId(), snapshot.getId());
            }

            // Get all the snapshots of the corresponding target volumes
            if (Volume.PersonalityTypes.SOURCE.name().equals(sourceVolume.getPersonality())) {
                StringSet rpTargets = sourceVolume.getRpTargets();
                for (String rpTarget : rpTargets) {
                    Volume rpTargetVolume = _dbClient.queryObject(Volume.class, URI.create(rpTarget));
                    for (BlockSnapshot targetSnapshot : this.getSnapshotsForVolume(rpTargetVolume)) {
                        if (targetSnapshot != null && !targetSnapshot.getInactive()) {
                            dependencies.put(rpTargetVolume.getId(), targetSnapshot.getId());
                        }
                    }
                }
            }
        }

        if (!dependencies.isEmpty()) {
            throw APIException.badRequests.cannotDeleteVolumeBlockSnapShotExists(String.valueOf(dependencies));
        }

        List<URI> volumeIDs = _rpHelper.getReplicationSetVolumes((Volume) object);

        // Do a relatively "normal" check, as long as it's a "broken"
        // protection set.
        // It's considered a broken protection set if there's only one
        // volume returned from the getRPVolumes() call,
        // Because that can only have happened if the CG was already torn
        // down.
        if (volumeIDs.size() == 1) {
            List<Class<? extends DataObject>> excludes = new ArrayList<Class<? extends DataObject>>();
            if (excludeTypes != null) {
                excludes.addAll(excludeTypes);
            }
            excludes.add(Task.class);
            String depMsg = _dependencyChecker.checkDependencies(object.getId(), object.getClass(), true, excludes);
            if (depMsg != null) {
                return depMsg;
            }
            return object.canBeDeleted();
        }

        return null;
    }

    @Override
    public TaskList deactivateMirror(StorageSystem device, URI mirror, String task, String deleteType) {
        throw APIException.methodNotAllowed.notSupported();
    }

    @Override
    public StorageSystemConnectivityList getStorageSystemConnectivity(StorageSystem system) {
        _log.debug("getStorageSystemConnectivity START");
        // Connectivity list to return
        StorageSystemConnectivityList connectivityList = new StorageSystemConnectivityList();
        // Set used to ensure unique values are added to the connectivity list
        Set<String> existing = new HashSet<String>();

        // Get all the RPSiteArrays that contain this Storage System
        List<RPSiteArray> rpSiteArrays = CustomQueryUtility.queryActiveResourcesByConstraint(_dbClient, RPSiteArray.class,
                AlternateIdConstraint.Factory.getConstraint(RPSiteArray.class, "storageSystem", system.getId().toString()));

        Map<URI, ProtectionSystem> protSysMap = new HashMap<URI, ProtectionSystem>();
        Map<String, StorageSystem> storageSystemBySerialNumber = new HashMap<String, StorageSystem>();

        // For each of the RPSiteArrays get the Protection System
        for (RPSiteArray rpSiteArray : rpSiteArrays) {
            if ((rpSiteArray.getRpProtectionSystem() != null)) {
                ProtectionSystem protectionSystem = protSysMap.get(rpSiteArray.getRpProtectionSystem());
                if (protectionSystem == null) {
                    protectionSystem = _dbClient.queryObject(ProtectionSystem.class, rpSiteArray.getRpProtectionSystem());
                    protSysMap.put(protectionSystem.getId(), protectionSystem);
                }
                // Loop through the associated Storage Systems for this Protection System to build the
                // connectivity response list. Only store unique responses.
                for (String associatedStorageSystemStr : protectionSystem.getAssociatedStorageSystems()) {
                    String associatedStorageSystemSerialNumber = ProtectionSystem
                            .getAssociatedStorageSystemSerialNumber(associatedStorageSystemStr);
                    StorageSystem associatedStorageSystem = storageSystemBySerialNumber.get(associatedStorageSystemSerialNumber);
                    if (associatedStorageSystem == null) {
                        URI associatedStorageSystemURI = ConnectivityUtil
                                .findStorageSystemBySerialNumber(associatedStorageSystemSerialNumber, _dbClient, StorageSystemType.BLOCK);
                        associatedStorageSystem = _dbClient.queryObject(StorageSystem.class, associatedStorageSystemURI);
                        if (associatedStorageSystem == null || associatedStorageSystem.getInactive()) {
                            continue;
                        }
                        storageSystemBySerialNumber.put(associatedStorageSystemSerialNumber, associatedStorageSystem);
                    }
                    if (ConnectivityUtil.isAVPlex(associatedStorageSystem)) {
                        continue;
                    }

                    StorageSystemConnectivityRestRep connection = new StorageSystemConnectivityRestRep();
                    connection.getConnectionTypes().add(ProtectionSystem._RP);
                    connection.setProtectionSystem(toNamedRelatedResource(ResourceTypeEnum.PROTECTION_SYSTEM,
                            rpSiteArray.getRpProtectionSystem(), protectionSystem.getLabel()));
                    connection.setStorageSystem(toNamedRelatedResource(ResourceTypeEnum.STORAGE_SYSTEM, associatedStorageSystem.getId(),
                            associatedStorageSystem.getSerialNumber()));

                    // The key is a transient unique ID, since none of the actual fields guarantee uniqueness.
                    // We use this to make sure we don't add the same storage system more than once for the same
                    // protection system and connection type.
                    String key = connection.getProtectionSystem().toString() + connection.getConnectionTypes()
                            + connection.getStorageSystem().toString();
                    if (!existing.contains(key)) {
                        existing.add(key);
                        connectivityList.getConnections().add(connection);
                    }
                }
            }
        }

        _log.debug("getStorageSystemConnectivity END");
        return connectivityList;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void verifyVarrayChangeSupportedForVolumeAndVarray(Volume volume, VirtualArray newVarray) throws APIException {
        throw APIException.badRequests.changesNotSupportedFor("VirtualArray", "RP protected volumes");
    }

    /**
     * Update the replication mode for the CG in RecoverPoint.
     *
     * @param volumes the selected volumes who's common CG is being updated.
     * @param newVpool the new virtual pool with the replication mode change specified.
     * @param taskId the task id
     * @throws InternalException
     */
    private void updateReplicationMode(List<Volume> volumes, VirtualPool newVpool, String taskId) throws InternalException {
        if (volumes == null || volumes.isEmpty()) {
            throw APIException.badRequests.vpoolChangeNotAllowedInvalidVolumeList();
        }

        // Validate all volumes to ensure they have a valid protection system and
        // consistency group associated to them.
        List<URI> volumeURIs = new ArrayList<URI>();

        Volume firstVol = volumes.get(0);

        // Verify that all volumes belong to the same consistency group.
        URI cgURI = firstVol.getConsistencyGroup();

        for (Volume volume : volumes) {
            // Collect a list of volume URIs to be used for the controller call.
            volumeURIs.add(volume.getId());

            if (NullColumnValueGetter.isNullURI(volume.getProtectionController())
                    || NullColumnValueGetter.isNullURI(volume.getConsistencyGroup())) {
                // Either the protection system or CG is invalid so we must fail.
                throw APIException.badRequests.vpoolChangeInvalidProtectionSystemOrCg(volume.getId().toString());
            }

            if (!cgURI.equals(volume.getConsistencyGroup())) {
                // Not all volumes belong to the same consistency group.
                throw APIException.badRequests.vpoolChangeNotAllowedCGsMustBeTheSame();
            }
        }

        RPController controller = getController(RPController.class, "rp");
        controller.updateConsistencyGroupPolicy(firstVol.getProtectionController(), firstVol.getConsistencyGroup(), volumeURIs,
                newVpool.getId(), taskId);
    }

    /**
     * Upgrade a local block volume to a protected RP volume
     *
     * @param changeVpoolVolume the existing volume being protected.
     * @param newVpool the requested virtual pool
     * @param vpoolChangeParam Param sent down by the API Service
     * @param taskId the task identifier
     * @throws InternalException
     */
    private void upgradeToProtectedVolume(Volume changeVpoolVolume, VirtualPool newVpool, VirtualPoolChangeParam vpoolChangeParam,
            String taskId) throws InternalException {

        Project project = _dbClient.queryObject(Project.class, changeVpoolVolume.getProject());

        if (VirtualPool.vPoolSpecifiesProtection(newVpool)) {
            // The volume can not already be in a CG for RP+VPLEX. This may change in the future, but
            // for now we can not allow it. Inform the user that they will first need to remove
            // the volume from the existing CG before they can proceed.
            if (!NullColumnValueGetter.isNullURI(changeVpoolVolume.getConsistencyGroup())) {
                BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, changeVpoolVolume.getConsistencyGroup());
                throw APIException.badRequests.cannotCreateRPVolumesInCG(changeVpoolVolume.getLabel(), cg.getLabel());
            }

            // The user needs to specify a CG for this operation.
            if (vpoolChangeParam.getConsistencyGroup() == null) {
                throw APIException.badRequests.addRecoverPointProtectionRequiresCG();
            }
            
            if (!CollectionUtils.isEmpty(getSnapshotsForVolume(changeVpoolVolume))) {
                throw APIException.badRequests.cannotAddProtectionWhenSnapshotsExist(changeVpoolVolume.getLabel());
            }
        }

        VirtualPoolCapabilityValuesWrapper capabilities = new VirtualPoolCapabilityValuesWrapper();
        capabilities.put(VirtualPoolCapabilityValuesWrapper.SIZE, changeVpoolVolume.getCapacity());
        capabilities.put(VirtualPoolCapabilityValuesWrapper.RESOURCE_COUNT, 1);
        capabilities.put(VirtualPoolCapabilityValuesWrapper.BLOCK_CONSISTENCY_GROUP, vpoolChangeParam.getConsistencyGroup());
        capabilities.put(VirtualPoolCapabilityValuesWrapper.CHANGE_VPOOL_VOLUME, changeVpoolVolume.getId().toString());

        List<Recommendation> recommendations = getRecommendationsForVirtualPoolChangeRequest(changeVpoolVolume, newVpool, vpoolChangeParam,
                capabilities);

        if (recommendations.isEmpty()) {
            throw APIException.badRequests.noStorageFoundForVolume();
        }

        // Get the volume's varray
        VirtualArray varray = _dbClient.queryObject(VirtualArray.class, changeVpoolVolume.getVirtualArray());

        // Generate a VolumeCreate object that contains the information that createVolumes likes to consume.
        VolumeCreate param = new VolumeCreate(changeVpoolVolume.getLabel(), String.valueOf(changeVpoolVolume.getCapacity()), 1,
                newVpool.getId(), changeVpoolVolume.getVirtualArray(), changeVpoolVolume.getProject().getURI());

        TaskList taskList = new TaskList();
        createTaskForVolume(changeVpoolVolume, ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VPOOL, taskList, taskId);
        Map<VpoolUse, List<Recommendation>> recommendationMap = new HashMap<VpoolUse, List<Recommendation>>();
        recommendationMap.put(VpoolUse.ROOT, recommendations);
        createVolumes(param, project, varray, newVpool, recommendationMap, taskList, taskId, capabilities);
    }

    /**
     * {@inheritDoc}
     *
     * @throws InternalException
     */
    @Override
    public TaskList changeVolumeVirtualPool(URI systemURI, Volume volume, VirtualPool newVpool,
            VirtualPoolChangeParam vpoolChangeParam, String taskId) throws InternalException {
        _log.info(String.format("RP change virtual pool operation for volume [%s](%s) moving to new vpool [%s](%s).",
                volume.getLabel(), volume.getId(), newVpool.getLabel(), newVpool.getId()));

        ArrayList<Volume> volumes = new ArrayList<Volume>();
        volumes.add(volume);

        // Check for common Vpool updates handled by generic code. It returns true if handled.
        if (checkCommonVpoolUpdates(volumes, newVpool, taskId)) {
            return null;
        }

        // Get the storage system to check for supported types
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, systemURI);
        String systemType = storageSystem.getSystemType();
        if ((DiscoveredDataObject.Type.vplex.name().equals(systemType))
                || (DiscoveredDataObject.Type.vmax.name().equals(systemType))
                || (DiscoveredDataObject.Type.vnxblock.name().equals(systemType))
                || (DiscoveredDataObject.Type.xtremio.name().equals(systemType))
                || (DiscoveredDataObject.Type.unity.name().equals(systemType))) {

            // Get the current vpool
            VirtualPool currentVpool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());

            String currentVpoolCopyMode = currentVpool.getRpCopyMode() == null ? "" : currentVpool.getRpCopyMode();
            String newVpoolCopyMode = newVpool.getRpCopyMode() == null ? "" : newVpool.getRpCopyMode();

            if (volume.checkForRp() && !VirtualPool.vPoolSpecifiesMetroPoint(currentVpool)
                    && VirtualPool.vPoolSpecifiesRPVPlex(currentVpool) && VirtualPool.vPoolSpecifiesMetroPoint(newVpool)) {
                _log.info("Upgrade To MetroPoint");
                upgradeToMetroPointVolume(volume, newVpool, vpoolChangeParam, taskId);
            } else if (volume.checkForRp() && !currentVpoolCopyMode.equals(newVpoolCopyMode)) {
                _log.info("Change Replication Mode");
                updateReplicationMode(volumes, newVpool, taskId);
            } else {
                _log.info("Add RecoverPoint Protection");
                upgradeToProtectedVolume(volume, newVpool, vpoolChangeParam, taskId);
            }
        }
        return null;
    }

    @Override
    public TaskList changeVolumeVirtualPool(List<Volume> volumes, VirtualPool vpool,
            VirtualPoolChangeParam vpoolChangeParam, String taskId) throws InternalException {        
        TaskList taskList = createTasksForVolumes(vpool, volumes, taskId);
        
        StringBuffer notSuppReasonBuff = new StringBuffer();
        notSuppReasonBuff.setLength(0);
        
        // Get the first volume for the change vpool operation
        Volume firstVolume =  volumes.get(0);
        
        // Get the current vpool from the first volume
        VirtualPool currentVpool = _dbClient.queryObject(VirtualPool.class, firstVolume.getVirtualPool());

        // Container for RP+VPLEX migrations (if there are any)
        List<RPVPlexMigration> validMigrations = new ArrayList<RPVPlexMigration>();
        
        if (firstVolume.checkForRp() && firstVolume.checkPersonality(Volume.PersonalityTypes.METADATA)) {
            boolean vplex = RPHelper.isVPlexVolume(firstVolume, _dbClient);
            if (vplex) {
                if (VirtualPoolChangeAnalyzer.vpoolChangeRequiresMigration(currentVpool, vpool)) {
                    // Allow the VPLEX Data Migration operation for the RP+VPLEX Journal 
                    // to proceed via the VPLEX Block Service.
                    vplexBlockServiceApiImpl.changeVolumeVirtualPool(volumes, vpool, vpoolChangeParam, taskId);
                }
            } 
        } else if (firstVolume.checkForRp()
                && !VirtualPool.vPoolSpecifiesProtection(vpool)) {
            removeProtection(volumes, vpool, taskId);
        } else if (VirtualPoolChangeAnalyzer.isSupportedRPVPlexMigrationVirtualPoolChange(firstVolume, currentVpool, vpool,
                _dbClient, notSuppReasonBuff, validMigrations)) {
            taskList.getTaskList().addAll(rpVPlexDataMigration(volumes, vpool, taskId, validMigrations, vpoolChangeParam).getTaskList());
        } else {
            // For now we only support changing the virtual pool for a single volume at a time
            // until CTRL-1347 and CTRL-5609 are fixed.
            if (volumes.size() == 1) {
                changeVolumeVirtualPool(firstVolume.getStorageController(), firstVolume, vpool, vpoolChangeParam, taskId);
            } else {
                throw APIException.methodNotAllowed.notSupportedWithReason(
                        "Multiple volume change virtual pool is currently not supported for RecoverPoint. "
                                + "Please select one volume at a time.");
            }
        }
        
        return taskList;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void verifyVolumeExpansionRequest(Volume volume, long newSize) {
        _log.debug("Verify if RP volume {} can be expanded", volume.getId());

        boolean vplex = RPHelper.isVPlexVolume(volume, _dbClient);
        
        //validate the source
        if (vplex) {
        	vplexBlockServiceApiImpl.verifyVolumeExpansionRequest(volume, newSize);
        } else {
           super.verifyVolumeExpansionRequest(volume, newSize);
        }
        
        //validate the targets
        if (volume.getRpTargets() != null) {
        	for (String volumeId : volume.getRpTargets()) {        	   
        		Volume targetVolume = _dbClient.queryObject(Volume.class, URI.create(volumeId));
	        		
        		if (RPHelper.isVPlexVolume(targetVolume, _dbClient)) {
        			if (targetVolume.getAssociatedVolumes() != null && !targetVolume.getAssociatedVolumes().isEmpty()) {
        				vplexBlockServiceApiImpl.verifyVolumeExpansionRequest(targetVolume, newSize);
	        		} 
        		} else {
                    super.verifyVolumeExpansionRequest(targetVolume, newSize); 	                     
        		}
    		}        	
        } else {
        	throw APIException.badRequests.notValidRPSourceVolume(volume.getLabel());        	
        }
        
        // Validate the source volume size is not greater than the target volume size
        RPHelper.validateRSetVolumeSizes(_dbClient, Arrays.asList(volume));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expandVolume(Volume volume, long newSize, String taskId) throws InternalException {
        Long originalVolumeSize = volume.getCapacity();
        List<URI> replicationSetVolumes = _rpHelper.getReplicationSetVolumes(volume);
        Map<URI, StorageSystem> volumeStorageSystems = new HashMap<URI, StorageSystem>();
        // Step1 : Determine if either the backing volume of the VPLEX RP source or target is on VMAX. If yes, then set
        // the requested volume size to the size as determined by if the VMAX volume is meta volume or not.
        // In the case of meta volumes, the actual provisioned volume size/capacity will be different than the requested
        // size. If either the VPLEX+RP source/target is on VMAX/VNX and the target/source is
        // on VNX/VMAX, then the size of the VNX volume must match the size of the VMAX volume after expand is done.
        // TODO: Move this segment of the code that computes if one of the volumes is VMAX and determines the potential
        // provisioned capacity into the computeProtectionAllocation Capacity.
        for (URI rpVolumeURI : replicationSetVolumes) {
            Volume rpVolume = _dbClient.queryObject(Volume.class, rpVolumeURI);
            Volume vmaxVolume = null;
            StorageSystem vmaxStorageSystem = null;
            if (rpVolume.getAssociatedVolumes() != null && !rpVolume.getAssociatedVolumes().isEmpty()) {
                // Check backend volumes for VPLEX
                for (String backingVolumeStr : rpVolume.getAssociatedVolumes()) {
                    Volume backingVolume = _dbClient.queryObject(Volume.class, URI.create(backingVolumeStr));
                    StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, backingVolume.getStorageController());
                    if (storageSystem.getSystemType().equalsIgnoreCase(DiscoveredDataObject.Type.vmax.toString())) {
                        vmaxVolume = backingVolume;
                        vmaxStorageSystem = storageSystem;
                        break;
                    }
                }
            } else {
                StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, rpVolume.getStorageController());
                if (storageSystem.getSystemType().equalsIgnoreCase(DiscoveredDataObject.Type.vmax.toString())) {
                    vmaxVolume = rpVolume;
                    vmaxStorageSystem = storageSystem;
                }
            }

            if (vmaxVolume != null && vmaxStorageSystem != null) {
                // Set the requested size to what the VMAX Meta utils determines will possibly be the provisioned
                // capacity.
                // All the other volumes, will need to be the same size as well or else RP will have a fit.
                newSize = _rpHelper.computeVmaxVolumeProvisionedCapacity(newSize, vmaxVolume, vmaxStorageSystem);
                _log.info(String.format("VMAX volume detected, expand size re-calculated to [%d]", newSize));
                // No need to continue, newSize has been calculated.
                break;
            }
        }

        try {
            List<Volume> allVolumesToUpdateCapacity = new ArrayList<Volume>();
            Map<URI, String> associatedVolumePersonalityMap = new HashMap<URI, String>();
            for (URI rpVolumeURI : replicationSetVolumes) {
                Volume rpVolume = _dbClient.queryObject(Volume.class, rpVolumeURI);
                if (rpVolume.getAssociatedVolumes() != null && !rpVolume.getAssociatedVolumes().isEmpty()) {
                    // Check backend volumes for VPLEX
                    for (String backingVolumeStr : rpVolume.getAssociatedVolumes()) {
                        Volume backingVolume = _dbClient.queryObject(Volume.class, URI.create(backingVolumeStr));
                        allVolumesToUpdateCapacity.add(backingVolume);
                        associatedVolumePersonalityMap.put(backingVolume.getId(), rpVolume.getPersonality());
                        addVolumeStorageSystem(volumeStorageSystems, backingVolume);
                    }
                } else {
                    allVolumesToUpdateCapacity.add(rpVolume);
                    addVolumeStorageSystem(volumeStorageSystems, rpVolume);
                }
            }

            if (!capacitiesCanMatch(volumeStorageSystems)) {
                Map<Volume.PersonalityTypes, Long> capacities = setUnMatchedCapacities(allVolumesToUpdateCapacity,
                        associatedVolumePersonalityMap, true, newSize);
                _log.info(
                        "Capacities for source and target of the Volume Expand request cannot match due to the differences in array types");
                _log.info("Expand Volume requested size : {}", newSize);
                _log.info("Expand source calculated size : {}", capacities.get(Volume.PersonalityTypes.SOURCE));
                _log.info("Expand target calcaluted size : {}", capacities.get(Volume.PersonalityTypes.TARGET));
                List<VolumeDescriptor> volumeDescriptors = createVolumeDescriptors(null, replicationSetVolumes, null, null, null);

                for (VolumeDescriptor volDesc : volumeDescriptors) {
                    if (volDesc.getType() == VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE) {
                        volDesc.setVolumeSize(capacities.get(Volume.PersonalityTypes.SOURCE));
                    } else if ((volDesc.getType() == VolumeDescriptor.Type.RP_TARGET)
                            || (volDesc.getType() == VolumeDescriptor.Type.RP_VPLEX_VIRT_TARGET)) {
                        volDesc.setVolumeSize(capacities.get(Volume.PersonalityTypes.TARGET));
                    }
                }

                BlockOrchestrationController controller = getController(BlockOrchestrationController.class,
                        BlockOrchestrationController.BLOCK_ORCHESTRATION_DEVICE);
                controller.expandVolume(volumeDescriptors, taskId);

            } else {
                // Step 2: Just because we have a RP source/target on VMAX and have a size calculated doesn't mean VNX can honor it.
                // The trick is that the size of the volume must be a multiple of 512 for VNX and 520 for VMAX because of the different
                // block sizes.
                // We will find a number (in bytes) that is greater than the requested size and meets the above criteria and use that our
                // final expanded volume size.
                long normalizedRequestSize = computeProtectionCapacity(replicationSetVolumes, newSize, true, false, null);

                // Step 3: Call the controller to do the expand.
                _log.info("Expand volume request size : {}", normalizedRequestSize);
                List<VolumeDescriptor> volumeDescriptors = createVolumeDescriptors(null, replicationSetVolumes, null, null, null);

                for (VolumeDescriptor volDesc : volumeDescriptors) {
                    volDesc.setVolumeSize(normalizedRequestSize);
                }

                BlockOrchestrationController controller = getController(BlockOrchestrationController.class,
                        BlockOrchestrationController.BLOCK_ORCHESTRATION_DEVICE);
                controller.expandVolume(volumeDescriptors, taskId);
            }
        } catch (ControllerException e) {
            // Set the volume size back to original size before the expand request
            for (URI volumeURI : replicationSetVolumes) {
                Volume rpVolume = _dbClient.queryObject(Volume.class, volumeURI);
                rpVolume.setCapacity(originalVolumeSize);
                _dbClient.updateObject(rpVolume);

                // Reset the backing volumes as well, if they exist
                if (rpVolume.getAssociatedVolumes() != null && !rpVolume.getAssociatedVolumes().isEmpty()) {
                    // Check backend volumes for VPLEX
                    for (String backingVolumeStr : rpVolume.getAssociatedVolumes()) {
                        Volume backingVolume = _dbClient.queryObject(Volume.class, URI.create(backingVolumeStr));
                        backingVolume.setCapacity(originalVolumeSize);
                        _dbClient.updateObject(backingVolume);
                    }
                }
            }
            throw APIException.badRequests.volumeNotExpandable(volume.getLabel());
        }
    }

    @Override
    protected Set<URI> getConnectedVarrays(URI varrayUID) {

        Set<URI> varrays = new HashSet<URI>();

        List<ProtectionSystem> protectionSystems = CustomQueryUtility.queryActiveResourcesByConstraint(_dbClient, ProtectionSystem.class,
                AlternateIdConstraint.Factory.getConstraint(ProtectionSystem.class, VIRTUAL_ARRAYS_CONSTRAINT, varrayUID.toString()));

        // Create and return the result.
        for (ProtectionSystem protectionSystem : protectionSystems) {
            if (protectionSystem.getVirtualArrays() != null) {
                for (String varrayURIStr : protectionSystem.getVirtualArrays()) {
                    varrays.add(URI.create(varrayURIStr));
                }
            }
        }

        return varrays;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateCreateSnapshot(Volume reqVolume, List<Volume> volumesToSnap, String snapshotType, String snapshotName,
            BlockFullCopyManager fcManager) {
        boolean vplex = RPHelper.isVPlexVolume(reqVolume, _dbClient);

        // For RP snapshots, validate that the volume type is not a target,
        // metadata, or null - must be source.
        if (snapshotType.equalsIgnoreCase(BlockSnapshot.TechnologyType.RP.toString())
                && (reqVolume.getPersonality() == null || !reqVolume.getPersonality().equals(PersonalityTypes.SOURCE.toString()))) {
            String volumeType = reqVolume.getPersonality() != null ? reqVolume.getPersonality().toLowerCase() : UNKNOWN_VOL_TYPE;
            throw APIException.badRequests.notSupportedSnapshotVolumeType(volumeType);
        }

        if (vplex) {
            // If this is a local array snap of an RP+VPlex source volume, we need to check for mixed
            // backing array storage systems. We cannot create local array snaps if the source volumes
            // in the CG don't all use the same backing arrays.
            if (snapshotType.equalsIgnoreCase(BlockSnapshot.TechnologyType.NATIVE.toString())
                    && NullColumnValueGetter.isNotNullValue(reqVolume.getPersonality())
                    && reqVolume.getPersonality().equals(PersonalityTypes.SOURCE.toString())) {
                if (!NullColumnValueGetter.isNullURI(reqVolume.getConsistencyGroup())) {
                    BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, reqVolume.getConsistencyGroup());
                    if (containsMixedBackingArrays(cg)) {
                        throw APIException.badRequests.notSupportedSnapshotWithMixedArrays(cg.getId());
                    }
                }
            }
        }

        // If not an RP bookmark snapshot, then do what the base class does, else
        // we just need to verify the volumes and name.
        if (!snapshotType.equalsIgnoreCase(TechnologyType.RP.toString())) {
            List<Volume> baseVolumesToSnap = new ArrayList<Volume>();
            if (vplex) {
                for (Volume vplexVolume : volumesToSnap) {
                    if (vplexVolume.getPersonality().equals(Volume.PersonalityTypes.TARGET.toString())
                            || vplexVolume.getPersonality().equals(Volume.PersonalityTypes.SOURCE.toString())) {
                        baseVolumesToSnap.add(vplexBlockServiceApiImpl.getVPLEXSnapshotSourceVolume(vplexVolume));
                    }
                }
            }
            List<Volume> volumes = (vplex ? baseVolumesToSnap : volumesToSnap);
            super.validateCreateSnapshot(reqVolume, volumes, snapshotType, snapshotName, fcManager);
        } else {
            ArgValidator.checkFieldNotEmpty(volumesToSnap, "volumes");
            ArgValidator.checkFieldNotEmpty(snapshotName, "name");

            // Check if there are any snapshots of the same name for the volume(s)
            for (Volume volumeToSnap : volumesToSnap) {
                checkForDuplicatSnapshotName(snapshotName, volumeToSnap);
            }
        }
    }

    /**
     * Determines if the CG source volumes contain mixed backing arrays.
     *
     * @param cg the consistency group to search.
     * @return true if the CG contains source volumes with mixed backing arrays, false otherwise.
     */
    private boolean containsMixedBackingArrays(BlockConsistencyGroup cg) {
        // Get the active RP SOURCE VPLEX volumes for the consistency group.
        List<Volume> vplexVolumes = BlockConsistencyGroupUtils.getActiveVplexVolumesInCG(cg, _dbClient, PersonalityTypes.SOURCE);

        if (vplexVolumes.size() > 1) {
            // Get the first volume's source side backing array. Compare the backing arrays
            // from other source volume's in the CG to see if they are different.
            URI storageSystemToCompare = getSourceBackingVolumeStorageSystem(vplexVolumes.get(0));

            for (Volume volume : vplexVolumes) {
                URI storageSystem = getSourceBackingVolumeStorageSystem(volume);
                if (!storageSystem.equals(storageSystemToCompare)) {
                    // The backing storage systems for the source side are different between
                    // volumes in the CG.
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Gets the non-HA side backing volume storage system for the given VPlex volume.
     *
     * @param vplexVolume
     *            the VPlex volume.
     * @return the storage system URI corresponding to the backing volume.
     */
    private URI getSourceBackingVolumeStorageSystem(Volume vplexVolume) {
        // Get the backing volume associated with the source side only
        Volume localVolume = VPlexUtil.getVPLEXBackendVolume(vplexVolume, true, _dbClient);

        return localVolume.getStorageController();
    }

    /**
     * Prepares the snapshots for a snapshot request.
     *
     * @param volumes The volumes for which snapshots are to be created.
     * @param snapShotType The snapshot technology type.
     * @param snapshotName The snapshot name.
     * @param snapshotURIs [OUT] The URIs for the prepared snapshots.
     * @param taskId The unique task identifier
     *
     * @return The list of snapshots
     */
    @Override
    public List<BlockSnapshot> prepareSnapshots(List<Volume> volumes, String snapshotType, String snapshotName, List<URI> snapshotURIs,
            String taskId) {

        List<BlockSnapshot> snapshots = new ArrayList<BlockSnapshot>();
        int index = 1;
        for (Volume volume : volumes) {
            VolumeGroup volumeGroup = volume.getApplication(_dbClient);
            boolean isInApplication = volumeGroup != null && !volumeGroup.getInactive();
            if (RPHelper.isProtectionBasedSnapshot(volume, snapshotType, _dbClient)
                    && snapshotType.equalsIgnoreCase(BlockSnapshot.TechnologyType.RP.toString())) {
                // For protection-based snapshots, get the protection domains we
                // need to create snapshots on
                if (!volume.getRpTargets().isEmpty()) {
                    List<URI> targetVolumeURIs = new ArrayList<URI>();

                    // Build a URI list of target volumes for the call to obtain copy access states
                    for (String targetVolumeStr : volume.getRpTargets()) {
                        targetVolumeURIs.add(URI.create(targetVolumeStr));
                    }

                    // Get a handle on the RPController so we can query the access states associated with the
                    // target volumes.
                    RPController rpController = getController(RPController.class, ProtectionSystem._RP);
                    Map<URI, String> copyAccessStates = rpController
                            .getCopyAccessStates(volume.getProtectionController(), targetVolumeURIs);

                    for (URI targetVolumeURI : targetVolumeURIs) {
                        Volume targetVolume = _dbClient.queryObject(Volume.class, targetVolumeURI);

                        // Only prepare the snapshot if the copy corresponding to the target is NOT in direct
                        // access mode. When a RecoverPoint copy is in direct access mode, no RP bookmarks
                        // can be created for that copy.
                        if (copyAccessStates != null && !copyAccessStates.isEmpty()
                                && RPHelper.isValidBookmarkState(copyAccessStates.get(targetVolume.getId()))) {
                            BlockSnapshot snapshot = prepareSnapshotFromVolume(volume, snapshotName, targetVolume, 0, snapshotType,
                                    isInApplication);
                            snapshot.setOpStatus(new OpStatusMap());
                            snapshot.setEmName(snapshotName);
                            snapshot.setEmInternalSiteName(targetVolume.getInternalSiteName());
                            snapshot.setVirtualArray(targetVolume.getVirtualArray());
                            snapshots.add(snapshot);

                            _log.info(String.format("Prepared snapshot : [%s]", snapshot.getLabel()));
                        } else {
                            _log.warn(String
                                    .format("A BlockSnapshot is not being prepared for target volume %s because copy %s is currently in a state [%s] that does not allow bookmarks to be created.",
                                            targetVolume.getId(), targetVolume.getRpCopyName(), copyAccessStates.get(targetVolume.getId())));
                        }
                    }
                }
            } else {
                boolean vplex = RPHelper.isVPlexVolume(volume, _dbClient);
                Volume volumeToSnap = volume;
                if (vplex) {
                    volumeToSnap = vplexBlockServiceApiImpl.getVPLEXSnapshotSourceVolume(volume);
                }

                boolean isRPTarget = false;
                if (NullColumnValueGetter.isNotNullValue(volume.getPersonality())
                        && volume.getPersonality().equals(PersonalityTypes.TARGET.name())) {
                    isRPTarget = true;
                }

                BlockSnapshot snapshot = prepareSnapshotFromVolume(volumeToSnap, snapshotName, (isRPTarget ? volume : null), index++,
                        snapshotType, isInApplication);
                snapshot.setTechnologyType(snapshotType);

                // Check to see if the RP Copy Name of this volume contains any of the RP Source
                // suffix's appended by ViPR
                boolean rpCopyNameContainsSrcSuffix = NullColumnValueGetter.isNotNullValue(volume.getRpCopyName())
                        && (volume.getRpCopyName().contains(SRC_COPY_SUFFIX)
                                || volume.getRpCopyName().contains(MP_ACTIVE_COPY_SUFFIX)
                                || volume.getRpCopyName().contains(MP_STANDBY_COPY_SUFFIX));
                // Hotfix for COP-18957
                // Check to see if the requested volume is a former Source.
                // We do this by checking to see if this is a Target volume and that the RP Copy Name
                // contains any of the RP Source suffix's appended by ViPR.
                //
                // TODO: This is a short term solution since there will be a better way of determining
                // this in future releases.
                //
                // FIXME: One concern here is RP ingestion where ViPR isn't the one who sets the copy names.
                // Valid concern and this needs to be changed when RP ingest supports RP+VPLEX: Yoda/Yoda+.
                boolean isFormerSource = isRPTarget && rpCopyNameContainsSrcSuffix;

                // Check to see if the requested volume is a former target that is now the
                // source as a result of a swap. This is done by checking the source volume's
                // virtual pool for RP protection. If RP protection does not exist, we know this
                // is a former target.
                // TODO: In the future the swap functionality should update the vpools accordingly to
                // add/remove protection. This check should be removed at that point and another
                // method to check for a swapped state should be used.
                boolean isFormerTarget = false;
                if (NullColumnValueGetter.isNotNullValue(volume.getPersonality()) &&
                        volume.getPersonality().equals(PersonalityTypes.SOURCE.name()) &&
                        !rpCopyNameContainsSrcSuffix) {
                    isFormerTarget = true;
                }

                if (!isInApplication && (((isRPTarget || isFormerTarget) && vplex && !isFormerSource) || !vplex)) {
                    // For RP+Vplex targets (who are not former source volumes) and former target volumes,
                    // we do not want to create a backing array CG snap. To avoid doing this, we do not
                    // set the consistency group.
                    // OR
                    // This is a native snapshot so do not set the consistency group, otherwise
                    // the SMIS code/array will get confused trying to look for a consistency
                    // group that only exists in RecoverPoint.
                    snapshot.setConsistencyGroup(null);
                }

                snapshots.add(snapshot);

                _log.info(String.format("Prepared snapshot : [%s]", snapshot.getLabel()));
            }
        }

        if (!snapshots.isEmpty()) {
            for (BlockSnapshot snapshot : snapshots) {
                Operation op = new Operation();
                op.setResourceType(ResourceOperationTypeEnum.CREATE_VOLUME_SNAPSHOT);
                op.setStartTime(Calendar.getInstance());
                snapshot.getOpStatus().createTaskStatus(taskId, op);
                snapshotURIs.add(snapshot.getId());
            }

            // Create all the snapshot objects
            _dbClient.createObject(snapshots);
        } else {
            // No snapshots are prepared so we must fail. The likely culprit here is trying to create a RP bookmark when all target copies
            // are in direct access mode (invalid bookmark state).
            throw APIException.badRequests.cannotCreateSnapshots();
        }

        // But only return the unique ones
        return snapshots;
    }

    /**
     * Creates and returns a new ViPR BlockSnapshot instance with the passed
     * name for the passed volume.
     *
     * @param volume The volume for which the snapshot is being created.
     * @param snapshotName The name to be given the snapshot
     * @param index Integer to make snapshot label unique in a group
     * @param snapshotType type of snapshot (NATIVE, RP, SRDF)
     * @param isInApplication If the source volume is in application
     * @return A reference to the new BlockSnapshot instance.
     */
    protected BlockSnapshot prepareSnapshotFromVolume(Volume volume, String snapshotName, Volume targetVolume, int index,
            String snapshotType, boolean isInApplication) {
        BlockSnapshot snapshot = new BlockSnapshot();
        snapshot.setId(URIUtil.createId(BlockSnapshot.class));
        URI cgUri = null;

        URIQueryResultList queryResults = new URIQueryResultList();
        _dbClient.queryByConstraint(AlternateIdConstraint.Factory.getVolumeByAssociatedVolumesConstraint(volume.getId().toString()),
                queryResults);
        if (queryResults.iterator().hasNext()) {
            Volume sourceVplexVolume = _dbClient.queryObject(Volume.class, queryResults.iterator().next());
            cgUri = sourceVplexVolume.getConsistencyGroup();
            snapshot.setProject(new NamedURI(sourceVplexVolume.getProject().getURI(), snapshotName));
        } else {
            cgUri = volume.getConsistencyGroup();
            snapshot.setProject(new NamedURI(volume.getProject().getURI(), snapshotName));
        }

        if (cgUri != null) {
            snapshot.setConsistencyGroup(cgUri);
        }

        snapshot.setSourceNativeId(volume.getNativeId());
        snapshot.setParent(new NamedURI(volume.getId(), snapshotName));
        String modifiedSnapshotName = snapshotName;

        // Don't add replication group instance to the snapshot name if this is an RP bookmark
        if (!snapshotType.equalsIgnoreCase(TechnologyType.RP.toString()) && isInApplication) {
            modifiedSnapshotName = modifiedSnapshotName + "-" + volume.getReplicationGroupInstance() + "-" + index;
        } else if (!snapshotType.equalsIgnoreCase(TechnologyType.RP.toString())) {
            modifiedSnapshotName = modifiedSnapshotName + "-" + index;
        }

        // We want snaps of targets to contain the varray label so we can distinguish multiple
        // targets from one another in the case of RP bookmarks.
        if (targetVolume != null && snapshotType.equalsIgnoreCase(TechnologyType.RP.toString())) {
            VirtualArray targetVarray = _dbClient.queryObject(VirtualArray.class, targetVolume.getVirtualArray());
            modifiedSnapshotName = modifiedSnapshotName + "-" + targetVarray.getLabel();
        }
        snapshot.setLabel(modifiedSnapshotName);

        snapshot.setStorageController(volume.getStorageController());
        snapshot.setSystemType(volume.getSystemType());
        snapshot.setVirtualArray(volume.getVirtualArray());
        snapshot.setProtocol(new StringSet());
        snapshot.getProtocol().addAll(volume.getProtocol());
        snapshot.setSnapsetLabel(ResourceOnlyNameGenerator.removeSpecialCharsForName(snapshotName, SmisConstants.MAX_SNAPSHOT_NAME_LENGTH));

        return snapshot;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Volume> getVolumesToSnap(Volume reqVolume, String snapshotType) {
        List<Volume> volumesToSnap = new ArrayList<Volume>();
        boolean vplex = RPHelper.isVPlexVolume(reqVolume, _dbClient);

        // If the requested volume is a regular RP protected volume (either RP source or RP target),
        // then just pass in that volume. RP protected (non-VPlex) volumes are not in any
        // underlying array CG.
        // OR
        // Only for RP+VPLEX just return the requested volume for RP bookmark (snapshot) requests. We only
        // want a single BlockSnapshot created.
        if (!vplex || (snapshotType != null && snapshotType.equalsIgnoreCase(BlockSnapshot.TechnologyType.RP.toString()))) {
            volumesToSnap.add(reqVolume);
            return volumesToSnap;
        }

        // If the passed volume is in a consistency group all volumes
        // of the same type (source or target) in the consistency group
        // should be snapped.
        URI cgURI = reqVolume.getConsistencyGroup();
        if (!NullColumnValueGetter.isNullURI(cgURI)) {
            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, cgURI);
            // If there is no corresponding native CG for the VPLEX
            // CG, then this is a CG created prior to 2.2 and in this
            // case we maintain pre-2.2 behavior by only snapping the
            // requested volume.
            if (!cg.checkForType(Types.LOCAL)) {
                volumesToSnap.add(reqVolume);
            } else {
                if (NullColumnValueGetter.isNotNullValue(reqVolume.getPersonality())) {
                    if (reqVolume.getPersonality().equals(PersonalityTypes.SOURCE.name())) {
                        List<Volume> cgVolumes = getActiveCGVolumes(cg);
                        for (Volume cgVolume : cgVolumes) {
                            // We want to snap all source volumes in the CG so add each one as we
                            // find them.
                            if (NullColumnValueGetter.isNotNullValue(cgVolume.getPersonality())
                                    && cgVolume.getPersonality().equals(PersonalityTypes.SOURCE.name())) {
                                volumesToSnap.add(cgVolume);
                            }
                        }
                    } else if (reqVolume.getPersonality().equals(PersonalityTypes.TARGET.name())) {
                        // In the case of target volumes, we do not want to snap all target
                        // volumes in the CG. We only want to create one for the requested
                        // volume.
                        volumesToSnap.add(reqVolume);
                    }
                }
            }
        } else {
            volumesToSnap.add(reqVolume);
        }

        return volumesToSnap;
    }

    /**
     * Uses the appropriate controller to create the snapshots.
     *
     * @param reqVolume The volume from the snapshot request.
     * @param snapshotURIs The URIs of the prepared snapshots.
     * @param snapshotType The snapshot technology type.
     * @param createInactive true if the snapshots should be created but not
     *            activated, false otherwise.
     * @param readOnly true if the snapshot should be read only, false otherwise
     * @param taskId The unique task identifier.
     */
    @Override
    public void createSnapshot(Volume reqVolume, List<URI> snapshotURIs,
            String snapshotType, Boolean createInactive, Boolean readOnly, String taskId) {
        boolean vplex = RPHelper.isVPlexVolume(reqVolume, _dbClient);
        ProtectionSystem protectionSystem = _dbClient.queryObject(ProtectionSystem.class, reqVolume.getProtectionController());
        URI storageControllerURI = null;
        if (vplex) {
            Volume backendVolume = vplexBlockServiceApiImpl.getVPLEXSnapshotSourceVolume(reqVolume);
            storageControllerURI = backendVolume.getStorageController();
        } else {
            storageControllerURI = reqVolume.getStorageController();
        }

        if (reqVolume.getProtectionController() != null && (snapshotType.equalsIgnoreCase(BlockSnapshot.TechnologyType.RP.toString())
                || reqVolume.getPersonality().equals(Volume.PersonalityTypes.TARGET.toString()))) {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storageControllerURI);
            RPController controller = getController(RPController.class, protectionSystem.getSystemType());
            controller.createSnapshot(protectionSystem.getId(), storageSystem.getId(), snapshotURIs, createInactive, readOnly, taskId);
        } else {
            if (vplex) {
                super.createSnapshot(vplexBlockServiceApiImpl.getVPLEXSnapshotSourceVolume(reqVolume), snapshotURIs, snapshotType,
                        createInactive, readOnly, taskId);
            } else {
                super.createSnapshot(reqVolume, snapshotURIs, snapshotType, createInactive, readOnly, taskId);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteSnapshot(BlockSnapshot requestedSnapshot, List<BlockSnapshot> allSnapshots, String taskId, String deleteType) {
        // If the volume is under protection
        if ((TechnologyType.RP.name().equals(requestedSnapshot.getTechnologyType()))
                && (!VolumeDeleteTypeEnum.VIPR_ONLY.name().equals(deleteType))) {
            Volume volume = _dbClient.queryObject(Volume.class, requestedSnapshot.getParent());
            RPController rpController = getController(RPController.class, ProtectionSystem._RP);
            rpController.deleteSnapshot(volume.getProtectionController(), requestedSnapshot.getId(), taskId);
        } else {
            super.deleteSnapshot(requestedSnapshot, allSnapshots, taskId, deleteType);
        }
    }

    /**
     * Validates a restore snapshot request.
     *
     * @param snapshot The snapshot to restore.
     * @param parent The parent of the snapshot
     */
    @Override
    public void validateRestoreSnapshot(BlockSnapshot snapshot, Volume parent) {
        // RecoverPoint snapshots (bookmarks) will be automatically activated
        // before restore.
        if (snapshot.getEmName() == null) {
            super.validateRestoreSnapshot(snapshot, parent);
        }
    }

    /**
     * Restore the passed parent volume from the passed snapshot of that parent volume.
     *
     * @param snapshot The snapshot to restore
     * @param parentVolume The volume to be restored.
     * @param taskId The unique task identifier.
     */
    @Override
    public void restoreSnapshot(BlockSnapshot snapshot, Volume parentVolume, String syncDirection, String taskId) {
        _log.info(String.format("Request to restore RP volume %s from snapshot %s.", parentVolume.getId().toString(),
                snapshot.getId().toString()));

        super.restoreSnapshot(snapshot, parentVolume, syncDirection, taskId);
    }

    /**
     * Get the snapshots for the passed volume.
     *
     * @param volume A reference to a volume.
     *
     * @return The snapshots for the passed volume.
     */
    @Override
    public List<BlockSnapshot> getSnapshots(Volume volume) {
        List<BlockSnapshot> snapshots = new ArrayList<BlockSnapshot>();

        // Get all the snapshots for this volume
        snapshots.addAll(super.getSnapshots(volume));

        // If this is a RP+VPLEX/MetroPoint volume get any local snaps for this volume as well,
        // we need to call out to VPLEX Api to get this information as the parent of these
        // snaps will be the backing volume.
        boolean vplex = RPHelper.isVPlexVolume(volume, _dbClient);
        if (vplex) {
            snapshots.addAll(vplexBlockServiceApiImpl.getSnapshots(volume));
        }

        // Get all bookmarks for the source copy if the passed in volume is an RP source volume
        if (volume.getPersonality().equalsIgnoreCase(Volume.PersonalityTypes.SOURCE.name())) {
            URI cgUri = volume.getConsistencyGroup();
            List<Volume> rpSourceVolumes = RPHelper.getCgSourceVolumes(cgUri, _dbClient);

            for (Volume rpSourceVolume : rpSourceVolumes) {
                // Get all RP bookmarks for this RP Volume. Since bookmarks are not assigned to any source volumes in the CG, we need to
                // query all the source volumes in the CG and fetch them.
                List<BlockSnapshot> allSnapshots = super.getSnapshots(rpSourceVolume);
                for (BlockSnapshot snapshot : allSnapshots) {
                    if (BlockSnapshot.TechnologyType.RP.name().equalsIgnoreCase(snapshot.getTechnologyType())) {
                        snapshots.add(snapshot);
                    }
                }
            }
        }

        return snapshots;
    }

    /**
     * Get the snapshots for the passed volume only, do not retrieve any of the related snaps.
     *
     * @param volume
     *            A reference to a volume.
     * @return The snapshots for the passed volume.
     */
    public List<BlockSnapshot> getSnapshotsForVolume(Volume volume) {
        List<BlockSnapshot> snapshots = new ArrayList<BlockSnapshot>();

        // Get all the related local snapshots and RP bookmarks for this RP Volume
        snapshots.addAll(super.getSnapshots(volume));

        // If this is a RP+VPLEX/MetroPoint volume get any local snaps for this volume as well,
        // we need to call out to VPLEX Api to get this information as the parent of these
        // snaps will be the backing volume.
        boolean vplex = RPHelper.isVPlexVolume(volume, _dbClient);
        if (vplex) {
            snapshots.addAll(vplexBlockServiceApiImpl.getSnapshots(volume));
        }

        return snapshots;
    }

    @Override
    public List<VolumeDescriptor> getDescriptorsForVolumesToBeDeleted(URI systemURI, List<URI> volumeURIs, String deletionType) {
        // Get descriptors for all volumes impacted by the deletion of the requested volumes.
        List<VolumeDescriptor> volumeDescriptors = _rpHelper.getDescriptorsForVolumesToBeDeleted(systemURI, volumeURIs, deletionType, null);

        // Filter these descriptors for a block volumes and VPLEX volumes.
        List<VolumeDescriptor> filteredDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_DATA, VolumeDescriptor.Type.VPLEX_VIRT_VOLUME },
                new VolumeDescriptor.Type[] {});

        // Now get descriptors for the mirrors of these block and VPLEX volumes.
        for (VolumeDescriptor descriptor : filteredDescriptors) {
            URI volumeURI = descriptor.getDeviceURI();
            Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
            if (volume != null && !volume.getInactive()) {
                if (VolumeDescriptor.Type.BLOCK_DATA == descriptor.getType()) {
                    addDescriptorsForMirrors(volumeDescriptors, volume);
                } else if (VolumeDescriptor.Type.VPLEX_VIRT_VOLUME == descriptor.getType()) {
                    vplexBlockServiceApiImpl.addDescriptorsForVplexMirrors(volumeDescriptors, volume);
                }
            }
        }

        return volumeDescriptors;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void cleanupForViPROnlyDelete(List<VolumeDescriptor> volumeDescriptors) {
        // Call super first.
        super.cleanupForViPROnlyDelete(volumeDescriptors);

        // Get the RP source volumes.
        List<VolumeDescriptor> sourceVolumeDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.RP_SOURCE, VolumeDescriptor.Type.RP_VPLEX_VIRT_SOURCE },
                new VolumeDescriptor.Type[] {});
        List<URI> sourceVolumeURIs = new ArrayList<URI>();
        for (URI sourceVolumeURI : VolumeDescriptor.getVolumeURIs(sourceVolumeDescriptors)) {
            Volume sourceVolume = _dbClient.queryObject(Volume.class, sourceVolumeURI);
            if (sourceVolume != null && !sourceVolume.getInactive() &&
                    !sourceVolume.isIngestedVolumeWithoutBackend(_dbClient)) { // Keeping this in here for when we do RP ingest.
                sourceVolumeURIs.add(sourceVolumeURI);
            }
        }

        // If we're deleting the last volume, we can delete the ProtectionSet object.
        // This list of volumes may contain volumes from many protection sets
        Set<URI> volumesToDelete = _rpHelper.getVolumesToDelete(sourceVolumeURIs);
        Map<URI, Set<URI>> psetToVolumesToDelete = new HashMap<URI, Set<URI>>();

        // Group volumes to delete by their protectionsets
        for (URI volumeURI : volumesToDelete) {
            Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
            if (volume.getProtectionSet() != null) {
                if (psetToVolumesToDelete.get(volume.getProtectionSet().getURI()) == null) {
                    psetToVolumesToDelete.put(volume.getProtectionSet().getURI(), new HashSet<URI>());
                }
                psetToVolumesToDelete.get(volume.getProtectionSet().getURI()).add(volumeURI);
            }
        }

        // Go through all protection sets and verify we have all of the volumes accounted for
        // and delete the protection set if necessary. Log otherwise.
        Set<URI> psetsDeleted = new HashSet<URI>();
        for (Entry<URI, Set<URI>> psetVolumeEntry : psetToVolumesToDelete.entrySet()) {
            ProtectionSet pset = _dbClient.queryObject(ProtectionSet.class, psetVolumeEntry.getKey());
            if (pset != null && !psetsDeleted.contains(pset.getId()) && psetVolumeEntry.getValue().size() == pset.getVolumes().size()) {
                _dbClient.markForDeletion(pset);
                psetsDeleted.add(pset.getId());
            } else if (pset.getVolumes() == null) {
                _dbClient.markForDeletion(pset);
                psetsDeleted.add(pset.getId());
                _log.info(String.format("Deleting protection %s because there are no volumes in it any longer", pset.getLabel()));
            } else if (volumesToDelete.size() != pset.getVolumes().size()) {
                // For debugging: log conditions that caused us to not delete the protection set
                _log.info(String.format(
                        "Not deleting protection %s because there are %d volumes to delete in the request, however there are %d volumes in the pset",
                        pset.getLabel(), _rpHelper.getVolumesToDelete(sourceVolumeURIs).size(), pset.getVolumes().size()));
            }
        }
    }

    @Override
    protected List<VirtualPoolChangeOperationEnum> getVirtualPoolChangeAllowedOperations(Volume volume, VirtualPool currentVpool,
            VirtualPool newVpool, StringBuffer notSuppReasonBuff) {

        List<VirtualPoolChangeOperationEnum> allowedOperations = new ArrayList<VirtualPoolChangeOperationEnum>();

        // If this a RP+VPLEX Journal check to see if a straight up VPLEX Data migration is
        // allowed.
        //
        // RP+VPLEX Journals are normally hidden in the UI since they are internal volumes, however they 
        // can been exposed in the Migration Services catalog to support RP+VPLEX Data Migrations.
        if (volume.checkPersonality(Volume.PersonalityTypes.METADATA)) {
            boolean vplex = RPHelper.isVPlexVolume(volume, _dbClient);
            if (vplex) {
                if (VirtualPoolChangeAnalyzer.vpoolChangeRequiresMigration(currentVpool, newVpool)) {
                    // Allow the VPLEX Data Migration operation
                    allowedOperations.add(VirtualPoolChangeOperationEnum.VPLEX_DATA_MIGRATION);
                }
            }
        }
        
        // Doesn't matter if this is VPLEX or not, if we have a
        // protected volume and we're looking to move to an unprotected
        // state return the RP_REMOVE_PROTECTION as the allowed operation
        // should it meet the correct criteria.
        if (volume.checkForRp()
                && VirtualPool.vPoolSpecifiesProtection(currentVpool)
                && !VirtualPool.vPoolSpecifiesProtection(newVpool)) {
            if (VirtualPoolChangeAnalyzer.isSupportedRPRemoveProtectionVirtualPoolChange(volume, currentVpool, newVpool,
                    _dbClient, notSuppReasonBuff)) {
                allowedOperations.add(VirtualPoolChangeOperationEnum.RP_REMOVE_PROTECTION);
            }
        }

        // All other operations depend on the new vpool at the minimum
        // specifying RP Protection.
        if (VirtualPool.vPoolSpecifiesProtection(newVpool)) {
            boolean vplex = RPHelper.isVPlexVolume(volume, _dbClient);
            // Check to see if this is a VPLEX volume
            if (vplex) {
                // Get the varray for the volume.
                URI volumeVarrayURI = volume.getVirtualArray();
                StringSet newVirtualPoolVarrays = newVpool.getVirtualArrays();
                if ((newVirtualPoolVarrays != null) && (!newVirtualPoolVarrays.isEmpty())
                        && (!newVirtualPoolVarrays.contains(volumeVarrayURI.toString()))) {
                    // The VirtualPool is not allowed because it is not available in the
                    // volume varray.
                    notSuppReasonBuff.append("The VirtualPool is not available to the volume's varray");
                } else if (VirtualPool.vPoolSpecifiesRPVPlex(currentVpool)
                            && VirtualPool.vPoolSpecifiesRPVPlex(newVpool)) {
                    if (VirtualPoolChangeAnalyzer.isSupportedRPVPlexMigrationVirtualPoolChange(volume, currentVpool, newVpool,
                            _dbClient, notSuppReasonBuff, null)) {
                        // Allow the VPLEX Data Migration operation
                        allowedOperations.add(VirtualPoolChangeOperationEnum.VPLEX_DATA_MIGRATION);
                    }
                    if (VirtualPoolChangeAnalyzer.isSupportedUpgradeToMetroPointVirtualPoolChange(volume, currentVpool, newVpool,
                            _dbClient, notSuppReasonBuff)) {
                        // Allow the RP change protection operation
                        allowedOperations.add(VirtualPoolChangeOperationEnum.RP_UPGRADE_TO_METROPOINT);
                    }
                } else if (VirtualPool.vPoolSpecifiesRPVPlex(newVpool)) {
                    if (VirtualPoolChangeAnalyzer.isSupportedAddRPProtectionVirtualPoolChange(volume, currentVpool, newVpool,
                            _dbClient, notSuppReasonBuff)) {
                        // Allow the RP Protection add operation
                        allowedOperations.add(VirtualPoolChangeOperationEnum.RP_PROTECTED);
                    }
                }
            } else if (VirtualPoolChangeAnalyzer.isSupportedAddRPProtectionVirtualPoolChange(volume, currentVpool,
                    newVpool, _dbClient, notSuppReasonBuff)) {
                allowedOperations.add(VirtualPoolChangeOperationEnum.RP_PROTECTED);
            }
        }

        return allowedOperations;
    }

    /**
     * Method to determine whether the capacities for all the volumes being provisioned
     * can match. Currently the capacities for vmax, vmax3, and xtremio cannot match exactly.
     *
     * @param volumeStorageSystems
     *            - map indicating storage system types required to fulfill this request
     * @return boolean - indicating if the capacity of all volumes can match
     */
    protected boolean capacitiesCanMatch(Map<URI, StorageSystem> volumeStorageSystems) {
        String systemToCompare = null;
        for (Map.Entry<URI, StorageSystem> entry : volumeStorageSystems.entrySet()) {
            StorageSystem storageSystem = entry.getValue();
            String storageType = storageSystem.getSystemType();
            if (storageSystem.isV3AllFlashArray()) {
                storageType = DiscoveredDataObject.Type.vmax3AFA.name();;
            } else if (storageSystem.checkIfVmax3()) {
                storageType = DiscoveredDataObject.Type.vmax3.name();
            }
            _log.info(String.format("Request requires provisioning on storage array of type: %s", storageType));
            if (NullColumnValueGetter.isNullValue(systemToCompare)) {
                systemToCompare = storageSystem.getSystemType();
                continue;
            }
            if (!capacityCalculatorFactory.getCapacityCalculator(systemToCompare).capacitiesCanMatch(entry.getValue().getSystemType())) {
                _log.info("Determined that the capacity for all volumes being provisioned cannot match.");
                return false;
            }
        }
        _log.info("Determined that the capacity for all volumes being provisioned can match.");
        return true;
    }

    /**
     * Method to determine and set the capacity for volumes being provisioned on
     * storage systems where the capacities cannot match
     *
     * @param allVolumesToUpdateCapacity - list of volumes to update the capacities of
     * @param associatedVolumePersonalityMap - map of the associated back end volumes and their personality type
     * @param isExpand - boolean indicating if this is an expand operation
     * @param requestedSize - size requested in an expansion operation
     */
    protected Map<Volume.PersonalityTypes, Long> setUnMatchedCapacities(List<Volume> allVolumesToUpdateCapacity,
            Map<URI, String> associatedVolumePersonalityMap, boolean isExpand, Long capacityToUseInCalculation) {
        _log.info("Capacities for RP Source and Target can not match. Capacities will be calculated so Source <= Target.");
        Long srcCapacity = 0L;
        Long tgtCapacity = 0L;
        Map<Volume.PersonalityTypes, Long> capacities = new HashMap<Volume.PersonalityTypes, Long>();
        for (Volume volume : allVolumesToUpdateCapacity) {
            if ((NullColumnValueGetter.isNotNullValue(volume.getPersonality())
                    && volume.getPersonality().equalsIgnoreCase(Volume.PersonalityTypes.SOURCE.toString()))
                    || (NullColumnValueGetter.isNotNullValue(associatedVolumePersonalityMap.get(volume.getId()))
                            && associatedVolumePersonalityMap.get(volume.getId())
                                    .equalsIgnoreCase(Volume.PersonalityTypes.SOURCE.toString()))) {
                srcCapacity = (srcCapacity != 0L) ? srcCapacity
                        : determineCapacity(volume, Volume.PersonalityTypes.SOURCE, capacityToUseInCalculation);
                if (!isExpand) {
                    updateVolumeCapacity(volume, srcCapacity, isExpand);
                }

            } else if ((NullColumnValueGetter.isNotNullValue(volume.getPersonality())
                    && volume.getPersonality().equalsIgnoreCase(Volume.PersonalityTypes.TARGET.toString()))
                    || (NullColumnValueGetter.isNotNullValue(associatedVolumePersonalityMap.get(volume.getId()))
                            && associatedVolumePersonalityMap.get(volume.getId())
                                    .equalsIgnoreCase(Volume.PersonalityTypes.TARGET.toString()))) {
                tgtCapacity = (tgtCapacity != 0L) ? tgtCapacity
                        : determineCapacity(volume, Volume.PersonalityTypes.TARGET, capacityToUseInCalculation);
                if (!isExpand) {
                    updateVolumeCapacity(volume, tgtCapacity, isExpand);
                }
            }
        }
        capacities.put(Volume.PersonalityTypes.SOURCE, srcCapacity);
        capacities.put(Volume.PersonalityTypes.TARGET, tgtCapacity);
        return capacities;
    }

    /**
     * Method to determine the capacity of either the source or target volumes in an unmatched capacity situation
     *
     * @param volume - volume to determine the capacity for
     * @param type - personality type of the volume
     * @param isExpand - boolean indicating if this is an expand operation
     * @param requestedSize - size requested in an expansion operation
     * @return the determined capacity size for the volume
     */
    private Long determineCapacity(Volume volume, Volume.PersonalityTypes type, Long capacityToUseInCalculation) {
        long capacity = 0L;

        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, volume.getStorageController());

        if (type == Volume.PersonalityTypes.SOURCE) {
            capacity = capacityCalculatorFactory.getCapacityCalculator(storageSystem.getSystemType())
                    .calculateAllocatedCapacity(capacityToUseInCalculation, volume, _dbClient);
        } else if (type == Volume.PersonalityTypes.TARGET) {
            capacity = capacityCalculatorFactory.getCapacityCalculator(storageSystem.getSystemType())
                    .calculateAllocatedCapacity(capacityToUseInCalculation + 5242880L, volume, _dbClient);
        }

        return capacity;
    }

    /**
     * Method to both update and persist the volume capacity
     *
     * @param volume - volume to update the capacity for
     * @param capacity - capacity to update the volume to
     * @param isExpand - boolean indicating if this is an expand operation
     */
    private void updateVolumeCapacity(Volume volume, Long capacity, boolean isExpand) {
        if (!volume.getCapacity().equals(capacity)) {
            // Update the volume's originally requested capacity in Cassandra
            // to be the new capacity. This will end up being used
            // as the capacity value when provisioning the volumes on the
            // storage systems via the SMI-S provider.
            //
            // However, we don't want to persist the size of the volume if the operation is
            // expand because the expand code takes care of that based on if the volume was
            // indeed expanded or not.
            if (!isExpand) {
                _log.info(String.format("Updating capacity for volume [%s] from %s to %s.",
                        volume.getLabel(),
                        volume.getCapacity(),
                        capacity));
                // Update capacity and persist volume
                volume.setCapacity(capacity);
                _dbClient.updateObject(volume);
            } else {
                _log.info(String.format("Do not update capacity for volume [%s] as this is an expand operation.", volume.getLabel()));
            }
        } else {
            _log.info(String.format("No need to update capacity for volume [%s].", volume.getLabel()));
        }
    }

    /**
     * Helper method to display volume attributes as they are prepared.
     *
     * @param volume
     */
    private String logVolumeInfo(Volume volume) {
        StringBuilder buf = new StringBuilder();
        if (null != volume && !NullColumnValueGetter.isNullURI(volume.getId())) {
            VirtualArray varray = _dbClient.queryObject(VirtualArray.class, volume.getVirtualArray());
            VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
            ProtectionSystem ps = _dbClient.queryObject(ProtectionSystem.class, volume.getProtectionController());
            BlockConsistencyGroup consistencyGroup = _dbClient.queryObject(BlockConsistencyGroup.class, volume.getConsistencyGroup());

            buf.append(String.format("%nPreparing RP %s Volume:%n", volume.getPersonality()));
            buf.append(String.format("\t Name : [%s] (%s)%n", volume.getLabel(), volume.getId()));
            buf.append(String.format("\t Personality : [%s]%n", volume.getPersonality()));

            if (RPHelper.isVPlexVolume(volume, _dbClient) && (null != volume.getAssociatedVolumes())) {
                buf.append(String.format("\t VPLEX : [%s] %n", ((volume.getAssociatedVolumes().size() > 1) ? "Distributed" : "Local")));
                buf.append(String.format("\t\t====="));
                for (String uriString : volume.getAssociatedVolumes()) {
                    Volume backingVolume = _dbClient.queryObject(Volume.class, URI.create(uriString));
                    VirtualArray backingVolumeVarray = _dbClient.queryObject(VirtualArray.class, backingVolume.getVirtualArray());
                    VirtualPool backingVolumeVpool = _dbClient.queryObject(VirtualPool.class, backingVolume.getVirtualPool());
                    StorageSystem backingVolumeStorageSystem = _dbClient.queryObject(StorageSystem.class,
                            backingVolume.getStorageController());
                    StoragePool backingVolumePool = _dbClient.queryObject(StoragePool.class, backingVolume.getPool());

                    buf.append(String.format("%n\t\t Backing Volume Name : [%s] (%s)%n", backingVolume.getLabel(), backingVolume.getId()));
                    buf.append(String.format("\t\t Backing Volume Virtual Array : [%s] (%s) %n", backingVolumeVarray.getLabel(),
                            backingVolumeVarray.getId()));
                    buf.append(String.format("\t\t Backing Volume Virtual Pool : [%s] (%s) %n", backingVolumeVpool.getLabel(),
                            backingVolumeVpool.getId()));
                    buf.append(String.format("\t\t Backing Volume Storage System : [%s] (%s) %n", backingVolumeStorageSystem.getLabel(),
                            backingVolumeStorageSystem.getId()));
                    buf.append(String.format("\t\t Backing Volume Storage Pool : [%s] (%s) %n", backingVolumePool.getLabel(),
                            backingVolumePool.getId()));
                    if (NullColumnValueGetter.isNotNullValue(backingVolume.getInternalSiteName())) {
                        String internalSiteName = ((ps.getRpSiteNames() != null)
                                ? ps.getRpSiteNames().get(backingVolume.getInternalSiteName()) : backingVolume.getInternalSiteName());
                        buf.append(String.format("\t\t Backing Volume RP Internal Site : [%s %s] %n", internalSiteName,
                                backingVolume.getInternalSiteName()));
                        buf.append(String.format("\t\t Backing Volume RP Copy Name : [%s] %n",
                                backingVolume.getRpCopyName()));
                    }
                }
                buf.append(String.format("\t\t=====%n"));
            }

            buf.append(String.format("\t Consistency Group : [%s] (%s)%n", consistencyGroup.getLabel(), consistencyGroup.getId()));
            buf.append(String.format("\t Virtual Array : [%s] (%s)%n", varray.getLabel(), varray.getId()));
            buf.append(String.format("\t Virtual Pool : [%s] (%s)%n", vpool.getLabel(), vpool.getId()));
            buf.append(String.format("\t Capacity : [%s] %n", volume.getCapacity()));

            if (!NullColumnValueGetter.isNullURI(volume.getStorageController())) {
                StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, volume.getStorageController());
                buf.append(String.format("\t Storage System : [%s] (%s)%n", storageSystem.getLabel(), storageSystem.getId()));
            }

            if (!NullColumnValueGetter.isNullURI(volume.getPool())) {
                StoragePool pool = _dbClient.queryObject(StoragePool.class, volume.getPool());
                buf.append(String.format("\t Storage Pool : [%s] (%s)%n", pool.getLabel(), pool.getId()));
            }

            if (!NullColumnValueGetter.isNullURI(volume.getAutoTieringPolicyUri())) {
                AutoTieringPolicy policy = _dbClient.queryObject(AutoTieringPolicy.class, volume.getAutoTieringPolicyUri());
                buf.append(String.format("\t Auto Tier Policy : [%s]%n", policy.getPolicyName()));
            }

            buf.append(String.format("\t RP Protection System : [%s] (%s)%n", ps.getLabel(), ps.getId()));
            buf.append(String.format("\t RP Replication Set : [%s]%n", volume.getRSetName()));

            if (Volume.PersonalityTypes.SOURCE.name().equals(volume.getPersonality())) {
                buf.append(String.format("\t RP MetroPoint enabled : [%s]%n",
                        (VirtualPool.vPoolSpecifiesMetroPoint(vpool) ? "true" : "false")));
            }

            if (volume.getRpTargets() != null && !volume.getRpTargets().isEmpty()) {
                buf.append(String.format("\t RP Target Volume(s) for Source : ["));
                for (String targetVolumeId : volume.getRpTargets()) {
                    Volume targetVolume = _dbClient.queryObject(Volume.class, URI.create(targetVolumeId));
                    buf.append(String.format("%s, ", targetVolume.getLabel()));
                }
                int endIndex = buf.length();
                buf.delete(endIndex - 2, endIndex);
                buf.append(String.format("]%n"));
            }

            String internalSiteName = ((ps.getRpSiteNames() != null) ? ps.getRpSiteNames().get(volume.getInternalSiteName())
                    : volume.getInternalSiteName());
            buf.append(String.format("\t RP Internal Site : [%s %s]%n", internalSiteName, volume.getInternalSiteName()));
            buf.append(String.format("\t RP Copy Name : [%s]%n", volume.getRpCopyName()));
        }

        return buf.toString();
    }

    /**
     * Validates and logs the MetroPointType. An exception is thrown for invalid
     * MetroPoint types.
     *
     * @param metroPointType
     *            the MetroPoint type to validate.
     */
    private void validateMetroPointType(MetroPointType metroPointType) {
        StringBuffer sb = new StringBuffer();

        if (metroPointType == MetroPointType.SINGLE_REMOTE) {
            sb.append("Preparing volumes for a MetroPoint configuration with a single remote copy.");
        } else if (metroPointType == MetroPointType.LOCAL_ONLY) {
            sb.append("Preparing volumes for a MetroPoint configuration with local only copies.");
        } else if (metroPointType == MetroPointType.ONE_LOCAL_REMOTE) {
            sb.append("Preparing volumes for a MetroPoint configuration with one local and one remote copy.");
        } else if (metroPointType == MetroPointType.TWO_LOCAL_REMOTE) {
            sb.append("Preparing volumes for a MetroPoint configuration with two local copies and one remote copy.");
        } else if (metroPointType == MetroPointType.INVALID) {
            throw APIException.badRequests.invalidMetroPointConfiguration();
        }

        _log.info(sb.toString());
    }

    /**
     * Parses the volume list and returns the VPLEX virtual volume
     *
     * @param volumes - List of volumes to parse
     * @return VPLEX virtual volume
     */
    private Volume getVPlexVirtualVolume(List<URI> volumes) {
        Volume vplexVirtualVolume = null;
        for (URI volumeURI : volumes) {
            Volume vplexVolume = _dbClient.queryObject(Volume.class, volumeURI);

            if (vplexVolume.getAssociatedVolumes() != null && !vplexVolume.getAssociatedVolumes().isEmpty()) {
                vplexVirtualVolume = vplexVolume;
                break;
            }
        }

        return vplexVirtualVolume;
    }

    /**
     * Upgrade a local block volume to a protected RP volume
     *
     * @param volume the existing volume being protected.
     * @param newVpool the requested virtual pool
     * @param taskId the task identifier
     * @throws InternalException
     */
    private void upgradeToMetroPointVolume(Volume volume, VirtualPool newVpool, VirtualPoolChangeParam vpoolChangeParam, String taskId)
            throws InternalException {
        _log.info(String.format("Upgrade [%s] to MetroPoint", volume.getLabel()));

        Project project = _dbClient.queryObject(Project.class, volume.getProject());

        // Now that we have a handle on the current vpool, let's set the new vpool on the volume.
        // The volume will not be persisted just yet but we need to have the new vpool to
        // properly make placement decisions and to add reference to the new vpool to the
        // recommendation objects that will be created.
        URI currentVpool = volume.getVirtualPool();
        volume.setVirtualPool(newVpool.getId());
        List<Recommendation> recommendations = getRecommendationsForVirtualPoolChangeRequest(volume, newVpool, vpoolChangeParam, null);
        volume.setVirtualPool(currentVpool);

        if (recommendations.isEmpty()) {
            throw APIException.badRequests.noStorageFoundForVolume();
        }

        // Get the volume's varray
        VirtualArray varray = _dbClient.queryObject(VirtualArray.class, volume.getVirtualArray());

        // Generate a VolumeCreate object that contains the information that createVolumes likes to consume.
        VolumeCreate param = new VolumeCreate(volume.getLabel(), String.valueOf(volume.getCapacity()), 1, newVpool.getId(),
                volume.getVirtualArray(), volume.getProject().getURI());

        VirtualPoolCapabilityValuesWrapper capabilities = new VirtualPoolCapabilityValuesWrapper();
        capabilities.put(VirtualPoolCapabilityValuesWrapper.RESOURCE_COUNT, 1);
        capabilities.put(VirtualPoolCapabilityValuesWrapper.BLOCK_CONSISTENCY_GROUP, volume.getConsistencyGroup());
        TaskList taskList = new TaskList();
        createTaskForVolume(volume, ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VPOOL, taskList, taskId);
        Map<VpoolUse, List<Recommendation>> recommendationMap = new HashMap<VpoolUse, List<Recommendation>>();
        recommendationMap.put(VpoolUse.ROOT, recommendations);
        createVolumes(param, project, varray, newVpool, recommendationMap, taskList, taskId, capabilities);
    }

    /**
     * Add the internal site found by the scheduler (stored in the rec objects) to the VPLEX SOURCE volumes
     * backing volumes should this be a MP or Export to HA side request.
     *
     * @param primaryRecommendation The primary rec from the scheduler
     * @param secondaryRecommendation The secondary rec from the scheduler
     * @param sourceVolume The volume that should be checked for its backing volumes to be updated
     * @param exportForMetroPoint Boolean set to true if we're MetroPoint
     * @param exportToHASideOnly Boolean set to true if we're only exportong to HA side
     * @param haVarrayConnectedToRp Only be used if isSrcAndHaSwapped == true, tells us which varray is the HA one
     * @param activeSourceCopyName Active RP Copy name
     * @param standbySourceCopyName Standby RP Copy name
     */
    private void setInternalSitesForSourceBackingVolumes(RPRecommendation primaryRecommendation, RPRecommendation secondaryRecommendation,
            Volume sourceVolume, boolean exportForMetroPoint, boolean exportToHASideOnly, String haVarrayConnectedToRp,
            String activeSourceCopyName, String standbySourceCopyName) {

        if (exportForMetroPoint) {
            // If this is MetroPoint request and we're looking at the SOURCE volume we need to ensure the
            // backing volumes are aware of which internal site they have been assigned (needed for exporting in
            // RPDeviceController).
            _log.info(String.format("MetroPoint export, update backing volumes for [%s] " + "with correct internal site",
                    sourceVolume.getLabel()));
            if (null == sourceVolume.getAssociatedVolumes() || sourceVolume.getAssociatedVolumes().isEmpty()) {
                _log.error("VPLEX volume {} has no backend volumes.", sourceVolume.forDisplay());
                throw InternalServerErrorException.
                    internalServerErrors.noAssociatedVolumesForVPLEXVolume(sourceVolume.forDisplay());
            }

            // Iterate over each backing volume...
            Iterator<String> it = sourceVolume.getAssociatedVolumes().iterator();
            while (it.hasNext()) {
                Volume backingVolume = _dbClient.queryObject(Volume.class, URI.create(it.next()));
                String rpSite = "";
                // If this backing volume's varray is equal to the MetroPoint Source virtual volume's varray,
                // then we're looking at the primary leg. Otherwise, it's the
                // secondary leg. Set the InternalSiteName accordingly.
                if (backingVolume.getVirtualArray().equals(sourceVolume.getVirtualArray())) {
                    rpSite = primaryRecommendation.getInternalSiteName();
                    backingVolume.setRpCopyName(activeSourceCopyName);
                } else {
                    rpSite = secondaryRecommendation.getInternalSiteName();
                    backingVolume.setRpCopyName(standbySourceCopyName);
                }
                // Save the internal site name to the backing volume, will need this for exporting later
                backingVolume.setInternalSiteName(rpSite);
                _dbClient.updateObject(backingVolume);
                _log.info(String.format("Backing volume [%s] internal site name set to [%s]", backingVolume.getLabel(), rpSite));
            }
        } else if (exportToHASideOnly) {
            // If this is RP+VPLEX request and we're looking at exporting to the HA side only,
            // we need to set the internal site name on the HA backing volume.
            _log.info(String.format("RP+VPLEX HA side export, update HA backing volume for [%s] " + "with correct internal site",
                    sourceVolume.getLabel()));
            if (null == sourceVolume.getAssociatedVolumes() || sourceVolume.getAssociatedVolumes().isEmpty()) {
                _log.error("VPLEX volume {} has no backend volumes.", sourceVolume.forDisplay());
                throw InternalServerErrorException.
                    internalServerErrors.noAssociatedVolumesForVPLEXVolume(sourceVolume.forDisplay());
            }

            // Iterate over each backing volume...
            Iterator<String> it = sourceVolume.getAssociatedVolumes().iterator();
            while (it.hasNext()) {
                Volume backingVolume = _dbClient.queryObject(Volume.class, URI.create(it.next()));

                if (backingVolume.getVirtualArray().toString().equals(haVarrayConnectedToRp)) {
                    // Save the internal site name to the HA backing volume, this will be needed
                    // for exporting the HA side/leg later in RPDeviceController
                    backingVolume.setInternalSiteName(primaryRecommendation.getInternalSiteName());
                    _dbClient.updateObject(backingVolume);
                    _log.info(String.format("Backing volume [%s] internal site name set to [%s]", backingVolume.getLabel(),
                            primaryRecommendation.getInternalSiteName()));
                    break;
                }
            }
        }
    }

    /**
     * Log the output from the descriptors created
     *
     * @param descriptors All descriptors to log
     */
    private void logDescriptors(List<VolumeDescriptor> descriptors) {
        StringBuffer buf = new StringBuffer();
        buf.append(String.format(NEW_LINE));
        buf.append(String.format("Volume descriptors for RP: %n"));

        for (VolumeDescriptor desc : descriptors) {
            Volume volume = _dbClient.queryObject(Volume.class, desc.getVolumeURI());
            buf.append(String.format("%n\t Volume Name: [%s] %n\t Descriptor Type: [%s] %n\t Full Descriptor Info: [%s] %n",
                    volume.getLabel(), desc.getType(), desc.toString()));
        }

        buf.append(String.format(NEW_LINE));
        _log.info(buf.toString());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateConsistencyGroupName(BlockConsistencyGroup consistencyGroup) {
        super.validateConsistencyGroupName(consistencyGroup);
        // TODO BBB - not sure how to not check this for just RP?
        vplexBlockServiceApiImpl.validateConsistencyGroupName(consistencyGroup);
    }

    /**
     * Add additional journal volume(s) to an existing recoverpoint
     * consistency group copy
     *
     * @param param - journal volume(s) creation parameters
     * @param project - the project
     * @param journalVarray - the virtual array for the journal(s)
     * @param journalVpool - the virtual pool for the journal(s)
     * @param consistencyGroup - the recoverpoint consistency group
     * @param capabilities - parameters for the journal volume(s)
     * @param task - the task identifier
     * @return TaskList
     */
    public TaskList addJournalCapacity(VolumeCreate param, Project project, VirtualArray journalVarray, VirtualPool journalVpool,
            BlockConsistencyGroup consistencyGroup, VirtualPoolCapabilityValuesWrapper capabilities, String task) {

        ProtectionSystem protectionSystem = getBlockScheduler().getCgProtectionSystem(consistencyGroup.getId());
        if (protectionSystem != null) {
            _log.info("Narrowing down placement to use protection system {}, which is currently used by RecoverPoint consistency group {}.",
                    protectionSystem.getLabel(), consistencyGroup);
        } else {
            throw APIException.badRequests.noProtectionSystemAssociatedWithTheCG(consistencyGroup.getId().toString());
        }

        // copy name to add the journal volume to
        String copyName = param.getName();
        // rp cluster internal site name of the copy
        String internalSiteName = null;
        // adding journal to a source copy
        boolean isSource = false;
        // adding journal to a target copy
        boolean isTarget = false;
        // adding journal to a metropoint standby copy
        boolean isMPStandby = copyName.contains("Standby");

        // get the list of source and target volumes; for metropoint, source volumes include both sides of the source
        // metro volume
        List<Volume> sourceVolumes = RPHelper.getCgSourceVolumes(consistencyGroup.getId(), _dbClient);
        if (sourceVolumes.isEmpty()) {
            throw APIException.badRequests.noSourceVolumesInCG(consistencyGroup.getLabel());
        }

        // only need one source volume to set up parameters for the operation
        Volume firstSrc = sourceVolumes.get(0);
        StringSet sourceInternalSiteNames = new StringSet();

        // if it's a metropoint volume we need to determine the internal site name for
        // both the active and the standby copies
        if (RPHelper.isMetroPointVolume(_dbClient, firstSrc)) {
            StringSet associatedVolumes = firstSrc.getAssociatedVolumes();
            if (associatedVolumes != null && !associatedVolumes.isEmpty()) {
                for (String associatedVolumeStr : associatedVolumes) {
                    URI associatedVolumeURI = URI.create(associatedVolumeStr);
                    Volume associatedVolume = _dbClient.queryObject(Volume.class, associatedVolumeURI);
                    sourceInternalSiteNames.add(associatedVolume.getInternalSiteName());
                    if (NullColumnValueGetter.isNotNullValue(associatedVolume.getRpCopyName())) {
                        if (associatedVolume.getRpCopyName().equals(copyName)) {
                            isSource = !isMPStandby;
                            internalSiteName = associatedVolume.getInternalSiteName();
                        }
                    }
                }
            }
            // determine the internal site name for a source copy
        } else {
            sourceInternalSiteNames.add(firstSrc.getInternalSiteName());
            if (NullColumnValueGetter.isNotNullValue(firstSrc.getRpCopyName())) {
                if (firstSrc.getRpCopyName().equals(copyName)) {
                    isSource = true;
                    internalSiteName = firstSrc.getInternalSiteName();
                }
            }
        }

        // determine the internal site name for a target copy
        for (String targetURIString : firstSrc.getRpTargets()) {
            Volume tgtVolume = _dbClient.queryObject(Volume.class, URI.create(targetURIString));
            if (NullColumnValueGetter.isNotNullValue(tgtVolume.getRpCopyName()) && tgtVolume.getRpCopyName().equals(copyName)) {
                isTarget = true;
                internalSiteName = tgtVolume.getInternalSiteName();
            }
        }

        if (internalSiteName == null) {
            throw APIException.badRequests.unableToFindTheSpecifiedCopy(copyName);
        }

        // if we're adding volumes to a target, we need to know if it's local or remote
        String targetType = RPHelper.LOCAL;
        int copyType = RecoverPointCGCopyType.PRODUCTION.getCopyNumber();
        if (isTarget) {
            if (sourceInternalSiteNames.contains(internalSiteName)) {
                copyType = RecoverPointCGCopyType.LOCAL.getCopyNumber();
                targetType = RPHelper.LOCAL;
            } else {
                copyType = RecoverPointCGCopyType.REMOTE.getCopyNumber();
                targetType = RPHelper.REMOTE;
            }
        }

        capabilities.put(VirtualPoolCapabilityValuesWrapper.RP_COPY_TYPE, copyType);

        RPProtectionRecommendation rpProtectionRecommendation = new RPProtectionRecommendation();
        rpProtectionRecommendation.setProtectionDevice(protectionSystem.getId());

        RPRecommendation journalRecommendation = getBlockScheduler().buildJournalRecommendation(rpProtectionRecommendation,
                internalSiteName, new Long(capabilities.getSize()).toString(), journalVarray, journalVpool, protectionSystem, capabilities,
                capabilities.getResourceCount(), null, false);
        if (journalRecommendation == null) {
            throw APIException.badRequests.unableToFindSuitableJournalRecommendation();
        }

        String copyTypeString = RPHelper.SOURCE;

        if (isSource) {
            rpProtectionRecommendation.setSourceJournalRecommendation(journalRecommendation);
        }

        if (isMPStandby) {
            rpProtectionRecommendation.setStandbyJournalRecommendation(journalRecommendation);
            copyTypeString = "standby " + RPHelper.SOURCE;
        }

        if (isTarget) {
            List<RPRecommendation> journalRecommendations = Lists.newArrayList();
            journalRecommendations.add(journalRecommendation);
            rpProtectionRecommendation.setTargetJournalRecommendations(journalRecommendations);
            copyTypeString = targetType + " " + RPHelper.TARGET;
        }

        List<Recommendation> recommendations = Lists.newArrayList();
        recommendations.add(rpProtectionRecommendation);

        // need to set the journal copy name to something unique
        param.setName(copyName + "_" + task);

        _log.info("Request to add journal capacity to {} copy {}", copyTypeString, copyName);
        _log.info("Copy {} is protected by RP Site {}", copyName, internalSiteName);

        TaskList taskList = new TaskList();
        Map<VpoolUse, List<Recommendation>> recommendationMap = new HashMap<VpoolUse, List<Recommendation>>();
        recommendationMap.put(VpoolUse.ROOT, recommendations);
        return this.createVolumes(param, project, journalVarray, journalVpool, recommendationMap, taskList, task, capabilities);
    }

    /**
     * Removes protection from the volume and leaves it in an unprotected state.
     *
     * @param volumes the existing volume being protected.
     * @param newVpool the requested virtual pool
     * @param taskId the task identifier
     * @throws InternalException
     */
    private void removeProtection(List<Volume> volumes, VirtualPool newVpool, String taskId) throws InternalException {
        List<URI> volumeURIs = new ArrayList<URI>();
        for (Volume volume : volumes) {
            _log.info(String.format("Request to remove protection from Volume [%s] (%s) and move it to Virtual Pool [%s] (%s)",
                    volume.getLabel(), volume.getId(), newVpool.getLabel(), newVpool.getId()));
            volumeURIs.add(volume.getId());

            // List of RP bookmarks to cleanup (if any)
            List<BlockSnapshot> rpBookmarks = new ArrayList<BlockSnapshot>();

            // Get all the block snapshots and RP bookmarks for the source.
            List<BlockSnapshot> sourceSnapshots = this.getSnapshotsForVolume(volume);
            // Iterate through all snapshots found for the source
            for (BlockSnapshot sourceSnapshot : sourceSnapshots) {
                // Check to see if this is an RP bookmark
                if (TechnologyType.RP.name().equals(sourceSnapshot.getTechnologyType())) {
                    // If this RP bookmark is exported, throw an exception to inform the
                    // user. The user should first un-export this bookmark before removing
                    // protection.
                    if (sourceSnapshot.isSnapshotExported(_dbClient)) {
                        String warningMessage = String.format(
                                "RP Bookmark/Snapshot [%s](%s) is exported to Host, "
                                        + "please un-export the Bookmark/Snapshot from all exports and place the order again",
                                sourceSnapshot.getLabel(), sourceSnapshot.getId());
                        _log.warn(warningMessage);
                        throw APIException.badRequests.rpBlockApiImplRemoveProtectionException(warningMessage);
                    }
                    // Add bookmark to be cleaned up in ViPR. These
                    // would have been automatically removed in RP when
                    // removing protection anyway. So this is a pro-active
                    // cleanup step.
                    rpBookmarks.add(sourceSnapshot);
                } else {
                    // This is a block snapshot.
                    // Check to see if the source volume is a RP+VPLEX/MetroPoint volume.
                    if (RPHelper.isVPlexVolume(volume, _dbClient)) {
                        // There are block snapshots on the RP+VPLEX/MetroPoint Source, throw an exception to inform the
                        // user. We can not remove protection from a RP+VPLEX Source when there are active block snapshots.
                        // RP+VPLEX/MetroPoint block snapshots are actually replica group snapshots (in a CG). Since we need to
                        // remove the CG from the volume we can not have the replica group containing snaps in it when
                        // trying to remove protection.
                        String warningMessage = String.format(
                                "RecoverPoint protected VPLEX Volume [%s](%s) has an active snapshot, please delete the "
                                        + "following snapshot and place the order again: [%s](%s)",
                                volume.getLabel(), volume.getId(), sourceSnapshot.getLabel(), sourceSnapshot.getId());
                        warningMessage = warningMessage.substring(0, warningMessage.length() - 2);
                        _log.warn(warningMessage);
                        throw APIException.badRequests.rpBlockApiImplRemoveProtectionException(warningMessage);
                    }
                }
            }

            // Loop through all targets and check for block snapshots.
            // We want to prevent the operation if any of the following conditions
            // exist:
            // 1. There are exported targets
            // 2. There are local array snapshots on any of the targets
            for (String targetId : volume.getRpTargets()) {
                Volume targetVolume = _dbClient.queryObject(Volume.class, URI.create(targetId));
                // Ensure targets are not exported
                if (targetVolume.isVolumeExported(_dbClient, true, true)) {
                    String warningMessage = String.format(
                            "Target Volume [%s](%s) is exported to Host, please "
                                    + "un-export the volume from all exports and place the order again",
                            targetVolume.getLabel(), targetVolume.getId());
                    _log.warn(warningMessage);
                    throw APIException.badRequests.rpBlockApiImplRemoveProtectionException(warningMessage);
                }

                List<BlockSnapshot> targetSnapshots = this.getSnapshotsForVolume(targetVolume);
                for (BlockSnapshot targetSnapshot : targetSnapshots) {
                    // There are snapshots on the targets, throw an exception to inform the
                    // user. We do not want to auto-clean up the snapshots on the target.
                    // The user should first clean up those snapshots.
                    String warningMessage = String.format(
                            "Target Volume [%s] (%s) has an active snapshot, please delete the "
                                    + "following snapshot and place the order again: [%s](%s)",
                            volume.getLabel(), volume.getId(), targetSnapshot.getLabel(), targetSnapshot.getId());
                    _log.warn(warningMessage);
                    throw APIException.badRequests.rpBlockApiImplRemoveProtectionException(warningMessage);
                }
            }

            if (!rpBookmarks.isEmpty()) {
                for (BlockSnapshot bookmark : rpBookmarks) {
                    _log.info(String.format("Deleting RP Snapshot/Bookmark [%s] (%s)", bookmark.getLabel(), bookmark.getId()));
                    // Generate task id
                    final String deleteSnapshotTaskId = UUID.randomUUID().toString();
                    // Delete the snapshot
                    this.deleteSnapshot(bookmark, Arrays.asList(bookmark), deleteSnapshotTaskId, VolumeDeleteTypeEnum.FULL.name());
                }
            }
        }

        // Get volume descriptors for all volumes to remove protection from.
        List<VolumeDescriptor> volumeDescriptors = _rpHelper.getDescriptorsForVolumesToBeDeleted(null, volumeURIs,
                RPHelper.REMOVE_PROTECTION, newVpool);

        BlockOrchestrationController controller = getController(BlockOrchestrationController.class,
                BlockOrchestrationController.BLOCK_ORCHESTRATION_DEVICE);
        controller.deleteVolumes(volumeDescriptors, taskId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateVolumesInVolumeGroup(VolumeGroupVolumeList addVolumes, List<Volume> removeVolumes, URI applicationId, String taskId) {

        VolumeGroup volumeGroup = _dbClient.queryObject(VolumeGroup.class, applicationId);
        ApplicationAddVolumeList addVolumeList = null;
        List<URI> removeVolumesURI = null;
        RPController controller = null;
        URI protSystemUri = null;
        Volume firstVolume = null;
        if (addVolumes != null && addVolumes.getVolumes() != null && !addVolumes.getVolumes().isEmpty()) {
            addVolumeList = addVolumesToApplication(addVolumes, volumeGroup);
            List<URI> vols = addVolumeList.getVolumes();
            if (vols != null && !vols.isEmpty()) {
                firstVolume = _dbClient.queryObject(Volume.class, vols.get(0));
            }
        }

        if (removeVolumes != null && !removeVolumes.isEmpty()) {
            removeVolumesURI = getValidVolumesToRemoveFromCG(removeVolumes);
            if (firstVolume == null) {
                firstVolume = removeVolumes.get(0);
            }

        }
        if ((addVolumeList != null && !addVolumeList.getVolumes().isEmpty()) || (removeVolumesURI != null && !removeVolumesURI.isEmpty())) {
            protSystemUri = firstVolume.getProtectionController();
            ProtectionSystem system = _dbClient.queryObject(ProtectionSystem.class, protSystemUri);
            controller = getController(RPController.class, system.getSystemType());
            controller.updateApplication(protSystemUri, addVolumeList, removeVolumesURI, volumeGroup.getId(), taskId);
        } else {
            // No need to call to controller. update the application task
            Operation op = volumeGroup.getOpStatus().get(taskId);
            op.ready();
            volumeGroup.getOpStatus().updateTaskStatus(taskId, op);
            _dbClient.updateObject(volumeGroup);
        }
    }

    /**
     * Get ApplicationAddVolumeList
     *
     * @param volumesList The add volume list
     * @param application The application that the volumes are added to
     * @return ApplicationVolumeList The volumes that are in the add volume list
     */
    private ApplicationAddVolumeList addVolumesToApplication(VolumeGroupVolumeList volumeList, VolumeGroup application) {
        List<URI> addVolumeURIs = volumeList.getVolumes();
        String groupName = volumeList.getReplicationGroupName();
        Set<URI> allVolumes = RPHelper.getReplicationSetVolumes(addVolumeURIs, _dbClient);
        Map<String, Boolean> checkedRGMap = new HashMap<String, Boolean>();

        for (URI volumeUri : allVolumes) {
            Volume volume = _dbClient.queryObject(Volume.class, volumeUri);
            if (volume == null || volume.getInactive()) {
                throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volumeUri.toString(), "Volume has been deleted");
            }

            BlockServiceUtils.validateVolumeNoReplica(volume, application, _dbClient);

            boolean vplex = RPHelper.isVPlexVolume(volume, _dbClient);
            if (vplex) {
                // get the backend volume
                Volume backendVol = VPlexUtil.getVPLEXBackendVolume(volume, true, _dbClient);
                if (backendVol == null || backendVol.getInactive()) {
                    throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volume.getLabel(),
                            "The backing volume for the VPLEX virtual volume has been deleted");
                }

                String rgName = backendVol.getReplicationGroupInstance();
                if (NullColumnValueGetter.isNotNullValue(rgName) && rgName.equals(groupName)) {
                    if (!vplexBlockServiceApiImpl.checkAllVPlexVolsInRequest(backendVol, allVolumes, checkedRGMap)) {
                        throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volume.getLabel(),
                                "Volume has to be added to a different replication group than it is currently in");
                    }
                }
                // Check if the backend volume is unity, and the subgroup already has snapshot.
                if (!BlockServiceUtils.checkUnityVolumeCanBeAddedOrRemovedToCG(volumeList.getReplicationGroupName(), backendVol, _dbClient, true)) {
                    throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volume.getLabel(),
                            "the Unity subgroup has snapshot.");
                }

            } else {
                if (!BlockServiceUtils.checkUnityVolumeCanBeAddedOrRemovedToCG(volumeList.getReplicationGroupName(), volume, _dbClient, true)) {
                    throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volume.getLabel(),
                            "the Unity subgroup has snapshot.");
                }
            }
        }

        ApplicationAddVolumeList outVolumesList = new ApplicationAddVolumeList();
        outVolumesList.setConsistencyGroup(volumeList.getConsistencyGroup());
        outVolumesList.setReplicationGroupName(groupName);
        outVolumesList.setVolumes(addVolumeURIs);

        return outVolumesList;
    }
    
    /**
     * Get valid volumes to remove for volume group updating
     * Unity array does not support remove volumes from the CG, which has snapshots.
     * 
     * @param removeVolumes The volumes to be removed from the application
     * @return the validated to-beremoved volumes URI list
     */
    List<URI> getValidVolumesToRemoveFromCG(List<Volume> removeVolumes) {
        List<URI> result = new ArrayList<URI>();
        for (Volume vol : removeVolumes) {
            boolean vplex = RPHelper.isVPlexVolume(vol, _dbClient);
            if (vplex) {
             // get the backend volume
                Volume backendVol = VPlexUtil.getVPLEXBackendVolume(vol, true, _dbClient);
                if (backendVol != null) {
                    // Check if the backend volume is unity, and the subgroup already has snapshot.
                    if (!BlockServiceUtils.checkUnityVolumeCanBeAddedOrRemovedToCG(null, backendVol, _dbClient, false)) {
                        throw APIException.badRequests.volumeCantBeRemovedFromVolumeGroup(vol.getLabel(),
                                "the Unity subgroup has snapshot.");
                    }
                }
            } else {
                if (!BlockServiceUtils.checkUnityVolumeCanBeAddedOrRemovedToCG(null, vol, _dbClient, false)) {
                    throw APIException.badRequests.volumeCantBeRemovedFromVolumeGroup(vol.getLabel(),
                            "the Unity subgroup has snapshot.");
                }
            }
            result.add(vol.getId());
        }
        return result;
        
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.emc.storageos.api.service.impl.resource.BlockServiceApi#getReplicationGroupNames(com.emc.storageos.db.client.
     * model.VolumeGroup)
     */
    @Override
    public Collection<? extends String> getReplicationGroupNames(VolumeGroup group) {
        Set<String> groupNames = new HashSet<String>();
        final List<Volume> volumes = CustomQueryUtility.queryActiveResourcesByConstraint(_dbClient, Volume.class,
                AlternateIdConstraint.Factory.getVolumesByVolumeGroupId(group.getId().toString()));
        for (Volume volume : volumes) {
            if (volume.isVPlexVolume(_dbClient)) {
                StringSet backingVolumes = volume.getAssociatedVolumes();
                if (backingVolumes != null) {
                    for (String backingVolId : backingVolumes) {
                        Volume backingVol = _dbClient.queryObject(Volume.class, URI.create(backingVolId));
                        if (backingVol != null && !backingVol.getInactive()
                                && NullColumnValueGetter.isNotNullValue(backingVol.getReplicationGroupInstance())) {
                            groupNames.add(backingVol.getReplicationGroupInstance());
                        }
                    }
                }
            } else if (NullColumnValueGetter.isNotNullValue(volume.getReplicationGroupInstance())) {
                groupNames.add(volume.getReplicationGroupInstance());
            }
        }
        return groupNames;
    }

    @Override
    public List<VolumeDescriptor> createVolumesAndDescriptors(List<VolumeDescriptor> descriptors, String name, Long size, Project project,
            VirtualArray varray, VirtualPool vpool, List<Recommendation> recommendations, TaskList taskList, String task,
            VirtualPoolCapabilityValuesWrapper vpoolCapabilities) {
        // This method is not used for RP
        return null;
    }

    /**
     * Either find the existing RP Copy Name from the CG or create it.
     *
     * @param vpool Vpool for the requested volume
     * @param varray Varray for the requested volume
     * @param consistencyGroup The CG for the requested volume
     * @param productionCopy True if a prod volume, false otherwise
     * @return The RP Copy Name
     */
    private String retrieveRpCopyName(VirtualPool vpool, VirtualArray varray, BlockConsistencyGroup consistencyGroup,
            boolean productionCopy) {
        String copyName = "";
        String existingCopyName = RPHelper.getCgCopyName(_dbClient, consistencyGroup, varray.getId(), productionCopy);
        // Prefer existingCopyName
        if (existingCopyName != null) {
            copyName = existingCopyName;
        } else {
            // Generate new copy name, default is to use the varray label
            copyName = varray.getLabel();
            if (productionCopy) {
                if (VirtualPool.vPoolSpecifiesMetroPoint(vpool)) {
                    VirtualArray haVarray = _dbClient.queryObject(VirtualArray.class, VPlexUtil.getHAVarray(vpool));
                    if (haVarray != null && haVarray.getId().equals(varray.getId())) {
                        // Standby Source Copy Name
                        copyName = copyName + MP_STANDBY_COPY_SUFFIX;
                    } else {
                        // Active Source Copy Name
                        copyName = copyName + MP_ACTIVE_COPY_SUFFIX;
                    }
                } else {
                    // Regular Source Copy Name
                    copyName = copyName + SRC_COPY_SUFFIX;
                }
            }
        }
        return copyName;
    }
    
    /**
     * Create the RP+VPLEX/MetroPoint Data Migration volume descriptors to be passed to the block orchestration
     * change vpool workflow. 
     * 
     * @param volumes The RP+VPLEX/MetroPoint volumes to migrate 
     * @param newVpool The vpool to migrate to
     * @param taskId The task
     * @param validMigrations All valid migrations
     * @param vpoolChangeParam VirtualPool change parameters used to determine if need to suspend on migration
     * @return List of tasks
     * @throws InternalException
     */
    private TaskList rpVPlexDataMigration(List<Volume> volumes, VirtualPool newVpool, String taskId, 
            List<RPVPlexMigration> validMigrations, VirtualPoolChangeParam vpoolChangeParam)
            throws InternalException {                
        // TaskList to return
        TaskList taskList = new TaskList();
        
        if (validMigrations == null || validMigrations.isEmpty()) {
            _log.warn(String.format("No RP+VPLEX migrations found"));
            return taskList;
        }
        
        _log.info(String.format("%s RP+VPLEX migrations found", validMigrations.size()));
      
        List<RPVPlexMigration> sourceVpoolMigrations = new ArrayList<RPVPlexMigration>();
        List<RPVPlexMigration> targetVpoolMigrations = new ArrayList<RPVPlexMigration>();
        List<RPVPlexMigration> journalVpoolMigrations = new ArrayList<RPVPlexMigration>();
        
        try {
            // Step 1
            //
            // Group the migrations by personality
            for (RPVPlexMigration migration : validMigrations) {
                switch (migration.getType()) {
                    case SOURCE:    
                        sourceVpoolMigrations.add(migration);
                        break;
                    case TARGET:
                        targetVpoolMigrations.add(migration);
                        break;
                    case METADATA:
                        journalVpoolMigrations.add(migration);
                        break;
                    default:
                        break;                    
                }
            }
            
            // Convenience booleans to quickly check which migrations are required 
            boolean sourceMigrationsExist = (!sourceVpoolMigrations.isEmpty());
            boolean targetMigrationsExist = (!targetVpoolMigrations.isEmpty());
            boolean journalMigrationsExist = (!journalVpoolMigrations.isEmpty());
            
            if (!sourceMigrationsExist && (targetMigrationsExist || journalMigrationsExist)) {                
                // When there are no Source migrations and the Source volumes are in RGs we need
                // to make sure all those Source volumes are in the request.
                //
                // Otherwise we could have the case where some Source volumes have been moved to a 
                // new vpool and some have not. 
                validateSourceVolumesInRGForMigrationRequest(volumes);                 
            }
            
            _log.info(String.format("%s SOURCE migrations, %s TARGET migrations, %s METADATA migrations", 
                    sourceVpoolMigrations.size(), targetVpoolMigrations.size(), journalVpoolMigrations.size()));
                      
            // Buffer to log all the migrations
            StringBuffer logMigrations = new StringBuffer();
            logMigrations.append("\n\nRP+VPLEX Migrations:\n");
      
            // Step 2
            //
            // Let's find out if there are any Source and Target volumes to migrate. 
            // Source and Target migrations will be treated in two different ways depending 
            // on if the VPLEX backend volumes are in an array Replication Group(RG) or not.
            //
            // 1. In RG
            //    Being in an RG means that the all volumes in the RG will need to be
            //    grouped and migrated together. 
            //    NOTE: 
            //         a) All volumes in the RG will need to be selected for the operation to proceed.
            //         b) There is restriction on the number of volumes in the RG that will be allowed for the migration. 
            //            Default value is 25 volumes. This is an existing limitation in the VPLEX code.
            //         c) Journal volumes will never be in a backend RG.
            // 2. Not in RG
            //    Treated as a normal single migration.   
            HashMap<VirtualPool, List<Volume>> allSourceVolumesToMigrate = new HashMap<VirtualPool, List<Volume>>();
            HashMap<VirtualPool, List<Volume>> allTargetVolumesToMigrate = new HashMap<VirtualPool, List<Volume>>();
            findSourceAndTargetMigrations(volumes, newVpool, sourceMigrationsExist, allSourceVolumesToMigrate, 
                    targetMigrationsExist, allTargetVolumesToMigrate, targetVpoolMigrations, taskList, taskId);
                        
            // Step 3
            //
            // Handle all Source and Target migrations. The ones grouped by RG will
            // be migrated together. The others will be treated as single migrations.
            
            // Map to store single migrations (those not grouped by RG)
            Map<Volume, VirtualPool> singleMigrations = new HashMap<Volume, VirtualPool>();
                                   
            // Source
            //
            // Source volumes could need to be grouped by RG or not (single migration).
            //
            // Grouped migrations will have a migration WF initiated via the 
            // call to migrateVolumesInReplicationGroup().
            //
            // Single migrations will be collected afterwards to be migrated explicitly in Step 4 and 6
            // below.
            rpVPlexGroupedMigrations(allSourceVolumesToMigrate, singleMigrations, 
                    Volume.PersonalityTypes.SOURCE.name(), logMigrations, taskList, taskId, vpoolChangeParam);
            
            // Targets
            //
            // Target volumes could need to be grouped by RG or not (single migration).
            //
            // Grouped migrations will have a migration WF initiated via the 
            // call to migrateVolumesInReplicationGroup().
            //
            // Single migrations will be collected afterwards to be migrated explicitly in Step 4 and 6
            // below.
            rpVPlexGroupedMigrations(allTargetVolumesToMigrate, singleMigrations, 
                    Volume.PersonalityTypes.TARGET.name(), logMigrations, taskList, taskId, vpoolChangeParam);
                            
            // Journals
            //
            // Journals will never be in RGs so they will always be treated as single migrations.
            // Journal volumes must be checked against the CG. So we need to gather all affected 
            // CGs in the request.
            // A new task will be generated to track each Journal migration.            
            Set<URI> cgURIs = BlockConsistencyGroupUtils.getAllCGsFromVolumes(volumes);
            rpVPlexJournalMigrations(journalMigrationsExist, journalVpoolMigrations, singleMigrations, 
                    cgURIs, logMigrations, taskList, taskId);
            
            logMigrations.append("\n");
            _log.info(logMigrations.toString());
             
            // Step 4
            //
            // Create the migration volume descriptors for all single migrations that are not in an RG.
            List<VolumeDescriptor> migrateVolumeDescriptors = new ArrayList<VolumeDescriptor>();
            for (Map.Entry<Volume, VirtualPool> entry : singleMigrations.entrySet()) {
                Volume migrateVolume = entry.getKey();
                VirtualPool migrateToVpool = entry.getValue();
                            
                StorageSystem vplexStorageSystem = _dbClient.queryObject(StorageSystem.class, migrateVolume.getStorageController());
                migrateVolumeDescriptors.addAll(vplexBlockServiceApiImpl
                                          .createChangeVirtualPoolDescriptors(vplexStorageSystem, migrateVolume, migrateToVpool, taskId,
                                                                              null, null, null));
            }
            
            // Step 5
            //
            // If there are migrations but there were no Source volumes involved then we implicitly 
            // need to update the passed in Source volumes with the new vpool (including their backing 
            // volume vpools).
            //
            // The best way to do this is to create a DUMMY_MIGRATE volume descriptor that will
            // be entered into the workflow but will never be consumed by any WF steps. This will
            // ensure the task is completed correctly and the vpools updated by the completer.
            if (!sourceMigrationsExist && (targetMigrationsExist || journalMigrationsExist)) {
                _log.info("No RP+VPLEX Source migrations detected, creating DUMMY_MIGRATE volume descriptors for the Source volumes.");
                for (Volume volume : volumes) {                              
                    if (volume.checkPersonality(Volume.PersonalityTypes.SOURCE)) {                       
                        // Add the VPLEX Virtual Volume Descriptor for change vpool
                        VolumeDescriptor dummyMigrate = new VolumeDescriptor(VolumeDescriptor.Type.DUMMY_MIGRATE,
                                volume.getStorageController(),
                                volume.getId(),
                                volume.getPool(), null);
    
                        Map<String, Object> volumeParams = new HashMap<String, Object>();
                        volumeParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_EXISTING_VOLUME_ID, volume.getId());
                        volumeParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_NEW_VPOOL_ID, newVpool.getId());
                        volumeParams.put(VolumeDescriptor.PARAM_VPOOL_CHANGE_OLD_VPOOL_ID, volume.getVirtualPool());
                        dummyMigrate.setParameters(volumeParams);
                        migrateVolumeDescriptors.add(dummyMigrate);
                    }
                }
            }
            
            // Step 6
            //
            // Invoke the block orchestration with the migration volume descriptors for the
            // single migrations.
            if (!migrateVolumeDescriptors.isEmpty()) {            
                // Invoke the block orchestrator for the change vpool operation
                BlockOrchestrationController controller = getController(
                        BlockOrchestrationController.class,
                        BlockOrchestrationController.BLOCK_ORCHESTRATION_DEVICE);
                controller.changeVirtualPool(migrateVolumeDescriptors, taskId);  
            } else {
                _log.info(String.format("No extra migrations needed."));
            }
        } catch (Exception e) {
            String errorMsg = String.format(
                    "Volume VirtualPool change error: %s", e.getMessage());
            _log.error(errorMsg, e);
            for (TaskResourceRep volumeTask : taskList.getTaskList()) {
                volumeTask.setState(Operation.Status.error.name());
                volumeTask.setMessage(errorMsg);
                _dbClient.updateTaskOpStatus(Volume.class, volumeTask
                        .getResource().getId(), taskId, new Operation(
                        Operation.Status.error.name(), errorMsg));
            }
            throw e;
        }
        
        return taskList;
    }
    
    /**
     * Determines if there are any Source or Target migrations and if so adds them to the
     * containers passed in.
     * 
     * @param volumes List of volumes to migrate
     * @param newVpool The newVpool for Source migrations
     * @param sourceMigrationsExist Determines if there are any source migrations
     * @param allSourceVolumesToMigrate Container for all Source volumes to migrate
     * @param targetMigrationsExist Determines if there are any Target migrations
     * @param allTargetVolumesToMigrate Container for all Target volumes to migrate
     * @param targetVpoolMigrations List of RPVPlexMigration for Target
     * @param taskList Task list
     * @param taskId Task id
     */
    private void findSourceAndTargetMigrations(List<Volume> volumes, VirtualPool newVpool, 
            boolean sourceMigrationsExist, HashMap<VirtualPool, List<Volume>> allSourceVolumesToMigrate, 
            boolean targetMigrationsExist, HashMap<VirtualPool, List<Volume>> allTargetVolumesToMigrate, List<RPVPlexMigration> targetVpoolMigrations, 
            TaskList taskList, String taskId) {
        for (Volume volume : volumes) {
            if (sourceMigrationsExist) {
                // Group Source migrations by new vpool to Source volumes
                List<Volume> sourceVolumesToMigrate = allSourceVolumesToMigrate.get(newVpool);
                if (sourceVolumesToMigrate == null) {
                    sourceVolumesToMigrate = new ArrayList<Volume>();
                    allSourceVolumesToMigrate.put(newVpool, sourceVolumesToMigrate);
                }                
                sourceVolumesToMigrate.add(volume);
            }
                        
            if (targetMigrationsExist) {                
                // Find the Targets for the volume
                StringSet rpTargets = volume.getRpTargets();
                for (String rpTargetId : rpTargets) {
                    Volume rpTargetVolume = _dbClient.queryObject(Volume.class, URI.create(rpTargetId));
                    // Check to see if this Target volume qualifies for migration
                    RPVPlexMigration targetMigration = null;
                    for (RPVPlexMigration migration : targetVpoolMigrations) {
                        if (rpTargetVolume.getVirtualArray().equals(migration.getVarray())
                                && rpTargetVolume.getVirtualPool().equals(migration.getMigrateFromVpool().getId())) {
                            targetMigration = migration;
                            break;
                        }
                    }
                    // If a target migration exists, add it to the list of targets to migrate and create
                    // a new task for the operation.
                    if (targetMigration != null) {
                        // Make sure the target volume is not involved in another task. If it is, an exception will
                        // be thrown.
                        BlockServiceUtils.checkForPendingTasks(rpTargetVolume.getTenant().getURI(), 
                                Arrays.asList(rpTargetVolume), _dbClient);
                        // Make sure the target volume does not have any other restrictions and is
                        // valid for migrations (ex: may have snapshots)
                        VirtualPool migrateFromVpool = targetMigration.getMigrateFromVpool();
                        VirtualPool migrateToVpool = targetMigration.getMigrateToVpool();
                        BlockService.verifyVPlexVolumeForDataMigration(rpTargetVolume, migrateFromVpool, migrateToVpool, _dbClient);
                        
                        Operation op = new Operation();
                        op.setResourceType(ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VPOOL);
                        op.setDescription("Change vpool operation - Migrate RP+VPLEX Target");
                        op = _dbClient.createTaskOpStatus(Volume.class, rpTargetVolume.getId(), taskId, op);
                        taskList.addTask(toTask(rpTargetVolume, taskId, op));
                        
                        // Group Target migrations by new vpool to Target volumes
                        List<Volume> targetVolumesToMigrate = allTargetVolumesToMigrate.get(migrateToVpool);
                        if (targetVolumesToMigrate == null) {
                            targetVolumesToMigrate = new ArrayList<Volume>();
                            allTargetVolumesToMigrate.put(migrateToVpool, targetVolumesToMigrate);
                        }                        
                        targetVolumesToMigrate.add(rpTargetVolume);
                    } else {
                        _log.info(String.format("No migration info was found for Target volume [%s](%s). Skipping...", 
                                rpTargetVolume.getLabel(), rpTargetVolume.getId()));
                    }
                }
            }
        }
    }
    
    /**
     * Calls out to the VPLEX Block Service to group any volumes in RG and migrate
     * the volumes together. If there are any volumes not in RGs they are returned
     * and then added to the single migration container to be migrated later.
     * 
     * @param volumesToMigrate All volumes to migrate
     * @param singleMigrations Container to keep track of single migrations
     * @param type Personality type for logging
     * @param logMigrations Log buffer
     * @param taskList List of tasks that will be returned to user
     * @param taskId Task id of order
     * @param vpoolChangeParam used to determine if we should suspend on migration commit
     */
    private void rpVPlexGroupedMigrations(HashMap<VirtualPool, List<Volume>> volumesToMigrate, 
            Map<Volume, VirtualPool> singleMigrations, String type, StringBuffer logMigrations, 
            TaskList taskList, String taskId, VirtualPoolChangeParam vpoolChangeParam) {
        for (Map.Entry<VirtualPool, List<Volume>> entry : volumesToMigrate.entrySet()) {
            // List to hold volumes that are grouped by RG and migrated together
            List<Volume> volumesInRG = new ArrayList<Volume>();;
            // List to hold volumes that are not grouped by RG and will be migrated as single migrations
            List<Volume> volumesNotInRG = new ArrayList<Volume>(); 
            
            VirtualPool migrateToVpool = entry.getKey();
            List<Volume> migrateVolumes = entry.getValue();
            
            ControllerOperationValuesWrapper operationsWrapper = new ControllerOperationValuesWrapper();
            operationsWrapper.put(ControllerOperationValuesWrapper.MIGRATION_SUSPEND_BEFORE_COMMIT,
                    vpoolChangeParam.getMigrationSuspendBeforeCommit());
            operationsWrapper.put(ControllerOperationValuesWrapper.MIGRATION_SUSPEND_BEFORE_DELETE_SOURCE,
                    vpoolChangeParam.getMigrationSuspendBeforeDeleteSource());
            
            TaskList taskList2 = vplexBlockServiceApiImpl.migrateVolumesInReplicationGroup(migrateVolumes, 
                    migrateToVpool, volumesNotInRG, volumesInRG, operationsWrapper, taskId);
            taskList.getTaskList().addAll(taskList2.getTaskList());
            
            for (Volume volumeInRG : volumesInRG) {
                logMigrations.append(String.format("\tRP+VPLEX migrate %s [%s](%s) to vpool [%s](%s) - GROUPED BY RG\n",                        
                        type, volumeInRG.getLabel(), volumeInRG.getId(),
                        migrateToVpool.getLabel(), migrateToVpool.getId()));
            }
            
            for (Volume volumeNotInRG : volumesNotInRG) {
                logMigrations.append(String.format("\tRP+VPLEX migrate %s [%s](%s) to vpool [%s](%s)\n",
                        type, volumeNotInRG.getLabel(), volumeNotInRG.getId(),
                        migrateToVpool.getLabel(), migrateToVpool.getId()));
                
                singleMigrations.put(volumeNotInRG, migrateToVpool);
            }
        }
    }
    
    /**
     * Special Journal migration step needed as Journals belong to a CG and need to be
     * gathered from the CG. These migrations are always single migrations.
     * 
     * @param journalMigrationsExist Boolean to determine if journal migrations exist
     * @param journalVpoolMigrations List of RPVPlexMigrations for Journals
     * @param singleMigrations Container to store all single migrations
     * @param cgURIs Set of URIs of all the CGs from the request
     * @param logMigrations String buffer for logging
     * @param taskList Task list
     * @param taskId Task id
     */
    private void rpVPlexJournalMigrations(boolean journalMigrationsExist, List<RPVPlexMigration> journalVpoolMigrations, 
            Map<Volume, VirtualPool> singleMigrations, 
            Set<URI> cgURIs, StringBuffer logMigrations, TaskList taskList, String taskId) {
        if (journalMigrationsExist) {            
            for (URI cgURI : cgURIs) {
                BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, cgURI);
                // Get all Journal volumes from the CG.
                List<Volume> journalVolumes = RPHelper.getCgVolumes(_dbClient, cg.getId(), 
                        Volume.PersonalityTypes.METADATA.name());            
                for (Volume journalVolume : journalVolumes) {                
                    // Check to see if this Journal volume qualifies for migration
                    RPVPlexMigration journalMigration = null;
                    for (RPVPlexMigration migration : journalVpoolMigrations) {
                        if (journalVolume.getVirtualArray().equals(migration.getVarray())
                                && journalVolume.getVirtualPool().equals(migration.getMigrateFromVpool().getId())) {                        
                            // Need to make sure we're migrating the right Journal, so check to make sure the copy names match
                            boolean isSourceJournal = migration.getSubType().equals(Volume.PersonalityTypes.SOURCE) ? true : false;
                            String copyName = RPHelper.getCgCopyName(_dbClient, cg, migration.getVarray(), isSourceJournal);                        
                            if (journalVolume.getRpCopyName().equals(copyName)) {                        
                                journalMigration = migration;
                                break;
                            }                        
                        }
                    }
                    // If a journal migration exists, add it to the list of journals to migrate and create
                    // a new task for the operation.
                    if (journalMigration != null) {
                        // Make sure the journal volume is not involved in another task. If it is, an exception will
                        // be thrown.
                        BlockServiceUtils.checkForPendingTasks(journalVolume.getTenant().getURI(), 
                                Arrays.asList(journalVolume), _dbClient);
                        
                        Operation op = new Operation();
                        op.setResourceType(ResourceOperationTypeEnum.CHANGE_BLOCK_VOLUME_VPOOL);
                        op.setDescription("Change vpool operation - Migrate RP+VPLEX Journal");
                        op = _dbClient.createTaskOpStatus(Volume.class, journalVolume.getId(), taskId, op);
                        taskList.addTask(toTask(journalVolume, taskId, op));
                                            
                        VirtualPool migrateToVpool = journalMigration.getMigrateToVpool();
                                            
                        logMigrations.append(String.format("\tRP+VPLEX migrate JOURNAL [%s](%s) to vpool [%s](%s)\n",
                                journalVolume.getLabel(), journalVolume.getId(),
                                migrateToVpool.getLabel(), migrateToVpool.getId()));
                        
                        singleMigrations.put(journalVolume, migrateToVpool);
                    } else {
                        _log.info(String.format("No migration info was found for Journal volume [%s](%s). Skipping...", 
                                journalVolume.getLabel(), journalVolume.getId()));
                    }
                }
            }
        }
    }
    
    /**
     * Given a list of RP Source volumes, check to see if all the volumes passed in are a part of the Replication
     * Group. If we are not passed in all the volumes from the RG, throw an exception. We need to migrate
     * these volumes together.
     * 
     * @param volumes List of Source volumes to check
     */
    private void validateSourceVolumesInRGForMigrationRequest(List<Volume> volumes) {
        // Group all volumes in the request by RG. If there are no volumes in the request
        // that are in an RG then the table will be empty.
        Table<URI, String, List<Volume>> groupVolumes = VPlexUtil.groupVPlexVolumesByRG(
                volumes, null, null, _dbClient);        
        for (Table.Cell<URI, String, List<Volume>> cell : groupVolumes.cellSet()) {
            // Get all the volumes in the request that have been grouped by RG 
            List<Volume> volumesInRGRequest = cell.getValue();
            // Grab the first volume
            Volume firstVolume = volumesInRGRequest.get(0);            
            // Get all the volumes from the RG
            List<Volume> rgVolumes = VPlexUtil.getVolumesInSameReplicationGroup(cell.getColumnKey(), cell.getRowKey(), firstVolume.getPersonality(), _dbClient);
            
            // If all the volumes in the request that have been grouped by RG are not all the volumes from 
            // the RG, throw an exception.
            // We need to migrate all the volumes from the RG together.
            if (volumesInRGRequest.size() != rgVolumes.size()) {
                throw APIException.badRequests.cantMigrateNotAllRPSourceVolumesInRequest();
            }
        }  
    }
}
