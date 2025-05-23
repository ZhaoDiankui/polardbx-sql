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

package com.alibaba.polardbx.optimizer.planmanager;

import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlNode;

import java.util.List;
import java.util.Map;

public interface BaselineManageable {

    void forceLoadAll();

    BaselineInfo createBaselineInfo(String parameterizedSql, SqlNode ast, ExecutionContext ec);

    PlanInfo createPlanInfo(String schema, String planJsonString, RelNode plan, int baselinInfoId, String traceId,
                            String origin, SqlNode ast,
                            ExecutionContext executionContext);

    BaselineInfo addBaselineInfo(String schema, String parameterSql, BaselineInfo baselineInfo);

    void updateBaseline(Map<String, List<String>> bMap);

    void deleteBaseline(String schema, String parameterSql);

    void deleteBaseline(String schema, Integer baselineId);

    void deleteBaseline(String schema, String parameterSql, int planInfoId);

    void deleteBaselineEvolved(String schema);

    void deleteBaselinePlan(String schema, Integer baselineId, Integer planInfoId);

    boolean checkBaselineHashCodeValid(BaselineInfo baselineInfo, PlanInfo planInfo);
}
