package com.zkry.domain.vo;

import java.util.List;

/**
 * 生成行程时实际采用的数据来源。景点候选来自自建 RAG 知识库，
 * 高德只负责对已选候选进行 POI 校验和地图增强。
 */
public record TripResearchEvidence(
    boolean amap_used,
    int amap_city_count,
    String rag_source,
    String rag_message,
    List<String> rag_trace_ids
) {
    public TripResearchEvidence(boolean amapUsed, int amapCityCount, String ragSource, String ragMessage) {
        this(amapUsed, amapCityCount, ragSource, ragMessage, traceIdsFrom(ragMessage));
    }

    public TripResearchEvidence {
        rag_trace_ids = rag_trace_ids == null ? List.of() : rag_trace_ids.stream()
            .filter(value -> value != null && !value.isBlank()).toList();
    }

    private static List<String> traceIdsFrom(String message) {
        if (message == null) return List.of();
        int start = message.indexOf("trace=[");
        if (start < 0) return List.of();
        int end = message.indexOf(']', start);
        if (end < 0) return List.of();
        String body = message.substring(start + 7, end).trim();
        if (body.isBlank()) return List.of();
        return java.util.Arrays.stream(body.split(","))
            .map(String::trim).filter(value -> !value.isBlank()).toList();
    }
}
