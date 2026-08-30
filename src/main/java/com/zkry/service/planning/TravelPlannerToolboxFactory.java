package com.zkry.service.planning;

import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.TripPlan;
import com.zkry.domain.dto.TripRequest;
import com.zkry.domain.dto.map.MapPoi;
import com.zkry.domain.dto.map.MapPoint;
import com.zkry.domain.dto.map.MapWeatherForecast;
import com.zkry.domain.dto.planning.PlannerEvidenceItem;
import com.zkry.integration.amap.service.AmapMapContextService;
import com.zkry.service.TripResearchProgressReporter;
import com.zkry.service.TripTaskProgress;
import com.zkry.service.TripTaskStage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Creates a request-bound toolbox for the one planning brain.
 *
 * <p>The owner id is captured from the claimed task and is never accepted as a model argument.
 * This prevents the model from changing identity while still allowing preference retrieval.
 */
@Service
public class TravelPlannerToolboxFactory {

    private final PlanningResearchService researchService;
    private final AmapMapContextService amapService;
    private final TripPlanPolicyValidator validator;
    private final int maxCalls;
    private final int maxOutputCharacters;

    public TravelPlannerToolboxFactory(
        PlanningResearchService researchService,
        AmapMapContextService amapService,
        TripPlanPolicyValidator validator,
        @Value("${tripstar.planning.tools.max-calls:10}") int maxCalls,
        @Value("${tripstar.planning.tools.max-output-characters:12000}") int maxOutputCharacters
    ) {
        this.researchService = researchService;
        this.amapService = amapService;
        this.validator = validator;
        this.maxCalls = Math.max(6, maxCalls);
        this.maxOutputCharacters = Math.max(8000, maxOutputCharacters);
    }

    public PlanningToolSession bind(String taskId, Long ownerId, TripRequest request) {
        return bind(taskId, ownerId, request, TripResearchProgressReporter.noop());
    }

    public PlanningToolSession bind(
        String taskId,
        Long ownerId,
        TripRequest request,
        TripResearchProgressReporter progressReporter
    ) {
        return new PlanningToolSession(
            taskId,
            ownerId,
            request,
            researchService,
            amapService,
            validator,
            maxCalls,
            maxOutputCharacters,
            progressReporter == null ? TripResearchProgressReporter.noop() : progressReporter
        );
    }

    public static final class PlanningToolSession {

        private static final Logger log = LoggerFactory.getLogger(PlanningToolSession.class);
        private static final String FOOD_TYPES = "050000";
        private static final String HOTEL_TYPES = "100000";

        private final String taskId;
        private final Long ownerId;
        private final TripRequest request;
        private final PlanningResearchService researchService;
        private final AmapMapContextService amapService;
        private final TripPlanPolicyValidator validator;
        private final int maxCalls;
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicInteger remainingOutputCharacters;
        private final Set<String> usedTools = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        private final TripResearchProgressReporter progressReporter;

        private PlanningToolSession(
            String taskId,
            Long ownerId,
            TripRequest request,
            PlanningResearchService researchService,
            AmapMapContextService amapService,
            TripPlanPolicyValidator validator,
            int maxCalls,
            int maxOutputCharacters,
            TripResearchProgressReporter progressReporter
        ) {
            this.taskId = taskId;
            this.ownerId = ownerId;
            this.request = request;
            this.researchService = researchService;
            this.amapService = amapService;
            this.validator = validator;
            this.maxCalls = maxCalls;
            this.remainingOutputCharacters = new AtomicInteger(maxOutputCharacters);
            this.progressReporter = progressReporter;
        }

        @Tool(
            name = TravelPlannerToolNames.MATCH_PREFERENCE,
            description = "读取当前登录用户的显式偏好和最近历史行程。用户身份由服务器绑定，模型不能提供或修改 userId。"
        )
        public String matchUserPreference(
            @ToolParam(description = "当前旅行需求摘要。", required = true) String tripContext
        ) {
            return execute(TravelPlannerToolNames.MATCH_PREFERENCE, "MYSQL", () -> evidencePayload(
                researchService.userPreferenceEvidence(ownerId, request)
            ));
        }

        @Tool(
            name = TravelPlannerToolNames.VALIDATE_POI,
            description = "仅校验已由自建 RAG 知识库提供的候选景点，并用高德实时 API 校准正式名称、地址和经纬度。不得用此工具自主发现或推荐新景点。"
        )
        public String validatePoi(
            @ToolParam(description = "自建 RAG 证据中出现的候选 POI 名称。", required = true) String query,
            @ToolParam(description = "城市名。", required = true) String city,
            @ToolParam(description = "类型：attraction、restaurant、hotel。", required = false) String poiType,
            @ToolParam(description = "返回数量，建议 3 到 6。", required = false) Integer topK
        ) {
            return execute(TravelPlannerToolNames.VALIDATE_POI, "AMAP", () -> {
                int limit = safeLimit(topK, 6);
                List<MapPoi> live = amapService.searchPois(
                    city,
                    query,
                    poiTypes(poiType),
                    limit
                );
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("validated_pois", live.stream().map(PlanningToolSession::poiFact).toList());
                result.put("validation_source", "AMAP_REALTIME");
                return result;
            });
        }

        @Tool(
            name = TravelPlannerToolNames.PLAN_ROUTE,
            description = "调用高德实时路线 API 验证一组有序途经点。最多 6 个点；返回每段距离和耗时，不向模型返回巨大 polyline。"
        )
        public String planRoute(
            @ToolParam(description = "有序途经点，必须包含名称、经度 longitude、纬度 latitude。", required = true)
            List<Waypoint> waypoints,
            @ToolParam(description = "driving、walking 或 transit。", required = true) String mode,
            @ToolParam(description = "公交模式使用的城市名或 adcode。", required = false) String city
        ) {
            return execute(TravelPlannerToolNames.PLAN_ROUTE, "AMAP", () -> {
                List<Waypoint> points = boundedWaypoints(waypoints, 6);
                if (points.size() < 2) {
                    throw new IllegalArgumentException("路线规划至少需要两个途经点。");
                }
                List<RouteSegment> segments = new ArrayList<>();
                long distance = 0L;
                long duration = 0L;
                for (int index = 0; index < points.size() - 1; index++) {
                    Waypoint from = points.get(index);
                    Waypoint to = points.get(index + 1);
                    AmapMapContextService.RouteResult route = amapService.route(
                        mode,
                        point(from),
                        point(to),
                        city
                    );
                    distance += route.distanceMeters();
                    duration += route.durationSeconds();
                    segments.add(new RouteSegment(
                        from.name(),
                        to.name(),
                        route.distanceMeters(),
                        route.durationSeconds()
                    ));
                }
                return new RouteSummary(distance, duration, List.copyOf(segments), "AMAP_REALTIME");
            });
        }

        @Tool(
            name = TravelPlannerToolNames.DISTANCE_MATRIX,
            description = "计算最多 8 个候选点的两两直线距离和预计交通时间，用于先做空间聚类；正式路线仍需调用 plan_route。"
        )
        public String getDistanceMatrix(
            @ToolParam(description = "候选地点列表。", required = true) List<Waypoint> points,
            @ToolParam(description = "walking 或 driving，用于估算速度。", required = false) String mode
        ) {
            return execute(TravelPlannerToolNames.DISTANCE_MATRIX, "LOCAL_ESTIMATE", () -> {
                List<Waypoint> safePoints = boundedWaypoints(points, 8);
                double speedMetersPerMinute = "walking".equalsIgnoreCase(mode) ? 75D : 350D;
                List<DistanceCell> cells = new ArrayList<>();
                for (int i = 0; i < safePoints.size(); i++) {
                    for (int j = i + 1; j < safePoints.size(); j++) {
                        Waypoint from = safePoints.get(i);
                        Waypoint to = safePoints.get(j);
                        long meters = Math.round(haversineMeters(from, to));
                        cells.add(new DistanceCell(
                            from.name(),
                            to.name(),
                            meters,
                            Math.max(1L, Math.round(meters / speedMetersPerMinute))
                        ));
                    }
                }
                return Map.of(
                    "method", "HAVERSINE_CLUSTERING_ESTIMATE",
                    "notice", "仅用于候选聚类，最终路线必须调用 plan_route。",
                    "pairs", cells
                );
            });
        }

        @Tool(
            name = TravelPlannerToolNames.SEARCH_NEARBY,
            description = "调用高德周边搜索查找景点附近的餐饮、酒店或交通设施。"
        )
        public String searchNearby(
            @ToolParam(description = "中心点经度。", required = true) Double longitude,
            @ToolParam(description = "中心点纬度。", required = true) Double latitude,
            @ToolParam(description = "搜索关键词。", required = false) String keyword,
            @ToolParam(description = "类型：restaurant、hotel 或 other。", required = false) String poiType,
            @ToolParam(description = "半径米数，100 到 50000。", required = false) Integer radius,
            @ToolParam(description = "返回数量，最多 10。", required = false) Integer topK
        ) {
            return execute(TravelPlannerToolNames.SEARCH_NEARBY, "AMAP", () ->
                amapService.searchNearby(
                    new MapPoint(longitude, latitude),
                    keyword,
                    poiTypes(poiType),
                    radius == null ? 2000 : radius,
                    safeLimit(topK, 6)
                ).stream().map(PlanningToolSession::poiFact).toList()
            );
        }

        @Tool(
            name = TravelPlannerToolNames.GET_WEATHER,
            description = "查询高德实时天气预报。天气失败属于可降级数据，不得因此编造天气。"
        )
        public String getWeather(
            @ToolParam(description = "城市名。", required = true) String city
        ) {
            return execute(TravelPlannerToolNames.GET_WEATHER, "AMAP", () -> {
                AmapMapContextService.GeocodeResult geocode = amapService.geocode(city);
                List<MapWeatherForecast> forecasts = amapService.weatherForecasts(city, geocode.adcode());
                return forecasts.stream().limit(7).toList();
            });
        }

        @Tool(
            name = TravelPlannerToolNames.EVALUATE_PLAN,
            description = "对行程草案进行确定性自检，检查天数、酒店、三餐、重复景点和坐标。返回问题后应修复再输出。"
        )
        public String evaluatePlan(
            @ToolParam(description = "待检查的完整结构化行程草案。", required = true) TripPlan plan
        ) {
            return execute(
                TravelPlannerToolNames.EVALUATE_PLAN,
                "LOCAL_VALIDATOR",
                () -> validator.evaluate(request, plan)
            );
        }

        @Tool(
            name = TravelPlannerToolNames.SEARCH_KNOWLEDGE,
            description = "从官方、审核网页和用户导入资料的混合 RAG 中查询历史文化、季节、人群和避坑证据。"
        )
        public String searchTravelKnowledge(
            @ToolParam(description = "自然语言查询。", required = true) String query,
            @ToolParam(description = "城市过滤。", required = true) String city,
            @ToolParam(description = "返回数量，建议 3 到 6。", required = false) Integer topK
        ) {
            return execute(TravelPlannerToolNames.SEARCH_KNOWLEDGE, "RAG", () -> evidencePayload(
                researchService.searchEvidence(
                    query,
                    city,
                    safeLimit(topK, 6),
                    Set.of("OFFICIAL", "WEB", "UPLOAD"),
                    "KNOWLEDGE"
                )
            ));
        }

        public ToolUsage usage() {
            Set<String> snapshot;
            synchronized (usedTools) {
                snapshot = Set.copyOf(usedTools);
            }
            return new ToolUsage(
                callCount.get(),
                snapshot,
                snapshot.stream().anyMatch(name -> name.startsWith("search_poi")
                    || name.startsWith("plan_route")
                    || name.startsWith("get_distance")
                    || name.startsWith("search_nearby")
                    || name.startsWith("get_weather"))
            );
        }

        private String execute(String tool, String source, ThrowingSupplier supplier) {
            int call = callCount.incrementAndGet();
            usedTools.add(tool);
            if (call > maxCalls) {
                return failure(tool, source, "本次规划 Tool 调用已达到上限 " + maxCalls + "，请使用现有证据完成规划。");
            }
            int progress = Math.min(
                TripTaskProgress.PLANNING_HEARTBEAT_MAX,
                TripTaskProgress.PLANNING + 2 + call * 2
            );
            progressReporter.report(
                TripTaskStage.PLANNING_TOOL,
                progress,
                "正在调用" + toolLabel(tool) + "（" + call + "/" + maxCalls + "）"
            );
            long startedAt = System.currentTimeMillis();
            try {
                Object data = supplier.get();
                String json = envelope(true, tool, source, data, "");
                if (!reserveOutputCharacters(json.length())) {
                    return failure(tool, source, "Tool 输出预算不足，请缩小 top_k 或使用已有结果。");
                }
                log.info("[PlanningTool] 调用成功 taskId={} tool={} call={}/{} outputChars={} remainingChars={} elapsedMs={}",
                    taskId,
                    tool,
                    call,
                    maxCalls,
                    json.length(),
                    remainingOutputCharacters.get(),
                    System.currentTimeMillis() - startedAt);
                progressReporter.report(
                    TripTaskStage.PLANNING_TOOL,
                    Math.min(TripTaskProgress.PLANNING_HEARTBEAT_MAX, progress + 1),
                    toolLabel(tool) + "完成，AI 正在合并结果"
                );
                return json;
            } catch (Exception ex) {
                log.warn("[PlanningTool] 调用失败 taskId={} tool={} call={}/{} elapsedMs={} reason={}",
                    taskId, tool, call, maxCalls, System.currentTimeMillis() - startedAt, rootMessage(ex));
                return failure(tool, source, rootMessage(ex));
            }
        }

        private String toolLabel(String tool) {
            return switch (tool) {
                case TravelPlannerToolNames.MATCH_PREFERENCE -> "用户偏好匹配";
                case TravelPlannerToolNames.VALIDATE_POI -> "真实 POI 校验";
                case TravelPlannerToolNames.PLAN_ROUTE -> "高德路线规划";
                case TravelPlannerToolNames.DISTANCE_MATRIX -> "地点距离聚类";
                case TravelPlannerToolNames.SEARCH_NEARBY -> "周边餐饮住宿搜索";
                case TravelPlannerToolNames.GET_WEATHER -> "实时天气查询";
                case TravelPlannerToolNames.EVALUATE_PLAN -> "行程草案自检";
                case TravelPlannerToolNames.SEARCH_KNOWLEDGE -> "旅行知识检索";
                default -> tool;
            };
        }

        private boolean reserveOutputCharacters(int amount) {
            while (true) {
                int current = remainingOutputCharacters.get();
                if (amount > current) {
                    return false;
                }
                if (remainingOutputCharacters.compareAndSet(current, current - amount)) {
                    return true;
                }
            }
        }

        private Object evidencePayload(PlanningResearchService.SourceResult result) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", result.items().stream().map(PlanningToolSession::compactEvidence).toList());
            payload.put("trace_ids", result.traceIds());
            payload.put("warnings", result.warnings());
            return payload;
        }

        private String failure(String tool, String source, String error) {
            return envelope(false, tool, source, null, error);
        }

        private String envelope(boolean success, String tool, String source, Object data, String error) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", success);
            body.put("tool", tool);
            body.put("source", source);
            if (success) {
                body.put("data", data);
            } else {
                body.put("error", error);
            }
            return JsonUtils.toJsonString(body);
        }

        private int safeLimit(Integer value, int fallback) {
            return value == null || value <= 0 ? fallback : Math.min(value, 8);
        }

        private String poiTypes(String value) {
            if (value == null) {
                return "";
            }
            return switch (value.trim().toLowerCase()) {
                case "restaurant", "food", "餐饮", "美食" -> FOOD_TYPES;
                case "hotel", "住宿", "酒店" -> HOTEL_TYPES;
                default -> "";
            };
        }

        private List<Waypoint> boundedWaypoints(List<Waypoint> values, int max) {
            if (values == null) {
                return List.of();
            }
            return values.stream()
                .filter(value -> value != null
                    && value.longitude() != null
                    && value.latitude() != null)
                .limit(max)
                .toList();
        }

        private static MapPoint point(Waypoint value) {
            return new MapPoint(value.longitude(), value.latitude());
        }

        private static PoiFact poiFact(MapPoi poi) {
            return new PoiFact(
                poi.name(),
                poi.address(),
                poi.location() == null ? null : poi.location().longitude(),
                poi.location() == null ? null : poi.location().latitude(),
                poi.type(),
                poi.rating(),
                poi.distance()
            );
        }

        private static PlannerEvidenceItem compactEvidence(PlannerEvidenceItem item) {
            String content = item.content();
            if (content != null && content.length() > 700) {
                content = content.substring(0, 700) + "…";
            }
            return new PlannerEvidenceItem(
                item.source(),
                item.evidenceId(),
                item.city(),
                item.title(),
                content,
                item.sourceUrl(),
                item.score()
            );
        }

        private static double haversineMeters(Waypoint from, Waypoint to) {
            double radius = 6_371_000D;
            double lat1 = Math.toRadians(from.latitude());
            double lat2 = Math.toRadians(to.latitude());
            double dLat = lat2 - lat1;
            double dLon = Math.toRadians(to.longitude() - from.longitude());
            double a = Math.sin(dLat / 2D) * Math.sin(dLat / 2D)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLon / 2D) * Math.sin(dLon / 2D);
            return radius * 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
        }

        private String rootMessage(Throwable error) {
            Throwable current = error;
            String message = error == null ? "未知错误" : error.getClass().getSimpleName();
            while (current != null) {
                if (current.getMessage() != null && !current.getMessage().isBlank()) {
                    message = current.getMessage();
                }
                current = current.getCause();
            }
            return message;
        }

        @FunctionalInterface
        private interface ThrowingSupplier {
            Object get() throws Exception;
        }
    }

    public record Waypoint(String name, Double longitude, Double latitude) {
    }

    public record PoiFact(
        String name,
        String address,
        Double longitude,
        Double latitude,
        String type,
        String rating,
        String distance
    ) {
    }

    public record RouteSegment(String from, String to, long distanceMeters, long durationSeconds) {
    }

    public record RouteSummary(
        long totalDistanceMeters,
        long totalDurationSeconds,
        List<RouteSegment> segments,
        String source
    ) {
    }

    public record DistanceCell(String from, String to, long distanceMeters, long estimatedMinutes) {
    }

    public record ToolUsage(int callCount, Set<String> tools, boolean amapUsed) {
    }
}
