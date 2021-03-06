/**
 * Copyright (C) 2015 Zalando SE (http://tech.zalando.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zalando.spring.boot.scheduling;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
public class CustomSchedulingConfiguration implements SchedulingConfigurer, DisposableBean {

    private final Logger log = LoggerFactory.getLogger(CustomSchedulingConfiguration.class);

    @Autowired
    private SchedulingProperties properties;

    @Autowired(required = false)
    @Qualifier(ScheduledAnnotationBeanPostProcessor.DEFAULT_TASK_SCHEDULER_BEAN_NAME)
    private ConcurrentTaskScheduler taskScheduler;

    private ScheduledExecutorService localExecutor;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        if (properties.isEnabled()) {
            if (taskScheduler != null) {
                taskRegistrar.setScheduler(taskScheduler);
            } else {
                throw new BeanCreationException("Expecting a 'ConcurrentTaskScheduler' injected, but was 'null'");
            }
        } else {
            log.info("'CustomSchedulingConfiguration' is disabled, create a default - 'ConcurrentTaskScheduler'");
            this.localExecutor = Executors.newSingleThreadScheduledExecutor();
            taskRegistrar.setScheduler(new ConcurrentTaskScheduler(localExecutor));
        }
    }

    @Override
    public void destroy() throws Exception {
        if (this.localExecutor != null) {
            this.localExecutor.shutdownNow();
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "scheduling.executor", name = "enabled", matchIfMissing = true)
    public static class DefaultSchedulingConfiguration {

        private final Logger log = LoggerFactory.getLogger(DefaultSchedulingConfiguration.class);

        @Autowired(required = false)
        @Qualifier("taskExecutor")
        private TaskExecutor executor;

        @Autowired
        private SchedulingProperties properties;

        @Bean(destroyMethod = "shutdown")
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        public ScheduledExecutorService scheduledExecutorService() {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(properties.getCorePoolSize());

            executor.setMaximumPoolSize(properties.getMaxPoolSize());

            executor.setThreadFactory(new CustomizableThreadFactory(properties.getThreadNamePrefix()));

            executor.setRejectedExecutionHandler(getConfiguredRejectedExecutionHandler());
            return executor;
        }

        private RejectedExecutionHandler getConfiguredRejectedExecutionHandler() {
            try {
                return (RejectedExecutionHandler) Class.forName(properties.getRejectedExecutionHandler()).newInstance();
            } catch (InstantiationException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        @Bean(name = ScheduledAnnotationBeanPostProcessor.DEFAULT_TASK_SCHEDULER_BEAN_NAME)
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        public TaskScheduler taskScheduler() {
            if (executor != null) {
                log.debug("create task-scheduler with pre-configured executor ...");
                return new ConcurrentTaskScheduler(executor, scheduledExecutorService());
            } else {
                log.debug("create task-scheduler without pre-configured executor ...");
                return new ConcurrentTaskScheduler(scheduledExecutorService());
            }
        }
    }
}
