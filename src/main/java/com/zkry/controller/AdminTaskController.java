package com.zkry.controller;

import com.zkry.domain.entity.TripTask;
import com.zkry.task.TripTaskStore;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/tasks")
public class AdminTaskController {

    private final TripTaskStore taskStore;

    public AdminTaskController(TripTaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @GetMapping("/dead-letters")
    public List<Map<String, Object>> deadLetters(@RequestParam(defaultValue = "50") int limit) {
        return taskStore.deadLetters(limit).stream().map(this::view).toList();
    }

    @PostMapping("/{taskId}/redrive")
    public Map<String, Object> redrive(@PathVariable String taskId) {
        return view(taskStore.requeueDeadLetter(taskId));
    }

    private Map<String, Object> view(TripTask task) {
        return Map.ofEntries(
            Map.entry("task_id", task.getTaskId()),
            Map.entry("owner_id", task.getOwnerId()),
            Map.entry("task_type", task.getTaskType()),
            Map.entry("status", task.getStatus()),
            Map.entry("stage", task.getStage()),
            Map.entry("attempt", task.getAttempt()),
            Map.entry("max_attempts", task.getMaxAttempts()),
            Map.entry("error_code", task.getErrorCode() == null ? "" : task.getErrorCode()),
            Map.entry("error", task.getErrorMessage() == null ? "" : task.getErrorMessage()),
            Map.entry("updated_at", task.getUpdateTime() == null ? "" : task.getUpdateTime().toString())
        );
    }
}
