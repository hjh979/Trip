package com.zkry.domain.dto.knowledge;

import java.util.List;

public record RagSearchRequest(
    String query,
    Integer top_k,
    List<Long> source_ids,
    String city,
    String place_name,
    List<String> topics
) {
    public RagSearchRequest(String query, Integer topK, List<Long> sourceIds) {
        this(query, topK, sourceIds, "", "", List.of());
    }
}
