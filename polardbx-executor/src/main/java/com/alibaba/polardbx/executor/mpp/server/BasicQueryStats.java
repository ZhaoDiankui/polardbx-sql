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

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.executor.mpp.server;

import com.alibaba.polardbx.executor.mpp.execution.QueryStats;
import com.alibaba.polardbx.executor.mpp.operator.BlockedReason;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableSet;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.joda.time.DateTime;

import javax.annotation.concurrent.Immutable;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Lightweight version of QueryStats. Parts of the web UI depend on the fields
 * being named consistently across these classes.
 */
@Immutable
public class BasicQueryStats {
    private final DateTime createTime;
    private final DateTime endTime;

    private final Duration elapsedTime;
    private final Duration executionTime;
    private final Duration totalPlanningTime;

    private final int totalPipelineExecs;
    private final int queuedPipelineExecs;
    private final int runningPipelineExecs;
    private final int completedPipelineExecs;

    private final double cumulativeMemory;
    private final DataSize totalMemoryReservation;
    private final DataSize peakMemoryReservation;
    private final Duration totalCpuTime;

    private final boolean fullyBlocked;
    private final Set<BlockedReason> blockedReasons;

    public BasicQueryStats(
        DateTime createTime,
        DateTime endTime,
        Duration elapsedTime,
        Duration executionTime,
        Duration totalPlanningTime,
        int totalPipelineExecs,
        int queuedPipelineExecs,
        int runningPipelineExecs,
        int completedPipelineExecs,
        double cumulativeMemory,
        DataSize totalMemoryReservation,
        DataSize peakMemoryReservation,
        Duration totalCpuTime,
        boolean fullyBlocked,
        Set<BlockedReason> blockedReasons) {
        this.createTime = createTime;
        this.endTime = endTime;

        this.elapsedTime = elapsedTime;
        this.executionTime = executionTime;
        this.totalPlanningTime = totalPlanningTime;

        checkArgument(totalPipelineExecs >= 0, "totalPipelineExecs is negative");
        this.totalPipelineExecs = totalPipelineExecs;
        checkArgument(queuedPipelineExecs >= 0, "queuedPipelineExecs is negative");
        this.queuedPipelineExecs = queuedPipelineExecs;
        checkArgument(runningPipelineExecs >= 0, "runningPipelineExecs is negative");
        this.runningPipelineExecs = runningPipelineExecs;
        checkArgument(completedPipelineExecs >= 0, "completedPipelineExecs is negative");
        this.completedPipelineExecs = completedPipelineExecs;

        this.cumulativeMemory = cumulativeMemory;
        this.totalMemoryReservation = totalMemoryReservation;
        this.peakMemoryReservation = peakMemoryReservation;
        this.totalCpuTime = totalCpuTime;

        this.fullyBlocked = fullyBlocked;
        this.blockedReasons = ImmutableSet.copyOf(requireNonNull(blockedReasons, "blockedReasons is null"));
    }

    public BasicQueryStats(QueryStats queryStats) {
        this(queryStats.getCreateTime(),
            queryStats.getEndTime(),
            queryStats.getElapsedTime(),
            queryStats.getExecutionTime(),
            queryStats.getTotalPlanningTime(),
            queryStats.getTotalPipelinExecs(),
            queryStats.getQueuedPipelinExecs(),
            queryStats.getRunningPipelinExecs(),
            queryStats.getCompletedPipelinExecs(),
            queryStats.getCumulativeMemory(),
            queryStats.getTotalMemoryReservation(),
            queryStats.getPeakMemoryReservation(),
            queryStats.getTotalCpuTime(),
            queryStats.isFullyBlocked(),
            queryStats.getBlockedReasons());
    }

    @JsonProperty
    public DateTime getCreateTime() {
        return createTime;
    }

    @JsonProperty
    public DateTime getEndTime() {
        return endTime;
    }

    @JsonProperty
    public Duration getElapsedTime() {
        return elapsedTime;
    }

    @JsonProperty
    public Duration getExecutionTime() {
        return executionTime;
    }

    @JsonProperty
    public int getTotalPipelineExecs() {
        return totalPipelineExecs;
    }

    @JsonProperty
    public int getQueuedPipelineExecs() {
        return queuedPipelineExecs;
    }

    @JsonProperty
    public int getRunningPipelineExecs() {
        return runningPipelineExecs;
    }

    @JsonProperty
    public int getCompletedPipelineExecs() {
        return completedPipelineExecs;
    }

    @JsonProperty
    public double getCumulativeMemory() {
        return cumulativeMemory;
    }

    @JsonProperty
    public DataSize getTotalMemoryReservation() {
        return totalMemoryReservation;
    }

    @JsonProperty
    public DataSize getPeakMemoryReservation() {
        return peakMemoryReservation;
    }

    @JsonProperty
    public Duration getTotalCpuTime() {
        return totalCpuTime;
    }

    @JsonProperty
    public boolean isFullyBlocked() {
        return fullyBlocked;
    }

    @JsonProperty
    public Set<BlockedReason> getBlockedReasons() {
        return blockedReasons;
    }

    @JsonProperty
    public Duration getTotalPlanningTime() {
        return totalPlanningTime;
    }
}
