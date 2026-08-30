package com.zkry.domain.dto.user;

public record UpdateUserRequest(
    String display_name,
    String email,
    String avatar_url,
    String role,
    String status,
    String bio
) {
}
