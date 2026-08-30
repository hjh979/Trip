package com.zkry.domain.dto.map;

import java.util.List;

public record AmapRouteBatchRequest(
    List<AmapRouteSegmentRequest> segments
) {
}
