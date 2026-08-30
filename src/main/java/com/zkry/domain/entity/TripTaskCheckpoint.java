package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/** Persisted output of an expensive workflow stage. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trip_task_checkpoint")
public class TripTaskCheckpoint extends BaseEntity {

    private String taskId;
    private String checkpointKey;
    private String payloadJson;
    private String payloadHash;
    /** Allows the serialized checkpoint contract to evolve without invalidating a task. */
    private Integer schemaVersion;
    /** WORKING_MEMORY, RECOVERY_CHECKPOINT or AUDIT_REFERENCE. */
    private String retentionClass;
    private LocalDateTime expiresAt;
}
