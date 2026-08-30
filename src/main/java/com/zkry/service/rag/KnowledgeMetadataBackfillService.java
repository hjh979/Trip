package com.zkry.service.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.domain.dto.knowledge.IndexKnowledgeDocumentRequest;
import com.zkry.domain.entity.KnowledgeDocument;
import com.zkry.domain.entity.KnowledgeSource;
import com.zkry.domain.vo.KnowledgeDocumentView;
import com.zkry.mapper.KnowledgeDocumentMapper;
import com.zkry.mapper.KnowledgeSourceMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 为早期没有 metadata_json 的知识文档补齐城市和主题，并触发重新向量化。
 */
@Service
public class KnowledgeMetadataBackfillService {

    private static final List<String> KNOWN_REGIONS = List.of(
        "北京", "上海", "天津", "重庆", "香港", "澳门", "杭州", "南京", "苏州",
        "西安", "成都", "广州", "深圳", "武汉", "长沙", "青岛", "厦门", "昆明",
        "大理", "丽江", "桂林", "三亚", "哈尔滨", "石家庄", "秦皇岛", "承德",
        "河北", "河南", "浙江", "云南"
    );

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeSourceMapper sourceMapper;
    private final KnowledgeIngestionService ingestionService;

    public KnowledgeMetadataBackfillService(
        KnowledgeDocumentMapper documentMapper,
        KnowledgeSourceMapper sourceMapper,
        KnowledgeIngestionService ingestionService
    ) {
        this.documentMapper = documentMapper;
        this.sourceMapper = sourceMapper;
        this.ingestionService = ingestionService;
    }

    public Map<String, Object> backfill() {
        List<KnowledgeDocument> documents = documentMapper.selectList(Wrappers.<KnowledgeDocument>lambdaQuery()
            .in(KnowledgeDocument::getStatus, List.of("INDEXED", "KEYWORD_ONLY"))
            .orderByAsc(KnowledgeDocument::getId));
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            KnowledgeSource source = sourceMapper.selectById(document.getSourceId());
            if (source == null || document.getContent() == null || document.getContent().isBlank()) continue;
            try {
                String city = inferCity(document);
                if (city.isBlank()) {
                    unchanged++;
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("city", city);
                String placeName = inferPlaceName(document.getTitle(), city);
                if (!placeName.isBlank()) metadata.put("place_name", placeName);
                metadata.put("topics", topics(source.getSourceType()));
                metadata.put("provider", source.getName());
                metadata.put("language", "zh-CN");
                KnowledgeDocumentView result = ingestionService.index(new IndexKnowledgeDocumentRequest(
                    source.getId(), document.getExternalId(), document.getTitle(), document.getSourceUrl(),
                    document.getContent(), document.getVisibility(), metadata
                ));
                if (result.message().contains("未变化")) unchanged++;
                else updated++;
            } catch (Exception ex) {
                failed++;
                errors.add(document.getTitle() + "：" + safeMessage(ex));
            }
        }
        return Map.of(
            "documents", documents.size(),
            "updated", updated,
            "unchanged", unchanged,
            "failed", failed,
            "errors", errors.stream().limit(20).toList()
        );
    }

    private String inferCity(KnowledgeDocument document) {
        String title = text(document.getTitle());
        int separator = title.indexOf(" ·");
        if (separator > 0) {
            String prefix = title.substring(0, separator).trim();
            if (prefix.length() >= 2 && prefix.length() <= 12) return prefix;
        }
        String haystack = title + " " + text(document.getContent()).substring(
            0, Math.min(800, text(document.getContent()).length())
        );
        return KNOWN_REGIONS.stream().filter(haystack::contains).findFirst().orElse("");
    }

    private String inferPlaceName(String title, String city) {
        String value = text(title);
        String prefix = city + " ·";
        if (!value.startsWith(prefix)) return "";
        String place = value.substring(prefix.length())
            .replace("游玩攻略", "")
            .replace("旅行指南（维基导游）", "")
            .trim();
        return place.length() > 120 ? place.substring(0, 120) : place;
    }

    private List<String> topics(String sourceType) {
        return switch (text(sourceType)) {
            case "AMAP" -> List.of("地图", "POI", "交通", "路线");
            case "OFFICIAL" -> List.of("官方资料", "开放时间", "预约", "公共交通");
            default -> List.of("城市概览", "景点", "交通", "餐饮");
        };
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
