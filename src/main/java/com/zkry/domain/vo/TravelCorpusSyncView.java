package com.zkry.domain.vo;

import java.util.List;

public record TravelCorpusSyncView(
    Long source_id,
    int requested,
    int fetched,
    int indexed,
    int keyword_only,
    int unchanged,
    int failed,
    int document_count,
    int chunk_count,
    List<String> errors
) {
}
