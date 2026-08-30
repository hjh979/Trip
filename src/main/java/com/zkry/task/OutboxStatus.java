package com.zkry.task;

public final class OutboxStatus {

    public static final String PENDING = "pending";
    public static final String PUBLISHING = "publishing";
    public static final String PUBLISHED = "published";
    public static final String FAILED = "failed";

    private OutboxStatus() {
    }
}
