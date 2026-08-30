package com.zkry.integration.ai.prompt;

import com.zkry.domain.dto.CityStay;
import com.zkry.domain.dto.TripRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds the compact request context passed to the planner model. */
public final class TripPlannerPrompts {
    private TripPlannerPrompts() { }

    public static Map<String, String> requestVariables(TripRequest request) {
        String cities = request.normalizedCities().stream()
            .map(city -> "- " + city.city() + ": " + city.safeDays() + " days")
            .collect(Collectors.joining("\n"));
        String cityNames = request.normalizedCities().stream().map(CityStay::city)
            .collect(Collectors.joining(", "));
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("city_names", cityNames);
        variables.put("city_stays", cities);
        variables.put("start_date", safe(request.start_date()));
        variables.put("end_date", safe(request.end_date()));
        variables.put("travel_days", String.valueOf(request.safeTravelDays()));
        variables.put("transportation", request.safeTransportation());
        variables.put("accommodation", request.safeAccommodation());
        variables.put("preferences", request.safePreferences().isEmpty()
            ? "none" : String.join(", ", request.safePreferences()));
        variables.put("free_text_input", safe(request.free_text_input()));
        variables.put("language", request.safeLanguage());
        return variables;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
