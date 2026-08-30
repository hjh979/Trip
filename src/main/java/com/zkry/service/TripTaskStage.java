package com.zkry.service;

/** Stable task stage identifiers. */
public final class TripTaskStage {
    public static final String SUBMITTED = "submitted";
    public static final String INITIALIZING = "initializing";
    public static final String TRAVEL_RESEARCH = "travel_research";
    public static final String ATTRACTION_SEARCH = "attraction_search";
    public static final String AMAP_POI_SEARCH = "amap_poi_search";
    public static final String WEATHER_SEARCH = "weather_search";
    public static final String HOTEL_SEARCH = "hotel_search";
    public static final String RESEARCH_MERGE = "research_merge";
    public static final String PLANNING = "planning";
    public static final String PLANNING_TOOL = "planning_tool";
    public static final String PLANNING_VALIDATION = "planning_validation";
    public static final String PLANNING_REPAIR = "planning_repair";
    public static final String PERSISTING = "persisting";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    private TripTaskStage() { }
}
