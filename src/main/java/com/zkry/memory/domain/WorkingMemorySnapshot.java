package com.zkry.memory.domain;

import java.time.Instant;
import java.util.List;

/** Compact, resumable task context. Large plans and raw tool responses remain in their own stores. */
public record WorkingMemorySnapshot(
    String taskId,
    String planId,
    Integer planVersion,
    RequestConstraintSummary constraints,
    List<ConversationTurnSummary> recentTurns,
    ToolExecutionSummary toolSummary,
    RetrievalSummary retrieval,
    VerifiedFactSummary verifiedFacts,
    RiskSummary risks,
    Instant updatedAt
) {
    public record RequestConstraintSummary(List<String> cities, String startDate, String endDate,
                                           Integer travelDays, List<String> explicitPreferences) { }
    public record ConversationTurnSummary(String role, String intent, String summary) { }
    public record ToolExecutionSummary(List<String> calledTools, List<String> failures) { }
    public record RetrievalSummary(List<String> traceIds, List<String> evidenceIds) { }
    public record VerifiedFactSummary(List<String> validatedPois, List<String> weatherRisk, List<String> routeRisk) { }
    public record RiskSummary(List<String> warnings) { }
}
