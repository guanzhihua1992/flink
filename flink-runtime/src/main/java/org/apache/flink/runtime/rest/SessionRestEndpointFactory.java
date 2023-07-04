/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.rest;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.blob.TransientBlobService;
import org.apache.flink.runtime.dispatcher.DispatcherGateway;
import org.apache.flink.runtime.dispatcher.DispatcherRestEndpoint;
import org.apache.flink.runtime.leaderelection.LeaderElectionService;
import org.apache.flink.runtime.resourcemanager.ResourceManagerGateway;
import org.apache.flink.runtime.rest.handler.RestHandlerConfiguration;
import org.apache.flink.runtime.rest.handler.legacy.metrics.MetricFetcher;
import org.apache.flink.runtime.rpc.FatalErrorHandler;
import org.apache.flink.runtime.webmonitor.WebMonitorEndpoint;
import org.apache.flink.runtime.webmonitor.retriever.LeaderGatewayRetriever;

import java.util.concurrent.ScheduledExecutorService;

/** {@link RestEndpointFactory} which creates a {@link DispatcherRestEndpoint}. */
public enum SessionRestEndpointFactory implements RestEndpointFactory<DispatcherGateway> {
    INSTANCE;

    @Override
    public WebMonitorEndpoint<DispatcherGateway> createRestEndpoint(
            Configuration configuration,
            //DispatcherGateway服务地址获取器， 用于获取当前活跃的dispatcherGateway地址。
            // 基于dispatcherGateway可以 实现与Dispatcher的RPC通信，
            // 最终提交的JobGraph通过 dispatcherGateway发送给Dispatcher组件。
            LeaderGatewayRetriever<DispatcherGateway> dispatcherGatewayRetriever,
            //ResourceManagerGateway服务地 址获取器，
            // 用于获取当前活跃的ResourceManagerGateway地址，
            // 通过 ResourceManagerGateway实现ResourceManager组件之间的RPC通信，
            // 例如在TaskManagersHandler中通过调用ResourceManagerGateway获取集群中的TaskManagers监控信息。
            LeaderGatewayRetriever<ResourceManagerGateway> resourceManagerGatewayRetriever,
            //临时二进制对象数据存储服务，
            // BlobServer 接收数据后，会及时清理Cache中的对象数据。
            TransientBlobService transientBlobService,
            //用于处理WebMonitorEndpoint请求的线程池服务。
            ScheduledExecutorService executor,
            //用于拉取JobManager和TaskManager上的Metric监控指标。
            MetricFetcher metricFetcher,
            //用于在高可用集群中启动和选择服务的 Leader节点，
            // 如通过leaderElectionService启动WebMonitorEndpoint RPC服 务，
            // 然后将Leader节点注册至ZooKeeper，以此实现 WebMonitorEndpoint服务的高可用。
            LeaderElectionService leaderElectionService,
            //异常处理器，当WebMonitorEndpoint出现异常 时调用fatalError-Handler的中处理接口。
            FatalErrorHandler fatalErrorHandler)
            throws Exception {
        // 通过Configuration获取RestHandlerConfiguration
        final RestHandlerConfiguration restHandlerConfiguration =
                RestHandlerConfiguration.fromConfiguration(configuration);
        //创建DispatcherRestEndpoint
        return new DispatcherRestEndpoint(
                dispatcherGatewayRetriever,
                configuration,
                restHandlerConfiguration,
                resourceManagerGatewayRetriever,
                transientBlobService,
                executor,
                metricFetcher,
                leaderElectionService,
                RestEndpointFactory.createExecutionGraphCache(restHandlerConfiguration),
                fatalErrorHandler);
    }
}
