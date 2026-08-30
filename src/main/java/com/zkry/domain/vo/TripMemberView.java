package com.zkry.domain.vo;

public record TripMemberView(
    Long id,
    String plan_id,
    String role,
    SystemUserView user,
    String joined_at
) {
}
