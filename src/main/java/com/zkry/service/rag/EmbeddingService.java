package com.zkry.service.rag;

import com.zkry.common.constant.TripstarSettingKeys;
import com.zkry.integration.ExternalCallBulkheads;
import com.zkry.service.TripstarRuntimeSettingsService;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private final TripstarRuntimeSettingsService settings;
    private final ExternalCallBulkheads bulkheads;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int batchSize;
    private final JdkClientHttpRequestFactory requestFactory;

    public EmbeddingService(
        TripstarRuntimeSettingsService settings,
        ExternalCallBulkheads bulkheads,
        @Value("${tripstar.rag.embedding.connect-timeout-ms:5000}") long connectTimeoutMillis,
        @Value("${tripstar.rag.embedding.read-timeout-ms:30000}") long readTimeoutMillis,
        @Value("${tripstar.rag.embedding.batch-size:16}") int batchSize
    ) {
        this.settings = settings;
        this.bulkheads = bulkheads;
        this.connectTimeout = Duration.ofMillis(Math.max(500, connectTimeoutMillis));
        this.readTimeout = Duration.ofMillis(Math.max(1000, readTimeoutMillis));
        this.batchSize = Math.max(1, Math.min(batchSize, 64));
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(this.connectTimeout)
            .build();
        this.requestFactory = new JdkClientHttpRequestFactory(httpClient);
        this.requestFactory.setReadTimeout(this.readTimeout);
    }

    public boolean isConfigured() {
        return settings.hasText(TripstarSettingKeys.EMBEDDING_BASE_URL)
            && settings.hasText(TripstarSettingKeys.EMBEDDING_API_KEY)
            && settings.hasText(TripstarSettingKeys.EMBEDDING_MODEL);
    }

    public Optional<List<List<Double>>> embed(List<String> texts) {
        if (!isConfigured() || texts == null || texts.isEmpty()) return Optional.empty();
        String baseUrl = settings.stringValue(TripstarSettingKeys.EMBEDDING_BASE_URL).orElseThrow();
        String apiKey = settings.stringValue(TripstarSettingKeys.EMBEDDING_API_KEY).orElseThrow();
        String model = settings.stringValue(TripstarSettingKeys.EMBEDDING_MODEL).orElseThrow();
        try {
            List<List<Double>> vectors = new ArrayList<>(texts.size());
            for (int from = 0; from < texts.size(); from += batchSize) {
                int to = Math.min(texts.size(), from + batchSize);
                Optional<List<List<Double>>> batch = embedBatch(
                    baseUrl, apiKey, model, texts.subList(from, to));
                if (batch.isEmpty()) return Optional.empty();
                vectors.addAll(batch.get());
            }
            return vectors.size() == texts.size() ? Optional.of(vectors) : Optional.empty();
        } catch (Exception ex) {
            log.warn("[RAG] Embedding request failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<List<List<Double>>> embedBatch(
        String baseUrl,
        String apiKey,
        String model,
        List<String> texts
    ) {
        return bulkheads.executeUnchecked(ExternalCallBulkheads.Provider.EMBEDDING, () -> {
            RestClient client = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
            Map<?, ?> body = client.post().uri("/embeddings")
                .body(Map.of("model", model, "input", texts, "encoding_format", "float"))
                .retrieve().body(Map.class);
            if (body == null || !(body.get("data") instanceof List<?> data)) return Optional.empty();
            List<Map<?, ?>> ordered = new ArrayList<>();
            for (Object item : data) {
                if (item instanceof Map<?, ?> map) ordered.add(map);
            }
            ordered.sort((left, right) -> Integer.compare(index(left.get("index")), index(right.get("index"))));
            List<List<Double>> vectors = new ArrayList<>();
            for (Map<?, ?> item : ordered) {
                if (!(item.get("embedding") instanceof List<?> rawVector)) return Optional.empty();
                vectors.add(rawVector.stream().map(value -> ((Number) value).doubleValue()).toList());
            }
            return vectors.size() == texts.size() ? Optional.of(vectors) : Optional.empty();
        });
    }

    private int index(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
