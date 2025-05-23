/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.executor.handler.subhandler;

import com.alibaba.polardbx.common.jdbc.ParameterContext;
import com.alibaba.polardbx.common.jdbc.Parameters;
import com.alibaba.polardbx.common.utils.logger.Logger;
import com.alibaba.polardbx.common.utils.logger.LoggerFactory;
import com.alibaba.polardbx.executor.common.ExecutorContext;
import com.alibaba.polardbx.executor.common.GsiStatisticsManager;
import com.alibaba.polardbx.executor.cursor.Cursor;
import com.alibaba.polardbx.executor.cursor.impl.ArrayResultCursor;
import com.alibaba.polardbx.executor.handler.VirtualViewHandler;
import com.alibaba.polardbx.executor.sync.GsiStatisticsSyncAction;
import com.alibaba.polardbx.executor.sync.SyncManagerHelper;
import com.alibaba.polardbx.gms.metadb.table.GsiStatisticsAccessorDelegate;
import com.alibaba.polardbx.gms.metadb.table.IndexesRecord;
import com.alibaba.polardbx.gms.sync.SyncScope;
import com.alibaba.polardbx.optimizer.config.table.GsiMetaManager;
import com.alibaba.polardbx.optimizer.config.table.statistic.StatisticManager;
import com.alibaba.polardbx.optimizer.config.table.statistic.StatisticResult;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.core.planner.SqlConverter;
import com.alibaba.polardbx.optimizer.core.row.Row;
import com.alibaba.polardbx.optimizer.view.InformationSchemaGlobalIndexes;
import com.alibaba.polardbx.optimizer.view.InformationSchemaTables;
import com.alibaba.polardbx.optimizer.view.VirtualView;
import com.alibaba.polardbx.statistics.RuntimeStatHelper;
import com.google.common.collect.ImmutableSet;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * @version 1.0
 */
public class InformationSchemaGlobalIndexesHandler extends BaseVirtualViewSubClassHandler {

    private static final Logger logger = LoggerFactory.getLogger(InformationSchemaGlobalIndexesHandler.class);

    public InformationSchemaGlobalIndexesHandler(VirtualViewHandler virtualViewHandler) {
        super(virtualViewHandler);
    }

    @Override
    public boolean isSupport(VirtualView virtualView) {
        return virtualView instanceof InformationSchemaGlobalIndexes;
    }

    @Override
    public Cursor handle(VirtualView virtualView, ExecutionContext executionContext, ArrayResultCursor cursor) {
        // get schema names in equivalence conditions
        // e.g., get 'db1' from 'WHERE SCHEMA=db1' or get 'db1', 'db2' from 'WHERE SCHEMA in (db1, db2)'
        Set<String> equalSchemaNames = getEqualSchemaNames(virtualView, executionContext);

        // get table names in equivalence conditions
        // e.g., get 'tb1' from 'WHERE TABLE=tb1' or get 'tb1', 'tb2' from 'WHERE TABLE in (tb1, tb2)'
        Set<String> equalTableNames = getEqualTableNames(virtualView, executionContext);

        final GsiMetaManager metaManager =
            ExecutorContext.getContext(executionContext.getSchemaName()).getGsiManager().getGsiMetaManager();

        final GsiMetaManager.GsiMetaBean meta = metaManager.getAllGsiMetaBean(equalSchemaNames, equalTableNames);

        // schemaName -> gsi table beans
        Map<String, List<GsiMetaManager.GsiTableMetaBean>> schemaToGsiTables =
            meta.getTableMeta().values().stream()
                // only collect gsi table bean, excluding columnar index
                .filter(bean -> bean.gsiMetaBean != null && !bean.gsiMetaBean.columnarIndex)
                // group by schema name
                .collect(Collectors.groupingBy(bean -> bean.tableSchema, Collectors.toList()));

        // generate a result row for each GSI table
        schemaToGsiTables.forEach((key, value) -> generateRows(key, value, cursor));

        return cursor;
    }

    /**
     * Generate cursor rows containing the statistics information of for all GSI tables
     * in schema with {schemaName}.
     * This function first use information_schema.TABLES to get statistics,
     * then generate a result row for each GSI table and add it into {cursor}
     */
    private void generateRows(String schemaName,
                              List<GsiMetaManager.GsiTableMetaBean> gsiTableBeans,
                              ArrayResultCursor resultCursor) {
        ExecutionContext executionContext = new ExecutionContext(schemaName);
        executionContext.setTraceId("gsi_statistics");
        executionContext.setParams(new Parameters());
        executionContext.setRuntimeStatistics(RuntimeStatHelper.buildRuntimeStat(executionContext));
        SqlConverter sqlConverter = SqlConverter.getInstance(schemaName, executionContext);
        RelOptCluster relOptCluster = sqlConverter.createRelOptCluster();

        // generate an InformationSchemaTables virtual view, and execute it to get statistics
        InformationSchemaTables informationSchemaTables =
            new InformationSchemaTables(relOptCluster, relOptCluster.getPlanner().emptyTraitSet());
        RexBuilder rexBuilder = relOptCluster.getRexBuilder();

        // filter condition 1: TABLE_SCHEMA = {schemaName}
        RexNode filterCondition = rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
            rexBuilder.makeInputRef(informationSchemaTables, InformationSchemaTables.getTableSchemaIndex()),
            rexBuilder.makeLiteral(schemaName));

        // filter condition 2: TABLE_NAME IN (tableName1, tableName2, ...)
        List<RexNode> tableNameLiterals = new ArrayList<>(gsiTableBeans.size());
        gsiTableBeans.forEach(bean ->
            tableNameLiterals.add(rexBuilder.makeLiteral(bean.tableName)));
        RexNode inCondition = rexBuilder.makeCall(SqlStdOperatorTable.IN,
            rexBuilder.makeInputRef(informationSchemaTables, InformationSchemaTables.getTableNameIndex()),
            rexBuilder.makeCall(SqlStdOperatorTable.ROW, tableNameLiterals));

        // final filter condition: condition 1 AND condition 2
        filterCondition = rexBuilder.makeCall(SqlStdOperatorTable.AND, filterCondition, inCondition);
        informationSchemaTables.pushFilter(filterCondition);
        informationSchemaTables.setIncludeGsi(true);

        // GSI table name -> GSI size in MB
        Map<String, Double> gsiSizes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        // If some GSI can not get size, just set its size to NULL.
        // In this way, all GSI tables are made sure to be presented int the final result.
        Cursor cursor = null;
        try {
            cursor = virtualViewHandler.handle(informationSchemaTables, executionContext);
            Row row;
            while ((row = cursor.next()) != null) {
                try {
                    String tableName = row.getString(2);
                    Long dataLength = row.getLong(9);
                    // index length of gsi will be added into that of primary table
                    Long indexLength = 0L;
                    // convert total size from Byte to MB
                    Double totalSize = null;
                    if (dataLength != null && indexLength != null) {
                        totalSize = BigDecimal.valueOf((dataLength.doubleValue() + indexLength.doubleValue())
                            / 1024d / 1024d).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
                    }
                    if (tableName != null && totalSize != null) {
                        gsiSizes.put(tableName, totalSize);
                    }
                } catch (Exception e) {
                    // ignore the exception
                    logger.warn("ignore exception: get single GSI size failed", e);
                }
            }
        } catch (Exception e) {
            // ignore the exception
            logger.warn("ignore exception: get all GSI size failed", e);
        } finally {
            try {
                if (cursor != null) {
                    cursor.close(new ArrayList<>());
                }
            } finally {
                executionContext.clearAllMemoryPool();
            }
        }

        //query gsi statistics info
        Map<String, Set<String>> schemaAndGsis = new TreeMap<>(String::compareToIgnoreCase);
        gsiTableBeans.forEach(tableBean -> {
            if (tableBean != null && tableBean.gsiMetaBean != null && !tableBean.gsiMetaBean.columnarIndex) {
                GsiMetaManager.GsiIndexMetaBean indexBean = tableBean.gsiMetaBean;
                String schema = indexBean.tableSchema;
                String index = indexBean.indexName;
                if (!schemaAndGsis.containsKey(schema)) {
                    schemaAndGsis.put(schema, new TreeSet<>(String::compareToIgnoreCase));
                }
                schemaAndGsis.get(schema).add(index);
            }
        });

        if (GsiStatisticsManager.enableGsiStatisticsCollection()) {
            for (String schemaToSync : schemaAndGsis.keySet()) {
                SyncManagerHelper.sync(
                    new GsiStatisticsSyncAction(schemaToSync, null, null, GsiStatisticsSyncAction.QUERY_RECORD),
                    SyncScope.ALL);
            }
        }

        //collect records by schemaName and indexName: Map<indexSchema, Map<indexName, record>>
        Map<String, Map<String, IndexesRecord>> records = new TreeMap<>(String::compareToIgnoreCase);
        for (String schema : schemaAndGsis.keySet()) {
            Set<String> gsiNames = schemaAndGsis.get(schema);
            List<IndexesRecord> tempRecords = queryGsiStatisticsByCondition(
                ImmutableSet.of(schemaName),
                null,
                null,
                gsiNames,
                null
            );
            for (IndexesRecord rd : tempRecords) {
                if (!records.containsKey(schema)) {
                    records.put(schema, new TreeMap<>(String::compareToIgnoreCase));
                }
                records.get(schema).put(rd.indexName, rd);
            }
        }

        // make sure all GSI tables are presented in the final result
        gsiTableBeans.forEach(tableBean -> {
            if (tableBean != null && tableBean.gsiMetaBean != null && !tableBean.gsiMetaBean.columnarIndex) {
                GsiMetaManager.GsiIndexMetaBean indexBean = tableBean.gsiMetaBean;
                // visit count, last access time
                String schema = indexBean.tableSchema;
                String indexName = indexBean.indexName;
                String primaryTableName = indexBean.tableName;

                Long cardinality = null;
                Long rowCount = null;
                IndexesRecord gsiStatisticsRecord = null;
                if (records.get(schema) != null && records.get(schema).get(indexName) != null) {
                    gsiStatisticsRecord = records.get(schema).get(indexName);

                    //selectivity
                    List<GsiMetaManager.GsiIndexColumnMetaBean> indexColumns = indexBean.indexColumns;
                    String columnStr = indexColumns.stream().map(indexColumn -> indexColumn.columnName)
                        .collect(Collectors.joining(","));
                    StatisticResult cardinalityResult =
                        StatisticManager.getInstance()
                            .getCardinality(schema, primaryTableName, columnStr, false, false);
                    cardinality = cardinalityResult.getLongValue();

                    StatisticResult rowCountResult =
                        StatisticManager.getInstance().getRowCount(schema, primaryTableName, false);
                    rowCount = rowCountResult.getLongValue();
                }

                resultCursor.addRow(new Object[] {
                    indexBean.tableSchema,
                    indexBean.tableName,
                    indexBean.nonUnique ? 1 : 0,
                    indexBean.indexName,
                    indexBean.indexColumns.stream()
                        .map(col -> col.columnName).collect(Collectors.joining(", ")),
                    indexBean.coveringColumns.stream()
                        .map(col -> col.columnName).collect(Collectors.joining(", ")),
                    indexBean.indexType,
                    tableBean.dbPartitionKey,
                    tableBean.dbPartitionPolicy,
                    tableBean.dbPartitionCount,
                    tableBean.tbPartitionKey,
                    tableBean.tbPartitionPolicy,
                    tableBean.tbPartitionCount,
                    indexBean.indexStatus.toString(),
                    gsiSizes.get(tableBean.tableName), // may be NULL if this GSI can not get size,
                    gsiStatisticsRecord == null ? null : gsiStatisticsRecord.visitFrequency,
                    gsiStatisticsRecord == null ? null : gsiStatisticsRecord.lastAccessTime,
                    cardinality,
                    rowCount
                });
            }
        });
    }

    /**
     * get schema names in equivalence conditions,
     * e.g. get 'db1' from "select * from global_indexes where schema='db1'"
     * get 'db1', 'db2' from "select * from global_indexes where schema in ('db1', 'db2')"
     *
     * @return empty set if no schema names specified in conditions
     */
    Set<String> getEqualSchemaNames(VirtualView virtualView, ExecutionContext executionContext) {
        Map<Integer, ParameterContext> params = executionContext.getParams().getCurrentParameter();
        return virtualView.getEqualsFilterValues(InformationSchemaGlobalIndexes.getTableSchemaIndex(), params);
    }

    /**
     * get table names in equivalence conditions,
     * e.g. get 'tb1' from "select * from global_indexes where table='tb1'"
     * or   get 'tb1', 'tb2' from "select * from global_indexes where table in ('tb1', 'tb2')"
     *
     * @return empty set if no table names specified in conditions
     */
    Set<String> getEqualTableNames(VirtualView virtualView, ExecutionContext executionContext) {
        Map<Integer, ParameterContext> params = executionContext.getParams().getCurrentParameter();
        return virtualView.getEqualsFilterValues(InformationSchemaGlobalIndexes.getTableNameIndex(), params);
    }

    private List<IndexesRecord> queryGsiStatisticsByCondition(Set<String> schemaNames, Set<String> tableNames,
                                                              String tableLike, Set<String> indexNames,
                                                              String indexLike) {
        return new GsiStatisticsAccessorDelegate<List<IndexesRecord>>() {
            @Override
            protected List<IndexesRecord> invoke() {
                return indexesAccessor.queryGsiByCondition(schemaNames, tableNames, tableLike, indexNames, indexLike);
            }
        }.execute();
    }

}
