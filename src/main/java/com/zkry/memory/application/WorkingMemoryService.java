package com.zkry.memory.application;

import com.zkry.domain.dto.ChatMessage;
import com.zkry.domain.dto.TripRequest;
import com.zkry.domain.dto.planning.PlannerContextPack;
import com.zkry.domain.dto.planning.PlanningFactPack;
import com.zkry.domain.entity.TripTask;
import com.zkry.memory.domain.CheckpointRetentionClass;
import com.zkry.memory.domain.WorkingMemorySnapshot;
import com.zkry.task.TaskCheckpointService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WorkingMemoryService {
    public static final String CHECKPOINT_KEY = "working.memory.v1";
    private final TaskCheckpointService checkpoints;
    public WorkingMemoryService(TaskCheckpointService checkpoints) { this.checkpoints = checkpoints; }

    public void initializePlanning(TripTask task, TripRequest request) {
        WorkingMemorySnapshot.RequestConstraintSummary constraints =
            new WorkingMemorySnapshot.RequestConstraintSummary(
                request.normalizedCities().stream().map(c -> c.city()).toList(), request.start_date(), request.end_date(),
                request.safeTravelDays(), request.safePreferences());
        save(new WorkingMemorySnapshot(task.getTaskId(), task.getPlanId(), null, constraints, List.of(),
            new WorkingMemorySnapshot.ToolExecutionSummary(List.of(), List.of()),
            new WorkingMemorySnapshot.RetrievalSummary(List.of(), List.of()),
            new WorkingMemorySnapshot.VerifiedFactSummary(List.of(), List.of(), List.of()),
            new WorkingMemorySnapshot.RiskSummary(List.of()), Instant.now()));
    }

    public void updatePlanningEvidence(TripTask task, PlannerContextPack context, PlanningFactPack facts) {
        WorkingMemorySnapshot current = checkpoints.load(task.getTaskId(), CHECKPOINT_KEY, WorkingMemorySnapshot.class)
            .orElse(null);
        if (current == null) return;
        List<String> warnings = context == null ? List.of() : context.safeWarnings();
        save(new WorkingMemorySnapshot(current.taskId(), current.planId(), current.planVersion(), current.constraints(),
            current.recentTurns(), new WorkingMemorySnapshot.ToolExecutionSummary(List.of("rag_retrieve", "validate_poi"), List.of()),
            new WorkingMemorySnapshot.RetrievalSummary(context == null ? List.of() : context.traceIds(), List.of()),
            new WorkingMemorySnapshot.VerifiedFactSummary(
                facts == null ? List.of() : facts.cities().stream().flatMap(city -> city.verifiedPois().stream())
                    .map(poi -> poi.name()).toList(), List.of(), List.of()),
            new WorkingMemorySnapshot.RiskSummary(warnings), Instant.now()));
    }

    public void captureModification(TripTask task, String planId, Integer version, String message, List<ChatMessage> history) {
        List<WorkingMemorySnapshot.ConversationTurnSummary> turns = (history == null ? List.<ChatMessage>of() : history).stream()
            .filter(turn -> "user".equalsIgnoreCase(turn.role()) || "assistant".equalsIgnoreCase(turn.role()))
            .skip(Math.max(0, (history == null ? 0 : history.size()) - 8L))
            .map(turn -> new WorkingMemorySnapshot.ConversationTurnSummary(turn.role(), "trip_adjust", compact(turn.content())))
            .toList();
        save(new WorkingMemorySnapshot(task.getTaskId(), planId, version, null, turns,
            new WorkingMemorySnapshot.ToolExecutionSummary(List.of(), List.of()),
            new WorkingMemorySnapshot.RetrievalSummary(List.of(), List.of()),
            new WorkingMemorySnapshot.VerifiedFactSummary(List.of(), List.of(), List.of()),
            new WorkingMemorySnapshot.RiskSummary(List.of(compact(message))), Instant.now()));
    }

    private void save(WorkingMemorySnapshot snapshot) {
        checkpoints.save(snapshot.taskId(), CHECKPOINT_KEY, snapshot, CheckpointRetentionClass.WORKING_MEMORY);
    }
    private String compact(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= 300 ? clean : clean.substring(0, 300);
    }
}
