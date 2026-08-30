package com.zkry.domain.vo;

public record RagCitationView(
    Long chunk_id,
    Long document_id,
    Long source_id,
    String source_name,
    String title,
    String source_url,
    String content,
    double score,
    String retrieval_method
) {
}
