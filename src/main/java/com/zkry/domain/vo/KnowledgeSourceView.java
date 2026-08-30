package com.zkry.domain.vo;

public record KnowledgeSourceView(
    Long id,
    String name,
    String source_type,
    String endpoint,
    String status,
    Integer document_count,
    String last_sync_at,
    String description
) {
}
