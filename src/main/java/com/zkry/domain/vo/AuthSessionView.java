package com.zkry.domain.vo;

public record AuthSessionView(
    String access_token,
    String token_type,
    long expires_in,
    SystemUserView user
) {
}
