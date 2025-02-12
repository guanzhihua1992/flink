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

package org.apache.flink.runtime.resourcemanager;

import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.metrics.groups.SlotManagerMetricGroup;
import org.apache.flink.runtime.resourcemanager.slotmanager.DeclarativeSlotManager;
import org.apache.flink.runtime.resourcemanager.slotmanager.DefaultResourceAllocationStrategy;
import org.apache.flink.runtime.resourcemanager.slotmanager.DefaultResourceTracker;
import org.apache.flink.runtime.resourcemanager.slotmanager.DefaultSlotStatusSyncer;
import org.apache.flink.runtime.resourcemanager.slotmanager.DefaultSlotTracker;
import org.apache.flink.runtime.resourcemanager.slotmanager.FineGrainedSlotManager;
import org.apache.flink.runtime.resourcemanager.slotmanager.FineGrainedTaskManagerTracker;
import org.apache.flink.runtime.resourcemanager.slotmanager.SlotManager;
import org.apache.flink.runtime.resourcemanager.slotmanager.SlotManagerConfiguration;
import org.apache.flink.runtime.resourcemanager.slotmanager.SlotManagerUtils;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.concurrent.ScheduledExecutor;

/** Container class for the {@link ResourceManager} services. */
public class ResourceManagerRuntimeServices {

    private final SlotManager slotManager;
    private final JobLeaderIdService jobLeaderIdService;

    public ResourceManagerRuntimeServices(
            SlotManager slotManager, JobLeaderIdService jobLeaderIdService) {
        this.slotManager = Preconditions.checkNotNull(slotManager);
        this.jobLeaderIdService = Preconditions.checkNotNull(jobLeaderIdService);
    }

    public SlotManager getSlotManager() {
        return slotManager;
    }

    public JobLeaderIdService getJobLeaderIdService() {
        return jobLeaderIdService;
    }

    // -------------------- Static methods --------------------------------------

    public static ResourceManagerRuntimeServices fromConfiguration(
            ResourceManagerRuntimeServicesConfiguration configuration,
            HighAvailabilityServices highAvailabilityServices,
            ScheduledExecutor scheduledExecutor,
            SlotManagerMetricGroup slotManagerMetricGroup) {
        // 创建SlotManager服务
        final SlotManager slotManager =
                createSlotManager(configuration, scheduledExecutor, slotManagerMetricGroup);
        // 创建JobLeaderIdService服务
        final JobLeaderIdService jobLeaderIdService =
                new DefaultJobLeaderIdService(
                        highAvailabilityServices, scheduledExecutor, configuration.getJobTimeout());
        // 返回ResourceManagerRuntimeServices
        return new ResourceManagerRuntimeServices(slotManager, jobLeaderIdService);
    }

    private static SlotManager createSlotManager(
            ResourceManagerRuntimeServicesConfiguration configuration,
            ScheduledExecutor scheduledExecutor,
            SlotManagerMetricGroup slotManagerMetricGroup) {
        // 创建SlotManagerConfiguration
        final SlotManagerConfiguration slotManagerConfiguration =
                configuration.getSlotManagerConfiguration();
        //配置判断是否支持细粒度资源管理
        if (configuration.isEnableFineGrainedResourceManagement()) {
            //cluster.fine-grained-resource-management.enabled为true开启细粒度资源分配
            return new FineGrainedSlotManager(
                    scheduledExecutor,
                    slotManagerConfiguration,
                    slotManagerMetricGroup,
                    new DefaultResourceTracker(),
                    new FineGrainedTaskManagerTracker(),
                    new DefaultSlotStatusSyncer(
                            slotManagerConfiguration.getTaskManagerRequestTimeout()),
                    new DefaultResourceAllocationStrategy(
                            SlotManagerUtils.generateTaskManagerTotalResourceProfile(
                                    slotManagerConfiguration.getDefaultWorkerResourceSpec()),
                            slotManagerConfiguration.getNumSlotsPerWorker(),
                            slotManagerConfiguration.isEvenlySpreadOutSlots(),
                            slotManagerConfiguration.getTaskManagerTimeout(),
                            slotManagerConfiguration.getRedundantTaskManagerNum()));
        } else {
            return new DeclarativeSlotManager(
                    scheduledExecutor,
                    slotManagerConfiguration,
                    slotManagerMetricGroup,
                    new DefaultResourceTracker(),
                    new DefaultSlotTracker());
        }
    }
}
