package com.zkry.integration.amap.service;

import com.zkry.domain.dto.Location;
import com.zkry.domain.dto.WeatherInfo;

import com.zkry.service.TripstarRuntimeSettingsService;
import com.zkry.common.constant.TripstarSettingKeys;
import com.zkry.common.exception.BizException;
import com.zkry.common.exception.CommonErrorCode;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.map.MapPoi;
import com.zkry.domain.dto.map.MapPoint;
import com.zkry.domain.dto.map.MapWeatherForecast;
import com.zkry.integration.amap.support.AmapCoordinateNormalizer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.zkry.integration.ExternalCallBulkheads;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 高德 REST API 访问层。
 *
 * <p>这里放确定性、可测试的 HTTP 能力：地理编码、POI、天气。上层通过
 * {@link AmapGeoPoiTools}、{@link AmapWeatherTools} 和 {@link AmapHotelTools}
 * 暴露给 ReactAgent 调用。这样 Tool 只是“外壳”，不会复制一套高德请求逻辑。
 */
@Service
public class AmapMapContextService {

    private static final Logger log = LoggerFactory.getLogger(AmapMapContextService.class);
    private static final String AMAP_RATE_LIMIT_INFOCODE = "10021";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .build();
    private final ExternalCallBulkheads bulkheads;
    private final Object rateLimitMonitor = new Object();

    private final TripstarRuntimeSettingsService runtimeSettingsService;
    private long lastRequestAt;

    public AmapMapContextService(
        TripstarRuntimeSettingsService runtimeSettingsService,
        ExternalCallBulkheads bulkheads
    ) {
        this.runtimeSettingsService = runtimeSettingsService;
        this.bulkheads = bulkheads;
    }

    @Value("${tripstar.map.amap.enabled:false}")
    private boolean enabled;

    @Value("${tripstar.map.amap.base-url:https://restapi.amap.com}")
    private String baseUrl;

    @Value("${tripstar.map.amap.min-interval-ms:350}")
    private long minIntervalMs;

    @Value("${tripstar.map.amap.rate-limit-retries:2}")
    private int rateLimitRetries;

    @Value("${tripstar.map.amap.rate-limit-retry-delay-ms:1000}")
    private long rateLimitRetryDelayMs;

    public GeocodeResult geocode(String city) throws IOException, InterruptedException {
        validateReady();
        log.info("[AMap] 地理编码 city={}", city);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("address", city);
        params.put("city", city);
        params.put("output", "JSON");
        JsonNode root = get("/v3/geocode/geo", params);
        JsonNode geocodes = root.path("geocodes");
        if (!geocodes.isArray() || geocodes.isEmpty()) {
            log.info("[AMap] 地理编码无结果 city={}", city);
            return new GeocodeResult("", null);
        }
        JsonNode first = geocodes.get(0);
        GeocodeResult result = new GeocodeResult(text(first.path("adcode")), parsePoint(text(first.path("location"))));
        log.info("[AMap] 地理编码成功 city={} adcode={} location={}",
            city, result.adcode(), result.point() == null ? "-" : result.point().longitude() + "," + result.point().latitude());
        return result;
    }

    public List<MapPoi> searchPois(String city, String keywords, int limit) throws IOException, InterruptedException {
        return searchPois(city, keywords, "", limit);
    }

    /**
     * 按高德 POI 类型代码搜索。
     *
     * <p>酒店和餐饮属于明确的数据类别，必须在请求高德时限定类型，避免关键词相似的景区、
     * 商场或夜市进入错误数组。普通景点校准仍调用不带类型的重载方法。
     */
    public List<MapPoi> searchPois(
        String city,
        String keywords,
        String types,
        int limit
    ) throws IOException, InterruptedException {
        validateReady();
        log.info("[AMap] POI 搜索 city={} keywords={} types={} limit={}",
            city, keywords, types == null || types.isBlank() ? "-" : types, limit);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("keywords", keywords);
        params.put("region", city);
        params.put("city_limit", "true");
        params.put("page_size", String.valueOf(limit));
        if (types != null && !types.isBlank()) {
            params.put("types", types);
        }
        params.put("show_fields", "business,photos");
        params.put("output", "JSON");
        JsonNode root = get("/v5/place/text", params);
        JsonNode pois = root.path("pois");
        if (!pois.isArray()) {
            log.info("[AMap] POI 搜索无数组结果 city={} keywords={}", city, keywords);
            return List.of();
        }

        List<MapPoi> result = new ArrayList<>();
        for (JsonNode poi : pois) {
            if (result.size() >= limit) {
                break;
            }
            String name = text(poi.path("name"));
            if (name.isBlank()) {
                continue;
            }
            result.add(new MapPoi(
                name,
                text(poi.path("address")),
                parsePoint(text(poi.path("location"))),
                text(poi.path("type")),
                firstNonBlank(text(poi.path("business").path("rating")), text(poi.path("biz_ext").path("rating"))),
                text(poi.path("distance")),
                firstPhoto(poi.path("photos"))
            ));
        }
        log.info("[AMap] POI 搜索完成 city={} keywords={} types={} resultCount={}",
            city, keywords, types == null || types.isBlank() ? "-" : types, result.size());
        return result;
    }

    /**
     * Search facilities around a concrete coordinate.
     *
     * <p>This is intentionally a deterministic API method rather than another Agent. The single
     * planning Agent decides when nearby data is needed; Java validates coordinates, bounds the
     * result count and calls AMap.
     */
    public List<MapPoi> searchNearby(
        MapPoint center,
        String keywords,
        String types,
        int radiusMeters,
        int limit
    ) throws IOException, InterruptedException {
        validateReady();
        MapPoint normalizedCenter = normalizeRoutePoint("周边搜索中心", center);
        int safeRadius = Math.max(100, Math.min(radiusMeters, 50000));
        int safeLimit = Math.max(1, Math.min(limit, 10));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("location", coordinate(normalizedCenter));
        params.put("radius", String.valueOf(safeRadius));
        params.put("page_size", String.valueOf(safeLimit));
        params.put("sortrule", "distance");
        params.put("show_fields", "business,photos");
        params.put("output", "JSON");
        if (keywords != null && !keywords.isBlank()) {
            params.put("keywords", keywords.trim());
        }
        if (types != null && !types.isBlank()) {
            params.put("types", types.trim());
        }
        log.info("[AMap] 周边搜索 center={} keywords={} types={} radius={} limit={}",
            coordinate(normalizedCenter), keywords, types, safeRadius, safeLimit);
        JsonNode root = get("/v5/place/around", params);
        JsonNode pois = root.path("pois");
        if (!pois.isArray()) {
            return List.of();
        }
        List<MapPoi> result = new ArrayList<>();
        for (JsonNode poi : pois) {
            if (result.size() >= safeLimit) {
                break;
            }
            String name = text(poi.path("name"));
            if (name.isBlank()) {
                continue;
            }
            result.add(new MapPoi(
                name,
                text(poi.path("address")),
                parsePoint(text(poi.path("location"))),
                text(poi.path("type")),
                firstNonBlank(
                    text(poi.path("business").path("rating")),
                    text(poi.path("biz_ext").path("rating"))
                ),
                firstNonBlank(
                    text(poi.path("distance")),
                    text(poi.path("business").path("distance"))
                ),
                firstPhoto(poi.path("photos"))
            ));
        }
        log.info("[AMap] 周边搜索完成 resultCount={}", result.size());
        return List.copyOf(result);
    }

    public List<MapWeatherForecast> weatherForecasts(String city, String adcode) throws IOException, InterruptedException {
        validateReady();
        String cityCode = firstNonBlank(adcode, city);
        if (cityCode.isBlank()) {
            log.info("[AMap] 天气查询跳过 city={} reason=cityCodeBlank", city);
            return List.of();
        }
        log.info("[AMap] 天气查询 city={} cityCode={}", city, cityCode);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("city", cityCode);
        params.put("extensions", "all");
        params.put("output", "JSON");
        JsonNode root;
        try {
            root = get("/v3/weather/weatherInfo", params);
        } catch (BizException ex) {
            if (isSpecialAdministrativeRegion(adcode) && ex.getMessage() != null
                && ex.getMessage().contains("infocode=20003")) {
                throw new BizException(
                    CommonErrorCode.BUSINESS_ERROR,
                    "高德天气 Web Service 当前未返回" + city
                        + "（adcode=" + adcode + "）的预报数据；天气将标记为暂不可用，但不应中断行程规划。",
                    ex
                );
            }
            throw ex;
        }
        JsonNode forecasts = root.path("forecasts");
        if (!forecasts.isArray() || forecasts.isEmpty()) {
            log.info("[AMap] 天气查询无 forecasts city={}", city);
            return List.of();
        }
        JsonNode casts = forecasts.get(0).path("casts");
        if (!casts.isArray()) {
            log.info("[AMap] 天气查询无 casts city={}", city);
            return List.of();
        }

        List<MapWeatherForecast> result = new ArrayList<>();
        for (JsonNode cast : casts) {
            result.add(new MapWeatherForecast(
                text(cast.path("date")),
                city,
                text(cast.path("dayweather")),
                text(cast.path("nightweather")),
                parseInteger(text(cast.path("daytemp"))),
                parseInteger(text(cast.path("nighttemp"))),
                text(cast.path("daywind")),
                text(cast.path("daypower"))
            ));
        }
        log.info("[AMap] 天气查询完成 city={} forecastDays={}", city, result.size());
        return result;
    }

    /**
     * 查询两点间的真实道路轨迹。
     *
     * <p>路线规划放在后端调用高德 Web Service，避免浏览器端路线插件同时受到
     * JS Key、securityJsCode、域名白名单和本地缓存配置的多重影响。地图底图仍由
     * Web JS API 展示，轨迹坐标由该方法确定性返回。
     */
    public RouteResult route(String mode, MapPoint origin, MapPoint destination)
        throws IOException, InterruptedException {
        return route(mode, origin, destination, "");
    }

    public RouteResult route(String mode, MapPoint origin, MapPoint destination, String cityCode)
        throws IOException, InterruptedException {
        validateReady();
        origin = normalizeRoutePoint("起点", origin);
        destination = normalizeRoutePoint("终点", destination);

        String normalizedMode = "walking".equalsIgnoreCase(mode)
            ? "walking"
            : "transit".equalsIgnoreCase(mode) ? "transit" : "driving";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("origin", coordinate(origin));
        params.put("destination", coordinate(destination));
        if ("transit".equals(normalizedMode)) {
            String city = cityCode == null ? "" : cityCode.trim();
            if (city.isBlank()) throw new BizException("公交路径规划需要城市 citycode。");
            // 前端允许传城市中文名以保持调用简单；高德公交 2.0 的 city1/city2
            // 实际要求行政区划代码，因此在服务端统一解析，不能把“杭州”直接传给接口。
            if (!city.matches("\\d{6}")) {
                GeocodeResult geocode = geocode(city);
                if (geocode == null || geocode.adcode() == null || geocode.adcode().isBlank()) {
                    throw new BizException("高德公交路径规划无法解析城市 citycode：" + city);
                }
                city = geocode.adcode();
            }
            params.put("city1", city);
            params.put("city2", city);
            params.put("strategy", "0");
            // 公交 2.0 默认不返回线路坐标；polyline 必须显式加入 show_fields。
            params.put("show_fields", "cost,navi,polyline");
        } else if ("walking".equals(normalizedMode)) {
            params.put("isindoor", "0");
            params.put("show_fields", "cost,polyline");
        } else {
            params.put("strategy", "32");
            params.put("show_fields", "cost,navi,polyline");
        }

        log.info("[AMap-Route] 路径规划 mode={} origin={} destination={}",
            normalizedMode, coordinate(origin), coordinate(destination));
        String directionPath = "transit".equals(normalizedMode)
            ? "/v5/direction/transit/integrated"
            : "/v5/direction/" + normalizedMode;
        JsonNode root = get(directionPath, params);
        if ("transit".equals(normalizedMode)) {
            JsonNode transits = root.path("route").path("transits");
            if (!transits.isArray() || transits.isEmpty()) {
                throw new BizException("高德公交路径规划没有返回可用方案。");
            }
            JsonNode selectedTransit = transits.get(0);
            List<MapPoint> transitPoints = new ArrayList<>();
            appendAllPolylines(selectedTransit, transitPoints);
            List<MapPoint> transitResult = deduplicate(transitPoints);
            if (transitResult.size() < 2) {
                throw new BizException("高德公交路径规划成功，但没有返回可绘制的线路坐标。");
            }
            log.info("[AMap-Route] 公交路径规划成功 pointCount={}", transitResult.size());
            return new RouteResult(
                transitResult,
                parseLong(text(selectedTransit.path("distance"))),
                routeDuration(selectedTransit)
            );
        }

        JsonNode paths = root.path("route").path("paths");
        if (!paths.isArray() || paths.isEmpty()) {
            throw new BizException("高德路径规划没有返回可用方案。");
        }

        JsonNode selectedPath = paths.get(0);
        List<MapPoint> points = new ArrayList<>();
        JsonNode steps = selectedPath.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                appendPolyline(points, text(step.path("polyline")));
            }
        }
        if (points.size() < 2) {
            appendPolyline(points, text(selectedPath.path("polyline")));
        }
        List<MapPoint> result = deduplicate(points);
        if (result.size() < 2) {
            throw new BizException("高德路径规划成功，但没有返回可绘制的道路坐标。");
        }
        log.info("[AMap-Route] 路径规划成功 mode={} pointCount={}", normalizedMode, result.size());
        return new RouteResult(
            result,
            parseLong(text(selectedPath.path("distance"))),
            routeDuration(selectedPath)
        );
    }

    private MapPoint normalizeRoutePoint(String label, MapPoint point) {
        AmapCoordinateNormalizer.NormalizedPoint normalized = AmapCoordinateNormalizer.normalize(point);
        if (!normalized.valid() || normalized.point() == null || !normalized.point().available()) {
            String value = point == null ? "null" : point.longitude() + "," + point.latitude();
            throw new BizException("高德路径规划" + label + "坐标无效：" + value
                + "；坐标必须使用经度,纬度且在合法范围内。");
        }
        if (normalized.swapped()) {
            log.warn("[AMap-Route] 已自动纠正{}坐标顺序 raw={} normalized={}",
                label, coordinate(point), coordinate(normalized.point()));
        }
        return normalized.point();
    }

    private void appendAllPolylines(JsonNode node, List<MapPoint> points) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if ("polyline".equals(entry.getKey()) && entry.getValue().isValueNode()) {
                    appendPolyline(points, text(entry.getValue()));
                } else {
                    appendAllPolylines(entry.getValue(), points);
                }
            });
            return;
        }
        if (node.isArray()) node.forEach(child -> appendAllPolylines(child, points));
    }

    private JsonNode get(String path, Map<String, String> params) throws IOException, InterruptedException {
        Map<String, String> finalParams = new LinkedHashMap<>();
        finalParams.put("key", apiKey());
        finalParams.putAll(params);
        URI requestUri = uri(path, finalParams);
        int maxAttempts = Math.max(1, rateLimitRetries + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            waitForAmapSlot(path, attempt);
            HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
            long startedAt = System.currentTimeMillis();
            HttpResponse<String> response;
            try (ExternalCallBulkheads.Permit ignored =
                     bulkheads.acquire(ExternalCallBulkheads.Provider.AMAP)) {
                response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
            }
            log.debug("[AMap] HTTP GET path={} status={} attempt={}/{} elapsedMs={} bodyLength={}",
                path,
                response.statusCode(),
                attempt,
                maxAttempts,
                System.currentTimeMillis() - startedAt,
                response.body() == null ? 0 : response.body().length());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[AMap] HTTP 状态异常 path={} status={} attempt={}/{}",
                    path, response.statusCode(), attempt, maxAttempts);
                throw new BizException("高德 HTTP 状态异常 path=" + path + " status=" + response.statusCode());
            }
            JsonNode root = JsonUtils.getObjectMapper().readTree(response.body());
            if (!"0".equals(root.path("status").asText(""))) {
                return root;
            }
            String infocode = text(root.path("infocode"));
            String info = text(root.path("info"));
            if (isRateLimited(infocode) && attempt < maxAttempts) {
                log.warn("[AMap] 触发高德 QPS 限流，等待后重试 path={} infocode={} info={} attempt={}/{} retryDelayMs={}",
                    path, infocode, info, attempt, maxAttempts, safeRetryDelayMs());
                Thread.sleep(safeRetryDelayMs());
                continue;
            }
            log.warn("[AMap] 业务状态失败 path={} infocode={} info={} attempt={}/{}",
                path, infocode, info, attempt, maxAttempts);
            throw new BizException("高德接口返回失败 path=" + path
                + " infocode=" + infocode
                + " info=" + info);
        }
        throw new BizException("高德接口调用失败 path=" + path);
    }

    private void waitForAmapSlot(String path, int attempt) throws InterruptedException {
        long interval = Math.max(0, minIntervalMs);
        if (interval <= 0) {
            return;
        }
        synchronized (rateLimitMonitor) {
            long now = System.currentTimeMillis();
            long waitMs = lastRequestAt + interval - now;
            if (waitMs > 0) {
                log.debug("[AMap] 请求节流等待 path={} attempt={} waitMs={}", path, attempt, waitMs);
                Thread.sleep(waitMs);
                now = System.currentTimeMillis();
            }
            lastRequestAt = now;
        }
    }

    private boolean isRateLimited(String infocode) {
        return AMAP_RATE_LIMIT_INFOCODE.equals(infocode);
    }

    private boolean isSpecialAdministrativeRegion(String adcode) {
        return adcode != null && (adcode.startsWith("81") || adcode.startsWith("82"));
    }

    private long safeRetryDelayMs() {
        return Math.max(0, rateLimitRetryDelayMs);
    }

    private URI uri(String path, Map<String, String> params) {
        String query = params.entrySet().stream()
            .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBaseUrl + path + "?" + query);
    }

    private String apiKey() {
        return runtimeSettingsService.stringValue(TripstarSettingKeys.AMAP_WEB_KEY).orElse("");
    }

    public void validateReady() {
        String apiKey = apiKey();
        if (!enabled) {
            log.warn("[AMap] 高德地图未启用 enabled={}", enabled);
            throw new BizException("高德地图未启用，请检查 tripstar.map.amap.enabled 配置。");
        }
        if (apiKey.isBlank()) {
            log.warn("[AMap] 高德地图 Web Service Key 未配置");
            throw new BizException("高德地图 Web Service Key 未配置，请先在设置页填写“高德地图 Web Service Key”。");
        }
    }

    private MapPoint parsePoint(String location) {
        if (location == null || location.isBlank() || !location.contains(",")) {
            return null;
        }
        String[] parts = location.split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new MapPoint(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String firstPhoto(JsonNode photos) {
        if (photos == null || !photos.isArray() || photos.isEmpty()) {
            return "";
        }
        return text(photos.get(0).path("url"));
    }

    private String coordinate(MapPoint point) {
        return formatCoordinate(point.longitude()) + "," + formatCoordinate(point.latitude());
    }

    private String formatCoordinate(Double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private void appendPolyline(List<MapPoint> target, String polyline) {
        if (polyline == null || polyline.isBlank()) {
            return;
        }
        for (String pair : polyline.split(";")) {
            MapPoint point = parsePoint(pair);
            if (point != null && point.available()) {
                target.add(point);
            }
        }
    }

    private List<MapPoint> deduplicate(List<MapPoint> points) {
        List<MapPoint> result = new ArrayList<>();
        MapPoint previous = null;
        for (MapPoint point : points) {
            if (previous == null
                || !previous.longitude().equals(point.longitude())
                || !previous.latitude().equals(point.latitude())) {
                result.add(point);
                previous = point;
            }
        }
        return result;
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String value = text(item);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return String.join("、", values);
        }
        return node.asText("");
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private long routeDuration(JsonNode route) {
        String direct = text(route.path("duration"));
        String cost = text(route.path("cost").path("duration"));
        return parseLong(firstNonBlank(direct, cost));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record GeocodeResult(String adcode, MapPoint point) {
    }

    public record RouteResult(List<MapPoint> path, long distanceMeters, long durationSeconds) {
    }
}
