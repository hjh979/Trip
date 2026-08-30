package com.zkry.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.entity.MessageOutbox;
import com.zkry.domain.entity.TripTask;
import com.zkry.mapper.MessageOutboxMapper;
import com.zkry.mapper.TripTaskMapper;
import com.zkry.service.TripTaskNotFoundException;
import com.zkry.service.TripTaskStage;
import com.zkry.service.TripTaskStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundary for durable task state.
 *
 * <p>Every method that schedules RabbitMQ work inserts an outbox row in the same transaction.
 * The broker can therefore be unavailable without losing the user's task.</p>
 */
@Service
public class TripTaskStore {

    private final TripTaskMapper taskMapper;
    private final MessageOutboxMapper outboxMapper;
    private final int maxAttempts;
    private final Duration leaseDuration;
    private final boolean rabbitTransport;

    public TripTaskStore(
        TripTaskMapper taskMapper,
        MessageOutboxMapper outboxMapper,
        @Value("${tripstar.tasks.max-attempts:4}") int maxAttempts,
        @Value("${tripstar.tasks.lease-seconds:180}") long leaseSeconds,
        @Value("${tripstar.tasks.transport:local}") String transport
    ) {
        this.taskMapper = taskMapper;
        this.outboxMapper = outboxMapper;
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 10));
        this.leaseDuration = Duration.ofSeconds(Math.max(60, leaseSeconds));
        this.rabbitTransport = "rabbit".equalsIgnoreCase(
            transport == null ? "" : transport.trim()
        );
    }

    @Transactional
    public TripTask create(Long ownerId, String taskType, Object request, String submittedMessage) {
        return create(ownerId, taskType, null, null, request, submittedMessage);
    }

    @Transactional
    public TripTask create(
        Long ownerId,
        String taskType,
        String planId,
        String idempotencyKey,
        Object request,
        String submittedMessage
    ) {
        if (ownerId == null) throw new IllegalArgumentException("Task owner is required");
        String taskId = UUID.randomUUID().toString().replace("-", "");
        TripTask task = new TripTask();
        task.setTaskId(taskId);
        task.setOwnerId(ownerId);
        task.setTaskType(taskType);
        task.setPlanId(planId);
        task.setIdempotencyKey(idempotencyKey);
        task.setStatus(TripTaskStatus.QUEUED);
        task.setStage(TripTaskStage.SUBMITTED);
        task.setProgress(0);
        task.setProgressText(submittedMessage == null ? "" : submittedMessage);
        task.setRequestJson(JsonUtils.toJsonString(request));
        task.setErrorCode("");
        task.setErrorMessage("");
        task.setAttempt(0);
        task.setMaxAttempts(maxAttempts);
        task.setLastSeq(1L);
        task.setLockVersion(0);
        taskMapper.insert(task);
        enqueue(task, TaskQueues.TASK_EXCHANGE, TaskQueues.taskRoutingKey(taskType), 0);
        return task;
    }

    public Optional<TripTask> findByIdempotency(Long ownerId, String taskType, String key) {
        if (ownerId == null || key == null || key.isBlank()) return Optional.empty();
        return Optional.ofNullable(taskMapper.selectOne(
            Wrappers.<TripTask>lambdaQuery().eq(TripTask::getOwnerId, ownerId)
                .eq(TripTask::getTaskType, taskType)
                .eq(TripTask::getIdempotencyKey, key)
                .eq(TripTask::getDeleted, 0).last("LIMIT 1")
        ));
    }

    public Optional<TripTask> activeModification(String planId, Long ownerId) {
        if (planId == null || planId.isBlank() || ownerId == null) return Optional.empty();
        return Optional.ofNullable(taskMapper.selectOne(
            Wrappers.<TripTask>lambdaQuery().eq(TripTask::getPlanId, planId)
                .eq(TripTask::getOwnerId, ownerId)
                .eq(TripTask::getTaskType, TaskType.AI_MODIFICATION)
                .in(TripTask::getStatus, TripTaskStatus.QUEUED, TripTaskStatus.PROCESSING, TripTaskStatus.RETRYING)
                .eq(TripTask::getDeleted, 0).orderByDesc(TripTask::getCreateTime).last("LIMIT 1")
        ));
    }

    public Optional<TripTask> find(String taskId) {
        if (taskId == null || taskId.isBlank()) return Optional.empty();
        return Optional.ofNullable(taskMapper.selectOne(
            Wrappers.<TripTask>lambdaQuery().eq(TripTask::getTaskId, taskId).last("LIMIT 1")
        ));
    }

    public TripTask require(String taskId) {
        return find(taskId).orElseThrow(() -> new TripTaskNotFoundException(taskId));
    }

    public TripTask requireOwned(String taskId, Long ownerId) {
        TripTask task = require(taskId);
        if (ownerId == null || !ownerId.equals(task.getOwnerId())) {
            // Deliberately do not reveal whether another user's task exists.
            throw new TripTaskNotFoundException(taskId);
        }
        return task;
    }

    public List<TripTask> activeForOwner(Long ownerId, int limit) {
        return taskMapper.selectList(
            Wrappers.<TripTask>lambdaQuery()
                .eq(TripTask::getOwnerId, ownerId)
                .in(TripTask::getStatus, TripTaskStatus.QUEUED, TripTaskStatus.PROCESSING, TripTaskStatus.RETRYING)
                .orderByDesc(TripTask::getUpdateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 50)))
        );
    }

    /**
     * Returns durable work that can be claimed by the single-JVM dispatcher.
     *
     * <p>Interactive AI changes are ordered ahead of new plans, document indexing and bulk
     * corpus crawling. The claim CAS remains the source of truth, so polling and restart
     * recovery cannot execute the same task twice.</p>
     */
    public List<TripTask> dueTasks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.selectList(
            Wrappers.<TripTask>lambdaQuery()
                .in(TripTask::getStatus, TripTaskStatus.QUEUED, TripTaskStatus.RETRYING)
                .and(query -> query.isNull(TripTask::getNextRetryAt)
                    .or().le(TripTask::getNextRetryAt, now))
                .last("""
                    ORDER BY CASE task_type
                        WHEN 'ai_modification' THEN 0
                        WHEN 'trip_plan' THEN 1
                        WHEN 'knowledge_ingestion' THEN 2
                        WHEN 'knowledge_corpus_sync' THEN 3
                        ELSE 4
                    END, create_time ASC
                    LIMIT %d
                    """.formatted(safeLimit))
        );
    }

    public Optional<TripTask> claim(String taskId, String token) {
        int changed = taskMapper.claim(taskId, token, leaseUntil(), LocalDateTime.now());
        return changed == 1 ? find(taskId) : Optional.empty();
    }

    public boolean heartbeat(String taskId, String token) {
        return taskMapper.heartbeat(taskId, token, leaseUntil(), LocalDateTime.now()) == 1;
    }

    public TripTask progress(String taskId, String token, String stage, int progress, String message) {
        int changed = taskMapper.updateProgress(
            taskId,
            token,
            stage,
            Math.max(0, Math.min(progress, 100)),
            message == null ? "" : message,
            leaseUntil(),
            LocalDateTime.now()
        );
        if (changed != 1) {
            TripTask current = require(taskId);
            boolean staleProgress = TripTaskStatus.PROCESSING.equals(current.getStatus())
                && token != null
                && token.equals(current.getProcessingToken())
                && current.getProgress() != null
                && current.getProgress() > progress;
            if (staleProgress) {
                return current;
            }
            throw new IllegalStateException("Task lease was lost: " + taskId);
        }
        return require(taskId);
    }

    public Optional<TripTask> updateQueuedMessage(String taskId, String message) {
        int changed = taskMapper.updateQueuedMessage(
            taskId,
            safe(message, 500),
            LocalDateTime.now()
        );
        return changed == 1 ? find(taskId) : Optional.empty();
    }

    @Transactional
    public TripTask scheduleRetry(String taskId, String token, String errorCode, String errorMessage, int delaySeconds) {
        TripTask current = require(taskId);
        int changed = taskMapper.update(
            null,
            Wrappers.<TripTask>lambdaUpdate()
                .eq(TripTask::getTaskId, taskId)
                .eq(TripTask::getProcessingToken, token)
                .eq(TripTask::getStatus, TripTaskStatus.PROCESSING)
                .set(TripTask::getStatus, TripTaskStatus.RETRYING)
                .set(TripTask::getErrorCode, safe(errorCode, 80))
                .set(TripTask::getErrorMessage, safe(errorMessage, 4000))
                .set(TripTask::getNextRetryAt, LocalDateTime.now().plusSeconds(delaySeconds))
                .set(TripTask::getProcessingToken, null)
                .set(TripTask::getLeaseUntil, null)
                .setSql("last_seq = last_seq + 1, lock_version = lock_version + 1")
        );
        if (changed != 1) throw new IllegalStateException("Task lease was lost before retry: " + taskId);
        TripTask updated = require(taskId);
        enqueue(
            updated,
            TaskQueues.RETRY_EXCHANGE,
            TaskQueues.retryRoutingKey(current.getTaskType(), delaySeconds),
            updated.getAttempt()
        );
        return updated;
    }

    @Transactional
    public TripTask completeGeneric(
        String taskId,
        String token,
        String planId,
        int version,
        String resultUrl,
        String message
    ) {
        int changed = taskMapper.update(
            null,
            Wrappers.<TripTask>lambdaUpdate()
                .eq(TripTask::getTaskId, taskId)
                .eq(TripTask::getProcessingToken, token)
                .eq(TripTask::getStatus, TripTaskStatus.PROCESSING)
                .set(TripTask::getStatus, TripTaskStatus.COMPLETED)
                .set(TripTask::getStage, TripTaskStage.COMPLETED)
                .set(TripTask::getProgress, 100)
                .set(TripTask::getProgressText, message)
                .set(TripTask::getResultPlanId, planId)
                .set(TripTask::getResultVersion, version)
                .set(TripTask::getResultUrl, resultUrl)
                .set(TripTask::getErrorCode, "")
                .set(TripTask::getErrorMessage, "")
                .set(TripTask::getProcessingToken, null)
                .set(TripTask::getLeaseUntil, null)
                .set(TripTask::getCompletedAt, LocalDateTime.now())
                .setSql("last_seq = last_seq + 1, lock_version = lock_version + 1")
        );
        if (changed != 1) throw new IllegalStateException("Task lease was lost before completion: " + taskId);
        return require(taskId);
    }

    @Transactional
    public TripTask fail(String taskId, String token, String errorCode, String errorMessage, boolean deadLetter) {
        String status = deadLetter ? TripTaskStatus.DEAD_LETTERED : TripTaskStatus.FAILED;
        int changed = taskMapper.update(
            null,
            Wrappers.<TripTask>lambdaUpdate()
                .eq(TripTask::getTaskId, taskId)
                .eq(TripTask::getProcessingToken, token)
                .eq(TripTask::getStatus, TripTaskStatus.PROCESSING)
                .set(TripTask::getStatus, status)
                .set(TripTask::getStage, TripTaskStage.FAILED)
                .set(TripTask::getProgress, 100)
                .set(TripTask::getProgressText, deadLetter ? "任务多次重试失败，已进入死信队列" : "任务执行失败")
                .set(TripTask::getErrorCode, safe(errorCode, 80))
                .set(TripTask::getErrorMessage, safe(errorMessage, 4000))
                .set(TripTask::getProcessingToken, null)
                .set(TripTask::getLeaseUntil, null)
                .set(TripTask::getCompletedAt, LocalDateTime.now())
                .setSql("last_seq = last_seq + 1, lock_version = lock_version + 1")
        );
        if (changed != 1) throw new IllegalStateException("Task lease was lost before failure: " + taskId);
        TripTask updated = require(taskId);
        if (deadLetter) {
            enqueue(updated, TaskQueues.DLX_EXCHANGE, TaskQueues.DLQ_KEY, updated.getAttempt());
        }
        return updated;
    }

    @Transactional
    public TripTask requeueDeadLetter(String taskId) {
        int changed = taskMapper.update(
            null,
            Wrappers.<TripTask>lambdaUpdate()
                .eq(TripTask::getTaskId, taskId)
                // Administrators also need to redrive permanent failures after a code or
                // configuration fix. Checkpoints make this safe without repeating paid stages.
                .in(TripTask::getStatus, TripTaskStatus.FAILED, TripTaskStatus.DEAD_LETTERED)
                .set(TripTask::getStatus, TripTaskStatus.QUEUED)
                .set(TripTask::getStage, TripTaskStage.SUBMITTED)
                .set(TripTask::getProgress, 0)
                .set(TripTask::getProgressText, "管理员已重新投递任务")
                .set(TripTask::getAttempt, 0)
                .set(TripTask::getNextRetryAt, null)
                .set(TripTask::getStartedAt, null)
                .set(TripTask::getCompletedAt, null)
                .set(TripTask::getErrorCode, "")
                .set(TripTask::getErrorMessage, "")
                .setSql("last_seq = last_seq + 1, lock_version = lock_version + 1")
        );
        if (changed != 1) {
            throw new IllegalStateException("Only failed or dead-lettered tasks can be requeued");
        }
        TripTask task = require(taskId);
        enqueue(task, TaskQueues.TASK_EXCHANGE, TaskQueues.taskRoutingKey(task.getTaskType()), 0);
        return task;
    }

    public List<TripTask> deadLetters(int limit) {
        return taskMapper.selectList(
            Wrappers.<TripTask>lambdaQuery()
                .eq(TripTask::getStatus, TripTaskStatus.DEAD_LETTERED)
                .orderByDesc(TripTask::getUpdateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200)))
        );
    }

    @Transactional
    public boolean recoverExpired(String taskId, String processingToken) {
        int changed = taskMapper.update(
            null,
            Wrappers.<TripTask>lambdaUpdate()
                .eq(TripTask::getTaskId, taskId)
                .eq(TripTask::getProcessingToken, processingToken)
                .eq(TripTask::getStatus, TripTaskStatus.PROCESSING)
                .lt(TripTask::getLeaseUntil, LocalDateTime.now())
                .set(TripTask::getStatus, TripTaskStatus.RETRYING)
                .set(TripTask::getProgressText, "检测到执行进程中断，任务将从检查点恢复")
                .set(TripTask::getProcessingToken, null)
                .set(TripTask::getLeaseUntil, null)
                .set(TripTask::getNextRetryAt, LocalDateTime.now())
                .setSql("last_seq = last_seq + 1, lock_version = lock_version + 1")
        );
        if (changed != 1) return false;
        TripTask task = require(taskId);
        enqueue(task, TaskQueues.TASK_EXCHANGE, TaskQueues.taskRoutingKey(task.getTaskType()), task.getAttempt());
        return true;
    }

    /**
     * In the explicitly single-instance deployment, every processing lease found when this JVM
     * starts belongs to the previous process and can be recovered immediately.
     */
    @Transactional
    public boolean recoverInterruptedAtStartup(String taskId, String processingToken) {
        int changed = taskMapper.update(
            null,
            Wrappers.<TripTask>lambdaUpdate()
                .eq(TripTask::getTaskId, taskId)
                .eq(TripTask::getProcessingToken, processingToken)
                .eq(TripTask::getStatus, TripTaskStatus.PROCESSING)
                .set(TripTask::getStatus, TripTaskStatus.RETRYING)
                .set(TripTask::getProgressText, "服务重启，任务将从持久化检查点恢复")
                .set(TripTask::getProcessingToken, null)
                .set(TripTask::getLeaseUntil, null)
                .set(TripTask::getNextRetryAt, LocalDateTime.now())
                .setSql("last_seq = last_seq + 1, lock_version = lock_version + 1")
        );
        if (changed != 1) return false;
        TripTask task = require(taskId);
        enqueue(task, TaskQueues.TASK_EXCHANGE, TaskQueues.taskRoutingKey(task.getTaskType()), task.getAttempt());
        return true;
    }

    public Duration leaseDuration() {
        return leaseDuration;
    }

    private void enqueue(TripTask task, String exchange, String routingKey, int attempt) {
        if (!rabbitTransport) {
            return;
        }
        MessageOutbox outbox = new MessageOutbox();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setAggregateType("trip_task");
        outbox.setAggregateId(task.getTaskId());
        outbox.setExchangeName(exchange);
        outbox.setRoutingKey(routingKey);
        outbox.setPayloadJson(JsonUtils.toJsonString(new TaskMessage(task.getTaskId(), task.getTaskType(), attempt)));
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setPublishAttempts(0);
        outbox.setNextAttemptAt(LocalDateTime.now());
        outbox.setLastError("");
        outboxMapper.insert(outbox);
    }

    private LocalDateTime leaseUntil() {
        return LocalDateTime.now().plus(leaseDuration);
    }

    private String safe(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
