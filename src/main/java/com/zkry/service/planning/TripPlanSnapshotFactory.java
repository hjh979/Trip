package com.zkry.service.planning;

import com.zkry.domain.dto.Attraction;
import com.zkry.domain.dto.DayPlan;
import com.zkry.domain.dto.TripPlan;
import com.zkry.domain.dto.TripPlanSnapshot;
import com.zkry.domain.dto.TripSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Builds a stable, compact snapshot from a generated or edited plan. */
@Service
public class TripPlanSnapshotFactory {

    public TripSnapshot createSnapshot(String planId, TripPlan plan, Integer version,
                                       java.util.Map<String, Object> request, List<String> evidenceRefs) {
        TripPlanSnapshot compact = create(plan, version, evidenceRefs);
        java.util.Map<String, Object> constraints = new java.util.LinkedHashMap<>();
        if (compact.constraints() != null) {
            constraints.put("budget", compact.constraints().budget());
            constraints.put("travel_days", compact.constraints().travel_days());
            constraints.put("preferences", compact.constraints().preferences());
            constraints.put("start_date", compact.constraints().start_date());
            constraints.put("end_date", compact.constraints().end_date());
            constraints.put("cities", compact.constraints().cities());
        }
        return new TripSnapshot(planId, version, request, plan, constraints,
            compact.verified_pois(), compact.rag_trace_ids(), plan == null ? "" : plan.overall_suggestions(), null);
    }

    public TripPlanSnapshot create(TripPlan plan, Integer version, List<String> ragTraceIds) {
        return create(plan, version, ragTraceIds, List.of());
    }

    public TripPlanSnapshot create(
        TripPlan plan,
        Integer version,
        List<String> ragTraceIds,
        List<String> preferences
    ) {
        if (plan == null) return new TripPlanSnapshot(null, version, List.of(), ragTraceIds, null);
        List<TripPlanSnapshot.VerifiedPoi> pois = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DayPlan day : plan.days() == null ? List.<DayPlan>of() : plan.days()) {
            for (Attraction attraction : day == null || day.attractions() == null
                ? List.<Attraction>of() : day.attractions()) {
                if (attraction == null || attraction.name() == null || attraction.location() == null
                    || attraction.location().longitude() == null || attraction.location().latitude() == null) continue;
                if (seen.add(attraction.name().trim().toLowerCase())) {
                    pois.add(new TripPlanSnapshot.VerifiedPoi(
                        attraction.name().trim(), attraction.location().longitude(), attraction.location().latitude(), "AMAP"));
                }
            }
        }
        Long budget = plan.budget() == null || plan.budget().total() == null
            ? 0L : Math.max(0L, plan.budget().total().longValue());
        TripPlanSnapshot.TripPlanConstraints constraints = new TripPlanSnapshot.TripPlanConstraints(
            budget,
            plan.days() == null ? 0 : plan.days().size(),
            clean(preferences),
            plan.start_date(),
            plan.end_date(),
            clean(plan.cities() == null ? (plan.city() == null ? List.of() : List.of(plan.city())) : plan.cities())
        );
        return new TripPlanSnapshot(plan, version, pois, ragTraceIds, constraints);
    }

    private List<String> clean(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }
}
