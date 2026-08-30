package com.zkry.domain.vo;

public record AuditLogView(
    Long id,
    String time,
    String actor,
    String action,
    String detail,
    String source,
    String result
) {
}
