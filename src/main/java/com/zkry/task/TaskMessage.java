package com.zkry.task;

/** The deliberately small message placed on RabbitMQ. */
public record TaskMessage(String taskId, String taskType, int attempt) {
}
