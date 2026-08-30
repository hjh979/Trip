package com.zkry.service.rag;

import com.zkry.integration.ExternalCallBulkheads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MilvusVectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStoreService.class);
    private static final List<String> OUTPUT_FIELDS = List.of(
        "chunk_id", "document_id", "source_id", "source_name", "title", "source_url", "content",
        "city", "place_name", "topics", "license"
    );

    private final RestClient client;
    private final String collection;
    private final ExternalCallBulkheads bulkheads;

    public MilvusVectorStoreService(
        @Value("${tripstar.rag.milvus.url:http://localhost:19530}") String baseUrl,
        @Value("${tripstar.rag.milvus.token:root:Milvus}") String token,
        @Value("${tripstar.rag.milvus.collection:voyagemind_knowledge_zh}") String collection,
        ExternalCallBulkheads bulkheads
    ) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", ""));
        if (token != null && !token.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim());
        }
        this.client = builder.defaultHeader("Request-Timeout", "10").build();
        this.collection = collection;
        this.bulkheads = bulkheads;
    }

    public boolean isAvailable() {
        try {
            Map<?, ?> body = call(() -> client.post().uri("/v2/vectordb/collections/list")
                .body(Map.of()).retrieve().body(Map.class));
            return body != null && number(body.get("code")) == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean collectionExists() {
        Map<?, ?> body = call(() -> client.post().uri("/v2/vectordb/collections/list")
            .body(Map.of()).retrieve().body(Map.class));
        if (body == null || number(body.get("code")) != 0 || !(body.get("data") instanceof List<?> names)) {
            return false;
        }
        return names.stream().map(String::valueOf).anyMatch(collection::equals);
    }

    public void ensureCollection(int dimensions) {
        if (collectionExists()) return;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("collectionName", collection);
        request.put("dimension", dimensions);
        request.put("primaryFieldName", "id");
        request.put("idType", "Int64");
        request.put("vectorFieldName", "vector");
        request.put("metricType", "COSINE");
        request.put("autoId", false);
        request.put("enableDynamicField", true);
        assertSuccess(call(() -> client.post().uri("/v2/vectordb/collections/create")
            .body(request).retrieve().body(Map.class)), "create collection");
    }

    public void upsert(List<VectorPoint> points) {
        if (points == null || points.isEmpty()) return;
        ensureCollection(points.getFirst().vector().size());
        List<Map<String, Object>> data = new ArrayList<>();
        for (VectorPoint point : points) {
            Map<String, Object> entity = new LinkedHashMap<>(point.payload());
            entity.put("id", point.id());
            entity.put("vector", point.vector());
            data.add(entity);
        }
        assertSuccess(call(() -> client.post().uri("/v2/vectordb/entities/upsert")
            .body(Map.of("collectionName", collection, "data", data))
            .retrieve().body(Map.class)), "upsert vectors");
    }

    public List<VectorHit> search(List<Double> vector, int limit, List<Long> sourceIds) {
        return search(vector, limit, sourceIds, "");
    }

    public List<VectorHit> search(List<Double> vector, int limit, List<Long> sourceIds, String city) {
        if (vector == null || vector.isEmpty() || !collectionExists()) return List.of();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("collectionName", collection);
        request.put("data", List.of(vector));
        request.put("annsField", "vector");
        request.put("limit", limit);
        request.put("outputFields", OUTPUT_FIELDS);
        request.put("searchParams", Map.of("metricType", "COSINE", "params", Map.of()));
        List<String> filters = new ArrayList<>();
        if (sourceIds != null && !sourceIds.isEmpty()) filters.add("source_id in [" + sourceIds.stream()
            .map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("") + "]");
        if (city != null && !city.isBlank()) filters.add("city == \"" + escapeFilter(city.trim()) + "\"");
        if (!filters.isEmpty()) request.put("filter", String.join(" and ", filters));
        try {
            Map<?, ?> body = call(() -> client.post().uri("/v2/vectordb/entities/search")
                .body(request).retrieve().body(Map.class));
            if (body == null || number(body.get("code")) != 0 || !(body.get("data") instanceof List<?> result)) {
                return List.of();
            }
            List<VectorHit> hits = new ArrayList<>();
            for (Object raw : result) {
                if (!(raw instanceof Map<?, ?> item)) continue;
                Map<String, Object> fields = new LinkedHashMap<>();
                item.forEach((key, value) -> fields.put(String.valueOf(key), value));
                hits.add(new VectorHit(number(item.get("id")), decimal(item.get("distance")), fields));
            }
            return hits;
        } catch (Exception ex) {
            log.warn("[RAG] Milvus search failed: {}", ex.getMessage());
            return List.of();
        }
    }

    public void deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || !collectionExists()) return;
        String values = ids.stream().map(String::valueOf)
            .reduce((left, right) -> left + "," + right).orElse("");
        if (values.isBlank()) return;
        try {
            assertSuccess(call(() -> client.post().uri("/v2/vectordb/entities/delete")
                .body(Map.of("collectionName", collection, "filter", "id in [" + values + "]"))
                .retrieve().body(Map.class)), "delete vectors");
        } catch (Exception ex) {
            log.warn("[RAG] Milvus vector cleanup failed ids={} reason={}", ids.size(), ex.getMessage());
        }
    }

    private void assertSuccess(Map<?, ?> body, String action) {
        if (body == null || number(body.get("code")) != 0) {
            throw new IllegalStateException("Milvus " + action + " failed: " + (body == null ? "empty response" : body.get("message")));
        }
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0D;
    }

    private String escapeFilter(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private <T> T call(Supplier<T> supplier) {
        return bulkheads.executeUnchecked(ExternalCallBulkheads.Provider.MILVUS, supplier);
    }

    public record VectorPoint(long id, List<Double> vector, Map<String, Object> payload) {
    }

    public record VectorHit(long id, double score, Map<String, Object> fields) {
    }
}
