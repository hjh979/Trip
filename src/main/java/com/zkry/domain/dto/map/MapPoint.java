package com.zkry.domain.dto.map;

public record MapPoint(
    Double longitude,
    Double latitude
) {
    public boolean available() {
        return longitude != null && latitude != null;
    }
}
