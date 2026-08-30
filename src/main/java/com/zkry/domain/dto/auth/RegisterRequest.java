package com.zkry.domain.dto.auth;

public record RegisterRequest(
    String username,
    String display_name,
    String email,
    String password
) {
}
