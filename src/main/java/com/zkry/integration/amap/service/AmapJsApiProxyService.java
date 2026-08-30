package com.zkry.integration.amap.service;

import com.zkry.common.exception.BizException;
import com.zkry.common.exception.CommonErrorCode;
import com.zkry.common.constant.TripstarSettingKeys;
import com.zkry.service.TripstarRuntimeSettingsService;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 高德 JS API 安全代理。
 *
 * <p>浏览器只访问同源的 {@code /_AMapService}；安全密钥由后端注入，既避免密钥出现在
 * 前端产物中，也避开浏览器直接请求高德鉴权服务时的跨域与网络兼容问题。</p>
 */
@Service
public class AmapJsApiProxyService {

    private static final String PROXY_PREFIX = "/_AMapService";
    private static final String STYLE_ORIGIN = "https://webapi.amap.com";
    private static final String REST_ORIGIN = "https://restapi.amap.com";
    private static final String JSAPI_ORIGIN = "https://jsapi.amap.com";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final TripstarRuntimeSettingsService runtimeSettingsService;

    public AmapJsApiProxyService(TripstarRuntimeSettingsService runtimeSettingsService) {
        this.runtimeSettingsService = runtimeSettingsService;
    }

    public ProxyResponse forward(String requestUri, String rawQuery) {
        String jscode = runtimeSettingsService.stringValue(TripstarSettingKeys.AMAP_SECURITY_JS_CODE).orElse("");
        if (jscode.isBlank()) {
            throw new BizException(CommonErrorCode.BUSINESS_ERROR, "高德 JS API 安全代理缺少 AMAP_JS_SECURITY_CODE");
        }

        String path = normalizePath(requestUri);
        String upstreamPath = path;
        String origin;
        if (path.startsWith("/_jsapi/")) {
            origin = JSAPI_ORIGIN;
            upstreamPath = path.substring("/_jsapi".length());
        } else if (path.startsWith("/v4/map/styles")) {
            origin = STYLE_ORIGIN;
        } else {
            origin = REST_ORIGIN;
        }
        String query = appendSecurityCode(rawQuery, jscode);
        URI target = URI.create(origin + upstreamPath + (query.isBlank() ? "" : "?" + query));

        HttpRequest request = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(12))
            .header("Accept", "*/*")
            .header("User-Agent", "VoyageMind-AMap-Proxy/1.0")
            .GET()
            .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new ProxyResponse(
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse("application/octet-stream"),
                response.headers().firstValue("Cache-Control").orElse("no-store"),
                response.body()
            );
        } catch (IOException exception) {
            throw new BizException(CommonErrorCode.BUSINESS_ERROR, "高德 JS API 安全代理网络请求失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException(CommonErrorCode.BUSINESS_ERROR, "高德 JS API 安全代理请求被中断");
        }
    }

    private String normalizePath(String requestUri) {
        if (requestUri == null || !requestUri.startsWith(PROXY_PREFIX)) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, "高德代理请求路径无效");
        }
        String path = requestUri.substring(PROXY_PREFIX.length());
        if (path.isBlank()) path = "/";
        if (!path.startsWith("/") || path.contains("..") || path.contains(":") || path.contains("\\")) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, "高德代理请求路径无效");
        }
        return path;
    }

    private String appendSecurityCode(String rawQuery, String jscode) {
        String filtered = rawQuery == null || rawQuery.isBlank()
            ? ""
            : Arrays.stream(rawQuery.split("&"))
                .filter(item -> !item.regionMatches(true, 0, "jscode=", 0, "jscode=".length()))
                .collect(Collectors.joining("&"));
        String encoded = URLEncoder.encode(jscode, StandardCharsets.UTF_8);
        return filtered.isBlank() ? "jscode=" + encoded : filtered + "&jscode=" + encoded;
    }

    public record ProxyResponse(int statusCode, String contentType, String cacheControl, byte[] body) {
    }
}
