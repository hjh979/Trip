package com.zkry.domain.dto.knowledge;

import java.util.List;

public record RagAnswerRequest(
    String question,
    Integer top_k,
    List<Long> source_ids,
    String city,
    String place_name,
    List<String> topics
) {
    public RagAnswerRequest(String question, Integer topK, List<Long> sourceIds) {
        this(question, topK, sourceIds, "", "", List.of());
    }
}
