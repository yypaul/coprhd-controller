/*
 * Copyright (c) 2012 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.db.client.impl;

import java.lang.ref.SoftReference;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.serializers.StringSerializer;

/**
 * Utility class for cleaning out old index entries
 */
public class IndexCleaner {
    private static final Logger _log = LoggerFactory.getLogger(IndexCleaner.class);
    private static final int DEFAULT_INDEX_CLEANER_POOL_SIZE = 10;
    private ExecutorService _indexCleanerExe;

    public IndexCleaner() {
        this(DEFAULT_INDEX_CLEANER_POOL_SIZE);
    }

    public IndexCleaner(int poolSize) {
        _indexCleanerExe = Executors.newFixedThreadPool(poolSize);
    }

    /**
     * Clean out old column / index entries synchronously
     * 
     * @param mutator
     * @param doType
     * @param listToCleanRef
     */
    public void cleanIndex(RowMutator mutator, DataObjectType doType, SoftReference<IndexCleanupList> listToCleanRef) {
        /*
         * We use SoftReference here instead of Strong Reference to avoid OOM in huge concurrent requests,
         * Objects hold by SoftReference will be cleared if low memory. refer to:CTRL-10228 for detail.
         */
        IndexCleanupList listToClean = listToCleanRef.get();
        if (listToClean == null) {
            _log.warn("clean up list for {} has been recycled by GC, skip it", doType.getClass().getName());
            return;
        }

        Map<String, List<Column<CompositeColumnName>>> cleanList = listToClean.getColumnsToClean();
        Iterator<Map.Entry<String, List<Column<CompositeColumnName>>>> entryIt = cleanList.entrySet().iterator();
        Map<String, ColumnField> dependentFields = new HashMap<>();
        while (entryIt.hasNext()) {
            Map.Entry<String, List<Column<CompositeColumnName>>> entry = entryIt.next();

            String rowKey = entry.getKey();
            List<Column<CompositeColumnName>> cols = entry.getValue();
            for (int i = 0; i < cols.size(); i++) {
                Column<CompositeColumnName> column = cols.get(i);
                ColumnField field = doType.getColumnField(column.getName().getOne());
                field.removeColumn(rowKey, column, mutator, listToClean.getAllColumns(rowKey));
                List<ColumnField> depFields = field.getDependentFields();
                for (ColumnField depField : depFields) {
                    dependentFields.put(depField.getName(), depField);
                }
            }
            for (ColumnField depField : dependentFields.values()) {
                depField.removeIndex(rowKey, null, mutator, listToClean.getAllColumns(rowKey),
                        listToClean.getObject(rowKey));
            }
        }
        // If this is an IndexCleanupList, means we're called because someone changed the object fields
        // We need to check if .inactive is changed to true, if so, we need to hide (remove) related index entries from index CFs
        removeIndexOfInactiveObjects(mutator, doType, (IndexCleanupList) listToClean, true);

        mutator.execute();
    }

    public void removeColumnAndIndex(RowMutator mutator, DataObjectType doType, RemovedColumnsList listToClean) {

        Map<String, List<Column<CompositeColumnName>>> cleanList = listToClean.getColumnsToClean();
        Iterator<Map.Entry<String, List<Column<CompositeColumnName>>>> entryIt = cleanList.entrySet().iterator();
        while (entryIt.hasNext()) {
            Map.Entry<String, List<Column<CompositeColumnName>>> entry = entryIt.next();

            String rowKey = entry.getKey();
            List<Column<CompositeColumnName>> cols = entry.getValue();
            for (int i = 0; i < cols.size(); i++) {
                Column<CompositeColumnName> column = cols.get(i);
                ColumnField field = doType.getColumnField(column.getName().getOne());
                field.removeColumn(rowKey, column, mutator, listToClean.getAllColumns(rowKey));
            }
        }

        mutator.execute();
    }

    public void removeIndexOfInactiveObjects(RowMutator mutator, DataObjectType doType, IndexCleanupList indexCleanList,
            boolean forChangedObjectsOnly) {
        // Get list of columns to cleanup for objects has changes on their indexed fields
        Map<String, List<Column<CompositeColumnName>>> indexesToClean = indexCleanList.getIndexesToClean(forChangedObjectsOnly);

        // See if there's index entries need us to hide (remove), this list is non-empty if any object's .inactive is true
        // and the object has at least one indexed field (except .inactive itself)
        if (indexesToClean != null && !indexesToClean.isEmpty()) { // Have at least one object's one indexed field to hide
            // Hide each object's indexed fields
            for (Map.Entry<String, List<Column<CompositeColumnName>>> entry : indexesToClean.entrySet()) {
                String rowKey = entry.getKey();
                // Hide each column of this object
                for (Column<CompositeColumnName> col : entry.getValue()) {
                    // Column -> ColumnField -> DbIndex -> .remove
                    ColumnField field = doType.getColumnField(col.getName().getOne());
                    DbIndex index = field.getIndex();

                    if (index != null) {
                        index.removeColumn(rowKey, col, doType.getDataObjectClass().getSimpleName(), mutator,
                                indexCleanList.getAllColumns(rowKey));
                    }
                }
            }
        }
    }

    /**
     * remove index from old index table; used in migration if index cf has changed
     * 
     * @param mutator
     * @param doType
     * @param cleanList
     * @param oldIndexCf
     */
    public void removeOldIndex(RowMutator mutator, DataObjectType doType, Map<String, List<Column<CompositeColumnName>>> cleanList,
            String oldIndexCf) {
        Iterator<Map.Entry<String, List<Column<CompositeColumnName>>>> entryIt = cleanList.entrySet().iterator();
        while (entryIt.hasNext()) {
            Map.Entry<String, List<Column<CompositeColumnName>>> entry = entryIt.next();

            String rowKey = entry.getKey();
            List<Column<CompositeColumnName>> cols = entry.getValue();
            Map<String, List<Column<CompositeColumnName>>> fieldColumnMap = buildFieldMapFromColumnList(cols);
            for (Column<CompositeColumnName> column : cols) {
                ColumnField field = doType.getColumnField(column.getName().getOne());
                ColumnFamily<String, IndexColumnName> currentIndexCF = field.getIndex().getIndexCF();
                ColumnFamily<String, IndexColumnName> oldIndexCF = new ColumnFamily<String, IndexColumnName>(
                        oldIndexCf, StringSerializer.get(), IndexColumnNameSerializer.get());
                field.getIndex().setIndexCF(oldIndexCF);
                field.removeIndex(rowKey, column, mutator, fieldColumnMap);
                field.getIndex().setIndexCF(currentIndexCF);
            }
        }
        mutator.execute();
    }

    /**
     * Clean out old column and its index asynchronously
     * 
     * @param mutator
     * @param doType
     * @param listToCleanRef
     */
    public void cleanIndexAsync(final RowMutator mutator, final DataObjectType doType,
            final SoftReference<IndexCleanupList> listToCleanRef) {
        _indexCleanerExe.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                cleanIndex(mutator, doType, listToCleanRef);
                return null;
            }
        });
    }

    private static Map<String, List<Column<CompositeColumnName>>> buildFieldMapFromColumnList(List<Column<CompositeColumnName>> columns) {
        Map<String, List<Column<CompositeColumnName>>> columnMap = new HashMap<>();
        for (Column<CompositeColumnName> column : columns) {
            String fieldName = column.getName().getOne();
            List<Column<CompositeColumnName>> fieldList = columnMap.get(fieldName);
            if (fieldList == null) {
                fieldList = new ArrayList<>();
                columnMap.put(fieldName, fieldList);
            }
            fieldList.add(column);
        }
        return columnMap;
    }
}
