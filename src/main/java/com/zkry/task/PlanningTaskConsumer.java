package com.zkry.task;

import com.rabbitmq.client.Channel;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.entity.TripTask;
import com.zkry.service.TripTaskStatus;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tripstar.tasks.transport", havingValue = "rabbit")
public class PlanningTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(PlanningTaskConsumer.class);
    private static final int[] RETRY_DELAYS = {30, 120, 300};

    private final TripTaskStore taskStore;
    private final TaskExecutionEngine executionEngine;
    private final TaskFailureClassifier failureClassifier;
    private final TaskRealtimePublisher realtimePublisher;
    private final ScheduledExecutorService taskLeaseHeartbeatExecutor;

    public PlanningTaskConsumer(
        TripTaskStore taskStore,
        TaskExecutionEngine executionEngine,
        TaskFailureClassifier failureClassifier,
        TaskRealtimePublisher realtimePublisher,
        @Qualifier("taskLeaseHeartbeatExecutor")
        ScheduledExecutorService taskLeaseHeartbeatExecutor
    ) {
        this.taskStore = taskStore;
        this.executionEngine = executionEngine;
        this.failureClassifier = failureClassifier;
        this.realtimePublisher = realtimePublisher;
        this.taskLeaseHeartbeatExecutor = taskLeaseHeartbeatExecutor;
    }

    @RabbitListener(
        queues = TaskQueues.PLAN_QUEUE,
        containerFactory = "planTaskRabbitListenerContainerFactory"
    )
    public void consume(Message amqpMessage, Channel channel) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        TaskMessage message;
        try {
            message = JsonUtils.parseObject(amqpMessage.getBody(), TaskMessage.class);
            if (message == null) throw new IllegalArgumentException("empty task message");
        } catch (RuntimeException ex) {
            log.error("[TaskConsumer] rejecting malformed message id={} reason={}",
                amqpMessage.getMessageProperties().getMessageId(), ex.getMessage());
            channel.basicReject(deliveryTag, false);
            return;
        }

        String token = UUID.randomUUID().toString();
        Optional<TripTask> claimed = taskStore.claim(message.taskId(), token);
        if (claimed.isEmpty()) {
            TripTask current = taskStore.find(message.taskId()).orElse(null);
            if (current != null && TripTaskStatus.RETRYING.equals(current.getStatus())
                && current.getNextRetryAt() != null
                && current.getNextRetryAt().isAfter(java.time.LocalDateTime.now())) {
                channel.basicNack(deliveryTag, false, true);
                return;
            }
            // Duplicate deliveries for a terminal or already-running task are safe to acknowledge.
            channel.basicAck(deliveryTag, false);
            return;
        }

        ScheduledFuture<?> heartbeat = taskLeaseHeartbeatExecutor.scheduleAtFixedRate(
            () -> taskStore.heartbeat(message.taskId(), token),
            30,
            30,
            TimeUnit.SECONDS
        );
        try {
            executionEngine.execute(claimed.get(), token);
            channel.basicAck(deliveryTag, false);
        } catch (Throwable error) {
            log.error("[TaskConsumer] task execution failed taskId={} attempt={} reason={}",
                message.taskId(), claimed.get().getAttempt(), rootMessage(error), error);
            try {
                handleFailure(claimed.get(), token, error);
                channel.basicAck(deliveryTag, false);
            } catch (Throwable transitionError) {
                log.error("[TaskConsumer] failure transition failed taskId={} reason={}",
                    message.taskId(), rootMessage(transitionError), transitionError);
                channel.basicNack(deliveryTag, false, true);
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private void handleFailure(TripTask task, String token, Throwable error) {
        TaskFailureClassifier.Classification classification = failureClassifier.classify(error);
        int attempt = task.getAttempt() == null ? 1 : task.getAttempt();
        int maxAttempts = task.getMaxAttempts() == null ? 4 : task.getMaxAttempts();
        TripTask updated;
        if (classification.transientFailure() && attempt < maxAttempts) {
            int delay = RETRY_DELAYS[Math.min(attempt - 1, RETRY_DELAYS.length - 1)];
            updated = taskStore.scheduleRetry(
                task.getTaskId(), token, classification.code(), rootMessage(error), delay
            );
        } else {
            boolean deadLetter = classification.transientFailure() && attempt >= maxAttempts;
            updated = taskStore.fail(
                task.getTaskId(), token, classification.code(), rootMessage(error), deadLetter
            );
        }
        realtimePublisher.publish(updated);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error == null ? "Unknown task failure" : error.getClass().getSimpleName();
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) message = current.getMessage();
            current = current.getCause();
        }
        return message;
    }
}
