package com.zkry.domain.vo;

import java.util.List;

public record TripDayView(
    Long id,
    Integer day_number,
    String date,
    String title,
    List<TripItemView> items
) {
}
