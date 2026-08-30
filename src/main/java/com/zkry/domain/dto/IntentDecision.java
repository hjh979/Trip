package com.zkry.domain.dto;

import java.util.List;

/** Stable routing decision shared by HTTP, async tasks and conversational edits. */
public record IntentDecision(
    Intent intent,
    Mode mode,
    double confidence,
    String scope,
    List<String> missing_slots,
    boolean need_rag,
    boolean need_tools
) {
    public enum Intent { CREATE_TRIP, EDIT_TRIP, ASK_TRIP, REPLAN_TRIP, CANCEL_TASK, UNKNOWN }
    public enum Mode { FULL_PLAN, LOCAL_PATCH, SEMANTIC_PATCH, SCOPED_REPLAN, RAG_QA, CANCEL, UNKNOWN }

    public IntentDecision {
        missing_slots = missing_slots == null ? List.of() : List.copyOf(missing_slots);
        scope = scope == null || scope.isBlank() ? "FULL_TRIP" : scope;
    }
}
