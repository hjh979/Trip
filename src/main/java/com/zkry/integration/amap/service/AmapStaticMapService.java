package com.zkry.integration.amap.service;

import com.zkry.common.constant.TripstarSettingKeys;
import com.zkry.common.exception.BizException;
import com.zkry.domain.dto.map.AmapStaticMapRequest;
import com.zkry.domain.dto.map.MapPoint;
import com.zkry.integration.amap.support.AmapCoordinateNormalizer;
import com.zkry.service.TripstarRuntimeSettingsService;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class AmapStaticMapService {

    private static final int MAX_MARKERS = 10;
    private static final int MAX_PATHS = 4;
    private static final int MAX_PATH_POINTS = 80;

    private final TripstarRuntimeSettingsService settings;
    private final RestClient client = RestClient.create();

    public AmapStaticMapService(TripstarRuntimeSettingsService settings) {
        this.settings = settings;
    }

    public byte[] render(AmapStaticMapRequest request) {
        String key = settings.stringValue(TripstarSettingKeys.AMAP_WEB_KEY)
            .orElseThrow(() -> new BizException("高德 Web Service Key 未配置。"));
        List<MapPoint> markers = valid(request == null ? null : request.markers(), MAX_MARKERS);
        if (markers.isEmpty()) throw new BizException("静态地图至少需要一个有效地点。 ");

        UriComponentsBuilder builder = UriComponentsBuilder
            .fromUriString("https://restapi.amap.com/v3/staticmap")
            .queryParam("key", key)
            .queryParam("size", "1024*660")
            .queryParam("scale", "1")
            .queryParam("traffic", "0")
            .queryParam("markers", markerParameter(markers));

        String pathParameter = pathParameter(request == null ? null : request.paths());
        if (!pathParameter.isBlank()) builder.queryParam("paths", pathParameter);
        URI uri = builder.build().encode().toUri();
        byte[] image = client.get().uri(uri).retrieve().body(byte[].class);
        if (image == null || image.length < 1024) throw new BizException("高德静态地图没有返回有效图片。 ");
        return image;
    }

    private String markerParameter(List<MapPoint> markers) {
        List<String> groups = new ArrayList<>();
        for (int index = 0; index < markers.size(); index++) {
            groups.add("mid,0x145B5D," + (index + 1) + ":" + coordinate(markers.get(index)));
        }
        return String.join("|", groups);
    }

    private String pathParameter(List<List<MapPoint>> rawPaths) {
        if (rawPaths == null || rawPaths.isEmpty()) return "";
        List<String> paths = new ArrayList<>();
        for (List<MapPoint> rawPath : rawPaths.stream().limit(MAX_PATHS).toList()) {
            List<MapPoint> points = simplify(valid(rawPath, Integer.MAX_VALUE));
            if (points.size() < 2) continue;
            paths.add("6,0x145B5D,0.9,,:" + points.stream().map(this::coordinate)
                .reduce((left, right) -> left + ";" + right).orElse(""));
        }
        return String.join("|", paths);
    }

    private List<MapPoint> simplify(List<MapPoint> points) {
        if (points.size() <= MAX_PATH_POINTS) return points;
        int step = (int) Math.ceil(points.size() / (double) MAX_PATH_POINTS);
        List<MapPoint> result = new ArrayList<>();
        for (int index = 0; index < points.size(); index += step) result.add(points.get(index));
        MapPoint last = points.getLast();
        if (!result.getLast().equals(last)) result.add(last);
        return result;
    }

    private List<MapPoint> valid(List<MapPoint> points, int limit) {
        if (points == null) return List.of();
        return points.stream()
            .map(AmapCoordinateNormalizer::normalize)
            .filter(AmapCoordinateNormalizer.NormalizedPoint::valid)
            .map(AmapCoordinateNormalizer.NormalizedPoint::point)
            .limit(limit)
            .toList();
    }

    private String coordinate(MapPoint point) {
        return String.format(Locale.ROOT, "%.6f,%.6f", point.longitude(), point.latitude());
    }
}
