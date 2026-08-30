package com.zkry.task;

import com.rabbitmq.client.Channel;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.entity.TripTask;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tripstar.tasks.transport", havingValue = "rabbit")
public class KnowledgeIngestionTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionTaskConsumer.class);
    private static final int[] RETRY_DELAYS = {30, 120, 300};

    private final TripTaskStore taskStore;
    private final TaskExecutionEngine executionEngine;
    private final TaskFailureClassifier classifier;
    private final TaskRealtimePublisher publisher;
    private final ScheduledExecutorService heartbeatExecutor;

    public KnowledgeIngestionTaskConsumer(
        TripTaskStore taskStore,
        TaskExecutionEngine executionEngine,
        TaskFailureClassifier classifier,
        TaskRealtimePublisher publisher,
        @Qualifier("taskLeaseHeartbeatExecutor")
        ScheduledExecutorService heartbeatExecutor
    ) {
        this.taskStore = taskStore;
        this.executionEngine = executionEngine;
        this.classifier = classifier;
        this.publisher = publisher;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    @RabbitListener(
        queues = TaskQueues.KNOWLEDGE_QUEUE,
        containerFactory = "knowledgeTaskRabbitListenerContainerFactory"
    )
    public void consume(Message amqpMessage, Channel channel) throws IOException {
        long tag = amqpMessage.getMessageProperties().getDeliveryTag();
        TaskMessage message;
        try {
            message = JsonUtils.parseObject(amqpMessage.getBody(), TaskMessage.class);
        } catch (RuntimeException ex) {
            channel.basicReject(tag, false);
            return;
        }
        if (message == null) {
            channel.basicReject(tag, false);
            return;
        }
        String token = UUID.randomUUID().toString();
        TripTask task = taskStore.claim(message.taskId(), token).orElse(null);
        if (task == null) {
            channel.basicAck(tag, false);
            return;
        }
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
            () -> taskStore.heartbeat(message.taskId(), token), 30, 30, TimeUnit.SECONDS
        );
        try {
            executionEngine.execute(task, token);
            channel.basicAck(tag, false);
        } catch (Throwable error) {
            log.error("[KnowledgeTask] task failed taskId={} reason={}",
                message.taskId(), errorMessage(error), error);
            try {
                TaskFailureClassifier.Classification result = classifier.classify(error);
                int attempt = task.getAttempt() == null ? 1 : task.getAttempt();
                int max = task.getMaxAttempts() == null ? 4 : task.getMaxAttempts();
                TripTask updated;
                if (result.transientFailure() && attempt < max) {
                    updated = taskStore.scheduleRetry(
                        task.getTaskId(), token, result.code(), errorMessage(error),
                        RETRY_DELAYS[Math.min(attempt - 1, RETRY_DELAYS.length - 1)]
                    );
                } else {
                    updated = taskStore.fail(
                        task.getTaskId(), token, result.code(), errorMessage(error),
                        result.transientFailure() && attempt >= max
                    );
                }
                publisher.publish(updated);
                channel.basicAck(tag, false);
            } catch (Throwable transitionError) {
                log.error("[KnowledgeTask] failure transition failed taskId={} reason={}",
                    message.taskId(), errorMessage(transitionError), transitionError);
                channel.basicNack(tag, false, true);
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private String errorMessage(Throwable error) {
        Throwable current = error;
        String message = "Knowledge ingestion failed";
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) message = current.getMessage();
            current = current.getCause();
        }
        return message;
    }
}
