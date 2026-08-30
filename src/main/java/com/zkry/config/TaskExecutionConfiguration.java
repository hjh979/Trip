package com.zkry.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Transport-independent task execution infrastructure.
 *
 * <p>Local MySQL dispatch and the optional Rabbit adapter share the same virtual-thread worker
 * pool and lease/progress schedulers. Keeping these beans outside the Rabbit configuration lets
 * the application run with no broker on the classpath runtime path.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class TaskExecutionConfiguration {

    @Bean(destroyMethod = "close")
    public ExecutorService taskVirtualThreadExecutor() {
        return Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("trip-task-vt-", 0).factory()
        );
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService taskLeaseHeartbeatExecutor() {
        return Executors.newScheduledThreadPool(
            1,
            Thread.ofPlatform().name("trip-task-heartbeat-", 0).factory()
        );
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService planningProgressHeartbeatExecutor() {
        return Executors.newScheduledThreadPool(
            1,
            Thread.ofPlatform().name("planning-progress-", 0).factory()
        );
    }

    /**
     * Keep scheduled polling and recovery away from task lease heartbeats.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("trip-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
