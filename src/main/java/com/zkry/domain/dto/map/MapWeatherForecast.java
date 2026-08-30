package com.zkry.domain.dto.map;

public record MapWeatherForecast(
    String date,
    String city,
    String dayWeather,
    String nightWeather,
    Integer dayTemp,
    Integer nightTemp,
    String windDirection,
    String windPower
) {
}
