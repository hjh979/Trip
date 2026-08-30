package com.zkry.domain.vo;

import com.zkry.domain.dto.TripPlan;
import com.zkry.domain.dto.TripPatchOperation;
import java.util.List;

/** Complete result of an AI conversation turn against the current itinerary. */
public record TripChatResponse(
    Boolean success,
    String reply,
    String change_summary,
    TripPlan trip_plan,
    String retrieval_trace_id,
    List<RagCitationView> citations,
    List<TripPatchOperation> operations,
    boolean need_route_recalculate
) {
    public TripChatResponse(
        Boolean success,
        String reply,
        String changeSummary,
        TripPlan tripPlan
    ) {
        this(success, reply, changeSummary, tripPlan, "", List.of(), List.of(), false);
    }

    public TripChatResponse(
        Boolean success,
        String reply,
        String changeSummary,
        TripPlan tripPlan,
        String retrievalTraceId,
        List<RagCitationView> citations
    ) {
        this(success, reply, changeSummary, tripPlan, retrievalTraceId, citations, List.of(), false);
    }
}
