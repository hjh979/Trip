package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 已完成旅行计划的持久化记录。
 *
 * <p>完整响应保存为 JSON，列表页常用字段单独成列，既能在后端重启后恢复结果，
 * 又不需要为了 Agent 输出中的每个嵌套字段建立大量关系表。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trip_plan_record")
public class TripPlanRecord extends BaseEntity {

    private String planId;
    private String taskId;
    private String status;
    private String city;
    private String startDate;
    private String endDate;
    private Integer travelDays;
    private String overallSuggestions;
    private String resultJson;
    private String snapshotJson;
    private String errorMessage;
}
