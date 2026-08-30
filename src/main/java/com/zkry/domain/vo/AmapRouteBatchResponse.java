package com.zkry.domain.vo;

import com.zkry.domain.dto.map.MapPoint;
import java.util.List;

public record AmapRouteBatchResponse(
    boolean success,
    String message,
    int success_count,
    int failure_count,
    List<RouteSegmentResult> routes
) {
    public record RouteSegmentResult(
        String id,
        String mode,
        boolean success,
        String message,
        long distance_meters,
        long duration_seconds,
        List<MapPoint> path
    ) {
    }
}
