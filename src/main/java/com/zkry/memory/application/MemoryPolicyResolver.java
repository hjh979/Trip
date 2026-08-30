package com.zkry.memory.application;

import com.zkry.common.util.JsonUtils;
import com.zkry.memory.domain.MemoryContextPack;
import com.zkry.memory.domain.MemoryStatus;
import com.zkry.memory.domain.ResolvedMemoryItem;
import com.zkry.memory.domain.UserMemoryFact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** Applies scope and precedence in Java before a memory is allowed into an AI prompt. */
@Component
public class MemoryPolicyResolver {
    public MemoryContextPack resolve(List<UserMemoryFact> facts, String city, String planId) {
        List<ResolvedMemoryItem> accepted = new ArrayList<>();
        List<String> suppressed = new ArrayList<>();
        for (UserMemoryFact fact : facts == null ? List.<UserMemoryFact>of() : facts) {
            if (!MemoryStatus.ACTIVE.name().equals(fact.getStatus()) || !inScope(fact, city, planId)) continue;
            int priority = priority(fact);
            if (priority <= 0) { suppressed.add(fact.getMemoryKey() + "：范围不匹配"); continue; }
            accepted.add(new ResolvedMemoryItem(fact.getMemoryType(), fact.getMemoryKey(),
                parseValue(fact.getMemoryValueJson()), priority, fact.getSource(), fact.getScopeType(),
                fact.getConfidence() == null ? 0D : fact.getConfidence(), evidence(fact.getEvidenceRefsJson())));
        }
        accepted.sort(Comparator.comparingInt(ResolvedMemoryItem::priority).reversed()
            .thenComparing(Comparator.comparingDouble(ResolvedMemoryItem::confidence).reversed()));
        return new MemoryContextPack(accepted, suppressed);
    }
    private boolean inScope(UserMemoryFact f, String city, String planId) {
        return switch (f.getScopeType()) {
            case "GLOBAL" -> true;
            case "CITY" -> f.getScopeValue() != null && f.getScopeValue().equalsIgnoreCase(city == null ? "" : city);
            case "TRIP" -> f.getScopeValue() != null && f.getScopeValue().equals(planId);
            case "SEASON" -> true; // date-sensitive filtering can be added without changing persisted facts.
            default -> false;
        };
    }
    private int priority(UserMemoryFact fact) {
        if (Boolean.TRUE.equals(fact.getHardConstraint())) return 90;
        if ("CONFIRMED".equals(fact.getSource())) return 70;
        if ("EXPLICIT".equals(fact.getSource())) return 60;
        return fact.getConfidence() != null && fact.getConfidence() >= .8D ? 40 : 0;
    }
    private Object parseValue(String value) { try { return JsonUtils.parseObject(value, Object.class); } catch (RuntimeException e) { return value; } }
    private List<String> evidence(String value) { try { return JsonUtils.parseObject(value, List.class); } catch (RuntimeException e) { return List.of(); } }
}
