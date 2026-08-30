package com.zkry.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.domain.dto.knowledge.RecallBenchmarkCase;
import com.zkry.domain.dto.knowledge.RecallBenchmarkRequest;
import com.zkry.domain.dto.knowledge.SyncTravelCorpusRequest;
import com.zkry.domain.entity.KnowledgeChunk;
import com.zkry.domain.entity.KnowledgeDocument;
import com.zkry.domain.vo.RecallBenchmarkView;
import com.zkry.domain.vo.TravelCorpusSyncView;
import com.zkry.domain.vo.SubmitTripPlanResponse;
import com.zkry.mapper.KnowledgeChunkMapper;
import com.zkry.mapper.KnowledgeDocumentMapper;
import com.zkry.service.rag.KnowledgeRecallBenchmarkService;
import com.zkry.service.rag.KnowledgeMetadataBackfillService;
import com.zkry.service.rag.WikivoyageKnowledgeSyncService;
import com.zkry.task.KnowledgeCorpusSyncTaskService;
import com.zkry.security.VoyagePrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeCorpusAdminController {

    private final WikivoyageKnowledgeSyncService corpusSyncService;
    private final KnowledgeRecallBenchmarkService benchmarkService;
    private final KnowledgeMetadataBackfillService metadataBackfillService;
    private final KnowledgeCorpusSyncTaskService corpusSyncTaskService;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;

    public KnowledgeCorpusAdminController(
        WikivoyageKnowledgeSyncService corpusSyncService,
        KnowledgeRecallBenchmarkService benchmarkService,
        KnowledgeMetadataBackfillService metadataBackfillService,
        KnowledgeCorpusSyncTaskService corpusSyncTaskService,
        KnowledgeDocumentMapper documentMapper,
        KnowledgeChunkMapper chunkMapper
    ) {
        this.corpusSyncService = corpusSyncService;
        this.benchmarkService = benchmarkService;
        this.metadataBackfillService = metadataBackfillService;
        this.corpusSyncTaskService = corpusSyncTaskService;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @PostMapping("/corpus/sync")
    public TravelCorpusSyncView sync(@RequestBody(required = false) SyncTravelCorpusRequest request) {
        return corpusSyncService.sync(request);
    }

    @PostMapping("/corpus/sync/async")
    public SubmitTripPlanResponse syncAsync(
        @RequestBody(required = false) SyncTravelCorpusRequest request,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        return corpusSyncTaskService.submit(request, principal);
    }

    @GetMapping("/corpus/default-cities")
    public List<String> defaultCities() {
        return corpusSyncService.defaultCities();
    }

    @PostMapping("/recall/evaluate")
    public RecallBenchmarkView evaluate(@RequestBody(required = false) RecallBenchmarkRequest request) {
        return benchmarkService.evaluate(request);
    }

    @GetMapping("/recall/cases")
    public List<RecallBenchmarkCase> cases() {
        return benchmarkService.defaultCases();
    }

    @PostMapping("/metadata/backfill")
    public Map<String, Object> backfillMetadata() {
        return metadataBackfillService.backfill();
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        long documents = documentMapper.selectCount(null);
        long chunks = chunkMapper.selectCount(null);
        long indexedDocuments = documentMapper.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
            .eq(KnowledgeDocument::getStatus, "INDEXED"));
        long metadataChunks = chunkMapper.selectCount(Wrappers.<KnowledgeChunk>lambdaQuery()
            .ne(KnowledgeChunk::getMetadataJson, "{}")
            .isNotNull(KnowledgeChunk::getMetadataJson));
        return Map.of(
            "document_count", documents,
            "chunk_count", chunks,
            "indexed_document_count", indexedDocuments,
            "metadata_chunk_count", metadataChunks,
            "vector_coverage", percent(indexedDocuments, documents),
            "metadata_coverage", percent(metadataChunks, chunks)
        );
    }

    private double percent(long value, long total) {
        return total == 0 ? 0D : Math.round(value * 10_000D / total) / 100D;
    }
}
