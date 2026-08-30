package com.zkry.service.planning;

import com.zkry.domain.dto.Attraction;
import com.zkry.domain.dto.DayPlan;
import com.zkry.domain.dto.Meal;
import com.zkry.domain.dto.TripPlan;
import com.zkry.domain.dto.TripRequest;
import com.zkry.domain.dto.planning.PlanningFactPack;
import com.zkry.integration.amap.support.AmapCoordinateNormalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Deterministic policy gate after the model self-evaluation.
 *
 * <p>The Agent may choose to call {@code evaluate_plan}, but correctness cannot depend on it
 * voluntarily doing so. This validator always runs before persistence.
 */
@Service
public class TripPlanPolicyValidator {

    private static final Set<String> REQUIRED_MEALS = Set.of("breakfast", "lunch", "dinner");

    public Evaluation evaluate(TripRequest request, TripPlan plan) {
        return evaluate(request, plan, null);
    }

    public Evaluation evaluate(TripRequest request, TripPlan plan, PlanningFactPack verifiedFacts) {
        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        if (plan == null) {
            return new Evaluation(false, 0, List.of("行程对象为空。"), List.of("重新生成结构化行程。"));
        }
        List<DayPlan> days = plan.days() == null ? List.of() : plan.days();
        int expectedDays = request == null ? days.size() : request.safeTravelDays();
        if (days.size() != expectedDays) {
            issues.add("行程天数应为 " + expectedDays + " 天，实际为 " + days.size() + " 天。");
        }
        Set<String> usedAttractions = new HashSet<>();
        for (int index = 0; index < days.size(); index++) {
            DayPlan day = days.get(index);
            String label = "第 " + (index + 1) + " 天";
            if (day == null) {
                issues.add(label + "内容为空。");
                continue;
            }
            if (day.day_index() == null || day.day_index() != index) {
                issues.add(label + " day_index 必须为 " + index + "。");
            }
            if (!hasText(day.city())) {
                issues.add(label + "缺少城市。");
            }
            List<Attraction> attractions = day.attractions() == null ? List.of() : day.attractions();
            if (attractions.isEmpty()) {
                issues.add(label + "没有景点。");
            }
            for (Attraction attraction : attractions) {
                if (attraction == null || !hasText(attraction.name())) {
                    issues.add(label + "存在无名称景点。");
                    continue;
                }
                String normalizedName = attraction.name().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
                if (!usedAttractions.add(normalizedName)) {
                    issues.add("景点重复安排：" + attraction.name() + "。");
                }
                if (attraction.location() == null
                    || attraction.location().longitude() == null
                    || attraction.location().latitude() == null
                    || !AmapCoordinateNormalizer.isValid(
                        attraction.location().longitude(),
                        attraction.location().latitude()
                    )) {
                    issues.add(label + "景点“" + attraction.name() + "”缺少合法高德经纬度。");
                }
                if (verifiedFacts != null && !verifiedFacts.containsPoi(day.city(), attraction.name())) {
                    issues.add(label + "景点“" + attraction.name() + "”不在本次 AMap 核验事实目录中。");
                }
            }
            if (day.hotel() == null || !hasText(day.hotel().name())) {
                issues.add(label + "缺少具体酒店或住宿候选。");
            } else if (verifiedFacts != null && !verifiedFacts.containsHotel(day.city(), day.hotel().name())) {
                issues.add(label + "酒店“" + day.hotel().name() + "”不在本次 AMap 核验事实目录中。");
            }
            Set<String> mealTypes = new HashSet<>();
            for (Meal meal : day.meals() == null ? List.<Meal>of() : day.meals()) {
                if (meal != null && hasText(meal.type())) {
                    mealTypes.add(meal.type().trim().toLowerCase(Locale.ROOT));
                }
            }
            if (!mealTypes.containsAll(REQUIRED_MEALS)) {
                issues.add(label + "必须包含 breakfast、lunch、dinner 三餐。");
            }
        }
        if (plan.budget() == null || plan.budget().total() == null) {
            issues.add("缺少完整预算。");
        }
        if (!issues.isEmpty()) {
            suggestions.add("只修复列出的结构或事实问题，不要改变用户明确的城市、日期和偏好。");
            suggestions.add("缺少经纬度时调用 validate_poi，不得凭空编造坐标。");
        }
        int score = Math.max(0, 10 - issues.size() * 2);
        return new Evaluation(issues.isEmpty(), score, List.copyOf(issues), List.copyOf(suggestions));
    }

    /** Validates only a changed day so a local edit does not re-check unrelated cities. */
    public DayEvaluation evaluateDay(TripPlan plan, int dayIndex) {
        List<String> issues = new ArrayList<>();
        if (plan == null || plan.days() == null || dayIndex < 0 || dayIndex >= plan.days().size()) {
            return new DayEvaluation(false, dayIndex, List.of("变更日期不存在"));
        }
        DayPlan day = plan.days().get(dayIndex);
        if (day == null) issues.add("日期内容为空");
        else {
            if (day.day_index() != null && day.day_index() != dayIndex) issues.add("day_index 不连续");
            List<Attraction> attractions = day.attractions() == null ? List.of() : day.attractions();
            if (attractions.isEmpty()) issues.add("日期至少需要一个景点");
            Set<String> names = new HashSet<>();
            for (Attraction attraction : attractions) {
                if (attraction == null || !hasText(attraction.name())) { issues.add("存在无名称景点"); continue; }
                if (!names.add(attraction.name().replaceAll("\\s+", "").toLowerCase(Locale.ROOT))) {
                    issues.add("景点重复: " + attraction.name());
                }
                if (attraction.location() == null || attraction.location().longitude() == null
                    || attraction.location().latitude() == null
                    || !AmapCoordinateNormalizer.isValid(attraction.location().longitude(), attraction.location().latitude())) {
                    issues.add("景点坐标无效: " + attraction.name());
                }
            }
        }
        return new DayEvaluation(issues.isEmpty(), dayIndex, List.copyOf(issues));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Evaluation(
        boolean passed,
        int score,
        List<String> issues,
        List<String> suggestions
    ) {
    }

    public record DayEvaluation(boolean passed, int dayIndex, List<String> issues) { }
}
