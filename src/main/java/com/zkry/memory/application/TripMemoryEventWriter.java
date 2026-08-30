package com.zkry.memory.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.TripPatchOperation;
import com.zkry.domain.dto.TripPlanSnapshot;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.vo.TripChatResponse;
import com.zkry.memory.domain.TripMemoryEvent;
import com.zkry.memory.domain.TripMemoryEventType;
import com.zkry.memory.infrastructure.TripMemoryEventMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/** Writes deterministic plan/version deltas in the same transaction as the itinerary. */
@Service
public class TripMemoryEventWriter {
    private final TripMemoryEventMapper mapper;
    public TripMemoryEventWriter(TripMemoryEventMapper mapper) { this.mapper = mapper; }
    public void planCreated(TripTask task, String planId, int version, TripPlanSnapshot snapshot) {
        write(task.getTaskId() + ":" + version + ":created", task, planId, version, TripMemoryEventType.PLAN_CREATED,
            "PLAN", planId, city(snapshot), null, null, "SYSTEM", "计划创建", "SYSTEM");
    }
    public void modification(TripTask task, String planId, int version, TripPlanSnapshot before,
                             TripPlanSnapshot after, TripChatResponse result, String message) {
        List<TripPatchOperation> operations = result.operations() == null ? List.of() : result.operations();
        if (operations.isEmpty()) {
            write(task.getTaskId() + ":" + version + ":0", task, planId, version, inferredType(message), "PLAN", planId,
                city(after), JsonUtils.toJsonString(before), JsonUtils.toJsonString(after), "USER_REQUEST", message, "AI_PROPOSAL");
            return;
        }
        for (int i = 0; i < operations.size(); i++) {
            TripPatchOperation op = operations.get(i); String path = op.path() == null ? "" : op.path();
            write(task.getTaskId() + ":" + version + ":" + i, task, planId, version, operationType(op), targetType(path), path,
                city(after), JsonUtils.toJsonString(op.from()), JsonUtils.toJsonString(op.value()), "USER_REQUEST",
                op.reason() == null || op.reason().isBlank() ? message : op.reason(),
                result.operations().isEmpty() ? "AI_PROPOSAL" : "LOCAL_PATCH");
        }
    }
    private void write(String id, TripTask task, String planId, int version, TripMemoryEventType type, String targetType,
                       String targetRef, String city, String before, String after, String reasonCode, String reason, String source) {
        if (mapper.selectCount(Wrappers.<TripMemoryEvent>lambdaQuery().eq(TripMemoryEvent::getEventId, id)) > 0) return;
        TripMemoryEvent e = new TripMemoryEvent(); e.setEventId(id); e.setUserId(task.getOwnerId()); e.setPlanId(planId);
        e.setPlanVersion(version); e.setTaskId(task.getTaskId()); e.setEventType(type.name()); e.setTargetType(targetType);
        e.setTargetRef(targetRef == null ? "" : targetRef); e.setCity(city); e.setBeforeJson(before); e.setAfterJson(after);
        e.setReasonCode(reasonCode); e.setReasonText(reason == null ? "" : reason.substring(0, Math.min(1000, reason.length())));
        e.setSource(source); e.setEvidenceRefsJson("[]"); e.setOccurredAt(LocalDateTime.now()); e.setConsolidationStatus("PENDING"); mapper.insert(e);
    }
    private TripMemoryEventType operationType(TripPatchOperation op) {
        String path = op.path() == null ? "" : op.path();
        if (path.contains("attractions")) return switch (op.op()) { case "add" -> TripMemoryEventType.POI_ADDED; case "remove" -> TripMemoryEventType.POI_REMOVED; default -> TripMemoryEventType.POI_REPLACED; };
        if (path.contains("hotel") || path.contains("accommodation")) return TripMemoryEventType.HOTEL_CHANGED;
        if (path.contains("budget")) return TripMemoryEventType.BUDGET_CHANGED;
        if (path.contains("transport")) return TripMemoryEventType.TRANSPORT_CHANGED;
        if (path.contains("days") && ("move".equals(op.op()) || "copy".equals(op.op()))) return TripMemoryEventType.DAY_REORDERED;
        return inferredType(op.reason());
    }
    private TripMemoryEventType inferredType(String message) {
        String value = message == null ? "" : message;
        if (value.contains("预算") || value.contains("便宜")) return TripMemoryEventType.BUDGET_CHANGED;
        if (value.contains("酒店") || value.contains("住宿")) return TripMemoryEventType.HOTEL_CHANGED;
        if (value.contains("高铁") || value.contains("交通")) return TripMemoryEventType.TRANSPORT_CHANGED;
        return TripMemoryEventType.PACE_CHANGED;
    }
    private String targetType(String path) { return path.contains("attractions") ? "POI" : path.contains("days") ? "DAY" : "PLAN"; }
    private String city(TripPlanSnapshot snapshot) { return snapshot == null || snapshot.plan() == null || snapshot.plan().city() == null ? "" : snapshot.plan().city(); }
}
