package com.zkry.domain.vo;

public record TripSummaryView(
    String plan_id,
    String title,
    String city,
    String start_date,
    String end_date,
    Integer travel_days,
    String status,
    String visibility,
    long item_count,
    long member_count,
    String updated_at,
    boolean owner
) {
}
