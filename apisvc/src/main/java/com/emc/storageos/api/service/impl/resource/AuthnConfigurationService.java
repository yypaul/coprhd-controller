/*
 * Copyright 2013 EMC Corporation
 * Copyright 2016 Intel Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.emc.storageos.api.service.impl.resource;

import static com.emc.storageos.api.mapper.AuthMapper.map;
import static com.emc.storageos.api.mapper.DbObjectMapper.map;

import java.net.*;
import java.util.*;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.emc.storageos.api.service.impl.resource.utils.OpenStackSynchronizationTask;
import com.emc.storageos.cinder.CinderConstants;
import com.emc.storageos.db.client.model.*;
import com.emc.storageos.keystone.restapi.utils.KeystoneUtils;
import com.emc.storageos.model.project.ProjectElement;
import com.emc.storageos.model.project.ProjectParam;
import com.emc.storageos.model.tenant.TenantOrgRestRep;
import com.emc.storageos.model.tenant.TenantCreateParam;
import com.emc.storageos.model.tenant.UserMappingParam;
import com.emc.storageos.security.authorization.*;
import com.emc.storageos.security.authorization.Role;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import com.emc.storageos.api.mapper.AuthMapper;
import com.emc.storageos.coordinator.exceptions.CoordinatorException;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.NamedElementQueryResultList;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.AuthnProvider.ProvidersType;
import com.emc.storageos.db.client.model.AuthnProvider.SearchScope;
import com.emc.storageos.db.common.VdcUtil;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.emc.storageos.model.ResourceTypeEnum;
import com.emc.storageos.model.auth.AuthnCreateParam;
import com.emc.storageos.model.auth.AuthnProviderBaseParam;
import com.emc.storageos.model.auth.AuthnProviderList;
import com.emc.storageos.model.auth.AuthnProviderParamsToValidate;
import com.emc.storageos.model.auth.AuthnProviderRestRep;
import com.emc.storageos.model.auth.AuthnUpdateParam;
import com.emc.storageos.model.auth.RoleAssignmentEntry;
import com.emc.storageos.security.authentication.AuthSvcEndPointLocator;
import com.emc.storageos.security.authentication.AuthSvcInternalApiClientIterator;
import com.emc.storageos.security.authentication.StorageOSUser;
import com.emc.storageos.security.authorization.BasePermissionsHelper.UserMapping;
import com.emc.storageos.security.validator.Validator;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.svcs.errorhandling.resources.BadRequestException;
import com.sun.jersey.api.client.ClientResponse;

/**
 * API for creating and manipulating authentication providers
 */
@Path("/vdc/admin/authnproviders")
@DefaultPermissions(readRoles = { Role.SECURITY_ADMIN },
        writeRoles = { Role.SECURITY_ADMIN })
public class AuthnConfigurationService extends TaggedResource {
    private static final Logger _log = LoggerFactory.getLogger(AuthnConfigurationService.class);

    private static String FEATURE_NAME_LDAP_GROUP_SUPPORT = "Group support for LDAP Authentication Provider";

    @Autowired
    private TenantsService _tenantsService;
    @Autowired
    private AuthSvcEndPointLocator _authSvcEndPointLocator;
    private static final URI _URI_RELOAD = URI.create("/internal/reload");

    private KeystoneUtils _keystoneUtils;

    private OpenStackSynchronizationTask _openStackSynchronizationTask;

    private static final String EVENT_SERVICE_TYPE = "authconfig";

    @Override
    public String getServiceType() {
        return EVENT_SERVICE_TYPE;
    }

    public void setTenantsService(TenantsService tenantsService) {
        this._tenantsService = tenantsService;
    }

    public void setKeystoneUtils(KeystoneUtils keystoneUtils) {
        this._keystoneUtils = keystoneUtils;
    }

    public void setOpenStackSynchronizationTask(OpenStackSynchronizationTask openStackSynchronizationTask) {
        this._openStackSynchronizationTask = openStackSynchronizationTask;
    }

    /**
     * Get detailed information for the authentication provider with the given URN
     *
     * @param id authentication provider URN
     * @brief Show authentication provider
     * @return Provider details
     * @see AuthnProviderRestRep
     */
    @GET
    @Path("/{id}")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public AuthnProviderRestRep getProvider(@PathParam("id") URI id) {
        // TODO: if you need to copy/paste this code, please modify the AbstractPermissionFilter class instead and
        // related CheckPermission annotation code to support "TENANT_ADMIN_IN_ANY_TENANT" permission.
        StorageOSUser user = getUserFromContext();
        if (!_permissionsHelper.userHasGivenRoleInAnyTenant(user, Role.SECURITY_ADMIN, Role.TENANT_ADMIN)) {
            throw APIException.forbidden.insufficientPermissionsForUser(user.getName());
        }

        return map(getProviderById(id, false));
    }

    /**
     * List authentication providers in the zone.
     *
     * @brief List authentication providers
     * @return List of authentication providers
     */
    @GET
    // no id, just "/"
            @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
            public
            AuthnProviderList listProviders() {
        // TODO: if you need to copy/paste this code, please modify the AbstractPermissionFilter class instead and
        // related CheckPermission annotation code to support "TENANT_ADMIN_IN_ANY_TENANT" permission.
        StorageOSUser user = getUserFromContext();
        if (!_permissionsHelper.userHasGivenRoleInAnyTenant(user, Role.SECURITY_ADMIN, Role.TENANT_ADMIN)) {
            throw APIException.forbidden.insufficientPermissionsForUser(user.getName());
        }

        NamedElementQueryResultList providers = new NamedElementQueryResultList();
        List<URI> uris = _dbClient.queryByType(AuthnProvider.class, true);
        List<AuthnProvider> configs = _dbClient.queryObject(AuthnProvider.class, uris);

        List<NamedElementQueryResultList.NamedElement> elements =
                new ArrayList<NamedElementQueryResultList.NamedElement>(configs.size());
        for (AuthnProvider p : configs) {
            elements.add(NamedElementQueryResultList.NamedElement.createElement(p.getId(), p.getLabel()));
        }
        providers.setResult(elements.iterator());

        AuthnProviderList list = new AuthnProviderList();
        list.getProviders().addAll(map(ResourceTypeEnum.AUTHN_PROVIDER, providers));
        return list;
    }

    @Override
    protected DataObject queryResource(URI id) {
        return getProviderById(id, false);
    }

    @Override
    protected URI getTenantOwner(URI id) {
        return null;
    }

    /**
     * Create an authentication provider.
     * The submitted provider element values will be validated.
     * <p>
     * The minimal set of parameters include: mode, server_urls, manager_dn, manager_password, domains, search_base, search_filter and
     * group_attribute
     * <p>
     *
     * @param param AuthnCreateParam The provider representation with all necessary elements.
     * @brief Create an authentication provider
     * @return Newly created provider details as AuthnProviderRestRep
     * @see com.emc.storageos.model.auth.AuthnCreateParam
     * @see AuthnProviderRestRep
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN })
    public AuthnProviderRestRep createProvider(AuthnCreateParam param) {
        validateAuthnCreateParam(param);
        if (param.getDisable() == null || !param.getDisable()) {
            _log.debug("Validating manager dn credentials before provider creation...");
            AuthnProviderParamsToValidate validateP = AuthMapper.mapToValidateCreate(param, null);
            validateP.setUrls(new ArrayList<String>(param.getServerUrls()));
            StringBuilder errorString = new StringBuilder();
            if (!Validator.isUsableAuthenticationProvider(validateP, errorString)) {
                throw BadRequestException.badRequests.
                        authnProviderCouldNotBeValidated(errorString.toString());
            }
        }

        AuthnProvider provider = map(param);
        provider.setId(URIUtil.createId(AuthnProvider.class));

        String mode = provider.getMode();
        if (null != mode && AuthnProvider.ProvidersType.keystone.toString().equalsIgnoreCase(mode)) {
           provider.setKeys(_keystoneUtils.populateKeystoneToken(provider.getServerUrls(), provider.getManagerDN(), getPassword(provider, null)));

            // If the checkbox is checked, then register CoprHD.
            if(provider.getAutoRegCoprHDNImportOSProjects()){
                _keystoneUtils.registerCoprhdInKeystone(provider.getManagerDN(), provider.getServerUrls(), provider.getManagerPassword());
                String interval = _openStackSynchronizationTask.getIntervalFromTenantSyncSet(provider.getTenantsSynchronizationOptions());
                // Set default interval time when chosen interval is lower than minimal value.
                if (Integer.parseInt(interval) < OpenStackSynchronizationTask.MIN_INTERVAL_DELAY) {
                    _log.debug("Setting default interval time as chosen interval is lower than minimal value.");
                    provider.getTenantsSynchronizationOptions().remove(interval);
                    provider.getTenantsSynchronizationOptions().add(Integer.toString(OpenStackSynchronizationTask.DEFAULT_INTERVAL_DELAY));
                }
            }
        } else {
            // Now validate the authn provider to make sure
            // either both group object classes and
            // member attributes present or
            // both of them empty. Throw bad request exception
            // if only one of them presents.
            validateLDAPGroupProperties(provider);
        }

        _log.debug("Saving the provider: {}: {}", provider.getId(), provider.toString());
        persistProfileAndNotifyChange(provider, true);

        auditOp(OperationTypeEnum.CREATE_AUTHPROVIDER, true, null,
                provider.toString(), provider.getId().toString());

        // TODO:
        // recordTenantEvent(RecordableEventManager.EventType.ProfiletCreated,
        // "Authentication Profile created", provider.getId());

        return map(provider);
    }

    /**
     * Returns new password when old password is different from new one. Otherwise returns old password.
     *
     * @param provider AuthnProvider with new password.
     * @param oldPassword Old password for AuthnProvider.
     * @return Password.
     */
    private String getPassword(AuthnProvider provider, String oldPassword){

        String password = "";
        if (null == oldPassword) {
            // create case
            password = provider.getManagerPassword();
        } else {
            // Update case
            String newPassword = provider.getManagerPassword();
            password = (null == newPassword) ? oldPassword :
                    (oldPassword.equals(newPassword) ? oldPassword : newPassword);
        }

        return password;
    }

    public void createTenantsAndProjectsForAutomaticKeystoneRegistration() {

        List<URI> osTenantURI = _dbClient.queryByType(OSTenant.class, true);
        Iterator<OSTenant> osTenantIter = _dbClient.queryIterativeObjects(OSTenant.class, osTenantURI);

        while (osTenantIter.hasNext()) {
            OSTenant osTenant = osTenantIter.next();
            if (!osTenant.getExcluded()) {
                createTenantAndProjectForOpenstackTenant(osTenant);
            }
        }
    }

    public void createTenantAndProjectForOpenstackTenant(OSTenant tenant) {

        TenantCreateParam param = prepareTenantMappingForOpenstack(tenant);

        // Create a tenant.
        TenantOrgRestRep tenantOrgRestRep = _tenantsService.createSubTenant(_permissionsHelper.getRootTenant().getId(), param);

        // Create a project.
        ProjectParam projectParam = new ProjectParam(tenant.getName() + CinderConstants.PROJECT_NAME_SUFFIX);
        ProjectElement projectElement = _tenantsService.createProject(tenantOrgRestRep.getId(), projectParam);

        _keystoneUtils.tagProjectWithOpenstackId(projectElement.getId(), tenant.getOsId(), tenantOrgRestRep.getId().toString());
    }

    public TenantCreateParam prepareTenantMappingForOpenstack(OSTenant tenant) {

        List<UserMappingParam> userMappings = _keystoneUtils.prepareUserMappings(tenant.getOsId());

        TenantCreateParam param = new TenantCreateParam(CinderConstants.TENANT_NAME_PREFIX + " " + tenant.getName(), userMappings);
        if (tenant.getDescription() != null) {
            param.setDescription(tenant.getDescription());
        } else {
            param.setDescription(CinderConstants.TENANT_NAME_PREFIX);
        }

        return param;
    }

    /**
     * Update the parameters of the target authentication provider. The ID is the URN of the authentication provider.
     *
     * @param param required The representation of the provider parameters to be modified.
     * @param id the URN of a ViPR authentication provider
     * @param allow Set this field to true to allow modification of the group-attribute field
     * @brief Update authentication provider
     * @return Provider details with updated values
     * @see AuthnProviderRestRep
     * @see com.emc.storageos.model.auth.AuthnUpdateParam
     */
    @PUT
    @Path("/{id}")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN })
    public AuthnProviderRestRep updateProvider(@PathParam("id") URI id,
            @DefaultValue("false") @QueryParam("allow_group_attr_change") boolean allow,
            AuthnUpdateParam param) {
        AuthnProvider provider = getProviderById(id, false);
        ArgValidator.checkEntityNotNull(provider, id, isIdEmbeddedInURL(id));
        validateAuthnUpdateParam(param, provider);
        // after the overlay, manager dn or manager password may end up null if they were
        // not part of the param object. If so we need to grab a copy now from the db model
        // object. When we validate, we cannot have null values.
        AuthnProviderParamsToValidate validateP = AuthMapper.mapToValidateUpdate(param, provider);
        boolean wasAlreadyDisabled = provider.getDisable();
        String mode = provider.getMode();
        if (null != mode && AuthnProvider.ProvidersType.keystone.toString().equalsIgnoreCase(mode)) {
            return updateKeystoneProvider(id, param, provider, validateP);
        } else {
            return updateAdOrLDAPProvider(id, allow, param, provider, validateP, wasAlreadyDisabled);
        }
    }

    /**
     * Checks if the only options that changed were Tenants Synchronization Options.
     *
     * @param authnProvider old AuthnProvider
     * @param param AuthnUpdateParam with params for updated AuthnProvider
     * @return true if only Tenants Synchronization Options changed, false otherwise
     */
    private boolean isTenantsSynchronizationOptionsChanged(AuthnProvider authnProvider, AuthnUpdateParam param) {

        if (param.getTenantsSynchronizationOptionsChanges().getAdd().isEmpty() &&
            param.getTenantsSynchronizationOptionsChanges().getRemove().isEmpty()) {
            return false;
        }

        if (param.getLabel() != null && !param.getLabel().equals(authnProvider.getLabel())) {
            return false;
        }

        if (param.getGroupAttribute() != null) {
            return false;
        }

        if (param.getManagerDn() != null) {
            return false;
        }

        if (param.getManagerPassword() != null) {
            return false;
        }

        if (param.getSearchBase() != null) {
            return false;
        }

        if (param.getSearchFilter() != null) {
            return false;
        }

        if (param.getSearchScope() != null) {
            return false;
        }

        if (param.getMode() != null && !param.getMode().equals(authnProvider.getMode())) {
            return false;
        }

        if (param.getLabel() != null) {
            return false;
        }

        if (param.getDescription() != null) {
            return false;
        }

        if (param.getDisable() != null) {
            return false;
        }

        if (param.getAutoRegCoprHDNImportOSProjects() != null) {
            return false;
        }

        if (param.getMaxPageSize() != null) {
            return false;
        }

        if (!param.getGroupWhitelistValueChanges().getAdd().isEmpty() || !param.getGroupWhitelistValueChanges().getRemove().isEmpty()) {
            return false;
        }

        if (!param.getDomainChanges().getAdd().isEmpty() || !param.getDomainChanges().getRemove().isEmpty()) {
            return false;
        }

        if (!param.getServerUrlChanges().getAdd().isEmpty() || !param.getServerUrlChanges().getRemove().isEmpty()) {
            return false;
        }

        if (!param.getGroupObjectClassChanges().getAdd().isEmpty() || !param.getGroupObjectClassChanges().getRemove().isEmpty()) {
            return false;
        }

        if (!param.getGroupMemberAttributeChanges().getAdd().isEmpty() || !param.getGroupMemberAttributeChanges().getRemove().isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * Updates Keystone Auth Provider with given id.
     *
     * @param id URI of AuthnProvider
     * @param param AuthnUpdateParam with params for updated AuthnProvider
     * @param provider AuthnProvider to update
     * @param validateP AuthnProviderParamsToValidate with parameters to validate the Provider.
     * @return AuthnProviderRestRep updated Provider
     */
    private AuthnProviderRestRep updateKeystoneProvider(URI id, AuthnUpdateParam param,
            AuthnProvider provider, AuthnProviderParamsToValidate validateP) {
        String oldPassword = provider.getManagerPassword();
        boolean isAutoRegistered = provider.getAutoRegCoprHDNImportOSProjects();
        int synchronizationInterval = OpenStackSynchronizationTask.DEFAULT_INTERVAL_DELAY;

        // if Auto Registration was done, get interval value from Tenant Sync Options
        if (isAutoRegistered) {
            synchronizationInterval = Integer
                    .parseInt(_openStackSynchronizationTask.getIntervalFromTenantSyncSet(provider.getTenantsSynchronizationOptions()));
        }

        // if the configured domain has tenant then we can't update that domain unless
        // the only options that changed were Tenants Synchronization Options
        // (update to Auth Provider was done from Tenants section)
        if (!isTenantsSynchronizationOptionsChanged(provider, param)) {
            checkForActiveTenantsUsingDomains(provider.getDomains());
        }

        overlayProvider(provider, param);
        // Set old password if new one is a blank or null.
        provider.setManagerPassword(getPassword(provider, oldPassword));

        // get interval value from updated Tenant Sync Options
        int newSynchronizationInterval = Integer
                .parseInt(_openStackSynchronizationTask.getIntervalFromTenantSyncSet(provider.getTenantsSynchronizationOptions()));

        // Whenever new interval value is below minimum, then set default interval time for keystone auth provider.
        if (newSynchronizationInterval < OpenStackSynchronizationTask.MIN_INTERVAL_DELAY) {
            _log.debug("Setting default interval time as chosen interval is lower than minimal value.");
            provider.getTenantsSynchronizationOptions().remove(Integer.toString(newSynchronizationInterval));
            provider.getTenantsSynchronizationOptions().add(Integer.toString(OpenStackSynchronizationTask.DEFAULT_INTERVAL_DELAY));
        }

        if (!provider.getDisable()) {
            _log.debug("Validating provider before modification...");
            validateP.setUrls(new ArrayList<String>(provider.getServerUrls()));
            StringBuilder errorString = new StringBuilder();
            if (!Validator.isUsableAuthenticationProvider(validateP, errorString)) {
                throw BadRequestException.badRequests.authnProviderCouldNotBeValidated(errorString.toString());
            }
        }
        provider.setKeys(_keystoneUtils.populateKeystoneToken(provider.getServerUrls(), provider.getManagerDN(),
                getPassword(provider, oldPassword)));
        _log.debug("Saving to the DB the updated provider: {}", provider.toString());
        persistProfileAndNotifyChange(provider, false);

        // if the Auto Registration was not done before, then register CoprHD in Keystone
        if (provider.getAutoRegCoprHDNImportOSProjects() && !isAutoRegistered) {
            _keystoneUtils.registerCoprhdInKeystone(provider.getManagerDN(), provider.getServerUrls(), provider.getManagerPassword());
        }

        // if the Automatic Registration is selected and new interval value is not the same as the old one,
        // then reschedule the task
        if (isAutoRegistered && synchronizationInterval != newSynchronizationInterval) {
            _openStackSynchronizationTask.rescheduleTask(newSynchronizationInterval);
        }
        auditOp(OperationTypeEnum.UPDATE_AUTHPROVIDER, true, null,
                provider.getId().toString(), provider.toString());
        return map(getProviderById(id, false));
    }

    private AuthnProviderRestRep updateAdOrLDAPProvider(URI id, boolean allow,
            AuthnUpdateParam param, AuthnProvider provider,
            AuthnProviderParamsToValidate validateP, boolean wasAlreadyDisabled) {
        // copy existing domains in separate set
        StringSet existingDomains = new StringSet(provider.getDomains());

        // up front check to see if we are modifying the group attribute.
        // this may trigger an audit.
        boolean groupAttributeModified = false;
        if (StringUtils.isNotBlank(param.getGroupAttribute()) &&
                StringUtils.isNotBlank(provider.getGroupAttribute()) &&
                !provider.getGroupAttribute().equalsIgnoreCase(param.getGroupAttribute())) {
            if (!allow) {
                throw APIException.badRequests.
                        modificationOfGroupAttributeNotAllowed(param.getGroupAttribute());
            } else {
                _log.warn("GROUP ATTRIBUTE FOR PROVIDER {} {} IS BEING " +
                        "MODIFIED USING THE FORCE FLAG, TENANT MAPPINGS, " +
                        "ROLE ASSIGNMENTS OR PROJECT ACLS MAY BE AFFECTED", provider.getLabel(),
                        provider.getId().toString());
                groupAttributeModified = true;
            }
        }

        overlayProvider(provider, param);
        // after overlay, copy new set of domains
        StringSet newDomains = new StringSet(provider.getDomains());
        // do a diff between the two sets and check any domain
        // that was removed
        existingDomains.replace(newDomains);
        Set<String> removed = existingDomains.getRemovedSet();
        if (removed != null) {
            verifyDomainsIsNotInUse(new StringSet(removed));
        }

        if (!provider.getDisable()) {
            _log.debug("Validating provider before modification...");
            validateP.setUrls(new ArrayList<String>(provider.getServerUrls()));
            StringBuilder errorString = new StringBuilder();
            if (!Validator.isUsableAuthenticationProvider(validateP, errorString)) {
                throw BadRequestException.badRequests.
                        authnProviderCouldNotBeValidated(errorString.toString());
            }
        } else if (!wasAlreadyDisabled) {
            // if we are disabling it and it wasn't already disabled, then
            // check if its domains still be in use.
            verifyDomainsIsNotInUse(provider.getDomains());
        }

        // Now validate the authn provider to make sure
        // either both group object classes and
        // member attributes present or
        // both of them empty. Throw bad request exception
        // if only one of them presents.
        validateLDAPGroupProperties(provider);

        _log.debug("Saving to the DB the updated provider: {}", provider.toString());
        persistProfileAndNotifyChange(provider, false);

        if (groupAttributeModified) {
            auditOp(OperationTypeEnum.UPDATE_AUTHPROVIDER_GROUP_ATTR, true, null,
                    provider.getId().toString(), provider.toString());
        } else {
            auditOp(OperationTypeEnum.UPDATE_AUTHPROVIDER, true, null,
                    provider.getId().toString(), provider.toString());
        }
        return map(getProviderById(id, false));
    }

    /**
     * Overlay an existing AuthnConfiguration object using a new AuthnConfiguration
     * update parameter. Regardless of whether the new AuthnConfiguration update
     * param's attribute is null or not, it will be used to overwrite existingi
     * AuthnConfiguration object's attribute or merge with existing AuthnConfiguration
     * object's attribute, because DbClient#persistObject supports partial writes.
     *
     * @param authn
     *            The existing AuthnConfiguration object
     *
     * @param param
     *            AuthnConfiguration update param to overlay existing AuthnConfiguration
     *            object
     */
    private void overlayProvider(AuthnProvider authn, AuthnUpdateParam param) {
        if (param == null) {
            return;
        }

        authn.setGroupAttribute(param.getGroupAttribute());

        if (param.getGroupWhitelistValueChanges() != null) {
            StringSet ssOld = authn.getGroupWhitelistValues();
            if (ssOld == null) {
                ssOld = new StringSet();
            }
            if (param.getGroupWhitelistValueChanges().getAdd() != null) {
                ssOld.addAll(param.getGroupWhitelistValueChanges().getAdd());
            }
            if (param.getGroupWhitelistValueChanges().getRemove() != null) {
                ssOld.removeAll(new HashSet<String>(param.getGroupWhitelistValueChanges().getRemove()));
            }
            authn.setGroupWhitelistValues(ssOld);
        }

        if (param.getDomainChanges() != null) {
            StringSet ssOld = authn.getDomains();
            if (ssOld == null) {
                ssOld = new StringSet();
            }
            Set<String> toAdd = param.getDomainChanges().getAdd();
            if (toAdd != null) {
                for (String s : toAdd) {
                    // When Authn provider is added, the domains are converted to lower case, we need to do the same when update provider.
                    // This change is made to fix bug CTRL-5076
                    ssOld.add(s.toLowerCase());
                }
            }
            Set<String> toRemove = param.getDomainChanges().getRemove();
            ssOld.removeAll(toRemove);
            // We shouldn't convert toRemove to lower case. It will disallow us removing the domain we don't want, moreover it might remove
            // a entry we want to keep.
            authn.setDomains(ssOld);
        }

        if (param.getManagerDn() != null) {
            authn.setManagerDN(param.getManagerDn());
        }

        authn.setManagerPassword(param.getManagerPassword());

        authn.setSearchBase(param.getSearchBase());

        authn.setSearchFilter(param.getSearchFilter());

        authn.setSearchScope(param.getSearchScope());

        if (param.getServerUrlChanges() != null) {
            StringSet ssOld = authn.getServerUrls();
            if (ssOld == null) {
                ssOld = new StringSet();
            }
            if (param.getServerUrlChanges().getAdd() != null) {
                ssOld.addAll(param.getServerUrlChanges().getAdd());
            }
            if (param.getServerUrlChanges().getRemove() != null) {
                ssOld.removeAll(new HashSet<String>(param.getServerUrlChanges().getRemove()));
            }
            if (ssOld.isEmpty()) {
                ArgValidator.checkFieldNotEmpty(ssOld,
                        "Attempt to remove the last url is not allowed.  At least one url must be in the provider.");
            }
            authn.setServerUrls(ssOld);
        }

        if (param.getMode() != null
                && param.getMode().equals(AuthnProvider.ProvidersType.keystone.toString())
                && param.getTenantsSynchronizationOptionsChanges() != null) {
            StringSet oldOptions = authn.getTenantsSynchronizationOptions();
            if (oldOptions == null) {
                oldOptions = new StringSet();
            }
            if (param.getTenantsSynchronizationOptionsChanges().getAdd() != null) {
                oldOptions.addAll(param.getTenantsSynchronizationOptionsChanges().getAdd());
            }
            if (param.getTenantsSynchronizationOptionsChanges().getRemove() != null) {
                oldOptions.removeAll(new HashSet<String>(param.getTenantsSynchronizationOptionsChanges().getRemove()));
            }
            if (oldOptions.isEmpty()) {
                ArgValidator.checkFieldNotEmpty(oldOptions,
                        "Interval cannot be empty. Please provide the value.");
            }
            authn.setTenantsSynchronizationOptions(oldOptions);
        }

        if (param.getMode() != null) {
            authn.setMode(param.getMode());
        }

        authn.setLabel(param.getLabel());

        authn.setDescription(param.getDescription());

        authn.setDisable(param.getDisable() != null ? param.getDisable() : authn.getDisable());

        authn.setAutoRegCoprHDNImportOSProjects(param.getAutoRegCoprHDNImportOSProjects() != null ? param.getAutoRegCoprHDNImportOSProjects()
                : authn.getAutoRegCoprHDNImportOSProjects());

        authn.setMaxPageSize(param.getMaxPageSize());

        if (param.getGroupObjectClassChanges() != null) {
            StringSet ssOld = authn.getGroupObjectClassNames();
            if (ssOld == null) {
                ssOld = new StringSet();
            }

            if (param.getGroupObjectClassChanges().getRemove() != null) {
                ssOld.removeAll(new HashSet<String>(param.getGroupObjectClassChanges().getRemove()));
            }

            if (param.getGroupObjectClassChanges().getAdd() != null) {
                ssOld.addAll(param.getGroupObjectClassChanges().getAdd());
            }

            authn.setGroupObjectClassNames(ssOld);
        }

        if (param.getGroupMemberAttributeChanges() != null) {
            StringSet ssOld = authn.getGroupMemberAttributeTypeNames();
            if (ssOld == null) {
                ssOld = new StringSet();
            }

            if (param.getGroupMemberAttributeChanges().getRemove() != null) {
                ssOld.removeAll(new HashSet<String>(param.getGroupMemberAttributeChanges().getRemove()));
            }

            if (param.getGroupMemberAttributeChanges().getAdd() != null) {
                ssOld.addAll(param.getGroupMemberAttributeChanges().getAdd());
            }

            authn.setGroupMemberAttributeTypeNames(ssOld);
        }
    }

    /**
     * check if given domains are in use or not,
     *
     * if any of them is in use, throw exception.
     *
     * @param domains
     */
    private void verifyDomainsIsNotInUse(StringSet domains) {
        // check that there are no active tenants with a mapping for given domain(s).
        checkForActiveTenantsUsingDomains(domains);

        // check that there are no users/groups from given domain(s) has vdc role(s).
        checkForVdcRolesUsingDomains(domains);

        // check tenants that no users/groups from given domain(s) has tenant role(s).
        checkForTenantRolesUsingDomains(domains);

        // check if any user attributes group using the domains.
        checkForUserGroupsUsingDomains(domains);
    }

    /**
     * Queries all tenants in the system for which the passed in domain set
     * has a match in their user mapping. If so, will throw an exception.
     * Else will just return normally.
     *
     * @param domains the domains to check
     */
    private void checkForActiveTenantsUsingDomains(StringSet domains) {
        // check that there are no active tenants with a mapping for this
        // provider's domain(s).
        List<URI> matchingTenants = new ArrayList<URI>();
        for (String domainToCheck : domains) {
            Map<URI, List<UserMapping>> mappings = _permissionsHelper.getAllUserMappingsForDomain(domainToCheck);
            Set<URI> tenantIDset;
            if (mappings == null) {
            	_log.debug("No matching tenant found for domain {}", domainToCheck);
                continue;
            }
            tenantIDset = mappings.keySet();
            _log.debug("{} matching tenants found for domain {}", tenantIDset.size(), domainToCheck);
            List<TenantOrg> tenants = _dbClient.queryObject(TenantOrg.class,
                    new ArrayList<URI>(tenantIDset));
            if (tenants != null) {
                for (TenantOrg tenant : tenants) {
                    if (!tenant.getInactive()) {
                        matchingTenants.add(tenant.getId());
                    }
                }
            }
        }
        if (!matchingTenants.isEmpty()) {
            throw APIException.badRequests.cannotDeleteAuthProviderWithTenants(matchingTenants.size(),
                    matchingTenants);
        }
    }

    /**
     * check local vdc to see if any vdc role assignments belongs to the passed in domain set.
     * if so, throw an exception;
     * else, return silently.
     *
     * @param domains the domains to check
     */
    private void checkForVdcRolesUsingDomains(StringSet domains) {
        // get local vdc's role assignments
        VirtualDataCenter localVdc = VdcUtil.getLocalVdc();
        List<RoleAssignmentEntry> vdcRoles =
                _permissionsHelper.convertToRoleAssignments(localVdc.getRoleAssignments(), true);

        List<String> matchingUsers = checkRolesUsingDomains(vdcRoles, domains);

        if (!matchingUsers.isEmpty()) {
            throw APIException.badRequests.cannotDeleteAuthProviderWithVdcRoles(matchingUsers.size(), matchingUsers);
        }
    }

    /**
     * check tenants to see if any tenant role assignments belongs to the passed in domain set.
     * if so, throw an exception;
     * else, return silently.
     *
     * @param domains the domains to check
     */
    private void checkForTenantRolesUsingDomains(StringSet domains) {
        List<URI> tenantURIList = _dbClient.queryByType(TenantOrg.class, true);
        for (URI tenantURI : tenantURIList) {
            TenantOrg tenant = _dbClient.queryObject(TenantOrg.class, tenantURI);
            _log.debug("checking " + tenant.getLabel());
            List<RoleAssignmentEntry> tenantRoles =
                    _permissionsHelper.convertToRoleAssignments(tenant.getRoleAssignments(), false);

            List<String> matchingUsers = checkRolesUsingDomains(tenantRoles, domains);
            if (!matchingUsers.isEmpty()) {
                throw APIException.badRequests.cannotDeleteAuthProviderWithTenantRoles(tenant.getLabel(), matchingUsers.size(),
                        matchingUsers);
            }
        }

    }

    /**
     * compare role assignments against domain(s), return matching users.
     *
     * @param roleAssignments
     * @param domains
     */
    private List<String> checkRolesUsingDomains(List<RoleAssignmentEntry> roleAssignments, StringSet domains) {

        List<String> matchingUsers = new ArrayList<String>();
        for (RoleAssignmentEntry roleAssignment : roleAssignments) {
            String idOrGroup = !StringUtils.isEmpty(roleAssignment.getSubjectId()) ?
                    roleAssignment.getSubjectId() : roleAssignment.getGroup();

            _log.debug("checking " + idOrGroup);
            String domain = "";
            if (idOrGroup.lastIndexOf("@") != -1) {
                domain = idOrGroup.substring(idOrGroup.lastIndexOf("@") + 1);
            } else {
                continue;
            }

            for (String domainToCheck : domains) {
                if (domainToCheck.equalsIgnoreCase(domain)) {
                    matchingUsers.add(idOrGroup);
                }
            }
        }

        return matchingUsers;
    }

    /**
     * Delete the provider with the provided id.
     * Authentication services will no longer use this provider for authentication.
     *
     * @param id the URN of a ViPR provider authentication to be deleted
     * @brief Delete authentication provider
     * @return No data returned in response body
     */
    @DELETE
    @Path("/{id}")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN })
    public Response deleteProvider(@PathParam("id") URI id) {
        AuthnProvider provider = getProviderById(id, false);
        ArgValidator.checkEntityNotNull(provider, id, isIdEmbeddedInURL(id));
        checkForActiveTenantsUsingDomains(provider.getDomains());
        if (!AuthnProvider.ProvidersType.keystone.toString()
                .equalsIgnoreCase(provider.getMode())) {
            checkForVdcRolesUsingDomains(provider.getDomains());
            checkForUserGroupsUsingDomains(provider.getDomains());
            verifyDomainsIsNotInUse(provider.getDomains());
        } else {
            _openStackSynchronizationTask.stopSynchronizationTask();
            // Remove all OSTenant objects from DB when Keystone Provider is deleted.
            List<URI> osTenantURIs = _dbClient.queryByType(OSTenant.class, true);
            List<OSTenant> tenants = _dbClient.queryObject(OSTenant.class, osTenantURIs);
            for (OSTenant osTenant : tenants) {
                _dbClient.removeObject(osTenant);
            }
            // Delete Cinder endpoints.
            _keystoneUtils.deleteCinderEndpoints(provider.getManagerDN(), provider.getServerUrls(), provider.getManagerPassword());
        }
        _dbClient.removeObject(provider);
        notifyChange();

        // TODO - record event

        auditOp(OperationTypeEnum.DELETE_AUTHPROVIDER, true, null,
                provider.getId().toString());
        return Response.ok().build();
    }

    /**
     * @param id the URN of a ViPR authentication provider to get details from
     * @param checkInactive If true, make sure that provider is not inactive
     * @return AuthnProvider object for the given id
     */
    private AuthnProvider getProviderById(URI id, boolean checkInactive) {
        if (id == null) {
            _log.debug("Provider ID is NULL");
            return null;
        }
        _log.debug("Provider ID is {}", id.toString());
        AuthnProvider provider = _permissionsHelper.getObjectById(id, AuthnProvider.class);
        ArgValidator.checkEntity(provider, id, isIdEmbeddedInURL(id), checkInactive);
        return provider;
    }

    /**
     * Checks if an authn provider exists for the given domain
     *
     * @param domain
     * @return true if a provider exists, false otherwise
     */
    private boolean doesProviderExistForDomain(String domain) {
        URIQueryResultList providers = new URIQueryResultList();
        try {
            _dbClient.queryByConstraint(
                    AlternateIdConstraint.Factory.getAuthnProviderDomainConstraint(domain),
                    providers);
        } catch (DatabaseException ex) {
            _log.error("Could not query for authn providers to check for existing domain {}", domain, ex.getStackTrace());
            throw ex;
        }
        return providers.iterator().hasNext();
    }

    private void validateAuthnProviderParam(AuthnProviderBaseParam param, AuthnProvider provider,
            Set<String> server_urls, Set<String> domains, Set<String> group_whitelist_values) {
        if (param.getLabel() != null && !param.getLabel().isEmpty()) {
            if (provider == null || !provider.getLabel().equalsIgnoreCase(param.getLabel())) {
                checkForDuplicateName(param.getLabel(), AuthnProvider.class);
            }
        }
        if (param.getMode() != null) {
            ArgValidator.checkFieldNotEmpty(param.getMode(), "mode");
            try {
                ProvidersType.valueOf(param.getMode());
            } catch (IllegalArgumentException ex) {
                throw APIException.badRequests.invalidParameterWithCause("mode", param.getMode(), ex);
            }
        }
        if (param.getManagerDn() != null) {
            ArgValidator.checkFieldNotEmpty(param.getManagerDn(), "manager_dn");
        }
        if (param.getManagerPassword() != null) {
            ArgValidator.checkFieldNotEmpty(param.getManagerPassword(), "manager_password");
        }
        // validate syntax of the provided parameters for POST and PUT operations
        if (domains != null) {
            ArgValidator.checkFieldNotEmpty(domains, "domains");
            for (String domain : domains) {
                ArgValidator.checkFieldNotEmpty(domain, "domain");
                if (provider == null || !provider.getDomains().contains(domain.toLowerCase())) {
                    if (doesProviderExistForDomain(domain)) {
                        throw APIException.badRequests.domainAlreadyExists(domain);
                    }
                }
            }
        }
        if (param.getGroupAttribute() != null) {
            ArgValidator.checkFieldNotEmpty(param.getGroupAttribute(), "group_attribute");
        }
        // Just validate that the mode is one of the enums
        if (group_whitelist_values != null) {
            for (String groupName : group_whitelist_values) {
                ArgValidator.checkFieldNotEmpty(groupName, "group_whitelist_values");
            }
        }
        if (!AuthnProvider.ProvidersType.keystone.toString().equalsIgnoreCase(param.getMode())) {
            if (server_urls != null) {
                boolean isNonSecure = false;
                boolean isSecure = false;
                for (String url : server_urls) {
                    ArgValidator.checkFieldNotEmpty(url, "server_urls");
                    String lowerCaseUrl = url.toLowerCase();
                    if (lowerCaseUrl.startsWith("ldap://")) {
                        isNonSecure = true;
                    } else if (lowerCaseUrl.startsWith("ldaps://")) {
                        isSecure = true;
                    } else {
                        throw APIException.badRequests.invalidParameter("server_url", url);
                    }
                    if (isNonSecure && isSecure) {
                        throw APIException.badRequests.cannotMixLdapAndLdapsUrls();
                    }
                }
            }
            String searchFilter = param.getSearchFilter();
            if (searchFilter != null) {
                if (!searchFilter.contains("=")) {
                    throw APIException.badRequests.searchFilterMustContainEqualTo();
                }

                String afterEqual = searchFilter.substring(searchFilter.indexOf("="));
                if (!afterEqual.contains("%u") && !afterEqual.contains("%U")) {
                    throw APIException.badRequests.searchFilterMustContainPercentU();
                }
            }
            String searchScope = param.getSearchScope();
            if (searchScope != null) {
                try {
                    // Just validate that the scope is one of the enums
                    SearchScope.valueOf(param.getSearchScope());
                } catch (IllegalArgumentException ex) {
                    throw APIException.badRequests.invalidParameterWithCause("search_scope", param.getSearchScope(), ex);
                }
            }
            if (param.getSearchBase() != null) {
                ArgValidator.checkFieldNotEmpty(param.getSearchBase(), "search_base");
            }
            if (param.getMaxPageSize() != null) {
                if (param.getMaxPageSize() <= 0) {
                    throw APIException.badRequests.parameterMustBeGreaterThan("max_page_size", 0);
                }
            }
        } else {
            String managerDn = param.getManagerDn();
            if (managerDn != null) {
                if (!managerDn.contains("=")) {
                    throw APIException.badRequests.managerDNMustcontainEqualTo();
                }

                if (!(managerDn.contains("username") || managerDn.contains("userName"))
                        || !(managerDn.contains("tenantName") || managerDn.contains("tenantname"))) {
                    throw APIException.badRequests.managerDNMustcontainUserNameAndTenantName();
                }
            }
        }
    }

    private void validateAuthnCreateParam(AuthnCreateParam param) {
        if (param == null) {
            throw APIException.badRequests.resourceEmptyConfiguration("authn provider");
        }

        _log.debug("Zone authentication create param: {}", param);

        ArgValidator.checkFieldNotNull(param.getMode(), "mode");
        ArgValidator.checkFieldNotNull(param.getManagerDn(), "manager_dn");
        ArgValidator.checkFieldNotNull(param.getManagerPassword(), "manager_password");
        // The syntax for search_filter will be checked in the following section of this function
        // Check that the LDAP server URL is present.
        // The syntax will be checked in the following section of this function
        ArgValidator.checkFieldNotEmpty(param.getServerUrls(), "server_urls");

        // The domains tag must be present in any new profile
        ArgValidator.checkFieldNotNull(param.getDomains(), "domains");
        if (!AuthnProvider.ProvidersType.keystone.toString().equalsIgnoreCase(param.getMode())) {
            ArgValidator.checkFieldNotNull(param.getSearchFilter(), "search_filter");
            ArgValidator.checkFieldNotNull(param.getSearchBase(), "search_base");
        } else {
            ensureSingleKeystoneProvider(param);
        }

        checkIfCreateLDAPGroupPropertiesSupported(param);

        validateAuthnProviderParam(param, null, param.getServerUrls(), param.getDomains(),
                param.getGroupWhitelistValues());
    }

    /**
     * Ensures that there exists a single keystone provider always.
     *
     * Requests for keystone authentication provider will be coming from OpenStack
     * environment, since it is not possible to figure out which keystone provider to
     * use for which request, hence only single keystone authentication provider is
     * supported.
     *
     * @param param
     */
    private void ensureSingleKeystoneProvider(AuthnCreateParam param) {
        List<URI> allProviders = _dbClient.queryByType(AuthnProvider.class, true);
        for (URI providerURI : allProviders) {
            AuthnProvider provider = getProviderById(providerURI, true);
            if (AuthnProvider.ProvidersType.keystone.toString().equalsIgnoreCase(provider.getMode().toString())) {
                throw APIException.badRequests.keystoneProviderAlreadyPresent();
            }
        }

    }

    private void validateAuthnUpdateParam(AuthnUpdateParam param, AuthnProvider provider) {
        if (param == null) {
            throw APIException.badRequests.resourceEmptyConfiguration("authn provider");
        }
        _log.debug("Vdc authentication update param: {}", param);

        Set<String> server_urls = null;
        Set<String> domains = null;
        Set<String> group_whitelist_values = null;
        if (param.getServerUrlChanges() != null && !param.getServerUrlChanges().getAdd().isEmpty()) {
            server_urls = param.getServerUrlChanges().getAdd();
        }
        // validate syntax of the provided parameters for POST and PUT operations
        if (param.getDomainChanges() != null && !param.getDomainChanges().getAdd().isEmpty()) {
            domains = param.getDomainChanges().getAdd();
        }
        if (param.getGroupWhitelistValueChanges() != null &&
                !param.getGroupWhitelistValueChanges().getAdd().isEmpty()) {
            group_whitelist_values = param.getGroupWhitelistValueChanges().getAdd();
        }

        checkIfUpdateLDAPGroupPropertiesSupported(param);

        validateAuthnProviderParam(param, provider, server_urls, domains, group_whitelist_values);
    }

    /**
     * Call the internode URI on all authSvc endpoints to reload the auth handlers
     */
    private void notifyChange() {
        try {
            AuthSvcInternalApiClientIterator authSvcItr = new AuthSvcInternalApiClientIterator(_authSvcEndPointLocator, _coordinator);
            while (authSvcItr.hasNext()) {
                String endpoint = authSvcItr.peek().toString();
                try {
                    ClientResponse response = authSvcItr.post(_URI_RELOAD, null);
                    if (response.getStatus() != ClientResponse.Status.OK.getStatusCode()) {
                        _log.error("Failed to reload authN providers on endpoint {} response {}", endpoint, response.toString());
                    }
                } catch (Exception e) {
                    _log.error("Caught exception trying to reload an authsvc on {} continuing", endpoint, e);
                }
            }
        } catch (CoordinatorException e) {
            _log.error("Caught coordinator exception trying to find an authsvc endpoint", e);
        }
    }

    /**
     * update the timestamp and notify
     */
    private synchronized void persistProfileAndNotifyChange(AuthnProvider modifiedProvider,
            boolean newObject) {
        modifiedProvider.setLastModified(System.currentTimeMillis());
        if (newObject) {
            _dbClient.createObject(modifiedProvider);
        } else {
            _dbClient.persistObject(modifiedProvider);
        }
        notifyChange();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Class<AuthnProvider> getResourceClass() {
        return AuthnProvider.class;
    }

    @Override
    protected ResourceTypeEnum getResourceType() {
        return ResourceTypeEnum.AUTHN_PROVIDER;
    }

    /***
     * Make sure all the VDCs in the federation are in the minimum expected version
     * for using the ldap group support.
     *
     * @param createParam set of group object classes.
     */
    private void checkIfCreateLDAPGroupPropertiesSupported(AuthnCreateParam createParam) {
        boolean checkCompatibleVersion = false;
        if (createParam != null) {
            if (StringUtils.isNotEmpty(createParam.getGroupAttribute())) {
                checkCompatibleVersion = true;
            } else if (!CollectionUtils.isEmpty(createParam.getGroupWhitelistValues())) {
                checkCompatibleVersion = true;
            } else if (!CollectionUtils.isEmpty(createParam.getGroupObjectClasses())) {
                checkCompatibleVersion = true;
            } else if (!CollectionUtils.isEmpty(createParam.getGroupMemberAttributes())) {
                checkCompatibleVersion = true;
            }
        }

        if (checkCompatibleVersion) {
            checkCompatibleVersionForLDAPGroupSupport();
        }
    }

    /***
     * Make sure all the VDCs in the federation are in the minimum expected version
     * for using the ldap group support.
     *
     * @param updateParam set of group object classes.
     */
    private void checkIfUpdateLDAPGroupPropertiesSupported(AuthnUpdateParam updateParam) {
        boolean checkCompatibleVersion = false;
        if (updateParam != null) {
            if (StringUtils.isNotBlank(updateParam.getGroupAttribute())) {
                checkCompatibleVersion = true;
            } else if (updateParam.getGroupWhitelistValueChanges() != null &&
                    (!CollectionUtils.isEmpty(updateParam.getGroupWhitelistValueChanges().getAdd()) ||
                    !CollectionUtils.isEmpty(updateParam.getGroupWhitelistValueChanges().getRemove()))) {
                checkCompatibleVersion = true;
            } else if (updateParam.getGroupObjectClassChanges() != null &&
                    (!CollectionUtils.isEmpty(updateParam.getGroupObjectClassChanges().getAdd()) ||
                    !CollectionUtils.isEmpty(updateParam.getGroupObjectClassChanges().getRemove()))) {
                checkCompatibleVersion = true;
            } else if (updateParam.getGroupMemberAttributeChanges() != null &&
                    (!CollectionUtils.isEmpty(updateParam.getGroupMemberAttributeChanges().getAdd()) ||
                    !CollectionUtils.isEmpty(updateParam.getGroupMemberAttributeChanges().getRemove()))) {
                checkCompatibleVersion = true;
            }
        }

        if (checkCompatibleVersion) {
            checkCompatibleVersionForLDAPGroupSupport();
        }
    }

    /**
     * Check if all the VDCs in the federation are in the same expected
     * or minimum supported version for this api.
     * 
     */
    private void checkCompatibleVersionForLDAPGroupSupport() {
        if (!_dbClient.checkGeoCompatible(AuthnProvider.getExpectedGeoVDCVersionForLDAPGroupSupport())) {
            throw APIException.badRequests.incompatibleGeoVersions(AuthnProvider.getExpectedGeoVDCVersionForLDAPGroupSupport(),
                    FEATURE_NAME_LDAP_GROUP_SUPPORT);
        }
    }

    /***
     * Validate the ldap group properties (object classes and member attributes)
     * and throws bad request exception if either one of object classes
     * or member attributes present in the authn provider.
     * 
     * @param authnProvider to be validated for ldap group properties.
     * @throws APIException
     */
    private void validateLDAPGroupProperties(AuthnProvider authnProvider) {
        if (authnProvider == null) {
            _log.error("Invalid authentication provider");
            return;
        }

        boolean isGroupObjectClassesEmpty = CollectionUtils.isEmpty(authnProvider.getGroupObjectClassNames());
        boolean isGroupMemberAttributesEmpty = CollectionUtils.isEmpty(authnProvider.getGroupMemberAttributeTypeNames());

        // Return error if either only one of object classes or member attributes
        // is entered and other one is empty.
        if (isGroupObjectClassesEmpty ^ isGroupMemberAttributesEmpty) {
            String param = "Group object classes";
            if (!isGroupObjectClassesEmpty) {
                param = "Group member attributes";
            }
            throw APIException.badRequests.authnProviderGroupObjectClassesAndMemberAttributesRequired(param);
        }
    }

    /**
     * Check if any of the user group using the domains in the authnprovider or not.
     * if so, throw an exception;
     * else, return silently.
     * 
     * @param domains the domains to check
     */
    private void checkForUserGroupsUsingDomains(StringSet domains) {
        if (CollectionUtils.isEmpty(domains)) {
            _log.error("Invalid domains");
            return;
        }

        Set<URI> matchingUserGroupsURI = new HashSet<URI>();
        for (String domain : domains) {
            if (StringUtils.isBlank(domain)) {
                _log.warn("Invalid domain");
                continue;
            }

            List<UserGroup> matchingUserGroup = _permissionsHelper.getAllUserGroupForDomain(domain);
            if (CollectionUtils.isEmpty(matchingUserGroup)) {
                _log.debug("No user group found for the domain {}", domain);
                continue;
            }

            for (UserGroup userGroup : matchingUserGroup) {
                matchingUserGroupsURI.add(userGroup.getId());
            }
        }

        if (!CollectionUtils.isEmpty(matchingUserGroupsURI)) {
            throw APIException.badRequests.cannotDeleteAuthnProviderWithUserGroup(matchingUserGroupsURI.size(),
                    matchingUserGroupsURI);
        }
    }
}
