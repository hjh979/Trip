package com.zkry.controller;

import com.zkry.domain.dto.knowledge.IndexKnowledgeDocumentRequest;
import com.zkry.domain.dto.knowledge.RagAnswerRequest;
import com.zkry.domain.dto.knowledge.RagSearchRequest;
import com.zkry.domain.vo.KnowledgeDocumentView;
import com.zkry.domain.vo.RagAnswerView;
import com.zkry.domain.vo.RagSearchView;
import com.zkry.service.rag.EmbeddingService;
import com.zkry.service.rag.KnowledgeIngestionService;
import com.zkry.service.rag.KnowledgeRagService;
import com.zkry.service.rag.MilvusVectorStoreService;
import com.zkry.service.rag.RetrievalTraceService;
import com.zkry.task.KnowledgeIngestionTaskService;
import com.zkry.domain.vo.SubmitTripPlanResponse;
import com.zkry.security.VoyagePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeRagController {

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeRagService ragService;
    private final EmbeddingService embeddingService;
    private final MilvusVectorStoreService vectorStore;
    private final RetrievalTraceService traceService;
    private final KnowledgeIngestionTaskService ingestionTaskService;

    public KnowledgeRagController(KnowledgeIngestionService ingestionService, KnowledgeRagService ragService,
                                  EmbeddingService embeddingService, MilvusVectorStoreService vectorStore,
                                  RetrievalTraceService traceService,
                                  KnowledgeIngestionTaskService ingestionTaskService) {
        this.ingestionService = ingestionService;
        this.ragService = ragService;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.traceService = traceService;
        this.ingestionTaskService = ingestionTaskService;
    }

    @PostMapping("/documents/index")
    public KnowledgeDocumentView index(@RequestBody IndexKnowledgeDocumentRequest request) {
        return ingestionService.index(request);
    }

    @PostMapping("/documents/index/async")
    public SubmitTripPlanResponse indexAsync(
        @RequestBody IndexKnowledgeDocumentRequest request,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        return ingestionTaskService.submit(request, principal);
    }

    @PostMapping("/search")
    public RagSearchView search(@RequestBody RagSearchRequest request) {
        return ragService.search(request);
    }

    @PostMapping("/answer")
    public RagAnswerView answer(@RequestBody RagAnswerRequest request) {
        return ragService.answer(request);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        boolean milvusAvailable = vectorStore.isAvailable();
        boolean embeddingConfigured = embeddingService.isConfigured();
        return Map.of(
            "milvus_available", milvusAvailable,
            "embedding_configured", embeddingConfigured,
            "retrieval_mode", milvusAvailable && embeddingConfigured ? "MILVUS_HYBRID" : "KEYWORD_ONLY"
        );
    }

    @PostMapping("/traces/{traceId}/adoption")
    public Map<String, Object> markAdoption(
        @org.springframework.web.bind.annotation.PathVariable String traceId,
        @org.springframework.web.bind.annotation.RequestParam boolean adopted
    ) {
        traceService.markAdopted(traceId, adopted);
        return Map.of("trace_id", traceId, "adopted", adopted);
    }
}
