package com.zkry.domain.dto.knowledge;

public record CreateKnowledgeSourceRequest(
    String name,
    String source_type,
    String endpoint,
    String description
) {
}
