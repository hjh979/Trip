package com.zkry.task;

import com.zkry.domain.entity.TripTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Durable single-instance task dispatcher backed only by MySQL.
 *
 * <p>The database task state is the queue. Polling selects due work, claim performs the CAS,
 * and virtual threads run blocking AI/map/RAG calls outside Tomcat and Netty event loops.
 * At most one low-priority knowledge job runs by default, leaving capacity for plans and
 * interactive modifications.</p>
 */
@Component
@ConditionalOnProperty(
    name = "tripstar.tasks.transport",
    havingValue = "local",
    matchIfMissing = true
)
public class LocalTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalTaskDispatcher.class);
    private static final int[] RETRY_DELAYS = {30, 120, 300};

    private final TripTaskStore taskStore;
    private final TaskExecutionEngine executionEngine;
    private final TaskFailureClassifier failureClassifier;
    private final TaskRealtimePublisher realtimePublisher;
    private final ExecutorService taskExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Semaphore taskSlots;
    private final Semaphore backgroundSlots;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final boolean dispatchEnabled;

    public LocalTaskDispatcher(
        TripTaskStore taskStore,
        TaskExecutionEngine executionEngine,
        TaskFailureClassifier failureClassifier,
        TaskRealtimePublisher realtimePublisher,
        @Qualifier("taskVirtualThreadExecutor") ExecutorService taskExecutor,
        @Qualifier("taskLeaseHeartbeatExecutor") ScheduledExecutorService heartbeatExecutor,
        @Value("${tripstar.tasks.local.max-concurrency:4}") int maxConcurrency,
        @Value("${tripstar.tasks.local.background-concurrency:1}") int backgroundConcurrency,
        @Value("${tripstar.tasks.local.dispatch-enabled:true}") boolean dispatchEnabled
    ) {
        this.taskStore = taskStore;
        this.executionEngine = executionEngine;
        this.failureClassifier = failureClassifier;
        this.realtimePublisher = realtimePublisher;
        this.taskExecutor = taskExecutor;
        this.heartbeatExecutor = heartbeatExecutor;
        int safeMax = Math.max(1, Math.min(maxConcurrency, 32));
        this.taskSlots = new Semaphore(safeMax);
        this.backgroundSlots = new Semaphore(
            Math.max(1, Math.min(backgroundConcurrency, safeMax))
        );
        this.dispatchEnabled = dispatchEnabled;
    }

    /** Compatibility constructor retained for focused unit tests and local tooling. */
    public LocalTaskDispatcher(
        TripTaskStore taskStore,
        TripPlanningWorkflow planningWorkflow,
        AiModificationWorkflow modificationWorkflow,
        KnowledgeIngestionWorkflow knowledgeWorkflow,
        TaskFailureClassifier failureClassifier,
        TaskRealtimePublisher realtimePublisher,
        ExecutorService taskExecutor,
        ScheduledExecutorService heartbeatExecutor,
        int maxConcurrency,
        int backgroundConcurrency,
        boolean dispatchEnabled
    ) {
        this(taskStore, new TaskExecutionEngine(planningWorkflow, modificationWorkflow, knowledgeWorkflow),
            failureClassifier, realtimePublisher, taskExecutor, heartbeatExecutor,
            maxConcurrency, backgroundConcurrency, dispatchEnabled);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announceLocalMode() {
        if (!dispatchEnabled) {
            log.warn("[LocalTaskDispatcher] 本地任务领取已暂停；数据库任务保持原状态");
            return;
        }
        log.info(
            "[LocalTaskDispatcher] 本地持久化任务调度已启用 maxConcurrency={} backgroundConcurrency={}",
            taskSlots.availablePermits(),
            backgroundSlots.availablePermits()
        );
        dispatchDueTasks();
    }

    @Scheduled(fixedDelayString = "${tripstar.tasks.local.poll-ms:500}")
    public void dispatchDueTasks() {
        if (!dispatchEnabled) {
            return;
        }
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            int capacity = taskSlots.availablePermits();
            if (capacity <= 0) {
                return;
            }
            List<TripTask> due = taskStore.dueTasks(Math.max(capacity * 4, 8));
            for (TripTask candidate : due) {
                if (!taskSlots.tryAcquire()) {
                    break;
                }
                boolean background = isBackground(candidate.getTaskType());
                if (background && !backgroundSlots.tryAcquire()) {
                    taskSlots.release();
                    continue;
                }
                String token = UUID.randomUUID().toString();
                Optional<TripTask> claimed = taskStore.claim(candidate.getTaskId(), token);
                if (claimed.isEmpty()) {
                    releaseSlots(background);
                    continue;
                }
                try {
                    taskExecutor.execute(() -> executeClaimed(claimed.get(), token, background));
                } catch (RejectedExecutionException ex) {
                    releaseSlots(background);
                    transitionFailure(claimed.get(), token, ex);
                }
            }
        } finally {
            polling.set(false);
        }
    }

    private void executeClaimed(TripTask task, String token, boolean background) {
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
            () -> taskStore.heartbeat(task.getTaskId(), token),
            30,
            30,
            TimeUnit.SECONDS
        );
        try {
            log.info("[LocalTaskDispatcher] 开始执行 taskId={} type={} attempt={}",
                task.getTaskId(), task.getTaskType(), task.getAttempt());
            executionEngine.execute(task, token);
        } catch (Throwable error) {
            log.error("[LocalTaskDispatcher] 任务执行失败 taskId={} type={} attempt={} reason={}",
                task.getTaskId(), task.getTaskType(), task.getAttempt(), rootMessage(error), error);
            try {
                transitionFailure(task, token, error);
            } catch (Throwable transitionError) {
                // The lease recovery job will redrive this PROCESSING row after its lease expires.
                log.error("[LocalTaskDispatcher] 失败状态转换失败 taskId={} reason={}",
                    task.getTaskId(), rootMessage(transitionError), transitionError);
            }
        } finally {
            heartbeat.cancel(false);
            releaseSlots(background);
        }
    }


    private void transitionFailure(TripTask task, String token, Throwable error) {
        TaskFailureClassifier.Classification result = failureClassifier.classify(error);
        int attempt = task.getAttempt() == null ? 1 : task.getAttempt();
        int maxAttempts = task.getMaxAttempts() == null ? 4 : task.getMaxAttempts();
        TripTask updated;
        if (result.transientFailure() && attempt < maxAttempts) {
            int delay = RETRY_DELAYS[Math.min(attempt - 1, RETRY_DELAYS.length - 1)];
            updated = taskStore.scheduleRetry(
                task.getTaskId(),
                token,
                result.code(),
                rootMessage(error),
                delay
            );
        } else {
            updated = taskStore.fail(
                task.getTaskId(),
                token,
                result.code(),
                rootMessage(error),
                result.transientFailure() && attempt >= maxAttempts
            );
        }
        realtimePublisher.publish(updated);
    }

    private boolean isBackground(String taskType) {
        return TaskType.KNOWLEDGE_INGESTION.equals(taskType)
            || TaskType.KNOWLEDGE_CORPUS_SYNC.equals(taskType);
    }

    private void releaseSlots(boolean background) {
        if (background) {
            backgroundSlots.release();
        }
        taskSlots.release();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error == null ? "Unknown local task failure" : error.getClass().getSimpleName();
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message;
    }
}
