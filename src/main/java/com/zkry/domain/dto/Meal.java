package com.zkry.domain.dto;

public record Meal(
    String type,
    String name,
    String address,
    Location location,
    String description,
    Integer estimated_cost
) {
}
