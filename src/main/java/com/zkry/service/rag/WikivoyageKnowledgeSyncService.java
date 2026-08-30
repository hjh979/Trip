package com.zkry.service.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.domain.dto.knowledge.IndexKnowledgeDocumentRequest;
import com.zkry.domain.dto.knowledge.SyncTravelCorpusRequest;
import com.zkry.domain.entity.KnowledgeDocument;
import com.zkry.domain.entity.KnowledgeSource;
import com.zkry.domain.vo.KnowledgeDocumentView;
import com.zkry.domain.vo.TravelCorpusSyncView;
import com.zkry.mapper.KnowledgeChunkMapper;
import com.zkry.mapper.KnowledgeDocumentMapper;
import com.zkry.mapper.KnowledgeSourceMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 通过 Wikimedia 官方 MediaWiki API 同步中文维基导游城市条目。
 *
 * <p>固定访问受信任的 API 地址，不接受任意抓取 URL，避免 SSRF。所有文档保留
 * 原始条目链接和 CC BY-SA 4.0 授权元数据，可重复同步且不会重复建文档。
 */
@Service
public class WikivoyageKnowledgeSyncService {

    private static final Logger log = LoggerFactory.getLogger(WikivoyageKnowledgeSyncService.class);
    private static final String SOURCE_NAME = "维基导游开放旅行知识";
    private static final String API_BASE_URL = "https://zh.wikivoyage.org";
    private static final String API_ENDPOINT = API_BASE_URL + "/w/api.php";
    private static final int MAX_CITIES = 50;
    private static final int MAX_CONTENT_CHARS = 30_000;
    private static final List<String> DEFAULT_CITIES = List.of(
        "北京", "上海", "天津", "重庆", "香港", "澳门",
        "杭州", "南京", "苏州", "西安", "成都", "广州", "深圳",
        "武汉", "长沙", "青岛", "厦门", "昆明", "大理", "丽江",
        "桂林", "三亚", "哈尔滨", "石家庄", "秦皇岛", "承德",
        "河北", "河南", "浙江", "云南", "福州", "泉州", "南宁",
        "贵阳", "拉萨", "乌鲁木齐", "西宁", "兰州", "银川", "呼和浩特",
        "济南", "洛阳", "开封", "大同", "平遥", "张家界", "九寨沟",
        "西双版纳", "黄山", "武夷山"
    );

    private final KnowledgeSourceMapper sourceMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeIngestionService ingestionService;
    private final RestClient client;
    private long nextFetchNanos;

    public WikivoyageKnowledgeSyncService(
        KnowledgeSourceMapper sourceMapper,
        KnowledgeDocumentMapper documentMapper,
        KnowledgeChunkMapper chunkMapper,
        KnowledgeIngestionService ingestionService
    ) {
        this.sourceMapper = sourceMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.ingestionService = ingestionService;
        this.client = RestClient.builder()
            .baseUrl(API_BASE_URL)
            .defaultHeader(HttpHeaders.ACCEPT, "application/json")
            .defaultHeader(HttpHeaders.USER_AGENT, "VoyageMind/1.0 (travel RAG knowledge sync)")
            .build();
    }

    public TravelCorpusSyncView sync(SyncTravelCorpusRequest request) {
        return sync(request, (completed, total, city) -> {
        });
    }

    public TravelCorpusSyncView sync(
        SyncTravelCorpusRequest request,
        ProgressListener progressListener
    ) {
        List<String> cities = normalizeCities(request == null ? List.of() : request.cities());
        boolean refresh = request == null || request.refresh_existing() == null || request.refresh_existing();
        KnowledgeSource source = requireSource();
        int fetched = 0;
        int indexed = 0;
        int keywordOnly = 0;
        int unchanged = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        source.setStatus("SYNCING");
        sourceMapper.updateById(source);
        for (int index = 0; index < cities.size(); index++) {
            String city = cities.get(index);
            String externalId = externalId(city);
            try {
                if (!refresh && documentExists(source.getId(), externalId)) {
                    unchanged++;
                    continue;
                }
                awaitFetchSlot();
                PageExtract page = fetch(city);
                fetched++;
                KnowledgeDocumentView result = ingestionService.index(new IndexKnowledgeDocumentRequest(
                    source.getId(),
                    externalId,
                    city + "旅行指南（维基导游）",
                    page.url(),
                    licensedContent(page.content(), page.url()),
                    "PUBLIC",
                    Map.of(
                        "city", city,
                        "topics", List.of("城市概览", "交通", "景点", "住宿", "餐饮", "购物", "安全"),
                        "provider", "Wikivoyage",
                        "license", "CC BY-SA 4.0",
                        "language", "zh-CN"
                    )
                ));
                if (result.message().contains("未变化") || result.message().contains("未重复")) unchanged++;
                else if (result.vector_indexed()) indexed++;
                else keywordOnly++;
            } catch (Exception ex) {
                failed++;
                String reason = city + "：" + safeMessage(ex);
                errors.add(reason);
                log.warn("[RAG-CORPUS] Wikivoyage sync failed city={} reason={}", city, ex.getMessage());
            } finally {
                progressListener.onProgress(index + 1, cities.size(), city);
            }
        }

        int documentCount = Math.toIntExact(documentMapper.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
            .eq(KnowledgeDocument::getSourceId, source.getId())));
        int chunkCount = countChunks(source.getId());
        source.setDocumentCount(documentCount);
        source.setLastSyncAt(LocalDateTime.now());
        source.setStatus(failed == cities.size() ? "ERROR" : "READY");
        sourceMapper.updateById(source);
        log.info("[RAG-CORPUS] Wikivoyage sync completed requested={} fetched={} indexed={} keywordOnly={} unchanged={} failed={} documents={} chunks={}",
            cities.size(), fetched, indexed, keywordOnly, unchanged, failed, documentCount, chunkCount);
        return new TravelCorpusSyncView(source.getId(), cities.size(), fetched, indexed, keywordOnly,
            unchanged, failed, documentCount, chunkCount, errors.stream().limit(20).toList());
    }

    public List<String> defaultCities() {
        return DEFAULT_CITIES;
    }

    private PageExtract fetch(String city) {
        Map<?, ?> body = client.get().uri(uri -> uri.path("/w/api.php")
                .queryParam("action", "query")
                .queryParam("prop", "extracts|info")
                .queryParam("inprop", "url")
                .queryParam("explaintext", "1")
                .queryParam("exsectionformat", "plain")
                .queryParam("redirects", "1")
                .queryParam("format", "json")
                .queryParam("formatversion", "2")
                .queryParam("titles", city)
                .build())
            .retrieve().body(Map.class);
        if (body == null || !(body.get("query") instanceof Map<?, ?> query)
            || !(query.get("pages") instanceof List<?> pages) || pages.isEmpty()
            || !(pages.getFirst() instanceof Map<?, ?> page)) {
            throw new BizException("MediaWiki API 未返回页面。");
        }
        if (Boolean.TRUE.equals(page.get("missing"))) throw new BizException("维基导游不存在该城市条目。");
        String content = clean(text(page.get("extract")));
        if (content.length() < 120) throw new BizException("页面正文过短，未入库。");
        String url = text(page.get("fullurl"));
        if (url.isBlank()) {
            url = API_BASE_URL + "/wiki/" + URLEncoder.encode(city, StandardCharsets.UTF_8).replace("+", "%20");
        }
        return new PageExtract(city, url, truncateContent(content));
    }

    /**
     * A process-local crawl budget protects the upstream API even when background workers or an
     * administrator trigger synchronization concurrently.
     */
    private synchronized void awaitFetchSlot() {
        long now = System.nanoTime();
        long remaining = nextFetchNanos - now;
        if (remaining > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(remaining);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Knowledge synchronization was interrupted", ex);
            }
        }
        nextFetchNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
    }

    private KnowledgeSource requireSource() {
        KnowledgeSource source = sourceMapper.selectOne(Wrappers.<KnowledgeSource>lambdaQuery()
            .eq(KnowledgeSource::getName, SOURCE_NAME).last("LIMIT 1"));
        if (source != null) return source;
        source = new KnowledgeSource();
        source.setName(SOURCE_NAME);
        source.setSourceType("WEB");
        source.setEndpoint(API_ENDPOINT);
        source.setStatus("READY");
        source.setDocumentCount(0);
        source.setDescription("中文维基导游城市条目，使用 MediaWiki API 同步，CC BY-SA 4.0");
        sourceMapper.insert(source);
        return source;
    }

    private List<String> normalizeCities(List<String> requested) {
        List<String> source = requested == null || requested.isEmpty() ? DEFAULT_CITIES : requested;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String raw : source) {
            String city = raw == null ? "" : raw.trim();
            if (city.length() >= 2 && city.length() <= 30) values.add(city);
            if (values.size() >= MAX_CITIES) break;
        }
        if (values.isEmpty()) throw new BizException("至少提供一个有效城市。");
        return List.copyOf(values);
    }

    private boolean documentExists(Long sourceId, String externalId) {
        return documentMapper.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
            .eq(KnowledgeDocument::getSourceId, sourceId)
            .eq(KnowledgeDocument::getExternalId, externalId)) > 0;
    }

    private int countChunks(Long sourceId) {
        List<Long> documentIds = documentMapper.selectList(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getSourceId, sourceId))
            .stream().map(KnowledgeDocument::getId).toList();
        if (documentIds.isEmpty()) return 0;
        return Math.toIntExact(chunkMapper.selectCount(Wrappers.<com.zkry.domain.entity.KnowledgeChunk>lambdaQuery()
            .in(com.zkry.domain.entity.KnowledgeChunk::getDocumentId, documentIds)));
    }

    private String externalId(String city) {
        return "wikivoyage:zh:" + city;
    }

    private String licensedContent(String content, String url) {
        return content + "\n\n资料来源：中文维基导游（" + url
            + "）。文字依据 CC BY-SA 4.0 授权同步；内容可能随社区编辑更新。";
    }

    private String truncateContent(String value) {
        if (value.length() <= MAX_CONTENT_CHARS) return value;
        int boundary = value.lastIndexOf('\n', MAX_CONTENT_CHARS);
        return value.substring(0, boundary > MAX_CONTENT_CHARS / 2 ? boundary : MAX_CONTENT_CHARS).trim();
    }

    private String clean(String value) {
        return value.replace("\r\n", "\n")
            .replaceAll("(?m)^[ \\t]+", "")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private record PageExtract(String title, String url, String content) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int completed, int total, String city);
    }
}
