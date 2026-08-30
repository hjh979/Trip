package com.zkry.domain.dto.map;

import com.zkry.domain.dto.Location;

public record MapPoi(
    String name,
    String address,
    MapPoint location,
    String type,
    String rating,
    String distance,
    String photoUrl
) {
}
