package com.zkry.domain.dto.knowledge;

import java.util.Map;

public record IndexKnowledgeDocumentRequest(
    Long source_id,
    String external_id,
    String title,
    String source_url,
    String content,
    String visibility,
    Map<String, Object> metadata
) {
    public IndexKnowledgeDocumentRequest(
        Long sourceId,
        String externalId,
        String title,
        String sourceUrl,
        String content,
        String visibility
    ) {
        this(sourceId, externalId, title, sourceUrl, content, visibility, Map.of());
    }
}
