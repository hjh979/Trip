package com.zkry.service.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.knowledge.IndexKnowledgeDocumentRequest;
import com.zkry.domain.entity.KnowledgeChunk;
import com.zkry.domain.entity.KnowledgeDocument;
import com.zkry.domain.entity.KnowledgeSource;
import com.zkry.domain.vo.KnowledgeDocumentView;
import com.zkry.mapper.KnowledgeChunkMapper;
import com.zkry.mapper.KnowledgeDocumentMapper;
import com.zkry.mapper.KnowledgeSourceMapper;
import com.zkry.service.rag.MilvusVectorStoreService.VectorPoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Staged knowledge ingestion: small DB transactions surround external vector calls. */
@Service
public class KnowledgeIngestionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private static final int CHUNK_SIZE = 650;
    private static final int CHUNK_OVERLAP = 100;

    private final KnowledgeSourceMapper sourceMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;
    private final MilvusVectorStoreService vectorStore;
    private final TransactionTemplate transaction;

    public KnowledgeIngestionService(
        KnowledgeSourceMapper sourceMapper,
        KnowledgeDocumentMapper documentMapper,
        KnowledgeChunkMapper chunkMapper,
        EmbeddingService embeddingService,
        MilvusVectorStoreService vectorStore,
        PlatformTransactionManager transactionManager
    ) {
        this.sourceMapper = sourceMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Compatibility facade used by the task workflow; each stage has its own boundary. */
    public KnowledgeDocumentView index(IndexKnowledgeDocumentRequest request) {
        PreparedDocument prepared = prepare(request);
        if (prepared.skippedView() != null) return prepared.skippedView();
        VectorizationResult vectors = vectorize(prepared);
        KnowledgeDocumentView result = finalizeIndex(prepared, vectors);
        if (vectors.indexed() && !prepared.oldVectorIds().isEmpty()) {
            try { vectorStore.deleteByIds(prepared.oldVectorIds()); }
            catch (RuntimeException ex) { log.warn("[RAG] old vector cleanup deferred documentId={} reason={}", prepared.document().getId(), ex.getMessage()); }
        }
        return result;
    }

    /** Prepare document/chunk rows in a short transaction; no Embedding/Milvus call is made. */
    public PreparedDocument prepare(IndexKnowledgeDocumentRequest request) {
        return transaction.execute(status -> prepareDb(request));
    }

    /** Vectorize outside a DB transaction. The result is checkpoint-safe and retryable. */
    public VectorizationResult vectorize(PreparedDocument prepared) {
        if (prepared == null || prepared.skippedView() != null || prepared.chunks().isEmpty()
            || !embeddingService.isConfigured() || !vectorStore.isAvailable()) {
            return new VectorizationResult(0, List.of(), false);
        }
        Optional<List<List<Double>>> embedded = embeddingService.embed(
            prepared.chunks().stream().map(KnowledgeChunk::getContent).toList());
        if (embedded.isEmpty() || embedded.get().size() != prepared.chunks().size()) {
            return new VectorizationResult(0, List.of(), false);
        }
        try {
            List<VectorPoint> points = new ArrayList<>();
            for (int index = 0; index < prepared.chunks().size(); index++) {
                KnowledgeChunk chunk = prepared.chunks().get(index);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("chunk_id", chunk.getId());
                payload.put("document_id", prepared.document().getId());
                payload.put("source_id", prepared.source().getId());
                payload.put("source_name", prepared.source().getName());
                payload.put("title", prepared.document().getTitle());
                payload.put("source_url", prepared.document().getSourceUrl());
                payload.put("content", chunk.getContent());
                payload.put("city", metadataText(prepared.metadata().get("city")));
                payload.put("place_name", metadataText(prepared.metadata().get("place_name")));
                payload.put("topics", metadataText(prepared.metadata().get("topics")));
                payload.put("license", metadataText(prepared.metadata().get("license")));
                points.add(new VectorPoint(chunk.getId(), embedded.get().get(index), payload));
            }
            vectorStore.upsert(points);
            return new VectorizationResult(points.size(), points.stream().map(VectorPoint::id).toList(), true);
        } catch (RuntimeException ex) {
            log.warn("[RAG] Milvus indexing failed documentId={} reason={}", prepared.document().getId(), ex.getMessage());
            return new VectorizationResult(0, List.of(), false);
        }
    }

    /** Finalize rows in a short transaction and publish the new document status. */
    public KnowledgeDocumentView finalizeIndex(PreparedDocument prepared, VectorizationResult vectors) {
        return transaction.execute(status -> finalizeDb(prepared, vectors));
    }

    private PreparedDocument prepareDb(IndexKnowledgeDocumentRequest request) {
        if (request == null || request.source_id() == null) throw new BizException("知识来源不能为空");
        KnowledgeSource source = sourceMapper.selectById(request.source_id());
        if (source == null) throw new BizException("知识来源不存在：" + request.source_id());
        String title = required(request.title(), "文档标题");
        String content = required(request.content(), "文档内容");
        String hash = sha256(content);
        String externalId = optional(request.external_id());
        Map<String, Object> metadata = normalizeMetadata(request.metadata(), source);
        String metadataJson = JsonUtils.toJsonString(metadata);
        KnowledgeDocument document = findExisting(source.getId(), externalId, hash);
        boolean created = document == null;
        if (!created && hash.equals(document.getContentHash()) && chunksMatchMetadata(document.getId(), metadataJson)
            && "INDEXED".equals(document.getStatus())) {
            int count = chunkMapper.selectCount(Wrappers.<KnowledgeChunk>lambdaQuery()
                .eq(KnowledgeChunk::getDocumentId, document.getId())).intValue();
            return PreparedDocument.skipped(new KnowledgeDocumentView(document.getId(), source.getId(), document.getTitle(),
                document.getSourceUrl(), document.getStatus(), count, count, true, "内容和元数据均未变化，跳过重复索引"));
        }
        List<Long> oldVectorIds = created ? List.of() : chunkMapper.selectList(Wrappers.<KnowledgeChunk>lambdaQuery()
            .eq(KnowledgeChunk::getDocumentId, document.getId())).stream()
            .filter(chunk -> chunk.getVectorRef() != null && !chunk.getVectorRef().isBlank())
            .map(KnowledgeChunk::getId).toList();
        if (created) {
            document = new KnowledgeDocument();
            document.setSourceId(source.getId());
            document.setExternalId(externalId);
        } else {
            chunkMapper.hardDeleteByDocumentId(document.getId());
        }
        document.setTitle(title);
        document.setSourceUrl(optional(request.source_url()));
        document.setContent(content);
        document.setContentHash(hash);
        document.setStatus("VECTOR_PENDING");
        document.setVisibility(normalizeVisibility(request.visibility()));
        if (created) documentMapper.insert(document); else documentMapper.updateById(document);
        List<String> texts = split(content);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(index);
            chunk.setContent(texts.get(index));
            chunk.setKeywords(buildKeywords(title, metadata));
            chunk.setVectorRef("");
            chunk.setMetadataJson(metadataJson);
            chunkMapper.insert(chunk);
            chunks.add(chunk);
        }
        return new PreparedDocument(source, document, chunks, metadata, oldVectorIds, created, null);
    }

    private KnowledgeDocumentView finalizeDb(PreparedDocument prepared, VectorizationResult vectors) {
        if (prepared == null || prepared.skippedView() != null) return prepared == null ? null : prepared.skippedView();
        boolean indexed = vectors != null && vectors.indexed() && vectors.vectorCount() == prepared.chunks().size();
        if (indexed) {
            for (KnowledgeChunk chunk : prepared.chunks()) {
                chunk.setVectorRef("milvus:" + chunk.getId());
                chunkMapper.updateById(chunk);
            }
        }
        prepared.document().setStatus(indexed ? "INDEXED" : "KEYWORD_ONLY");
        documentMapper.updateById(prepared.document());
        KnowledgeSource source = prepared.source();
        source.setDocumentCount(documentMapper.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
            .eq(KnowledgeDocument::getSourceId, source.getId())).intValue());
        source.setLastSyncAt(LocalDateTime.now());
        source.setStatus("READY");
        sourceMapper.updateById(source);
        return new KnowledgeDocumentView(prepared.document().getId(), source.getId(), prepared.document().getTitle(),
            prepared.document().getSourceUrl(), prepared.document().getStatus(), prepared.chunks().size(),
            indexed ? prepared.chunks().size() : 0, indexed,
            indexed ? (prepared.created() ? "文档已切分并写入 Milvus" : "文档已更新并写入 Milvus")
                : "文档已入库；Embedding 未配置或调用失败，当前使用关键词检索");
    }

    public record PreparedDocument(
        KnowledgeSource source,
        KnowledgeDocument document,
        List<KnowledgeChunk> chunks,
        Map<String, Object> metadata,
        List<Long> oldVectorIds,
        boolean created,
        KnowledgeDocumentView skippedView
    ) {
        static PreparedDocument skipped(KnowledgeDocumentView view) {
            return new PreparedDocument(null, null, List.of(), Map.of(), List.of(), false, view);
        }
    }

    public record VectorizationResult(int vectorCount, List<Long> vectorIds, boolean indexed) { }

    private KnowledgeDocument findExisting(Long sourceId, String externalId, String hash) {
        if (!externalId.isBlank()) {
            KnowledgeDocument found = documentMapper.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getSourceId, sourceId).eq(KnowledgeDocument::getExternalId, externalId).last("LIMIT 1"));
            if (found != null) return found;
        }
        return documentMapper.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
            .eq(KnowledgeDocument::getSourceId, sourceId).eq(KnowledgeDocument::getContentHash, hash).last("LIMIT 1"));
    }

    private boolean chunksMatchMetadata(Long documentId, String metadataJson) {
        List<KnowledgeChunk> chunks = chunkMapper.selectList(Wrappers.<KnowledgeChunk>lambdaQuery()
            .eq(KnowledgeChunk::getDocumentId, documentId));
        return !chunks.isEmpty() && chunks.stream().allMatch(chunk -> metadataJson.equals(optional(chunk.getMetadataJson())));
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> input, KnowledgeSource source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (input != null) {
            copyText(input, result, "city", 40); copyText(input, result, "province", 40);
            copyText(input, result, "place_name", 120); copyText(input, result, "language", 16);
            copyText(input, result, "provider", 80); copyText(input, result, "license", 80);
            Object topics = input.get("topics");
            if (topics instanceof Collection<?> values) {
                List<String> cleaned = values.stream().map(this::metadataText).filter(value -> !value.isBlank())
                    .map(value -> truncate(value, 40)).distinct().limit(12).toList();
                if (!cleaned.isEmpty()) result.put("topics", cleaned);
            } else { String value = truncate(metadataText(topics), 300); if (!value.isBlank()) result.put("topics", value); }
        }
        result.put("source_type", source.getSourceType()); result.put("source_name", source.getName());
        return result;
    }

    private void copyText(Map<String, Object> input, Map<String, Object> output, String key, int max) {
        String value = truncate(metadataText(input.get(key)), max); if (!value.isBlank()) output.put(key, value);
    }
    private String buildKeywords(String title, Map<String, Object> metadata) {
        LinkedHashSet<String> values = new LinkedHashSet<>(); values.add(title);
        for (String key : List.of("city", "province", "place_name", "topics", "source_type")) {
            String text = metadataText(metadata.get(key)); if (!text.isBlank()) values.add(text);
        }
        return truncate(String.join(",", values), 1000);
    }
    private String metadataText(Object value) {
        if (value == null) return "";
        if (value instanceof Collection<?> values) return values.stream().map(this::metadataText)
            .filter(text -> !text.isBlank()).reduce((left, right) -> left + "," + right).orElse("");
        return String.valueOf(value).trim();
    }
    private String truncate(String value, int max) { String text = value == null ? "" : value.trim(); return text.length() <= max ? text : text.substring(0, max); }
    private List<String> split(String content) {
        String text = content.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").trim(); List<String> result = new ArrayList<>(); int start = 0;
        while (start < text.length()) { int end = Math.min(start + CHUNK_SIZE, text.length()); if (end >= text.length()) { result.add(text.substring(start).trim()); break; }
            int boundary = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('\n', end)); if (boundary > start + CHUNK_SIZE / 2) end = boundary + 1;
            result.add(text.substring(start, end).trim()); start = Math.max(start + 1, end - CHUNK_OVERLAP); }
        return result.stream().filter(value -> !value.isBlank()).toList();
    }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private String normalizeVisibility(String value) { String normalized = optional(value).toUpperCase(Locale.ROOT); return List.of("PRIVATE", "TEAM", "PUBLIC").contains(normalized) ? normalized : "PRIVATE"; }
    private String required(String value, String label) { String text = optional(value); if (text.isBlank()) throw new BizException(label + "不能为空"); return text; }
    private String optional(String value) { return value == null ? "" : value.trim(); }
}
