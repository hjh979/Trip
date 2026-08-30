package com.zkry.memory.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zkry.domain.entity.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_memory_fact")
public class UserMemoryFact extends BaseEntity {
    private Long userId;
    private String memoryType;
    private String memoryKey;
    private String memoryValueJson;
    private String scopeType;
    private String scopeValue;
    private String source;
    private Double confidence;
    private Boolean hardConstraint;
    private String status;
    private String memoryFingerprint;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastObservedAt;
    private LocalDateTime lastConfirmedAt;
    private LocalDateTime expiresAt;
    private Long supersededBy;
    private String evidenceRefsJson;
}
