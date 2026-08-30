package com.zkry.domain.dto.planning;

import java.util.List;

/** Verified facts collected before the planner model is invoked. */
public record PlanningFactPack(List<CityFactPack> cities, List<String> warnings) {
    public PlanningFactPack {
        cities = cities == null ? List.of() : List.copyOf(cities);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public String asPromptContext() {
        return cities.stream().map(city -> {
            String pois = city.verifiedPois().stream()
                .map(poi -> poi.name() + "(" + poi.longitude() + "," + poi.latitude() + ")")
                .reduce((left, right) -> left + ", " + right).orElse("无");
            String hotels = city.hotels().stream().limit(3)
                .map(poi -> poi.name() + "(" + poi.longitude() + "," + poi.latitude() + ")")
                .reduce((left, right) -> left + ", " + right).orElse("无");
            return city.city() + " 已核验景点: " + pois + "；已核验住宿: " + hotels;
        }).reduce((left, right) -> left + "\n" + right).orElse("暂无地图核验事实");
    }

    /** A city is planning-ready only when the server has a usable POI and lodging directory. */
    public boolean completeForPlanning(List<String> requestedCities) {
        if (cities.isEmpty()) return false;
        for (String requested : requestedCities == null ? List.<String>of() : requestedCities) {
            CityFactPack city = cities.stream().filter(item -> item.city().equalsIgnoreCase(requested)).findFirst().orElse(null);
            if (city == null || city.verifiedPois().isEmpty() || city.hotels().isEmpty()) return false;
        }
        return !warnings.stream().anyMatch(value -> value != null && value.contains("AMap 事实核验失败"));
    }

    public boolean containsPoi(String city, String name) {
        if (name == null || name.isBlank()) return false;
        String target = normalize(name);
        return cities.stream().filter(item -> city == null || item.city().equalsIgnoreCase(city))
            .flatMap(item -> item.verifiedPois().stream())
            .anyMatch(poi -> normalize(poi.name()).equals(target)
                || normalize(poi.name()).contains(target) || target.contains(normalize(poi.name())));
    }

    public boolean containsHotel(String city, String name) {
        if (name == null || name.isBlank()) return false;
        String target = normalize(name);
        return cities.stream().filter(item -> city == null || item.city().equalsIgnoreCase(city))
            .flatMap(item -> item.hotels().stream())
            .anyMatch(poi -> normalize(poi.name()).equals(target)
                || normalize(poi.name()).contains(target) || target.contains(normalize(poi.name())));
    }

    private String normalize(String value) { return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT); }
}
