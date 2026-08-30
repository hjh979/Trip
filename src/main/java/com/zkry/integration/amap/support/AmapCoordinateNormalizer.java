package com.zkry.integration.amap.support;

import com.zkry.domain.dto.Location;
import com.zkry.domain.dto.map.MapPoint;

/**
 * 高德坐标顺序修正器。
 *
 * <p>高德 Web Service 固定使用“经度,纬度”。生成式模型偶尔会把中国坐标写成
 * “纬度,经度”，例如把杭州写为 {@code 30.24,120.10}。这种情况的纬度会超过 90，
 * 高德返回 {@code infocode=20000 INVALID_PARAMS}。这里在系统边界统一识别并交换。
 */
public final class AmapCoordinateNormalizer {

    private AmapCoordinateNormalizer() {
    }

    public static NormalizedPoint normalize(MapPoint point) {
        if (point == null || point.longitude() == null || point.latitude() == null) {
            return new NormalizedPoint(point, false, false);
        }
        double longitude = point.longitude();
        double latitude = point.latitude();
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)) {
            return new NormalizedPoint(point, false, false);
        }
        if (isValid(longitude, latitude)) {
            return new NormalizedPoint(point, false, true);
        }
        if (isLikelySwappedChinaCoordinate(longitude, latitude)) {
            return new NormalizedPoint(new MapPoint(latitude, longitude), true, true);
        }
        return new NormalizedPoint(point, false, false);
    }

    public static NormalizedLocation normalize(Location location) {
        if (location == null) {
            return new NormalizedLocation(null, false, false);
        }
        NormalizedPoint result = normalize(new MapPoint(location.longitude(), location.latitude()));
        MapPoint point = result.point();
        Location normalized = point == null ? null : new Location(point.longitude(), point.latitude());
        return new NormalizedLocation(normalized, result.swapped(), result.valid());
    }

    /** 经纬度的全球合法范围；高德请求前至少必须满足这一约束。 */
    public static boolean isValid(double longitude, double latitude) {
        return longitude >= -180 && longitude <= 180 && latitude >= -90 && latitude <= 90;
    }

    /** 中国大陆及港澳附近的典型范围，用于无歧义识别“纬度,经度”反写。 */
    private static boolean isLikelySwappedChinaCoordinate(double longitude, double latitude) {
        return longitude >= 3 && longitude <= 54 && latitude >= 73 && latitude <= 135;
    }

    public record NormalizedPoint(MapPoint point, boolean swapped, boolean valid) {
    }

    public record NormalizedLocation(Location location, boolean swapped, boolean valid) {
    }
}
