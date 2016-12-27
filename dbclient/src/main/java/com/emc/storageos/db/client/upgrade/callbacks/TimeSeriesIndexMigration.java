/*
 * Copyright (c) 2016. EMC  Corporation
 * All Rights Reserved
 */

package com.emc.storageos.db.client.upgrade.callbacks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.model.*;
import com.netflix.astyanax.query.RowQuery;
import com.netflix.astyanax.serializers.StringSerializer;
import com.netflix.astyanax.util.RangeBuilder;

import com.emc.storageos.db.client.impl.*;
import com.emc.storageos.db.client.model.uimodels.Order;
import com.emc.storageos.db.client.upgrade.BaseDefaultMigrationCallback;
import com.emc.storageos.svcs.errorhandling.resources.MigrationCallbackException;

public class TimeSeriesIndexMigration extends BaseDefaultMigrationCallback {
    private static final Logger log = LoggerFactory.getLogger(TimeSeriesIndexMigration.class);
    final public String SOURCE_INDEX_CF_NAME="TenantToOrder";

    public TimeSeriesIndexMigration() {
        super();
    }

    @Override
    public void process() throws MigrationCallbackException {
        long start = System.currentTimeMillis();

        log.info("lbym0: Adding new index records for class: {} field: {} annotation: {}",
                new Object[] { cfClass, fieldName, annotation.annotationType().getCanonicalName()});

        ColumnFamily<String, IndexColumnName> tenantToOrder =
                new ColumnFamily<>(SOURCE_INDEX_CF_NAME, StringSerializer.get(), IndexColumnNameSerializer.get());

        DataObjectType doType = TypeMap.getDoType(Order.class);
        ColumnField field = doType.getColumnField(Order.SUBMITTED);
        ColumnFamily<String, TimeSeriesIndexColumnName> newIndexCF = field.getIndexCF();


        DbClientImpl client = getInternalDbClient();
        Keyspace ks = client.getLocalKeyspace();
        MutationBatch mutationBatch = ks.prepareMutationBatch();

        try {
            long n = 0;
            long m;
            OperationResult<Rows<String, IndexColumnName>> result = ks.prepareQuery(tenantToOrder).getAllRows()
                    .setRowLimit(100)
                    .withColumnRange(new RangeBuilder().setLimit(0).build())
                    .execute();
            for (Row<String, IndexColumnName> row : result.getResult()) {
                n++;
                m = 0;
                RowQuery<String, IndexColumnName> rowQuery = ks.prepareQuery(tenantToOrder).getKey(row.getKey())
                        .autoPaginate(true)
                        .withColumnRange(new RangeBuilder().setLimit(5).build());
                ColumnList<IndexColumnName> cols;
                while (!(cols = rowQuery.execute().getResult()).isEmpty()) {
                    for (Column<IndexColumnName> col : cols) {
                        m++;
                        String indexKey = row.getKey();
                        String orderId = col.getName().getTwo();
                        log.info("lby tid={} order={} m={}", indexKey, orderId, m);

                        TimeSeriesIndexColumnName newCol = new TimeSeriesIndexColumnName(Order.class.getSimpleName(),
                                orderId, col.getName().getTimeUUID());

                        mutationBatch.withRow(newIndexCF, indexKey).putEmptyColumn(newCol, null);
                        if ( m % 10000 == 0) {
                            log.info("lby commit m={}", m);
                            mutationBatch.execute();
                        }
                    }
                }
            }

            mutationBatch.execute();

            long end = System.currentTimeMillis();
            log.info("Read {} records in  MS", n, (end - start)/1000);
        }catch (Exception e) {
            log.error("Migration to {} failed e=", newIndexCF.getName(), e);
        }
    }
}
