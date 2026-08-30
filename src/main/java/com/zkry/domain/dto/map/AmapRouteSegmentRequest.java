package com.zkry.domain.dto.map;

public record AmapRouteSegmentRequest(
    String id,
    String mode,
    String city,
    MapPoint origin,
    MapPoint destination
) {
}
