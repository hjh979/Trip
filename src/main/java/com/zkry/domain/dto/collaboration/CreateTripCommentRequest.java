package com.zkry.domain.dto.collaboration;

public record CreateTripCommentRequest(
    Long user_id,
    Long parent_id,
    String target_type,
    String target_ref,
    String content
) {
}
