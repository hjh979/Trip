package com.zkry.domain.dto;

/** RFC 6902-compatible operation used by local and edit planners. */
public record TripPatchOperation(
    String op,
    String path,
    Object value,
    String from,
    String reason
) {
    public TripPatchOperation(String op, String path, Object value) {
        this(op, path, value, null, null);
    }

    public TripPatchOperation(String op, String path, Object value, String from) {
        this(op, path, value, from, null);
    }
}
