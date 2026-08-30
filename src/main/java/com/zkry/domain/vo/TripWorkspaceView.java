package com.zkry.domain.vo;

import java.util.List;

public record TripWorkspaceView(
    String plan_id,
    Integer version,
    String title,
    String city,
    String city_code,
    String start_date,
    String end_date,
    Integer travel_days,
    Long budget_cents,
    String status,
    String visibility,
    List<TripDayView> days,
    List<TripMemberView> members,
    com.zkry.domain.dto.TripPlan plan
) {
}
