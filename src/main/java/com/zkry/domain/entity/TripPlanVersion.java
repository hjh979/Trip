package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Immutable full itinerary version used for idempotency, audit and conflict recovery. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trip_plan_version")
public class TripPlanVersion extends BaseEntity {

    private String planId;
    private Integer version;
    private Long createdBy;
    private String sourceType;
    private String taskId;
    private String resultJson;
    /** Complete reusable snapshot: plan, verified POIs, RAG trace ids and constraints. */
    private String snapshotJson;
}
