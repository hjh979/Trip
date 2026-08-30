package com.zkry.task;

import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.knowledge.IndexKnowledgeDocumentRequest;
import com.zkry.domain.dto.knowledge.SyncTravelCorpusRequest;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.vo.KnowledgeDocumentView;
import com.zkry.domain.vo.TravelCorpusSyncView;
import com.zkry.service.rag.KnowledgeIngestionService;
import com.zkry.service.rag.WikivoyageKnowledgeSyncService;
import com.zkry.service.planning.RAGContextCache;
import org.springframework.stereotype.Service;

/** Executes knowledge work with explicit prepare/vectorize/finalize checkpoints. */
@Service
public class KnowledgeIngestionWorkflow {
    public static final String RESULT_CHECKPOINT = "knowledge.ingestion.result";
    private static final String PREPARE_CHECKPOINT = "knowledge.prepare";
    private static final String VECTORIZE_CHECKPOINT = "knowledge.vectorize";
    private static final String FINALIZE_CHECKPOINT = "knowledge.finalize";

    private final TripTaskStore taskStore;
    private final TaskCheckpointService checkpoints;
    private final KnowledgeIngestionService ingestionService;
    private final WikivoyageKnowledgeSyncService corpusSyncService;
    private final TaskRealtimePublisher realtimePublisher;
    private final RAGContextCache ragContextCache;

    public KnowledgeIngestionWorkflow(
        TripTaskStore taskStore,
        TaskCheckpointService checkpoints,
        KnowledgeIngestionService ingestionService,
        WikivoyageKnowledgeSyncService corpusSyncService,
        TaskRealtimePublisher realtimePublisher,
        RAGContextCache ragContextCache
    ) {
        this.taskStore = taskStore;
        this.checkpoints = checkpoints;
        this.ingestionService = ingestionService;
        this.corpusSyncService = corpusSyncService;
        this.realtimePublisher = realtimePublisher;
        this.ragContextCache = ragContextCache;
    }

    public TripTask execute(TripTask task, String token) {
        if (TaskType.KNOWLEDGE_CORPUS_SYNC.equals(task.getTaskType())) return executeCorpusSync(task, token);
        IndexKnowledgeDocumentRequest request = JsonUtils.parseObject(task.getRequestJson(), IndexKnowledgeDocumentRequest.class);
        KnowledgeDocumentView result = checkpoints.load(task.getTaskId(), RESULT_CHECKPOINT, KnowledgeDocumentView.class)
            .orElseGet(() -> ingestDocument(task, token, request));
        TripTask completed = taskStore.completeGeneric(task.getTaskId(), token, String.valueOf(result.id()), 1,
            "/api/knowledge/documents/" + result.id(), "知识文档索引已完成");
        realtimePublisher.publish(completed);
        return completed;
    }

    private KnowledgeDocumentView ingestDocument(TripTask task, String token, IndexKnowledgeDocumentRequest request) {
        publish(task, token, "knowledge_prepare", 15, "正在准备文档和分块");
        KnowledgeIngestionService.PreparedDocument prepared = checkpoints
            .load(task.getTaskId(), PREPARE_CHECKPOINT, KnowledgeIngestionService.PreparedDocument.class)
            .orElseGet(() -> {
                KnowledgeIngestionService.PreparedDocument value = ingestionService.prepare(request);
                checkpoints.save(task.getTaskId(), PREPARE_CHECKPOINT, value);
                return value;
            });
        publish(task, token, "knowledge_vectorize", 45, "正在调用 Embedding/Milvus");
        KnowledgeIngestionService.VectorizationResult vectors = checkpoints
            .load(task.getTaskId(), VECTORIZE_CHECKPOINT, KnowledgeIngestionService.VectorizationResult.class)
            .orElseGet(() -> {
                KnowledgeIngestionService.VectorizationResult value = ingestionService.vectorize(prepared);
                checkpoints.save(task.getTaskId(), VECTORIZE_CHECKPOINT, value);
                return value;
            });
        publish(task, token, "knowledge_finalize", 80, "正在提交向量引用和文档状态");
        KnowledgeDocumentView result = checkpoints.load(task.getTaskId(), FINALIZE_CHECKPOINT, KnowledgeDocumentView.class)
            .orElseGet(() -> {
                KnowledgeDocumentView value = ingestionService.finalizeIndex(prepared, vectors);
                ragContextCache.bumpCorpusRevision();
                checkpoints.save(task.getTaskId(), FINALIZE_CHECKPOINT, value);
                return value;
            });
        checkpoints.save(task.getTaskId(), RESULT_CHECKPOINT, result);
        return result;
    }

    private void publish(TripTask task, String token, String stage, int progress, String message) {
        realtimePublisher.publish(taskStore.progress(task.getTaskId(), token, stage, progress, message));
    }

    private TripTask executeCorpusSync(TripTask task, String token) {
        SyncTravelCorpusRequest request = JsonUtils.parseObject(task.getRequestJson(), SyncTravelCorpusRequest.class);
        publish(task, token, "knowledge_crawling", 10, "正在同步可信开放旅行语料");
        TravelCorpusSyncView result = checkpoints.load(task.getTaskId(), "knowledge.corpus.result", TravelCorpusSyncView.class)
            .orElseGet(() -> {
                TravelCorpusSyncView synced = corpusSyncService.sync(request, (completed, total, city) ->
                    publish(task, token, "knowledge_crawling", 10 + completed * 75 / Math.max(1, total),
                        "已处理 " + completed + "/" + total + " 个目的地：" + city));
                checkpoints.save(task.getTaskId(), "knowledge.corpus.result", synced);
                return synced;
            });
        TripTask completed = taskStore.completeGeneric(task.getTaskId(), token, String.valueOf(result.source_id()), 1,
            "/api/admin/knowledge/metrics", "开放旅行语料同步完成：文档 " + result.document_count());
        realtimePublisher.publish(completed);
        return completed;
    }
}
