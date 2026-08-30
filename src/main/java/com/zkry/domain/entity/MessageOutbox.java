package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** A RabbitMQ publish intent committed in the same transaction as business state. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("message_outbox")
public class MessageOutbox extends BaseEntity {

    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private String exchangeName;
    private String routingKey;
    private String payloadJson;
    private String status;
    private Integer publishAttempts;
    private LocalDateTime nextAttemptAt;
    private String claimToken;
    private LocalDateTime claimUntil;
    private LocalDateTime publishedAt;
    private String lastError;
}
