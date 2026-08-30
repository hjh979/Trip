package com.zkry.domain.dto.planning;

/** Weather fact used to keep outdoor schedules realistic. */
public record WeatherFact(String date, String dayWeather, String nightWeather, Integer dayTemp, Integer nightTemp) { }
