package com.zkry.domain.dto.planning;

import java.util.List;

public record CityFactPack(String city, List<VerifiedPoi> verifiedPois, List<WeatherFact> weather,
                           List<VerifiedPoi> hotels, List<VerifiedPoi> restaurants) {
    public CityFactPack {
        verifiedPois = verifiedPois == null ? List.of() : List.copyOf(verifiedPois);
        weather = weather == null ? List.of() : List.copyOf(weather);
        hotels = hotels == null ? List.of() : List.copyOf(hotels);
        restaurants = restaurants == null ? List.of() : List.copyOf(restaurants);
    }
}
