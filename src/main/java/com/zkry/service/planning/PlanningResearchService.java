package com.zkry.service.planning;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.domain.dto.TripRequest;
import com.zkry.domain.dto.knowledge.RagSearchRequest;
import com.zkry.domain.dto.map.MapWeatherForecast;
import com.zkry.domain.dto.planning.PlannerContextPack;
import com.zkry.domain.dto.planning.PlannerEvidenceItem;
import com.zkry.domain.entity.KnowledgeSource;
import com.zkry.domain.vo.RagCitationView;
import com.zkry.domain.vo.RagSearchView;
import com.zkry.integration.amap.service.AmapMapContextService;
import com.zkry.mapper.KnowledgeSourceMapper;
import com.zkry.mapper.TripPlanMapper;
import com.zkry.memory.application.UserMemoryQueryService;
import com.zkry.memory.domain.MemoryContextPack;
import com.zkry.service.TripResearchProgressReporter;
import com.zkry.service.TripTaskProgress;
import com.zkry.service.TripTaskStage;
import com.zkry.service.rag.KnowledgeRagService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Parallel, non-Agent research fan-out used before the single planning Agent starts reasoning.
 *
 * <p>User memory and the self-managed travel knowledge base are independent reads. AMap is not
 * used to discover attractions here: the planner first selects candidates grounded by RAG, then
 * calls the request-bound AMap tools to validate POIs and enrich routes, weather, hotels and food.
 */
@Service
public class PlanningResearchService {

    private static final Logger log = LoggerFactory.getLogger(PlanningResearchService.class);
    private static final Set<String> GENERAL_SOURCE_TYPES = Set.of("OFFICIAL", "WEB", "UPLOAD");

    private final KnowledgeRagService ragService;
    private final KnowledgeSourceMapper sourceMapper;
    private final UserMemoryQueryService userMemoryQueryService;
    private final AmapMapContextService amapService;
    private final PlannerContextPackBuilder contextPackBuilder;
    private final ExecutorService executor;
    private final long timeoutSeconds;

    /** Compatibility constructor for callers that previously supplied the history-plan mapper. */
    public PlanningResearchService(
        KnowledgeRagService ragService,
        KnowledgeSourceMapper sourceMapper,
        TripPlanMapper ignoredHistoryPlanMapper,
        AmapMapContextService amapService,
        PlannerContextPackBuilder contextPackBuilder,
        ExecutorService executor,
        long timeoutSeconds
    ) {
        this(ragService, sourceMapper, amapService, contextPackBuilder, executor, timeoutSeconds, null);
    }

    @Autowired
    public PlanningResearchService(
        KnowledgeRagService ragService,
        KnowledgeSourceMapper sourceMapper,
        AmapMapContextService amapService,
        PlannerContextPackBuilder contextPackBuilder,
        @Qualifier("taskVirtualThreadExecutor") ExecutorService executor,
        @Value("${tripstar.planning.research-timeout-seconds:35}") long timeoutSeconds,
        UserMemoryQueryService userMemoryQueryService
    ) {
        this.ragService = ragService;
        this.sourceMapper = sourceMapper;
        this.userMemoryQueryService = userMemoryQueryService;
        this.amapService = amapService;
        this.contextPackBuilder = contextPackBuilder;
        this.executor = executor;
        this.timeoutSeconds = Math.max(10, Math.min(timeoutSeconds, 120));
    }

    public PlannerContextPack build(Long ownerId, TripRequest request) {
        return build(ownerId, request, TripResearchProgressReporter.noop());
    }

    public PlannerContextPack build(
        Long ownerId,
        TripRequest request,
        TripResearchProgressReporter progressReporter
    ) {
        long startedAt = System.currentTimeMillis();
        String query = planningQuery(request);
        TripResearchProgressReporter reporter = progressReporter == null
            ? TripResearchProgressReporter.noop()
            : progressReporter;
        List<SourceJob> jobs = List.of(
            new SourceJob("用户偏好", () -> userPreferenceEvidence(ownerId, request)),
            new SourceJob("自建旅行知识库", () ->
                searchEvidence(query, request.primaryCity(), 8, GENERAL_SOURCE_TYPES, "KNOWLEDGE"))
        );
        ExecutorCompletionService<NamedSourceResult> completions =
            new ExecutorCompletionService<>(executor);
        List<Future<NamedSourceResult>> futures = jobs.stream()
            .map(job -> completions.submit(() -> runSource(job)))
            .toList();

        List<PlannerEvidenceItem> evidence = new ArrayList<>();
        List<String> traceIds = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        int completed = 0;
        while (completed < jobs.size()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            try {
                Future<NamedSourceResult> completedFuture =
                    completions.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (completedFuture == null) {
                    break;
                }
                NamedSourceResult named = completedFuture.get();
                completed++;
                SourceResult result = named.result();
                evidence.addAll(result.items());
                traceIds.addAll(result.traceIds());
                warnings.addAll(result.warnings());
                reporter.report(
                    TripTaskStage.TRAVEL_RESEARCH,
                    researchProgress(completed),
                    "并行资料准备 " + completed + "/" + jobs.size() + "：" + named.label()
                        + "完成，获得 " + result.items().size() + " 条证据"
                );
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                warnings.add("并行资料准备被中断，已使用当前可用证据继续规划。");
                break;
            } catch (Exception ex) {
                completed++;
                warnings.add("并行资料源返回异常，已降级：" + rootMessage(ex));
            }
        }
        int unfinished = jobs.size() - completed;
        if (unfinished > 0) {
            futures.stream().filter(future -> !future.isDone()).forEach(future -> future.cancel(true));
            warnings.add("有 " + unfinished + " 个资料源超过 " + timeoutSeconds
                + " 秒预算，已跳过并继续规划。");
            reporter.report(
                TripTaskStage.RESEARCH_MERGE,
                TripTaskProgress.CONTEXT_READY - 2,
                "资料准备达到时间预算，正在使用已完成证据生成上下文"
            );
        }
        PlannerContextPack pack = contextPackBuilder.build(evidence, traceIds, warnings);
        reporter.report(
            TripTaskStage.RESEARCH_MERGE,
            TripTaskProgress.CONTEXT_READY,
            "资料包已完成：" + evidence.size() + " 条候选证据，压缩为 "
                + pack.characterCount() + " 字符"
        );
        log.info(
            "[SingleAgentResearch] 并行研究完成 ownerId={} cities={} sourceCounts={} contextChars={} warnings={} elapsedMs={}",
            ownerId,
            request.normalizedCities().stream().map(city -> city.city()).toList(),
            pack.sourceCounts(),
            pack.characterCount(),
            pack.safeWarnings(),
            System.currentTimeMillis() - startedAt
        );
        return pack;
    }

    /**
     * On-demand RAG entry exposed to the bound planning toolbox.
     */
    public SourceResult searchEvidence(
        String query,
        String city,
        int topK,
        Set<String> sourceTypes,
        String evidenceSource
    ) {
        List<Long> sourceIds = sourceIds(sourceTypes);
        if (sourceIds.isEmpty()) {
            return SourceResult.warning(evidenceSource + " 知识源未配置，已跳过该层检索。");
        }
        RagSearchView result = ragService.search(new RagSearchRequest(
            query,
            Math.max(1, Math.min(topK, 8)),
            sourceIds,
            city == null ? "" : city,
            "",
            List.of()
        ));
        List<PlannerEvidenceItem> items = result.citations().stream()
            .map(citation -> toEvidence(evidenceSource, city, citation))
            .toList();
        List<String> traceIds = hasText(result.trace_id()) ? List.of(result.trace_id()) : List.of();
        List<String> warnings = items.isEmpty()
            ? List.of(evidenceSource + " 没有检索到相关证据。")
            : List.of();
        return new SourceResult(items, traceIds, warnings);
    }

    public SourceResult userPreferenceEvidence(Long ownerId, TripRequest request) {
        List<PlannerEvidenceItem> items = new ArrayList<>();
        if (!request.safePreferences().isEmpty() || hasText(request.free_text_input())) {
            String explicit = "显式偏好=" + String.join("、", request.safePreferences())
                + "；额外要求=" + safe(request.free_text_input());
            items.add(new PlannerEvidenceItem(
                "USER_PREFERENCE",
                "request-explicit",
                request.primaryCity(),
                "本次用户明确要求",
                explicit,
                "",
                2D
            ));
        }
        if (ownerId == null || userMemoryQueryService == null) {
            return new SourceResult(items, List.of(), List.of("没有可用的已确认用户画像，本次仅使用显式偏好。"));
        }
        MemoryContextPack memory = userMemoryQueryService.context(ownerId, request.primaryCity(), null);
        if (!memory.active().isEmpty()) {
            items.add(new PlannerEvidenceItem(
                "USER_PREFERENCE", "profile-memory", request.primaryCity(), "已确认的用户画像",
                memory.promptContext(), "", 1.5D
            ));
        }
        List<String> warnings = memory.active().isEmpty()
            ? List.of("没有已确认的长期用户画像，个性化仅使用本次显式偏好。") : List.of();
        return new SourceResult(items, List.of(), warnings);
    }

    public SourceResult weatherEvidence(TripRequest request) {
        List<PlannerEvidenceItem> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (var stay : request.normalizedCities()) {
            try {
                AmapMapContextService.GeocodeResult geocode = amapService.geocode(stay.city());
                List<MapWeatherForecast> forecasts =
                    amapService.weatherForecasts(stay.city(), geocode.adcode());
                if (forecasts.isEmpty()) {
                    warnings.add(stay.city() + " 暂无实时天气预报。");
                    continue;
                }
                String content = forecasts.stream().limit(7)
                    .map(item -> "%s 白天%s%s℃，夜间%s%s℃，%s%s".formatted(
                        safe(item.date()),
                        safe(item.dayWeather()),
                        item.dayTemp() == null ? "" : item.dayTemp(),
                        safe(item.nightWeather()),
                        item.nightTemp() == null ? "" : item.nightTemp(),
                        safe(item.windDirection()),
                        safe(item.windPower())
                    ))
                    .reduce((left, right) -> left + "；" + right)
                    .orElse("");
                items.add(new PlannerEvidenceItem(
                    "WEATHER",
                    "amap-weather-" + stay.city(),
                    stay.city(),
                    stay.city() + "实时天气",
                    content,
                    "",
                    1D
                ));
            } catch (Exception ex) {
                warnings.add(stay.city() + " 天气查询降级：" + rootMessage(ex));
            }
        }
        return new SourceResult(items, List.of(), warnings);
    }

    private NamedSourceResult runSource(SourceJob job) {
        long startedAt = System.currentTimeMillis();
        try {
            SourceResult result = job.supplier().get();
            log.info("[SingleAgentResearch] 数据源完成 source={} items={} elapsedMs={}",
                job.label(), result.items().size(), System.currentTimeMillis() - startedAt);
            return new NamedSourceResult(job.label(), result);
        } catch (Exception ex) {
            log.warn("[SingleAgentResearch] 数据源降级 source={} elapsedMs={} reason={}",
                job.label(), System.currentTimeMillis() - startedAt, rootMessage(ex));
            return new NamedSourceResult(
                job.label(),
                SourceResult.warning(job.label() + " 调用失败，已降级：" + rootMessage(ex))
            );
        }
    }

    private int researchProgress(int completed) {
        return switch (Math.max(1, Math.min(completed, 5))) {
            case 1 -> TripTaskProgress.RESEARCH_SOURCE_1;
            case 2 -> TripTaskProgress.RESEARCH_SOURCE_2;
            case 3 -> TripTaskProgress.RESEARCH_SOURCE_3;
            case 4 -> TripTaskProgress.RESEARCH_SOURCE_4;
            default -> TripTaskProgress.RESEARCH_SOURCE_5;
        };
    }

    private List<Long> sourceIds(Set<String> sourceTypes) {
        if (sourceTypes == null || sourceTypes.isEmpty()) {
            return List.of();
        }
        return sourceMapper.selectList(
            Wrappers.<KnowledgeSource>lambdaQuery()
                .in(KnowledgeSource::getSourceType, sourceTypes)
                .eq(KnowledgeSource::getStatus, "READY")
        ).stream().map(KnowledgeSource::getId).toList();
    }

    private PlannerEvidenceItem toEvidence(
        String source,
        String city,
        RagCitationView citation
    ) {
        return new PlannerEvidenceItem(
            source,
            "chunk-" + citation.chunk_id(),
            city,
            citation.title(),
            citation.content(),
            citation.source_url(),
            citation.score()
        );
    }

    private String planningQuery(TripRequest request) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        request.normalizedCities().forEach(city -> terms.add(city.city()));
        terms.addAll(request.safePreferences());
        if (hasText(request.free_text_input())) {
            terms.add(request.free_text_input().trim());
        }
        terms.add(request.safeAccommodation());
        terms.add(request.safeTransportation());
        return String.join(" ", terms);
    }


    private String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error == null ? "未知错误" : error.getClass().getSimpleName();
        while (current != null) {
            if (hasText(current.getMessage())) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未提供" : value.trim();
    }

    public record SourceResult(
        List<PlannerEvidenceItem> items,
        List<String> traceIds,
        List<String> warnings
    ) {
        public SourceResult {
            items = items == null ? List.of() : List.copyOf(items);
            traceIds = traceIds == null ? List.of() : List.copyOf(traceIds);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public static SourceResult warning(String warning) {
            return new SourceResult(List.of(), List.of(), List.of(warning));
        }
    }

    private record SourceJob(String label, Supplier<SourceResult> supplier) {
    }

    private record NamedSourceResult(String label, SourceResult result) {
    }
}
