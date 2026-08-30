package com.zkry.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.domain.entity.MessageOutbox;
import com.zkry.mapper.MessageOutboxMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polling outbox relay.
 *
 * <p>A row is marked published only after a correlated broker confirm and only when the
 * mandatory publish was not returned as unroutable.</p>
 */
@Component
@ConditionalOnProperty(name = "tripstar.tasks.transport", havingValue = "rabbit")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final MessageOutboxMapper mapper;
    private final RabbitTemplate rabbitTemplate;
    private final TripTaskStore taskStore;
    private final TaskRealtimePublisher realtimePublisher;
    private final long confirmTimeoutMillis;
    private final AtomicBoolean publishing = new AtomicBoolean();
    private final AtomicLong lastExpiredClaimSweepNanos = new AtomicLong();
    private final Counter publishedCounter;
    private final Counter failedCounter;

    public OutboxPublisher(
        MessageOutboxMapper mapper,
        RabbitTemplate rabbitTemplate,
        TripTaskStore taskStore,
        TaskRealtimePublisher realtimePublisher,
        MeterRegistry meterRegistry,
        @Value("${tripstar.tasks.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMillis
    ) {
        this.mapper = mapper;
        this.rabbitTemplate = rabbitTemplate;
        this.taskStore = taskStore;
        this.realtimePublisher = realtimePublisher;
        this.confirmTimeoutMillis = Math.max(1000, confirmTimeoutMillis);
        this.publishedCounter = meterRegistry.counter("tripstar.outbox.published");
        this.failedCounter = meterRegistry.counter("tripstar.outbox.failed");
        this.rabbitTemplate.setMandatory(true);
    }

    @Scheduled(fixedDelayString = "${tripstar.tasks.outbox.poll-ms:500}")
    public void publishBatch() {
        if (!publishing.compareAndSet(false, true)) return;
        try {
            releaseExpiredClaimsWhenDue();
            List<MessageOutbox> candidates = mapper.selectList(
                Wrappers.<MessageOutbox>lambdaQuery()
                    .in(MessageOutbox::getStatus, OutboxStatus.PENDING, OutboxStatus.FAILED)
                    .le(MessageOutbox::getNextAttemptAt, LocalDateTime.now())
                    .and(query -> query.isNull(MessageOutbox::getClaimUntil)
                        .or().lt(MessageOutbox::getClaimUntil, LocalDateTime.now()))
                    .orderByAsc(MessageOutbox::getCreateTime)
                    .last("LIMIT 50")
            );
            for (MessageOutbox candidate : candidates) publish(candidate);
        } finally {
            publishing.set(false);
        }
    }

    private void releaseExpiredClaimsWhenDue() {
        long now = System.nanoTime();
        long previous = lastExpiredClaimSweepNanos.get();
        if (previous != 0L && now - previous < TimeUnit.SECONDS.toNanos(30)) return;
        if (!lastExpiredClaimSweepNanos.compareAndSet(previous, now)) return;
        releaseExpiredClaims();
    }

    private void publish(MessageOutbox candidate) {
        String claimToken = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (mapper.claim(candidate.getId(), claimToken, now.plusSeconds(30), now) != 1) return;
        MessageOutbox claimed = mapper.selectById(candidate.getId());
        try {
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding("UTF-8");
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(claimed.getEventId());
            properties.setHeader("x-tripstar-aggregate-id", claimed.getAggregateId());
            Message message = rabbitTemplate.getMessageConverter().toMessage(claimed.getPayloadJson(), properties);
            CorrelationData correlation = new CorrelationData(claimed.getEventId());
            rabbitTemplate.send(claimed.getExchangeName(), claimed.getRoutingKey(), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ nack: " + confirm.getReason());
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ returned unroutable message: "
                    + correlation.getReturned().getReplyText());
            }
            claimed.setStatus(OutboxStatus.PUBLISHED);
            claimed.setPublishedAt(LocalDateTime.now());
            claimed.setClaimToken(null);
            claimed.setClaimUntil(null);
            claimed.setLastError("");
            mapper.updateById(claimed);
            publishedCounter.increment();
            notifyQueuedTask(
                claimed,
                "任务已进入执行队列，正在等待可用的规划 Worker"
            );
        } catch (Exception ex) {
            claimed.setStatus(OutboxStatus.FAILED);
            claimed.setClaimToken(null);
            claimed.setClaimUntil(null);
            claimed.setLastError(truncate(ex.getMessage(), 1000));
            claimed.setNextAttemptAt(LocalDateTime.now().plusSeconds(backoffSeconds(claimed.getPublishAttempts())));
            mapper.updateById(claimed);
            failedCounter.increment();
            long retrySeconds = backoffSeconds(claimed.getPublishAttempts());
            notifyQueuedTask(
                claimed,
                "消息服务暂不可用，任务已安全保存；系统将在 " + retrySeconds
                    + " 秒内自动重试（第 " + claimed.getPublishAttempts() + " 次）"
            );
            log.warn("[Outbox] publish failed eventId={} exchange={} routingKey={} attempts={} reason={}",
                claimed.getEventId(), claimed.getExchangeName(), claimed.getRoutingKey(),
                claimed.getPublishAttempts(), ex.getMessage());
        }
    }

    private void notifyQueuedTask(MessageOutbox outbox, String message) {
        if (outbox == null || !"trip_task".equals(outbox.getAggregateType())) {
            return;
        }
        try {
            taskStore.updateQueuedMessage(outbox.getAggregateId(), message)
                .ifPresent(realtimePublisher::publish);
        } catch (RuntimeException ex) {
            log.debug("[Outbox] queued task status update failed taskId={} reason={}",
                outbox.getAggregateId(), ex.getMessage());
        }
    }

    private void releaseExpiredClaims() {
        mapper.update(
            null,
            Wrappers.<MessageOutbox>lambdaUpdate()
                .eq(MessageOutbox::getStatus, OutboxStatus.PUBLISHING)
                .lt(MessageOutbox::getClaimUntil, LocalDateTime.now())
                .set(MessageOutbox::getStatus, OutboxStatus.FAILED)
                .set(MessageOutbox::getClaimToken, null)
                .set(MessageOutbox::getClaimUntil, null)
                .set(MessageOutbox::getNextAttemptAt, LocalDateTime.now())
                .set(MessageOutbox::getLastError, "Publisher claim expired before confirm")
        );
    }

    private long backoffSeconds(Integer attempts) {
        int safe = attempts == null ? 1 : Math.max(1, attempts);
        return Math.min(60, 1L << Math.min(safe, 6));
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
