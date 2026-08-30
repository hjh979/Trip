package com.zkry.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.domain.dto.knowledge.CreateKnowledgeSourceRequest;
import com.zkry.domain.dto.knowledge.SyncTravelCorpusRequest;
import com.zkry.domain.dto.knowledge.UpdateKnowledgeSourceRequest;
import com.zkry.domain.entity.KnowledgeDocument;
import com.zkry.domain.entity.KnowledgeSource;
import com.zkry.domain.vo.KnowledgeSourceView;
import com.zkry.mapper.KnowledgeDocumentMapper;
import com.zkry.mapper.KnowledgeSourceMapper;
import com.zkry.service.rag.WikivoyageKnowledgeSyncService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeSourceService {

    private static final List<String> SOURCE_TYPES = List.of("OFFICIAL", "WEB", "UPLOAD");
    private static final List<String> STATUSES = List.of("READY", "SYNCING", "ERROR", "DISABLED");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final KnowledgeSourceMapper mapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final WikivoyageKnowledgeSyncService wikivoyageSyncService;

    public KnowledgeSourceService(
        KnowledgeSourceMapper mapper,
        KnowledgeDocumentMapper documentMapper,
        WikivoyageKnowledgeSyncService wikivoyageSyncService
    ) {
        this.mapper = mapper;
        this.documentMapper = documentMapper;
        this.wikivoyageSyncService = wikivoyageSyncService;
    }

    public List<KnowledgeSourceView> list() {
        return mapper.selectList(Wrappers.<KnowledgeSource>lambdaQuery()
                .in(KnowledgeSource::getSourceType, SOURCE_TYPES)
                .orderByAsc(KnowledgeSource::getName))
            .stream().map(this::toView).toList();
    }

    @Transactional
    public KnowledgeSourceView create(CreateKnowledgeSourceRequest request) {
        if (request == null) throw new BizException("知识来源信息不能为空。");
        String name = required(request.name(), "来源名称");
        ensureNameAvailable(name, null);
        KnowledgeSource source = new KnowledgeSource();
        source.setName(name);
        source.setSourceType(normalizeDefault(request.source_type(), SOURCE_TYPES, "WEB", "来源类型"));
        source.setEndpoint(optional(request.endpoint()));
        source.setStatus("READY");
        source.setDocumentCount(0);
        source.setLastSyncAt(null);
        source.setDescription(optional(request.description()));
        mapper.insert(source);
        return toView(source);
    }

    @Transactional
    public KnowledgeSourceView update(Long id, UpdateKnowledgeSourceRequest request) {
        if (request == null) throw new BizException("知识来源更新信息不能为空。");
        KnowledgeSource source = getEntity(id);
        if (request.name() != null) {
            String name = required(request.name(), "来源名称");
            ensureNameAvailable(name, id);
            source.setName(name);
        }
        if (request.source_type() != null) {
            source.setSourceType(normalize(request.source_type(), SOURCE_TYPES, "来源类型"));
        }
        if (request.endpoint() != null) source.setEndpoint(optional(request.endpoint()));
        if (request.status() != null) source.setStatus(normalize(request.status(), STATUSES, "来源状态"));
        if (request.description() != null) source.setDescription(optional(request.description()));
        mapper.updateById(source);
        return toView(source);
    }

    public KnowledgeSourceView sync(Long id) {
        KnowledgeSource source = getEntity(id);
        if ("DISABLED".equals(source.getStatus())) throw new BizException("已禁用的知识来源不能同步。");
        if (source.getEndpoint() != null && source.getEndpoint().contains("zh.wikivoyage.org")) {
            wikivoyageSyncService.sync(new SyncTravelCorpusRequest(List.of(), true));
            return toView(getEntity(id));
        }
        throw new BizException("该知识来源没有配置批量同步器；"
            + "官方资料、已爬取网页与 Markdown 文档请使用“导入文档”。");
    }

    @Transactional
    public void delete(Long id) {
        getEntity(id);
        mapper.deleteById(id);
    }

    private KnowledgeSource getEntity(Long id) {
        KnowledgeSource source = id == null ? null : mapper.selectById(id);
        if (source == null) throw new BizException("知识来源不存在，id=" + id);
        return source;
    }

    private KnowledgeSourceView toView(KnowledgeSource source) {
        int actualDocumentCount = Math.toIntExact(documentMapper.selectCount(
            Wrappers.<KnowledgeDocument>lambdaQuery().eq(KnowledgeDocument::getSourceId, source.getId())
        ));
        return new KnowledgeSourceView(
            source.getId(), source.getName(), source.getSourceType(), source.getEndpoint(), source.getStatus(),
            actualDocumentCount, format(source.getLastSyncAt()), source.getDescription()
        );
    }

    private void ensureNameAvailable(String name, Long ignoredId) {
        var query = Wrappers.<KnowledgeSource>lambdaQuery().eq(KnowledgeSource::getName, name);
        if (ignoredId != null) query.ne(KnowledgeSource::getId, ignoredId);
        if (mapper.selectCount(query) > 0) throw new BizException("知识来源名称已存在：" + name);
    }

    private String normalize(String value, List<String> allowed, String label) {
        if (value == null || value.isBlank()) throw new BizException(label + "不能为空。");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BizException(label + "无效：" + value);
        return normalized;
    }

    private String normalizeDefault(String value, List<String> allowed, String fallback, String label) {
        return value == null || value.isBlank() ? fallback : normalize(value, allowed, label);
    }

    private String required(String value, String label) {
        String text = optional(value);
        if (text.isBlank()) throw new BizException(label + "不能为空。");
        return text;
    }

    private String optional(String value) {
        return value == null ? "" : value.trim();
    }

    private String format(LocalDateTime value) {
        return value == null ? "" : TIME.format(value);
    }
}
