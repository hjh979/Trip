package com.zkry.domain.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public snapshot envelope used by orchestration APIs and audit tooling. */
public record TripSnapshot(
    String plan_id,
    Integer version,
    Map<String, Object> request,
    TripPlan plan,
    Map<String, Object> constraints,
    List<TripPlanSnapshot.VerifiedPoi> verified_pois,
    List<String> evidence_refs,
    String summary,
    Instant created_at
) {
    public TripSnapshot {
        request = request == null ? Map.of() : Map.copyOf(request);
        constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
        verified_pois = verified_pois == null ? List.of() : verified_pois.stream().filter(java.util.Objects::nonNull).toList();
        evidence_refs = evidence_refs == null ? List.of() : evidence_refs.stream().filter(value -> value != null && !value.isBlank()).toList();
        created_at = created_at == null ? Instant.now() : created_at;
    }
}
