package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_source")
public class KnowledgeSource extends BaseEntity {
    private String name;
    private String sourceType;
    private String endpoint;
    private String status;
    private Integer documentCount;
    private LocalDateTime lastSyncAt;
    private String description;
}
