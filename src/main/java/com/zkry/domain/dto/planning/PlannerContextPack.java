package com.zkry.domain.dto.planning;

import java.util.List;
import java.util.Map;

/**
 * Bounded input package for the single travel-planning Agent.
 */
public record PlannerContextPack(
    String context,
    Map<String, Integer> sourceCounts,
    List<String> traceIds,
    List<String> warnings,
    int characterCount,
    boolean factsVerified,
    List<String> candidateNames
) {
    /** Compatibility constructor for context packs built before fact verification was split out. */
    public PlannerContextPack(
        String context,
        Map<String, Integer> sourceCounts,
        List<String> traceIds,
        List<String> warnings,
        int characterCount
    ) {
        this(context, sourceCounts, traceIds, warnings, characterCount, false, List.of());
    }

    public int count(String source) {
        if (sourceCounts == null || source == null) {
            return 0;
        }
        return sourceCounts.getOrDefault(source, 0);
    }

    public List<String> safeTraceIds() {
        return traceIds == null ? List.of() : traceIds;
    }

    public List<String> safeWarnings() {
        return warnings == null ? List.of() : warnings;
    }

    public PlannerContextPack withFacts(PlanningFactPack facts) {
        return withFacts(facts, null);
    }

    public PlannerContextPack withFacts(PlanningFactPack facts, List<String> requestedCities) {
        if (facts == null || facts.cities().isEmpty()) return this;
        String merged = (context == null ? "" : context) + "\n\n【AMap 已核验事实】\n" + facts.asPromptContext();
        List<String> mergedWarnings = new java.util.ArrayList<>(safeWarnings());
        mergedWarnings.addAll(facts.warnings());
        return new PlannerContextPack(merged, sourceCounts, traceIds, mergedWarnings, merged.length(),
            facts.completeForPlanning(requestedCities == null
                ? facts.cities().stream().map(CityFactPack::city).toList() : requestedCities), candidateNames);
    }

    public PlannerContextPack withCandidateNames(List<String> names) {
        return new PlannerContextPack(context, sourceCounts, traceIds, warnings, characterCount, factsVerified,
            names == null ? List.of() : names.stream().filter(value -> value != null && !value.isBlank()).distinct().limit(20).toList());
    }
}
