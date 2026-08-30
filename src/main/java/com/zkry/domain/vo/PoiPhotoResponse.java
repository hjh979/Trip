package com.zkry.domain.vo;

public record PoiPhotoResponse(
    Boolean success,
    String message,
    PoiPhotoData data
) {
    public record PoiPhotoData(
        String name,
        String photo_url
    ) {
    }
}
