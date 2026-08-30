package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Durable state of a long-running task.
 *
 * <p>The row is the authoritative recovery snapshot. RabbitMQ contains only a small wake-up
 * message; request data, ownership, progress, retry state and the last realtime sequence live
 * here so a broker redelivery or JVM restart never has to reconstruct state from memory.</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trip_task")
public class TripTask extends BaseEntity {

    private String taskId;
    private Long ownerId;
    private String taskType;
    /** Plan targeted by a modification task; null for plan creation/knowledge jobs. */
    private String planId;
    /** Client supplied idempotency key, never used as a bearer credential. */
    private String idempotencyKey;
    private String status;
    private String stage;
    private Integer progress;
    private String progressText;
    private String requestJson;
    private String resultPlanId;
    private Integer resultVersion;
    private String resultUrl;
    private String errorCode;
    private String errorMessage;
    private Integer attempt;
    private Integer maxAttempts;
    private Long lastSeq;
    private Integer lockVersion;
    private String processingToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime nextRetryAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
