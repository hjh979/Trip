package com.zkry.memory.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zkry.domain.entity.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trip_memory_event")
public class TripMemoryEvent extends BaseEntity {
    private String eventId;
    private Long userId;
    private String planId;
    private Integer planVersion;
    private String taskId;
    private String eventType;
    private String targetType;
    private String targetRef;
    private String city;
    private Integer dayNumber;
    private String beforeJson;
    private String afterJson;
    private String reasonCode;
    private String reasonText;
    private String source;
    private String evidenceRefsJson;
    private LocalDateTime occurredAt;
    private String consolidationStatus;
    private LocalDateTime processedAt;
}
