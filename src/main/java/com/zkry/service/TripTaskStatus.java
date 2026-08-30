package com.zkry.service;

/**
 * 任务状态值。
 *
 * <p>状态表示任务生命周期，阶段表示当前执行到哪一步；前端通过状态查询读取这些值。
 */
public final class TripTaskStatus {

    public static final String QUEUED = "queued";
    public static final String PROCESSING = "processing";
    public static final String RETRYING = "retrying";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String DEAD_LETTERED = "dead_lettered";

    public static boolean terminal(String status) {
        return COMPLETED.equals(status) || FAILED.equals(status) || DEAD_LETTERED.equals(status);
    }

    private TripTaskStatus() {
    }
}
