package com.zkry.domain.vo;

public record KnowledgeDocumentView(
    Long id,
    Long source_id,
    String title,
    String source_url,
    String status,
    int chunk_count,
    int vector_count,
    boolean vector_indexed,
    String message
) {
}
