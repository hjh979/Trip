package com.zkry.service.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.zkry.domain.dto.Attraction;
import com.zkry.domain.dto.Budget;
import com.zkry.domain.dto.DayPlan;
import com.zkry.domain.dto.Hotel;
import com.zkry.domain.dto.Location;
import com.zkry.domain.dto.Meal;
import com.zkry.domain.dto.TripPlan;
import com.zkry.domain.dto.TripRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class TripPlanPolicyValidatorTest {

    private final TripPlanPolicyValidator validator = new TripPlanPolicyValidator();

    @Test
    void acceptsCompletePlan() {
        TripPlanPolicyValidator.Evaluation result = validator.evaluate(request(), validPlan());

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(10);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void rejectsInventedCoordinatesAndMissingMeals() {
        DayPlan invalidDay = new DayPlan(
            "2026-10-01",
            0,
            "成都",
            false,
            "",
            "第一天",
            "公共交通",
            "舒适型酒店",
            null,
            List.of(new Attraction(
                "武侯祠", "成都", new Location(999D, 999D), 120,
                "介绍", "景点", 4.8D, "", 50
            )),
            List.of(new Meal("lunch", "午餐", "成都", new Location(104.06D, 30.67D), "", 60))
        );
        TripPlan invalid = new TripPlan(
            "成都", List.of("成都"), "2026-10-01", "2026-10-01",
            List.of(invalidDay), List.of(), "", null
        );

        TripPlanPolicyValidator.Evaluation result = validator.evaluate(request(), invalid);

        assertThat(result.passed()).isFalse();
        assertThat(result.issues())
            .anyMatch(issue -> issue.contains("合法高德经纬度"))
            .anyMatch(issue -> issue.contains("breakfast"))
            .anyMatch(issue -> issue.contains("酒店"))
            .anyMatch(issue -> issue.contains("预算"));
    }

    private TripRequest request() {
        return new TripRequest(
            "成都", List.of(), "2026-10-01", "2026-10-01", 1,
            "公共交通", "舒适型酒店", List.of("文化"), "带父母", "zh"
        );
    }

    private TripPlan validPlan() {
        Location location = new Location(104.06D, 30.67D);
        DayPlan day = new DayPlan(
            "2026-10-01",
            0,
            "成都",
            false,
            "",
            "第一天",
            "公共交通",
            "舒适型酒店",
            new Hotel("成都酒店", "成都", location, "300", "4.5", "1km", "酒店", 300),
            List.of(new Attraction("武侯祠", "成都", location, 120, "介绍", "景点", 4.8D, "", 50)),
            List.of(
                new Meal("breakfast", "早餐", "成都", location, "", 20),
                new Meal("lunch", "午餐", "成都", location, "", 60),
                new Meal("dinner", "晚餐", "成都", location, "", 80)
            )
        );
        return new TripPlan(
            "成都",
            List.of("成都"),
            "2026-10-01",
            "2026-10-01",
            List.of(day),
            List.of(),
            "建议",
            new Budget(50, 300, 160, 50, 0, 560)
        );
    }
}
