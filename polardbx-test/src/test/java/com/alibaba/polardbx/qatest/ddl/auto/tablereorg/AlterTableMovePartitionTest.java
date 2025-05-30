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

package com.alibaba.polardbx.qatest.ddl.auto.tablereorg;

import com.alibaba.polardbx.optimizer.config.table.ComplexTaskMetaManager;
import com.alibaba.polardbx.optimizer.partition.common.PartitionStrategy;
import com.alibaba.polardbx.qatest.ddl.auto.tablegroup.AlterTableGroupTestBase;
import com.alibaba.polardbx.qatest.util.JdbcUtil;
import com.google.common.collect.ImmutableList;
import net.jcip.annotations.NotThreadSafe;
import org.apache.calcite.util.Pair;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.is;

/**
 * Created by luoyanxin.
 *
 * @author luoyanxin
 */
@RunWith(Parameterized.class)
@NotThreadSafe
public class AlterTableMovePartitionTest extends AlterTableReorgBaseTest {

    private static List<ComplexTaskMetaManager.ComplexTaskStatus> tableStatus =
        Stream.of(
            ComplexTaskMetaManager.ComplexTaskStatus.DELETE_ONLY,
            ComplexTaskMetaManager.ComplexTaskStatus.WRITE_REORG,
            ComplexTaskMetaManager.ComplexTaskStatus.READY_TO_PUBLIC,
            ComplexTaskMetaManager.ComplexTaskStatus.PUBLIC).collect(Collectors.toList());

    /*
    *
    * boolean needGenDml, int dmlType, int rowCount,
                                 String targetPart, Integer minVal1, Integer maxVal1,
                                 Integer minVal2, Integer maxVal2
    *
    * */
    static List<PartitionRuleInfo> partitionRuleInfos = new ArrayList<>(Arrays
        .asList(
            new PartitionRuleInfo(PartitionStrategy.KEY,
                1,
                PARTITION_BY_BIGINT_KEY,
                "alter table " + tableName + " move partitions p2 to ",
                true, 1, 10, "p2", 0, 0, 0, 0),
            new PartitionRuleInfo(PartitionStrategy.KEY,
                2,
                PARTITION_BY_INT_KEY, "alter table " + tableName + " move partitions p2 to ",
                true, 2, 10, "p2", 0, 0, 0, 0),
            new PartitionRuleInfo(PartitionStrategy.KEY,
                2,
                PARTITION_BY_INT_BIGINT_KEY, "alter table " + tableName + " move partitions p2 to ",
                true, 3, 10, "p2", 0, 0, 0, 0),
            new PartitionRuleInfo(PartitionStrategy.HASH,
                2,
                PARTITION_BY_INT_BIGINT_HASH, "alter table " + tableName + " move partitions p2 to ",
                true, 3, 10, "p2", 0, 0, 0, 0),
            new PartitionRuleInfo(PartitionStrategy.HASH,
                3,
                PARTITION_BY_MONTH_HASH, "alter table " + tableName + " move partitions p2 to ",
                true, 4, 10, "p2", 0, 0, 0, 0),
            new PartitionRuleInfo(PartitionStrategy.RANGE,
                1,
                PARTITION_BY_BIGINT_RANGE, "alter table " + tableName + " move partitions p2 to ",
                true, 5, 10, "p2", 100040, 100080, 0, 0),
            new PartitionRuleInfo(PartitionStrategy.RANGE_COLUMNS,
                2,
                PARTITION_BY_INT_BIGINT_RANGE_COL, "alter table " + tableName + " move partitions p2 to ",
                true, 6, 10, "p2", 10, 20, 100040, 100080),
            new PartitionRuleInfo(PartitionStrategy.LIST,
                1,
                PARTITION_BY_BIGINT_LIST, "alter table " + tableName + " move partitions p2 to ",
                true, 7, 10, "p2", 100010, 100020, 0, 0),
            new PartitionRuleInfo(PartitionStrategy.LIST_COLUMNS,
                4,
                PARTITION_BY_INT_BIGINT_LIST, "alter table " + tableName + " move partitions p2 to ",
                true, 6, 10, "p2", 1, 1, 100010, 100020),
            new PartitionRuleInfo(PartitionStrategy.KEY,
                1,
                SINGLE_TABLE, "alter table " + tableName + " move partitions p1 to ",
                true, 1, 10, "p1", 0, 0, 0, 0))
    );

    private static PartitionRuleInfo partitionRuleInfo;
    private static boolean firstIn = true;
    final static String logicalDatabase = "AlterTableMovePartitionTest";

    public AlterTableMovePartitionTest(PartitionRuleInfo curPartitionRuleInfo) {
        super(logicalDatabase, ImmutableList.of(curPartitionRuleInfo.getTableStatus().name()));
        if (this.partitionRuleInfo == null
            || !(curPartitionRuleInfo.getTableStatus() == partitionRuleInfo.getTableStatus()
            && curPartitionRuleInfo.getStrategy() == partitionRuleInfo.getStrategy() && curPartitionRuleInfo
            .getPartitionRule().equalsIgnoreCase(partitionRuleInfo.getPartitionRule()))) {
            this.partitionRuleInfo = curPartitionRuleInfo;
            firstIn = true;
        }

    }

    @Test
    public void testInsert() {
        if (!usingNewPartDb()) {
            return;
        }
        boolean isPublic = partitionRuleInfo.getTableStatus() == ComplexTaskMetaManager.ComplexTaskStatus.PUBLIC;
        boolean isDeleteOnly =
            partitionRuleInfo.getTableStatus() == ComplexTaskMetaManager.ComplexTaskStatus.DELETE_ONLY;
        boolean isReadyToPublic =
            partitionRuleInfo.getTableStatus() == ComplexTaskMetaManager.ComplexTaskStatus.READY_TO_PUBLIC;

        String sql = partitionRuleInfo.getDeleteClause();
        executeDml(sql, tddlConnection);
        sql = partitionRuleInfo.getInsertClause(false, false);
        sql = "trace " + sql;
        executeDml(sql, tddlConnection);
        List<List<String>> trace = getTrace(tddlConnection);

        int insertTrace = getTraceCount(trace, "insert");
        int expectTrace = 1 + 1;
        if (isPublic) {
            expectTrace = 1;
        } else if (isDeleteOnly) {
            expectTrace = 1;
        }

        Assert.assertThat(trace.toString(), insertTrace, is(expectTrace));

        //insert ignore, result ignore if id is sharding key
        sql = partitionRuleInfo.getInsertClause(true, false);
        sql = "trace " + sql;
        executeDml(sql, tddlConnection);

        sql = partitionRuleInfo.getDeleteClause();
        executeDml(sql, tddlConnection);
        //insert ignore, result insert
        sql = partitionRuleInfo.getInsertClause(true, false);
        sql = "trace " + sql;
        executeDml(sql, tddlConnection);
        trace = getTrace(tddlConnection);
        insertTrace = getTraceCount(trace, "insert");

        expectTrace = 1 + 1;
        if (isDeleteOnly) {
            expectTrace = 1;
        } else if (isPublic) {
            expectTrace = 1;
        }
        Assert.assertThat(trace.toString(), insertTrace,
            is(expectTrace));

        //replace into
        sql = partitionRuleInfo.getInsertClause(false, true);
        sql = "trace " + sql;
        executeDml(sql, tddlConnection);
        trace = getTrace(tddlConnection);

        int deleteTrace = getTraceCount(trace, "delete");
        insertTrace = getTraceCount(trace, "insert");
        int replaceTrace = getTraceCount(trace, "replace");
        expectTrace = 1 + 1 + 1 + 1;
        if (isDeleteOnly) {
            expectTrace = 1 + 1 + 1;
        } else if (isReadyToPublic) {
            if (replaceTrace != 0) {
                expectTrace = 1 + 1;
            } else {
                expectTrace = 1 + 1 + 1 + 1;
            }
        } else if (isPublic) {
            if (replaceTrace != 0) {
                expectTrace = 1;
            } else {
                expectTrace = 1 + 1;
            }
        }

        Assert.assertThat(trace.toString(), deleteTrace + insertTrace + replaceTrace,
            is(expectTrace));

        sql = partitionRuleInfo.getDeleteClause(ImmutableList.of(partitionRuleInfo.getPartVals().get(1)), true);
        executeDml(sql, tddlConnection);
        //insert..on..duplicate key update, result update
        sql = partitionRuleInfo.getInsertOnDuplicateKeyUpdate(partitionRuleInfo.getPartVals().get(0),
            partitionRuleInfo.getPartVals().get(1));
        sql = "trace " + sql;
        executeDml(sql, tddlConnection);
        trace = getTrace(tddlConnection);

        deleteTrace = getTraceCount(trace, "delete");
        insertTrace = getTraceCount(trace, "insert");
        int updateTrace = getTraceCount(trace, "update");
//delete + insert
        expectTrace = 1 + 1 + 1 + 1;
        if (isDeleteOnly) {
            expectTrace = 1 + 1 + 1;
        } else if (isPublic) {
            expectTrace = 1 + 1;
        }
        Assert.assertThat(trace.toString(), deleteTrace + insertTrace + updateTrace, is(expectTrace));

    }

    @Test
    public void testDelete() {
        if (!usingNewPartDb()) {
            return;
        }
        boolean isPublic = partitionRuleInfo.getTableStatus() == ComplexTaskMetaManager.ComplexTaskStatus.PUBLIC;

        String sql = partitionRuleInfo.getInsertClause(true, false);
        sql = "trace " + sql;
        executeDml(sql, tddlConnection);
        List<List<String>> res = partitionRuleInfo.getPartValFromPart(partitionRuleInfo.targetPart, "");
        sql = partitionRuleInfo.getDeleteClause(res, false);
        sql = "trace " + sql;

        executeDml(sql, tddlConnection);
        List<List<String>> trace = getTrace(tddlConnection);
        int expectTrace = 1 + 1;
        if (isPublic) {
            expectTrace = 1;
        }
        int deleteTrace = getTraceCount(trace, "delete");
        Assert.assertThat(trace.toString(), deleteTrace, is(expectTrace));

    }

    @Test
    public void testUpdate() {
        if (!usingNewPartDb()) {
            return;
        }
        boolean isPublic = partitionRuleInfo.getTableStatus() == ComplexTaskMetaManager.ComplexTaskStatus.PUBLIC;
        boolean isDeleteOnly =
            partitionRuleInfo.getTableStatus() == ComplexTaskMetaManager.ComplexTaskStatus.DELETE_ONLY;

        //update sharding key
        String sql = partitionRuleInfo.getDeleteClause(ImmutableList.of(partitionRuleInfo.getPartVals().get(0)), true);
        executeDml(sql, tddlConnection);
        sql = partitionRuleInfo.getInsertClause(true, false);
        executeDml(sql, tddlConnection);
        sql = partitionRuleInfo.getDeleteClause(ImmutableList.of(partitionRuleInfo.getPartVals().get(1)), true);
        executeDml(sql, tddlConnection);
        sql = partitionRuleInfo.getUpdateClause(partitionRuleInfo.getPartVals().get(0),
            partitionRuleInfo.getPartVals().get(1));
        sql = "trace " + sql;
        executeDml(sql, tddlConnection);
        List<List<String>> trace = getTrace(tddlConnection);
        int expectTrace = 1 * 2 + 1 * 2;
        if (isPublic) {
            expectTrace = 2;
        } else if (isDeleteOnly) {
            expectTrace = 1 * 2 + 1;
        }
        int deleteTrace = getTraceCount(trace, "delete");
        int insertTrace = getTraceCount(trace, "insert");
        int updateTrace = getTraceCount(trace, "update");
        if (partitionRuleInfo.tableStatus == ComplexTaskMetaManager.ComplexTaskStatus.PUBLIC
            && partitionRuleInfo.getPartitionRule().equalsIgnoreCase(SINGLE_TABLE)) {
            Assert.assertThat(trace.toString(), updateTrace, is(1));
            return;
        } else {
            Assert.assertThat(trace.toString(), updateTrace, is(0));
            Assert.assertThat(trace.toString(), deleteTrace + insertTrace, is(expectTrace));
        }
        if (!partitionRuleInfo.isPkIsPartCol()) {
            //update pk
            sql = partitionRuleInfo.getUpdatePkClause(partitionRuleInfo.getPartVals().get(1));
            sql = "trace " + sql;
            executeDml(sql, tddlConnection);
            trace = getTrace(tddlConnection);
            deleteTrace = getTraceCount(trace, "delete");
            insertTrace = getTraceCount(trace, "insert");
            updateTrace = getTraceCount(trace, "update");
            expectTrace = 1 * 2 + 1 * 2;
            if (isDeleteOnly) {
                expectTrace = 1 * 2 + 1;
            }
            if (!isPublic) {
                Assert.assertThat(trace.toString(), updateTrace, is(0));
                Assert.assertThat(trace.toString(), deleteTrace + insertTrace, is(expectTrace));
            } else {
                final List<Pair<String, String>> topology = JdbcUtil.getTopology(tddlConnection, tableName);
                Assert.assertThat(trace.toString(), updateTrace, is(topology.size()));
                Assert.assertThat(trace.toString(), deleteTrace + insertTrace, is(0));
            }
        }
    }

    @Parameterized.Parameters(name = "{index}:partitionRuleInfo={0}")
    public static List<PartitionRuleInfo[]> prepareData() {
        List<PartitionRuleInfo[]> status = new ArrayList<>();
        tableStatus.stream().forEach(c -> {
            partitionRuleInfos.stream().forEach(o -> {
                PartitionRuleInfo pi =
                    new PartitionRuleInfo(o.strategy, o.initDataType, o.partitionRule, o.alterCommand,
                        o.needGenDml, o.dmlType, o.rowCount, o.targetPart, o.minVal1, o.maxVal1, o.minVal2, o.maxVal2);
                pi.setTableStatus(c);
                status.add(new PartitionRuleInfo[] {pi});
            });
            if (c == ComplexTaskMetaManager.ComplexTaskStatus.PUBLIC) {
                partitionRuleInfos.forEach(o -> {
                    PartitionRuleInfo pi =
                        new PartitionRuleInfo(o.strategy, o.initDataType, o.partitionRule, o.alterCommand,
                            o.needGenDml, o.dmlType, o.rowCount, o.targetPart, o.minVal1, o.maxVal1, o.minVal2,
                            o.maxVal2);
                    pi.setTableStatus(c);
                    pi.setUsePhysicalTableBackfill(true);
                    status.add(new PartitionRuleInfo[] {pi});
                });
            }
        });
        return status;
    }

    @Before
    public void beforeDDLBaseNewDBTestCase() {
        if (firstIn) {
            super.beforeDDLBaseNewDBTestCase();
            setUpForMovePart(true, partitionRuleInfo);
            firstIn = false;
        }
        partitionRuleInfo.connection = getTddlConnection1();
        String sql = "use " + logicalDatabase;
        JdbcUtil.executeUpdateSuccess(tddlConnection, sql);
    }
}
