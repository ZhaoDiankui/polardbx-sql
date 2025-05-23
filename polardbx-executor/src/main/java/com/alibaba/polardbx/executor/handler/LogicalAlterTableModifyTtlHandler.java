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

package com.alibaba.polardbx.executor.handler;

import com.alibaba.polardbx.common.ddl.newengine.DdlState;
import com.alibaba.polardbx.executor.ddl.job.factory.ttl.AlterTableModifyTtlJobFactory;
import com.alibaba.polardbx.executor.ddl.newengine.job.DdlJob;
import com.alibaba.polardbx.executor.handler.ddl.LogicalCommonDdlHandler;
import com.alibaba.polardbx.executor.spi.IRepository;
import com.alibaba.polardbx.executor.utils.PolarPrivilegeUtils;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.core.rel.ddl.BaseDdlOperation;
import com.taobao.tddl.common.privilege.PrivilegePoint;
import org.apache.calcite.sql.SqlAlterTable;

/**
 * @author chenghui.lch
 */
public class LogicalAlterTableModifyTtlHandler extends LogicalCommonDdlHandler {

    public LogicalAlterTableModifyTtlHandler(IRepository repo) {
        super(repo);
    }

    @Override
    public DdlJob buildDdlJob(BaseDdlOperation logicalDdlPlan, ExecutionContext executionContext) {

        final String schemaName = logicalDdlPlan.getSchemaName();
        final String primaryTableName = logicalDdlPlan.getTableName();

        PolarPrivilegeUtils.checkPrivilege(schemaName, primaryTableName, PrivilegePoint.ALTER, executionContext);
        PolarPrivilegeUtils.checkPrivilege(schemaName, primaryTableName, PrivilegePoint.DELETE, executionContext);

        executionContext.getDdlContext().setPausedPolicy(DdlState.PAUSED);

        SqlAlterTable sqlAlterTable = (SqlAlterTable) logicalDdlPlan.getNativeSqlNode();

        AlterTableModifyTtlJobFactory jobFactory =
            new AlterTableModifyTtlJobFactory(schemaName, primaryTableName,
                logicalDdlPlan.relDdl,
                sqlAlterTable,
                executionContext);
        return jobFactory.create();

    }
}