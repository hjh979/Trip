package com.zkry.domain.vo;

import java.util.List;

public record RagSearchView(
    String query,
    boolean embedding_configured,
    boolean vector_store_available,
    String retrieval_mode,
    List<RagCitationView> citations,
    String trace_id
) {
    public RagSearchView(
        String query,
        boolean embeddingConfigured,
        boolean vectorStoreAvailable,
        String retrievalMode,
        List<RagCitationView> citations
    ) {
        this(query, embeddingConfigured, vectorStoreAvailable, retrievalMode, citations, "");
    }
}
