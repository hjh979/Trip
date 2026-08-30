package com.zkry.controller;

import com.zkry.integration.amap.service.AmapMapContextService;
import com.zkry.integration.amap.service.AmapPoiPhotoService;
import com.zkry.domain.dto.map.MapPoi;
import com.zkry.domain.dto.map.MapPoint;
import com.zkry.domain.vo.PoiPhotoResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/poi")
public class PoiController {

    private static final Logger log = LoggerFactory.getLogger(PoiController.class);

    private final AmapPoiPhotoService amapPoiPhotoService;
    private final AmapMapContextService amapMapContextService;

    public PoiController(
        AmapPoiPhotoService amapPoiPhotoService,
        AmapMapContextService amapMapContextService
    ) {
        this.amapPoiPhotoService = amapPoiPhotoService;
        this.amapMapContextService = amapMapContextService;
    }

    /**
     * 指定笔记模式使用的高德图片接口。
     *
     * <p>与原 {@code /photo} 完全分离，避免自主规划在没有显式选择时改变图片数据源。
     */
    @GetMapping("/photo/amap")
    public PoiPhotoResponse amapPhoto(@RequestParam String name, @RequestParam String city) {
        long startedAt = System.currentTimeMillis();
        log.info("[POI-AMAP] 收到景点图片请求 name={} city={}", name, city);
        String photoUrl = amapPoiPhotoService.photo(city, name);
        String message = photoUrl.isBlank()
            ? "未从高德 POI 找到可用图片。"
            : "获取高德 POI 图片成功";
        log.info("[POI-AMAP] 景点图片请求完成 name={} city={} found={} elapsedMs={}",
            name, city, !photoUrl.isBlank(), System.currentTimeMillis() - startedAt);
        return new PoiPhotoResponse(
            !photoUrl.isBlank(),
            message,
            new PoiPhotoResponse.PoiPhotoData(name, photoUrl)
        );
    }

    /** 创建行程页使用后端 Web Service 搜索 POI，浏览器地图只负责交互式渲染。 */
    @GetMapping("/search/amap")
    public AmapPoiSearchResponse amapSearch(
        @RequestParam String city,
        @RequestParam String keywords,
        @RequestParam(defaultValue = "8") int limit
    ) throws IOException, InterruptedException {
        int safeLimit = Math.min(Math.max(limit, 1), 10);
        return new AmapPoiSearchResponse(amapMapContextService.searchPois(city, keywords, safeLimit));
    }

    /** 地图初始定位同样走已配置的高德 Web Service Key，避免前端服务插件权限差异。 */
    @GetMapping("/geocode/amap")
    public AmapGeocodeResponse amapGeocode(@RequestParam String city)
        throws IOException, InterruptedException {
        AmapMapContextService.GeocodeResult result = amapMapContextService.geocode(city);
        return new AmapGeocodeResponse(result.adcode(), result.point());
    }

    /** 浏览器导出攻略时通过该接口读取高德图片，避免远程图片污染 Canvas。 */
    @GetMapping("/photo/amap/proxy")
    public ResponseEntity<byte[]> amapPhotoProxy(@RequestParam String url) {
        AmapPoiPhotoService.PhotoContent photo = amapPoiPhotoService.proxy(url);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(photo.contentType()))
            .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
            .body(photo.bytes());
    }

    public record AmapPoiSearchResponse(List<MapPoi> items) {
    }

    public record AmapGeocodeResponse(String adcode, MapPoint location) {
    }
}
