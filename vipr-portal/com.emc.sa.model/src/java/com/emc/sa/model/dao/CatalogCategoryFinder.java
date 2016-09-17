/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.sa.model.dao;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;

import com.emc.storageos.db.client.model.uimodels.CatalogCategory;
import com.emc.sa.model.util.SortedIndexUtils;
import com.emc.sa.model.util.TenantUtils;
import com.emc.storageos.db.client.constraint.NamedElementQueryResultList.NamedElement;
import com.emc.storageos.security.authentication.StorageOSUser;
import com.google.common.collect.Lists;

public class CatalogCategoryFinder extends TenantModelFinder<CatalogCategory> {

    public CatalogCategoryFinder(DBClientWrapper client) {
        super(CatalogCategory.class, client);
    }

    public List<CatalogCategory> findSubCatalogCategories(URI catalogCategoryId) {

        List<CatalogCategory> results = Lists.newArrayList();

        List<NamedElement> catalogCategoryIds = client
                .findBy(CatalogCategory.class, CatalogCategory.CATALOG_CATEGORY_ID, catalogCategoryId);
        if (catalogCategoryIds != null) {
            results.addAll(findByIds(toURIs(catalogCategoryIds)));
        }

        SortedIndexUtils.sort(results);

        return results;
    }

    public CatalogCategory getRootCategory(String tenant) {
        CatalogCategory root = null;
        if (StringUtils.isBlank(tenant)) {
            return root;
        }
        List<NamedElement> catalogCategoryIds = client.findBy(CatalogCategory.class, CatalogCategory.CATALOG_CATEGORY_ID,
                URI.create(CatalogCategory.NO_PARENT));
        List<CatalogCategory> tenantRootCategories = TenantUtils.filter(findByIds(toURIs(catalogCategoryIds)), tenant);
        if (tenantRootCategories != null && !tenantRootCategories.isEmpty()) {
            root = tenantRootCategories.get(0);
        }
        return root;
    }

    public Map<URI, Set<String>> findPermissions(StorageOSUser user, URI tenantId) {
        return findPermissions(this.clazz, user, tenantId);
    }

    public Map<URI, Set<String>> findPermissions(StorageOSUser user, URI tenantId, Set<String> filterBy) {
        return findPermissions(this.clazz, user, tenantId, filterBy);
    }

}
