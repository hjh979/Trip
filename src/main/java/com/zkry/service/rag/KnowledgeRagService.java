package com.zkry.service.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.knowledge.RagAnswerRequest;
import com.zkry.domain.dto.knowledge.RagSearchRequest;
import com.zkry.domain.entity.KnowledgeChunk;
import com.zkry.domain.entity.KnowledgeDocument;
import com.zkry.domain.entity.KnowledgeSource;
import com.zkry.domain.vo.RagAnswerView;
import com.zkry.domain.vo.RagCitationView;
import com.zkry.domain.vo.RagSearchView;
import com.zkry.integration.ai.service.AiTextService;
import com.zkry.mapper.KnowledgeChunkMapper;
import com.zkry.mapper.KnowledgeDocumentMapper;
import com.zkry.mapper.KnowledgeSourceMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRagService {

    private static final Pattern QUOTED_TERM = Pattern.compile("[“\\\"]([^”\\\"]{2,40})[”\\\"]");
    private static final Pattern CITY_BEFORE_QUOTE = Pattern.compile("为([^，。；;\\s]{2,12})的[“\\\"]");

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeSourceMapper sourceMapper;
    private final EmbeddingService embeddingService;
    private final MilvusVectorStoreService vectorStore;
    private final AiTextService aiTextService;
    private final RetrievalTraceService traceService;

    public KnowledgeRagService(
        KnowledgeChunkMapper chunkMapper,
        KnowledgeDocumentMapper documentMapper,
        KnowledgeSourceMapper sourceMapper,
        EmbeddingService embeddingService,
        MilvusVectorStoreService vectorStore,
        AiTextService aiTextService,
        RetrievalTraceService traceService
    ) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.sourceMapper = sourceMapper;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.aiTextService = aiTextService;
        this.traceService = traceService;
    }

    public RagSearchView search(RagSearchRequest request) {
        long startedAt = System.currentTimeMillis();
        String query = request == null || request.query() == null ? "" : request.query().trim();
        if (query.isBlank()) throw new BizException("检索问题不能为空。");
        int topK = Math.max(1, Math.min(request.top_k() == null ? 6 : request.top_k(), 20));
        List<Long> sourceIds = request.source_ids() == null ? List.of() : request.source_ids();
        String city = optional(request.city());
        String placeName = optional(request.place_name());
        List<String> topics = request.topics() == null ? List.of() : request.topics().stream()
            .map(this::optional).filter(value -> !value.isBlank()).distinct().limit(8).toList();
        boolean embeddingConfigured = embeddingService.isConfigured();
        boolean milvusAvailable = vectorStore.isAvailable();
        int candidateLimit = Math.min(60, topK * 4);

        List<RagCitationView> vectorCandidates = new ArrayList<>();
        if (embeddingConfigured && milvusAvailable) {
            embeddingService.embed(List.of(query)).ifPresent(vectors -> vectorStore
                .search(vectors.getFirst(), candidateLimit, sourceIds, city)
                .stream()
                .filter(hit -> city.isBlank() || city.equals(optional(text(hit.fields().get("city")))))
                .map(this::fromVector)
                .filter(this::hasUsableContent)
                .forEach(vectorCandidates::add));
        }
        List<RagCitationView> keywordCandidates = keywordSearch(query, candidateLimit, sourceIds, city);
        List<RagCitationView> citations = rrfFuse(vectorCandidates, keywordCandidates).stream()
            .filter(this::hasUsableContent)
            .map(this::normalizeCitation)
            .map(citation -> rerank(citation, query, city, placeName, topics))
            .sorted(Comparator.comparingDouble(RagCitationView::score).reversed())
            .limit(topK)
            .toList();
        String mode = !vectorCandidates.isEmpty()
            ? "MILVUS_HYBRID" : "KEYWORD_ONLY";
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("source_ids", sourceIds);
        filters.put("city", city);
        filters.put("place_name", placeName);
        filters.put("topics", topics);
        filters.put("top_k", topK);
        String traceId = traceService.save(
            query,
            filters,
            mode,
            Map.of("dense", vectorCandidates, "sparse", keywordCandidates),
            citations,
            System.currentTimeMillis() - startedAt
        );
        return new RagSearchView(query, embeddingConfigured, milvusAvailable, mode, citations, traceId);
    }

    /**
     * Reciprocal Rank Fusion makes dense and keyword ranks comparable without pretending their
     * raw similarity scores share a scale.
     */
    private List<RagCitationView> rrfFuse(
        List<RagCitationView> dense,
        List<RagCitationView> sparse
    ) {
        final double rrfK = 60D;
        Map<Long, FusionCandidate> fused = new LinkedHashMap<>();
        addRanking(fused, dense, rrfK, "MILVUS_VECTOR");
        addRanking(fused, sparse, rrfK, "KEYWORD");
        return fused.values().stream()
            .sorted(Comparator.comparingDouble(FusionCandidate::score).reversed())
            .map(candidate -> new RagCitationView(
                candidate.citation().chunk_id(),
                candidate.citation().document_id(),
                candidate.citation().source_id(),
                candidate.citation().source_name(),
                candidate.citation().title(),
                candidate.citation().source_url(),
                candidate.citation().content(),
                candidate.score(),
                String.join("+", candidate.methods())
            ))
            .toList();
    }

    private void addRanking(
        Map<Long, FusionCandidate> fused,
        List<RagCitationView> ranking,
        double rrfK,
        String method
    ) {
        for (int index = 0; index < ranking.size(); index++) {
            RagCitationView citation = ranking.get(index);
            FusionCandidate candidate = fused.computeIfAbsent(
                citation.chunk_id(),
                ignored -> new FusionCandidate(citation)
            );
            candidate.score += 1D / (rrfK + index + 1D);
            candidate.methods.add(method);
        }
    }

    public RagAnswerView answer(RagAnswerRequest request) {
        String question = request == null ? "" : request.question();
        RagSearchView search = search(new RagSearchRequest(question, request == null ? null : request.top_k(),
            request == null ? List.of() : request.source_ids(),
            request == null ? "" : request.city(),
            request == null ? "" : request.place_name(),
            request == null ? List.of() : request.topics()));
        if (search.citations().isEmpty()) {
            return new RagAnswerView(question, "当前知识库没有检索到可引用的内容，请先同步或导入资料。",
                search.retrieval_mode(), false, List.of());
        }
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < search.citations().size(); index++) {
            RagCitationView item = search.citations().get(index);
            context.append("[").append(index + 1).append("] ").append(item.source_name())
                .append(" / ").append(item.title()).append("\n").append(item.content()).append("\n\n");
        }
        String system = "你是旅策 VoyageMind 的旅行知识助手。只能依据给定证据回答；每个事实后用 [序号] 标注来源。证据不足时必须明确说明，不得编造。";
        String user = "问题：" + question + "\n\n可用证据：\n" + context;
        Optional<String> generated = aiTextService.generate(system, user);
        String answer = generated.orElseGet(() -> "已找到 " + search.citations().size()
            + " 条相关证据，但当前生成模型不可用。请查看下方引用内容。" );
        return new RagAnswerView(question, answer, search.retrieval_mode(), generated.isPresent(), search.citations());
    }

    public String plannerContext(String query, int topK) {
        return plannerContext(query, topK, "");
    }

    public String plannerContext(String query, int topK, String city) {
        RagSearchView result = search(new RagSearchRequest(query, topK, List.of(), city, "", List.of()));
        if (result.citations().isEmpty()) {
            return "知识库未检索到相关证据；不得因此编造地点事实。";
        }
        StringBuilder context = new StringBuilder("检索模式：").append(result.retrieval_mode()).append("\n");
        for (int index = 0; index < result.citations().size(); index++) {
            RagCitationView item = result.citations().get(index);
            context.append("[RAG-").append(index + 1).append("] 来源=").append(item.source_name())
                .append("；标题=").append(item.title()).append("；内容=").append(item.content()).append("\n");
        }
        return context.toString();
    }

    /**
     * Shared by planning and conversational editing. The trace and citations let a conversation
     * turn remain observable instead of hiding retrieval inside prompt construction.
     */
    public GroundingContext groundingContext(String query, int topK, String city) {
        RagSearchView result = search(new RagSearchRequest(query, topK, List.of(), city, "", List.of()));
        if (result.citations().isEmpty()) {
            return new GroundingContext(
                "知识库未检索到相关证据；不得因此编造地点事实。",
                result.trace_id(),
                List.of()
            );
        }
        StringBuilder context = new StringBuilder("检索模式：")
            .append(result.retrieval_mode())
            .append('\n');
        for (int index = 0; index < result.citations().size(); index++) {
            RagCitationView item = result.citations().get(index);
            context.append("[RAG-").append(index + 1)
                .append("] 来源=").append(item.source_name())
                .append("；标题=").append(item.title())
                .append("；内容=").append(item.content())
                .append('\n');
        }
        return new GroundingContext(context.toString(), result.trace_id(), result.citations());
    }

    private List<RagCitationView> keywordSearch(String query, int limit, List<Long> sourceIds, String city) {
        List<String> terms = extractSearchTerms(query);
        var wrapper = Wrappers.<KnowledgeChunk>lambdaQuery();
        if (terms.isEmpty()) {
            wrapper.like(KnowledgeChunk::getContent, query);
        } else {
            wrapper.and(group -> {
                boolean first = true;
                for (String term : terms) {
                    if (first) group.like(KnowledgeChunk::getContent, term);
                    else group.or().like(KnowledgeChunk::getContent, term);
                    first = false;
                }
            });
        }
        if (!city.isBlank()) {
            wrapper.and(group -> group.like(KnowledgeChunk::getMetadataJson, "\"city\":\"" + city + "\"")
                .or().like(KnowledgeChunk::getKeywords, city));
        }
        wrapper.orderByDesc(KnowledgeChunk::getUpdateTime).last("LIMIT " + limit * 3);
        List<KnowledgeChunk> chunks = chunkMapper.selectList(wrapper);
        List<RagCitationView> result = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            if (!city.isBlank() && !city.equals(metadataCity(chunk))) continue;
            KnowledgeDocument document = documentMapper.selectById(chunk.getDocumentId());
            if (document == null || (!sourceIds.isEmpty() && !sourceIds.contains(document.getSourceId()))) continue;
            String documentStatus = document.getStatus();
            if (documentStatus != null && !documentStatus.isBlank()
                && !"INDEXED".equals(documentStatus) && !"KEYWORD_ONLY".equals(documentStatus)) continue;
            KnowledgeSource source = sourceMapper.selectById(document.getSourceId());
            if (source == null) continue;
            String sourceStatus = source.getStatus();
            if (sourceStatus != null && !sourceStatus.isBlank() && !"READY".equals(sourceStatus)) continue;
            result.add(new RagCitationView(chunk.getId(), document.getId(), source.getId(), source.getName(),
                document.getTitle(), document.getSourceUrl(), chunk.getContent(), 0.35D, "KEYWORD"));
            if (result.size() >= limit) break;
        }
        return result;
    }

    private RagCitationView fromVector(MilvusVectorStoreService.VectorHit hit) {
        Map<String, Object> fields = hit.fields();
        return new RagCitationView(hit.id(), number(fields.get("document_id")), number(fields.get("source_id")),
            text(fields.get("source_name")), text(fields.get("title")), text(fields.get("source_url")),
            text(fields.get("content")), hit.score(), "MILVUS_VECTOR");
    }

    private List<String> extractSearchTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher quoted = QUOTED_TERM.matcher(query);
        while (quoted.find() && terms.size() < 8) terms.add(quoted.group(1).trim());
        Matcher city = CITY_BEFORE_QUOTE.matcher(query);
        if (city.find()) terms.add(city.group(1).trim());
        for (String part : query.split("[\\s,，。；;、：:（）()【】]+")) {
            String term = part.trim();
            if (term.length() >= 2 && term.length() <= 24) terms.add(term);
            if (terms.size() >= 8) break;
        }
        return terms.stream().filter(value -> !value.isBlank()).limit(8).toList();
    }

    private boolean hasUsableContent(RagCitationView citation) {
        return isUsableText(citation.content(), 4);
    }

    private RagCitationView normalizeCitation(RagCitationView citation) {
        String title = isUsableText(citation.title(), 2) ? citation.title() : citation.source_name() + "资料";
        return new RagCitationView(citation.chunk_id(), citation.document_id(), citation.source_id(),
            citation.source_name(), title, citation.source_url(), citation.content(), citation.score(),
            citation.retrieval_method());
    }

    private RagCitationView rerank(
        RagCitationView citation,
        String query,
        String city,
        String placeName,
        List<String> topics
    ) {
        String haystack = (citation.title() + " " + citation.content()).toLowerCase();
        double score = citation.score();
        long matchedTerms = extractSearchTerms(query).stream()
            .map(String::toLowerCase).filter(haystack::contains).distinct().count();
        score += Math.min(0.48D, matchedTerms * 0.08D);
        if (!city.isBlank() && haystack.contains(city.toLowerCase())) score += 0.35D;
        if (!placeName.isBlank() && haystack.contains(placeName.toLowerCase())) score += 0.45D;
        score += topics.stream().map(String::toLowerCase).filter(haystack::contains).distinct().count() * 0.06D;
        return new RagCitationView(citation.chunk_id(), citation.document_id(), citation.source_id(),
            citation.source_name(), citation.title(), citation.source_url(), citation.content(), score,
            citation.retrieval_method());
    }

    private String metadataCity(KnowledgeChunk chunk) {
        if (chunk == null || chunk.getMetadataJson() == null || chunk.getMetadataJson().isBlank()) return "";
        try {
            Map<String, Object> metadata = JsonUtils.parseMap(chunk.getMetadataJson());
            return metadata == null ? "" : optional(text(metadata.get("city")));
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isUsableText(String value, int minimumVisibleCharacters) {
        if (value == null || value.isBlank()) return false;
        long visible = value.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();
        long replacement = value.codePoints().filter(codePoint -> codePoint == '?' || codePoint == '？'
            || codePoint == 0xFFFD).count();
        return visible >= minimumVisibleCharacters && replacement * 4 < visible;
    }

    private long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String optional(String value) { return value == null ? "" : value.trim(); }

    public record GroundingContext(
        String content,
        String traceId,
        List<RagCitationView> citations
    ) {
    }

    private static final class FusionCandidate {
        private final RagCitationView citation;
        private final Set<String> methods = new LinkedHashSet<>();
        private double score;

        private FusionCandidate(RagCitationView citation) {
            this.citation = citation;
        }

        private RagCitationView citation() { return citation; }
        private Set<String> methods() { return methods; }
        private double score() { return score; }
    }
}
