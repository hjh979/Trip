package com.zkry.domain.dto;

import java.util.List;

/** The actor identity is intentionally absent; the server takes it from the authenticated task. */
public record AiModificationTaskRequest(
    String planId,
    Integer baseVersion,
    String message,
    List<ChatMessage> history,
    IntentDecision.Mode mode,
    String scope,
    String input_hash
) {
    public AiModificationTaskRequest(String planId, Integer baseVersion, String message, List<ChatMessage> history) {
        this(planId, baseVersion, message, history, null, "FULL_TRIP", null);
    }
}
