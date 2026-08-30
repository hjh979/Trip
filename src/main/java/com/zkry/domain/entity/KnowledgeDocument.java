package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_document")
public class KnowledgeDocument extends BaseEntity {
    private Long sourceId;
    private String externalId;
    private String title;
    private String sourceUrl;
    private String content;
    private String contentHash;
    private String status;
    private String visibility;
    private LocalDateTime publishedAt;
}
