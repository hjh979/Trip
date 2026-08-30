package com.zkry.domain.dto.user;

public record CreateUserRequest(
    String username,
    String display_name,
    String email,
    String avatar_url,
    String role,
    String bio
) {
}
