package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_chunk")
public class KnowledgeChunk extends BaseEntity {
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    private String keywords;
    private String vectorRef;
    private String metadataJson;
}
