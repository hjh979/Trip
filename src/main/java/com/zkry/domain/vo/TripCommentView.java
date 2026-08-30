package com.zkry.domain.vo;

public record TripCommentView(
    Long id,
    String plan_id,
    Long parent_id,
    String target_type,
    String target_ref,
    String content,
    String status,
    Integer like_count,
    SystemUserView author,
    String created_at,
    String updated_at
) {
}
