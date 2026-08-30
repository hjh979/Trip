package com.zkry.domain.vo;

import java.util.Map;

public record TripTaskEvent(
    String task_id,
    String plan_id,
    String status,
    String stage,
    Integer progress,
    String message,
    String error,
    TripPlanResponse result,
    Map<String, Object> request_payload,
    Long seq,
    Integer version,
    String result_url
) {
    public TripTaskEvent(
        String task_id,
        String plan_id,
        String status,
        String stage,
        Integer progress,
        String message,
        String error,
        TripPlanResponse result,
        Map<String, Object> request_payload
    ) {
        this(task_id, plan_id, status, stage, progress, message, error, result, request_payload, null, null, null);
    }
}
