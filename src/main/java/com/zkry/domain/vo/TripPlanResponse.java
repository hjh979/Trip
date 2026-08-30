package com.zkry.domain.vo;

import com.zkry.domain.dto.KnowledgeGraphData;
import com.zkry.domain.dto.TripPlan;

public record TripPlanResponse(
    Boolean success,
    String message,
    String plan_id,
    TripPlan data,
    KnowledgeGraphData graph_data,
    TripResearchEvidence research_evidence
) {
}
