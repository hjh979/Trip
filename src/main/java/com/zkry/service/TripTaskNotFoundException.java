package com.zkry.service;

public class TripTaskNotFoundException extends RuntimeException {

    public TripTaskNotFoundException(String taskId) {
        super("任务不存在: " + taskId);
    }
}
