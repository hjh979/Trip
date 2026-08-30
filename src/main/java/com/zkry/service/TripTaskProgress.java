package com.zkry.service;

/** Stable progress values consumed by the task UI. */
public final class TripTaskProgress {
    public static final int SUBMITTED = 5;
    public static final int INITIALIZING = 10;
    public static final int TRAVEL_RESEARCH = 14;
    public static final int RESEARCH_SOURCE_1 = 22;
    public static final int RESEARCH_SOURCE_2 = 30;
    public static final int RESEARCH_SOURCE_3 = 38;
    public static final int RESEARCH_SOURCE_4 = 46;
    public static final int RESEARCH_SOURCE_5 = 54;
    public static final int CONTEXT_READY = 60;
    public static final int AMAP_POI_SEARCH = 30;
    public static final int WEATHER_SEARCH = 46;
    public static final int HOTEL_SEARCH = 64;
    public static final int RESEARCH_MERGE = 74;
    public static final int PLANNING = 64;
    public static final int PLANNING_HEARTBEAT_MAX = 82;
    public static final int PLAN_DRAFT_READY = 84;
    public static final int PLAN_VALIDATING = 88;
    public static final int PLAN_REPAIRING = 91;
    public static final int PLAN_READY = 94;
    public static final int PERSISTING = 97;
    public static final int DONE = 100;
    private TripTaskProgress() { }
}
