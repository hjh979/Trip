package com.zkry.domain.dto;

import java.util.List;

public record TripChatRequest(
    String message,
    TripPlan trip_plan,
    List<ChatMessage> history,
    TripPlanSnapshot snapshot,
    String tenant_id,
    String user_id
) {
    public TripChatRequest(String message, TripPlan tripPlan, List<ChatMessage> history) {
        this(message, tripPlan, history, null, "public", "public");
    }

    public TripChatRequest(String message, TripPlan tripPlan, List<ChatMessage> history, TripPlanSnapshot snapshot) {
        this(message, tripPlan, history, snapshot, "public", "public");
    }
}
