package com.zkry.domain.dto;

public record CityStay(
    String city,
    Integer days
) {
    public int safeDays() {
        return days == null || days < 1 ? 1 : days;
    }
}
