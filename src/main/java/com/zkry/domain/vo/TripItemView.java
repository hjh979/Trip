package com.zkry.domain.vo;

public record TripItemView(
    Long id,
    Integer order,
    String name,
    String address,
    double longitude,
    double latitude,
    String start_time,
    Integer stay_minutes,
    String category,
    String note,
    String photo_url,
    Long cost_cents
) {
}
