package com.zkry.domain.dto.map;

import java.util.List;

public record AmapStaticMapRequest(
    List<MapPoint> markers,
    List<List<MapPoint>> paths
) {
}
