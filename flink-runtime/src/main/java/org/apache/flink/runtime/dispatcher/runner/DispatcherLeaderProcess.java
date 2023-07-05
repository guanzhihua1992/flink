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

import org.apache.flink.runtime.clusterframework.ApplicationStatus;
import org.apache.flink.runtime.dispatcher.Dispatcher;
import org.apache.flink.runtime.dispatcher.DispatcherGateway;
import org.apache.flink.util.AutoCloseableAsync;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Leader process which encapsulates the lifecycle of the {@link Dispatcher} component.
 * 负责管理Dispatcher生命周期，同时提 供了对JobGraph的任务恢复管理功能。
 * 如果基于ZooKeeper实现了集群 高可用，DispatcherLeader-Process会将提交的JobGraph存储在ZooKeeper 中，
 * 当集群停止或者出现异常时，就会通过DispatcherLeaderProcess对集群中的JobGraph进行恢复，
 * 这些JobGraph都会被存储在JobGraphStore的 实现类中。
 * SessionDispatcherLeaderProcess用于对多个 JobGraph进行恢复和提交，
 * 在高可用集群下通过JobGraphStore中存储 JobGraph进行存储及恢复，
 * 当集群重新启动后会将JobGraphStore中存 储的JobGraph恢复并创建相应的任务。
 * JobDispatcherLeaderProcess 用于单个JobGraph的恢复和提交，处理逻辑比较简单 */
interface DispatcherLeaderProcess extends AutoCloseableAsync {
    //用于启动 DispatcherLeaderProcess服务 同时提供了获取DispatcherGateway、 ShutDownFuture的方法。
    void start();

    UUID getLeaderSessionId();

    CompletableFuture<DispatcherGateway> getDispatcherGateway();

    CompletableFuture<String> getLeaderAddressFuture();

    CompletableFuture<ApplicationStatus> getShutDownFuture();
}
