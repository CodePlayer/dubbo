/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.registry.client.migration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.status.reporter.FrameworkStatusReporter;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.client.migration.model.MigrationRule;
import org.apache.dubbo.registry.client.migration.model.MigrationStep;
import org.apache.dubbo.registry.integration.DynamicDirectory;
import org.apache.dubbo.registry.integration.RegistryProtocol;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ConsumerModel;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.status.reporter.FrameworkStatusReporter.createConsumptionReport;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

public class MigrationInvoker<T> implements MigrationClusterInvoker<T> {
    private Logger logger = LoggerFactory.getLogger(MigrationInvoker.class);
    private static final String MIGRATION_DELAY_KEY = "dubbo.application.migration.delay";

    private URL url;
    private URL consumerUrl;
    private Cluster cluster;
    private Registry registry;
    private Class<T> type;
    private RegistryProtocol registryProtocol;

    private volatile ClusterInvoker<T> invoker;
    private volatile ClusterInvoker<T> serviceDiscoveryInvoker;
    private volatile ClusterInvoker<T> currentAvailableInvoker;
    private volatile MigrationStep step;
    private volatile MigrationRule rule;
    private volatile boolean migrated;

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public MigrationInvoker(RegistryProtocol registryProtocol,
                            Cluster cluster,
                            Registry registry,
                            Class<T> type,
                            URL url,
                            URL consumerUrl) {
        this(null, null, registryProtocol, cluster, registry, type, url, consumerUrl);
    }

    public MigrationInvoker(ClusterInvoker<T> invoker,
                            ClusterInvoker<T> serviceDiscoveryInvoker,
                            RegistryProtocol registryProtocol,
                            Cluster cluster,
                            Registry registry,
                            Class<T> type,
                            URL url,
                            URL consumerUrl) {
        this.invoker = invoker;
        this.serviceDiscoveryInvoker = serviceDiscoveryInvoker;
        this.registryProtocol = registryProtocol;
        this.cluster = cluster;
        this.registry = registry;
        this.type = type;
        this.url = url;
        this.consumerUrl = consumerUrl;

        ConsumerModel consumerModel = ApplicationModel.getConsumerModel(consumerUrl.getServiceKey());
        if (consumerModel != null) {
            consumerModel.getServiceMetadata().addAttribute("currentClusterInvoker", this);
        }
    }

    public ClusterInvoker<T> getInvoker() {
        return invoker;
    }

    public void setInvoker(ClusterInvoker<T> invoker) {
        this.invoker = invoker;
    }

    public ClusterInvoker<T> getServiceDiscoveryInvoker() {
        return serviceDiscoveryInvoker;
    }

    public void setServiceDiscoveryInvoker(ClusterInvoker<T> serviceDiscoveryInvoker) {
        this.serviceDiscoveryInvoker = serviceDiscoveryInvoker;
    }

    @Override
    public Class<T> getInterface() {
        return type;
    }

    @Override
    public void reRefer(URL newSubscribeUrl) {
        // update url to prepare for migration refresh
        this.url = url.addParameter(REFER_KEY, StringUtils.toQueryString(newSubscribeUrl.getParameters()));

        // re-subscribe immediately
        if (invoker != null && !invoker.isDestroyed()) {
            doReSubscribe(invoker, newSubscribeUrl);
        }
        if (serviceDiscoveryInvoker != null && !serviceDiscoveryInvoker.isDestroyed()) {
            doReSubscribe(serviceDiscoveryInvoker, newSubscribeUrl);
        }
    }

    private void doReSubscribe(ClusterInvoker<T> invoker, URL newSubscribeUrl) {
        DynamicDirectory<T> directory = (DynamicDirectory<T>) invoker.getDirectory();
        URL oldSubscribeUrl = directory.getRegisteredConsumerUrl();
        Registry registry = directory.getRegistry();
        registry.unregister(directory.getRegisteredConsumerUrl());
        directory.unSubscribe(RegistryProtocol.toSubscribeUrl(oldSubscribeUrl));
        registry.register(directory.getRegisteredConsumerUrl());

        directory.setRegisteredConsumerUrl(newSubscribeUrl);
        directory.buildRouterChain(newSubscribeUrl);
        directory.subscribe(RegistryProtocol.toSubscribeUrl(newSubscribeUrl));
    }

    @Override
    public void fallbackToInterfaceInvoker() {
        migrated = false;
        refreshInterfaceInvoker();
        setListener(invoker, () -> {
            if (!migrated) {
                migrated = true;
                this.destroyServiceDiscoveryInvoker();
                FrameworkStatusReporter.reportConsumptionStatus(
                        createConsumptionReport(consumerUrl.getServiceInterface(), consumerUrl.getVersion(), consumerUrl.getGroup(), "interface")
                );
            }
        });
    }

    @Override
    public void migrateToServiceDiscoveryInvoker(boolean forceMigrate) {
        if (!checkMigratingConditionMatch(consumerUrl)) {
            fallbackToInterfaceInvoker();
            return;
        }

        migrated = false;
        if (!forceMigrate) {
            refreshServiceDiscoveryInvoker();
            refreshInterfaceInvoker();
            // By the time the task gets scheduled, the address notifications are expected to be finished for both address
            // types. Otherwise, migration task will just pick interface invoker.
            if (!migrated) {
                scheduler.schedule(new MigrationTask(), getDelay(), TimeUnit.MILLISECONDS);
                setListener(invoker, () -> {
                    this.setAvailableInvoker(serviceDiscoveryInvoker, invoker);
                });
                setListener(serviceDiscoveryInvoker, () -> {
                    this.setAvailableInvoker(serviceDiscoveryInvoker, invoker);
                });
            }
        } else {
            refreshServiceDiscoveryInvoker();
            setListener(serviceDiscoveryInvoker, () -> {
                if (!migrated) {
                    migrated = true;
                    this.destroyInterfaceInvoker();
                    FrameworkStatusReporter.reportConsumptionStatus(
                            createConsumptionReport(consumerUrl.getServiceInterface(), consumerUrl.getVersion(), consumerUrl.getGroup(), "app")
                    );
                }
            });
        }
    }

    private int getDelay() {
        int delay = 60000;
        String delayStr = ConfigurationUtils.getProperty(MIGRATION_DELAY_KEY);
        if (StringUtils.isEmpty(delayStr)) {
            return delay;
        }

        try {
           delay = Integer.parseInt(delayStr);
        } catch (Exception e) {
            logger.warn("Invalid migration delay param " + delayStr);
        }
        return delay;
    }

    private class MigrationTask implements Runnable {
        @Override
        public void run() {
            if (migrated) {
                return;
            }

            Set<MigrationAddressComparator> detectors = ExtensionLoader.getExtensionLoader(MigrationAddressComparator.class).getSupportedExtensionInstances();
            if (CollectionUtils.isEmpty(detectors)) {
                migrated = true;
                destroyInterfaceInvoker();
                return;
            }

            if (detectors.stream().allMatch(comparator -> comparator.shouldMigrate(serviceDiscoveryInvoker, invoker, rule))) {
                destroyInterfaceInvoker();
                FrameworkStatusReporter.reportConsumptionStatus(
                        createConsumptionReport(consumerUrl.getServiceInterface(), consumerUrl.getVersion(), consumerUrl.getGroup(), "app_app")
                );
            } else {
                // by default
                destroyServiceDiscoveryInvoker();
                FrameworkStatusReporter.reportConsumptionStatus(
                        createConsumptionReport(consumerUrl.getServiceInterface(), consumerUrl.getVersion(), consumerUrl.getGroup(), "app_interface")
                );
            }
            migrated = true;
        }
    }

    private boolean checkMigratingConditionMatch(URL consumerUrl) {
        Set<PreMigratingConditionChecker> checkers = ExtensionLoader.getExtensionLoader(PreMigratingConditionChecker.class).getSupportedExtensionInstances();
        if (CollectionUtils.isNotEmpty(checkers)) {
            PreMigratingConditionChecker checker = checkers.iterator().next();
            return checker.checkCondition(consumerUrl);
        }
        return true;
    }

    @Override
    public void refreshServiceDiscoveryInvokerOnMappingCallback(boolean forceMigrate) {
        if (this.serviceDiscoveryInvoker != null) {
            DynamicDirectory dynamicDirectory = (DynamicDirectory) this.serviceDiscoveryInvoker.getDirectory();
            dynamicDirectory.subscribe(dynamicDirectory.getSubscribeUrl());
        } else {
            migrateToServiceDiscoveryInvoker(forceMigrate);
        }
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        if (currentAvailableInvoker != null) {
            return currentAvailableInvoker.invoke(invocation);
        }

        switch (step) {
            case APPLICATION_FIRST:
                // FIXME, check ClusterInvoker.hasProxyInvokers() or ClusterInvoker.isAvailable()
                if (checkInvokerAvailable(invoker)) {
                    currentAvailableInvoker = invoker;
                } else {
                    currentAvailableInvoker = serviceDiscoveryInvoker;
                }
                break;
            case FORCE_APPLICATION:
                currentAvailableInvoker = serviceDiscoveryInvoker;
                break;
            case INTERFACE_FIRST:
            default:
                currentAvailableInvoker = invoker;
        }

        return currentAvailableInvoker.invoke(invocation);
    }

    @Override
    public boolean isAvailable() {
        return currentAvailableInvoker != null
                ? currentAvailableInvoker.isAvailable()
                : (invoker != null && invoker.isAvailable()) || (serviceDiscoveryInvoker != null && serviceDiscoveryInvoker.isAvailable());
    }

    @Override
    public void destroy() {
        if (invoker != null) {
            invoker.destroy();
        }
        if (serviceDiscoveryInvoker != null) {
            serviceDiscoveryInvoker.destroy();
        }
        ConsumerModel consumerModel = ApplicationModel.getConsumerModel(consumerUrl.getServiceKey());
        if (consumerModel != null) {
            consumerModel.getServiceMetadata().getAttributeMap().remove("currentClusterInvoker");
        }
    }

    @Override
    public URL getUrl() {
        if (currentAvailableInvoker != null) {
            return currentAvailableInvoker.getUrl();
        } else if (invoker != null) {
            return invoker.getUrl();
        } else if (serviceDiscoveryInvoker != null) {
            return serviceDiscoveryInvoker.getUrl();
        }

        return consumerUrl;
    }

    @Override
    public URL getRegistryUrl() {
        if (currentAvailableInvoker != null) {
            return currentAvailableInvoker.getRegistryUrl();
        } else if (invoker != null) {
            return invoker.getRegistryUrl();
        } else if (serviceDiscoveryInvoker != null) {
            return serviceDiscoveryInvoker.getRegistryUrl();
        }
        return url;
    }

    @Override
    public Directory<T> getDirectory() {
        if (currentAvailableInvoker != null) {
            return currentAvailableInvoker.getDirectory();
        } else if (invoker != null) {
            return invoker.getDirectory();
        } else if (serviceDiscoveryInvoker != null) {
            return serviceDiscoveryInvoker.getDirectory();
        }
        return null;
    }

    @Override
    public boolean isDestroyed() {
        return currentAvailableInvoker != null
                ? currentAvailableInvoker.isDestroyed()
                : (invoker == null || invoker.isDestroyed()) && (serviceDiscoveryInvoker == null || serviceDiscoveryInvoker.isDestroyed());
    }

    @Override
    public boolean isServiceDiscovery() {
        return false;
    }

    @Override
    public MigrationStep getMigrationStep() {
        return step;
    }

    @Override
    public void setMigrationStep(MigrationStep step) {
        this.step = step;
    }

    @Override
    public MigrationRule getMigrationRule() {
        return rule;
    }

    @Override
    public void setMigrationRule(MigrationRule rule) {
        this.rule = rule;
    }

    @Override
    public boolean invokersChanged() {
        return invokersChanged;
    }

    private volatile boolean invokersChanged;

    /**
     * Set the available invoker before migration is determined. Interface invoker goes first.
     * @param serviceDiscoveryInvoker
     * @param invoker
     */
    private void setAvailableInvoker(ClusterInvoker<T> serviceDiscoveryInvoker, ClusterInvoker<T> invoker) {
        this.invokersChanged = true;

        if (currentAvailableInvoker == null) {
            if (invoker != null && !invoker.isDestroyed() && invoker.hasProxyInvokers()) {
                currentAvailableInvoker = invoker;
            } else if (serviceDiscoveryInvoker != null && !serviceDiscoveryInvoker.isDestroyed() && serviceDiscoveryInvoker.hasProxyInvokers()) {
                currentAvailableInvoker = serviceDiscoveryInvoker;
            }
            if (currentAvailableInvoker == null) {
                currentAvailableInvoker = invoker;
            }
        }
    }

    protected void destroyServiceDiscoveryInvoker() {
        if (this.invoker != null) {
            this.currentAvailableInvoker = this.invoker;
        }
        if (serviceDiscoveryInvoker != null && !serviceDiscoveryInvoker.isDestroyed()) {
            if (logger.isInfoEnabled()) {
                logger.info("Destroying instance address invokers, will not listen for address changes until re-subscribed, " + type.getName());
            }
            serviceDiscoveryInvoker.destroy();
            serviceDiscoveryInvoker = null;
        }
    }

    protected void refreshServiceDiscoveryInvoker() {
        clearListener(serviceDiscoveryInvoker);
        if (needRefresh(serviceDiscoveryInvoker)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Re-subscribing instance addresses, current interface " + type.getName());
            }
            serviceDiscoveryInvoker = registryProtocol.getServiceDiscoveryInvoker(cluster, registry, type, url);
        }
    }

    protected void refreshInterfaceInvoker() {
        clearListener(invoker);
        // FIXME invoker.destroy();
        if (needRefresh(invoker)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Re-subscribing interface addresses for interface " + type.getName());
            }

            invoker = registryProtocol.getInvoker(cluster, registry, type, url);
        }
    }

    protected void destroyInterfaceInvoker() {
        if (this.serviceDiscoveryInvoker != null) {
            this.currentAvailableInvoker = this.serviceDiscoveryInvoker;
        }
        if (invoker != null && !invoker.isDestroyed()) {
            if (logger.isInfoEnabled()) {
                logger.info("Destroying interface address invokers, will not listen for address changes until re-subscribed, " + type.getName());
            }
            invoker.destroy();
            invoker = null;
        }
    }

    private void clearListener(ClusterInvoker<T> invoker) {
        if (invoker == null) return;
        DynamicDirectory<T> directory = (DynamicDirectory<T>) invoker.getDirectory();
        directory.setInvokersChangedListener(null);
    }

    private void setListener(ClusterInvoker<T> invoker, InvokersChangedListener listener) {
        if (invoker == null) return;
        DynamicDirectory<T> directory = (DynamicDirectory<T>) invoker.getDirectory();
        directory.setInvokersChangedListener(listener);
    }

    private boolean needRefresh(ClusterInvoker<T> invoker) {
        return invoker == null || invoker.isDestroyed() || !invoker.hasProxyInvokers();
    }

    public boolean checkInvokerAvailable(ClusterInvoker<T> invoker) {
        return invoker != null && !invoker.isDestroyed() && invoker.isAvailable();
    }
}