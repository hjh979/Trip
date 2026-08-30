package com.zkry.domain.vo;

public record SystemUserView(
    Long id,
    String username,
    String display_name,
    String email,
    String avatar_url,
    String role,
    String status,
    String bio,
    String created_at
) {
}
