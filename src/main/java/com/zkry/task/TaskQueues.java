package com.zkry.task;

public final class TaskQueues {

    public static final String TASK_EXCHANGE = "tripstar.task";
    public static final String RETRY_EXCHANGE = "tripstar.task.retry";
    public static final String DLX_EXCHANGE = "tripstar.task.dlx";

    public static final String PLAN_QUEUE = "tripstar.task.plan";
    public static final String MODIFICATION_QUEUE = "tripstar.task.modification";
    public static final String KNOWLEDGE_QUEUE = "tripstar.task.knowledge";
    public static final String DLQ = "tripstar.task.dlq";

    public static final String PLAN_KEY = "task.plan";
    public static final String MODIFICATION_KEY = "task.modification";
    public static final String KNOWLEDGE_KEY = "task.knowledge";
    public static final String DLQ_KEY = "task.dead";

    private TaskQueues() {
    }

    public static String taskRoutingKey(String taskType) {
        return switch (taskType) {
            case TaskType.AI_MODIFICATION -> MODIFICATION_KEY;
            case TaskType.KNOWLEDGE_INGESTION, TaskType.KNOWLEDGE_CORPUS_SYNC -> KNOWLEDGE_KEY;
            default -> PLAN_KEY;
        };
    }

    public static String retryRoutingKey(String taskType, int delaySeconds) {
        String family = switch (taskType) {
            case TaskType.AI_MODIFICATION -> "modification";
            case TaskType.KNOWLEDGE_INGESTION, TaskType.KNOWLEDGE_CORPUS_SYNC -> "knowledge";
            default -> "plan";
        };
        return "retry." + family + "." + delaySeconds;
    }
}
