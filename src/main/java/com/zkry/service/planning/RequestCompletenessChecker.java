package com.zkry.service.planning;

import com.zkry.domain.dto.TripRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Validates planning slots before a durable task is created. */
@Service
public class RequestCompletenessChecker {

    public CompletenessResult check(TripRequest request) {
        List<String> missing = new ArrayList<>();
        if (request == null || request.normalizedCities().isEmpty()) missing.add("city");
        if (request == null || (blank(request.start_date()) && blank(request.end_date())
            && (request.travel_days() == null || request.travel_days() <= 0))) missing.add("date_or_days");
        if (!missing.isEmpty()) {
            return new CompletenessResult(false, List.copyOf(missing),
                "请补充" + String.join("、", missing) + "后再生成行程");
        }
        return new CompletenessResult(true, List.of(), "");
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record CompletenessResult(boolean complete, List<String> missingSlots, String followUpQuestion) { }
}
