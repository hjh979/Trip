package com.zkry.domain.dto;

import java.util.List;

public record TripPatch(
    List<TripPatchOperation> operations,
    boolean need_route_recalculate,
    List<Integer> affected_days
) {
    public TripPatch {
        operations = operations == null ? List.of() : List.copyOf(operations);
        affected_days = affected_days == null ? List.of() : List.copyOf(affected_days);
    }

    public TripPatch(List<TripPatchOperation> operations) {
        this(operations, false, List.of());
    }
}
