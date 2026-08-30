package com.zkry.memory.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.memory.domain.MemoryType;
import com.zkry.memory.domain.TripMemoryEvent;
import com.zkry.memory.infrastructure.TripMemoryEventMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Conservative batch inference: events become candidates only after cross-trip, time-spanning consistency. */
@Component
public class MemoryConsolidationJob {
    private final TripMemoryEventMapper events; private final UserMemoryCommandService memories;
    public MemoryConsolidationJob(TripMemoryEventMapper events, UserMemoryCommandService memories) { this.events = events; this.memories = memories; }
    @Scheduled(cron = "0 0 * * * *")
    public void consolidate() {
        List<TripMemoryEvent> pending = events.selectList(Wrappers.<TripMemoryEvent>lambdaQuery()
            .eq(TripMemoryEvent::getConsolidationStatus, "PENDING").orderByAsc(TripMemoryEvent::getOccurredAt).last("LIMIT 500"));
        // Keep contradictory choices and different targets separate. A broad user+type bucket
        // could incorrectly infer a preference from mutually exclusive trips.
        Map<String, List<TripMemoryEvent>> groups = pending.stream().collect(Collectors.groupingBy(e ->
            e.getUserId() + "|" + e.getEventType() + "|" + (e.getTargetRef() == null ? "" : e.getTargetRef())));
        groups.values().forEach(this::process);
    }
    private void process(List<TripMemoryEvent> group) {
        if (group.isEmpty()) return;
        long plans = group.stream().map(TripMemoryEvent::getPlanId).distinct().count();
        LocalDateTime first = group.getFirst().getOccurredAt(), last = group.getLast().getOccurredAt();
        boolean enough = group.size() >= 3 && plans >= 2 && first != null && last != null && Duration.between(first, last).toDays() >= 7;
        if (enough) {
            MemoryType type = type(group.getFirst().getEventType());
            if (type != null) memories.upsertCandidate(group.getFirst().getUserId(),
                new UserMemoryCommandService.MemoryInput(type.name(), "repeated_" + group.getFirst().getEventType().toLowerCase(),
                    Map.of("eventType", group.getFirst().getEventType(), "count", group.size()), "GLOBAL", "", false,
                    group.stream().map(TripMemoryEvent::getEventId).toList()), Math.min(.85D, .35D + group.size() * .1D),
                group.stream().map(TripMemoryEvent::getEventId).toList());
        }
        // Insufficient evidence stays pending so a later trip can complete the observation
        // window; permanently skipping it made the memory layer lose valid signals.
        if (enough) group.forEach(e -> { e.setConsolidationStatus("PROCESSED"); e.setProcessedAt(LocalDateTime.now()); events.updateById(e); });
    }
    private MemoryType type(String eventType) { return switch (eventType) {
        case "PACE_CHANGED" -> MemoryType.PACE; case "BUDGET_CHANGED" -> MemoryType.BUDGET;
        case "HOTEL_CHANGED" -> MemoryType.LODGING; case "TRANSPORT_CHANGED" -> MemoryType.TRANSPORT; default -> null; }; }
}
