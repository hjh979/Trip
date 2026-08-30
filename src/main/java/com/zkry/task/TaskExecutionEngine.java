package com.zkry.task;

import com.zkry.domain.entity.TripTask;
import org.springframework.stereotype.Service;

/** Single workflow dispatch point shared by Rabbit workers and the compatibility dispatcher. */
@Service
public class TaskExecutionEngine {
    private final TripPlanningWorkflow planning;
    private final AiModificationWorkflow modification;
    private final KnowledgeIngestionWorkflow knowledge;

    public TaskExecutionEngine(TripPlanningWorkflow planning,
                               AiModificationWorkflow modification,
                               KnowledgeIngestionWorkflow knowledge) {
        this.planning = planning;
        this.modification = modification;
        this.knowledge = knowledge;
    }

    public void execute(TripTask task, String token) {
        switch (task.getTaskType()) {
            case TaskType.TRIP_PLAN -> planning.execute(task, token);
            case TaskType.AI_MODIFICATION -> modification.execute(task, token);
            case TaskType.KNOWLEDGE_INGESTION, TaskType.KNOWLEDGE_CORPUS_SYNC -> knowledge.execute(task, token);
            default -> throw new IllegalArgumentException("Unsupported task type: " + task.getTaskType());
        }
    }
}
