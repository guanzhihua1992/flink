/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.dispatcher.runner;

import org.apache.flink.runtime.dispatcher.DispatcherId;
import org.apache.flink.runtime.highavailability.JobResultStore;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobmanager.ThrowingJobGraphWriter;
import org.apache.flink.runtime.jobmaster.JobResult;
import org.apache.flink.runtime.rpc.FatalErrorHandler;
import org.apache.flink.util.CollectionUtil;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.util.UUID;

/** {@link DispatcherLeaderProcess} implementation for the per-job mode.
 * JobDispatcherLeaderProcess 用于单个JobGraph的恢复和提交，处理逻辑比较简单* */
public class JobDispatcherLeaderProcess extends AbstractDispatcherLeaderProcess {

    private final DispatcherGatewayServiceFactory dispatcherGatewayServiceFactory;

    //JobGraph  JobResult 都只有一个
    @Nullable private final JobGraph jobGraph;
    @Nullable private final JobResult recoveredDirtyJobResult;
    //还有一个JobResultStore？
    private final JobResultStore jobResultStore;

    JobDispatcherLeaderProcess(
            UUID leaderSessionId,
            DispatcherGatewayServiceFactory dispatcherGatewayServiceFactory,
            @Nullable JobGraph jobGraph,
            @Nullable JobResult recoveredDirtyJobResult,
            JobResultStore jobResultStore,
            FatalErrorHandler fatalErrorHandler) {
        super(leaderSessionId, fatalErrorHandler);
        this.dispatcherGatewayServiceFactory = dispatcherGatewayServiceFactory;
        this.jobGraph = jobGraph;
        this.recoveredDirtyJobResult = recoveredDirtyJobResult;
        this.jobResultStore = Preconditions.checkNotNull(jobResultStore);
    }
    //
    @Override
    protected void onStart() {
        // 通过dispatcherGatewayServiceFactory创建DispatcherGatewayService
        final DispatcherGatewayService dispatcherService =
                dispatcherGatewayServiceFactory.create(
                        DispatcherId.fromUuid(getLeaderSessionId()),
                        CollectionUtil.ofNullable(jobGraph),
                        CollectionUtil.ofNullable(recoveredDirtyJobResult),
                        ThrowingJobGraphWriter.INSTANCE,
                        jobResultStore);
        //完成设定
        completeDispatcherSetup(dispatcherService);
    }
}
