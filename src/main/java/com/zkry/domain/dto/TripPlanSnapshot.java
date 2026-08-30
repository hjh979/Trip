package com.zkry.domain.dto;

import java.util.List;

/**
 * Immutable context captured together with an itinerary version.
 * Keeping this envelope separate from {@link TripPlan} lets edits reuse facts
 * and constraints without sending the whole conversation back to the model.
 */
public record TripPlanSnapshot(
    TripPlan plan,
    Integer version,
    List<VerifiedPoi> verified_pois,
    List<String> rag_trace_ids,
    TripPlanConstraints constraints
) {
    public TripPlanSnapshot {
        verified_pois = verified_pois == null ? List.of() : verified_pois.stream().filter(java.util.Objects::nonNull).toList();
        rag_trace_ids = rag_trace_ids == null ? List.of() : rag_trace_ids.stream()
            .filter(value -> value != null && !value.isBlank()).toList();
    }

    public record VerifiedPoi(
        String name,
        Double longitude,
        Double latitude,
        String source
    ) { }

    public record TripPlanConstraints(
        Long budget,
        Integer travel_days,
        List<String> preferences,
        String start_date,
        String end_date,
        List<String> cities
    ) {
        public TripPlanConstraints {
            preferences = preferences == null ? List.of() : List.copyOf(preferences);
            cities = cities == null ? List.of() : List.copyOf(cities);
        }
    }
}
