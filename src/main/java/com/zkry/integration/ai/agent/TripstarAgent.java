package com.zkry.integration.ai.agent;

/** Registered agents used by standard planning and scoped modification flows. */
public enum TripstarAgent {
    AMAP_POI_RESEARCH("amap-poi-research-agent"),
    AMAP_WEATHER_RESEARCH("amap-weather-research-agent"),
    AMAP_HOTEL_RESEARCH("amap-hotel-research-agent"),
    TRIP_PLANNER("trip-planner-agent"),
    TRIP_REVIEW("trip-review-agent"),
    TRIP_CHAT("trip-chat-agent");

    private final String id;

    TripstarAgent(String id) { this.id = id; }

    public String id() { return id; }
}
