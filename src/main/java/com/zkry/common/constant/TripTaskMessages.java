package com.zkry.common.constant;

/** User-facing progress messages for durable tasks. */
public final class TripTaskMessages {
    public static final String SUBMITTED = "任务已提交，正在初始化流程";
    public static final String INITIALIZING = "正在初始化旅行规划工作流";
    public static final String TRAVEL_RESEARCH = "正在召回旅行证据";
    public static final String AMAP_POI_SEARCH = "正在核验高德 POI 和坐标";
    public static final String WEATHER_SEARCH = "正在查询天气预报";
    public static final String HOTEL_SEARCH = "正在查询住宿和餐饮";
    public static final String RESEARCH_MERGE = "正在合并规划上下文";
    public static final String PLANNING = "正在生成结构化行程";
    public static final String COMPLETED = "旅行计划生成成功";
    public static final String FAILED = "旅行计划生成失败";
    private TripTaskMessages() { }
}
