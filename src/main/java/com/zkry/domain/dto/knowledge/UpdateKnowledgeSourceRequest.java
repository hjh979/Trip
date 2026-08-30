package com.zkry.domain.dto.knowledge;

public record UpdateKnowledgeSourceRequest(
    String name,
    String source_type,
    String endpoint,
    String status,
    String description
) {
}
