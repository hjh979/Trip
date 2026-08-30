package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_log")
public class AuditLog extends BaseEntity {
    private Long actorUserId;
    private String actorName;
    private String action;
    private String detail;
    private String source;
    private String result;
}
