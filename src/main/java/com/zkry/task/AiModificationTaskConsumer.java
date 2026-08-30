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
public class AiModificationTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiModificationTaskConsumer.class);
    private static final int[] RETRY_DELAYS = {30, 120, 300};

    private final TripTaskStore taskStore;
    private final TaskExecutionEngine executionEngine;
    private final TaskFailureClassifier classifier;
    private final TaskRealtimePublisher realtimePublisher;
    private final ScheduledExecutorService heartbeatExecutor;

    public AiModificationTaskConsumer(
        TripTaskStore taskStore,
        TaskExecutionEngine executionEngine,
        TaskFailureClassifier classifier,
        TaskRealtimePublisher realtimePublisher,
        @Qualifier("taskLeaseHeartbeatExecutor")
        ScheduledExecutorService heartbeatExecutor
    ) {
        this.taskStore = taskStore;
        this.executionEngine = executionEngine;
        this.classifier = classifier;
        this.realtimePublisher = realtimePublisher;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    @RabbitListener(
        queues = TaskQueues.MODIFICATION_QUEUE,
        containerFactory = "modificationTaskRabbitListenerContainerFactory"
    )
    public void consume(Message amqpMessage, Channel channel) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        TaskMessage message;
        try {
            message = JsonUtils.parseObject(amqpMessage.getBody(), TaskMessage.class);
        } catch (RuntimeException ex) {
            channel.basicReject(deliveryTag, false);
            return;
        }
        if (message == null) {
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
            } else {
                channel.basicAck(deliveryTag, false);
            }
            return;
        }
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
            () -> taskStore.heartbeat(message.taskId(), token), 30, 30, TimeUnit.SECONDS
        );
        try {
            executionEngine.execute(claimed.get(), token);
            channel.basicAck(deliveryTag, false);
        } catch (Throwable error) {
            log.error("[AiModification] task failed taskId={} reason={}",
                message.taskId(), message(error), error);
            try {
                transitionFailure(claimed.get(), token, error);
                channel.basicAck(deliveryTag, false);
            } catch (Throwable transitionError) {
                log.error("[AiModification] failure transition failed taskId={} reason={}",
                    message.taskId(), message(transitionError), transitionError);
                channel.basicNack(deliveryTag, false, true);
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private void transitionFailure(TripTask task, String token, Throwable error) {
        TaskFailureClassifier.Classification result = classifier.classify(error);
        int attempt = task.getAttempt() == null ? 1 : task.getAttempt();
        int max = task.getMaxAttempts() == null ? 4 : task.getMaxAttempts();
        TripTask updated;
        if (result.transientFailure() && attempt < max) {
            updated = taskStore.scheduleRetry(
                task.getTaskId(),
                token,
                result.code(),
                message(error),
                RETRY_DELAYS[Math.min(attempt - 1, RETRY_DELAYS.length - 1)]
            );
        } else {
            updated = taskStore.fail(
                task.getTaskId(),
                token,
                result.code(),
                message(error),
                result.transientFailure() && attempt >= max
            );
        }
        realtimePublisher.publish(updated);
    }

    private String message(Throwable error) {
        Throwable current = error;
        String value = "AI modification failed";
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) value = current.getMessage();
            current = current.getCause();
        }
        return value;
    }
}
