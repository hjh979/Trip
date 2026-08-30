package com.zkry.domain.vo;

public record SubmitTripPlanResponse(
    String task_id,
    String plan_id,
    String status,
    String message
) {
}
