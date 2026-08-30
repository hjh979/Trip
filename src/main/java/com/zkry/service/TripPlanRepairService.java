package com.zkry.service;

import com.zkry.domain.dto.Attraction;
import com.zkry.domain.dto.Budget;
import com.zkry.domain.dto.DayPlan;
import com.zkry.domain.dto.Hotel;
import com.zkry.domain.dto.Location;
import com.zkry.domain.dto.Meal;
import com.zkry.domain.dto.TripPlan;
import com.zkry.domain.dto.map.MapCityContext;
import com.zkry.domain.dto.map.MapPlanningContext;
import com.zkry.domain.dto.map.MapPoi;
import com.zkry.integration.amap.support.AmapCoordinateNormalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 对模型生成的行程做确定性修正。
 *
 * <p>LLM 负责规划，但类型枚举、POI 分类和预算求和不应依赖模型“碰巧答对”。
 * 本服务先修复能够由程序可靠判断的问题，剩余语义问题再交给 AI 修复与复审。
 */
@Service
public class TripPlanRepairService {

    private static final List<String> REQUIRED_MEAL_TYPES = List.of("breakfast", "lunch", "dinner");
    private static final Set<String> RESTAURANT_TOKENS = Set.of(
        "餐饮", "餐厅", "饭店", "酒家", "酒楼", "小吃", "快餐", "咖啡", "茶馆",
        "火锅", "烧烤", "面馆", "食堂", "restaurant", "food", "cafe"
    );
    private static final Set<String> RESTAURANT_NAME_TOKENS = Set.of(
        "餐厅", "饭店", "酒家", "酒楼", "小吃店", "快餐店", "咖啡馆", "茶馆",
        "火锅店", "烧烤店", "面馆", "食堂", "restaurant", "cafe"
    );

    public RepairResult repair(TripPlan plan, MapPlanningContext mapContext) {
        if (plan == null) {
            return new RepairResult(null, 0, 0, 0, 0, 0, 0, 0);
        }

        List<DayPlan> sourceDays = plan.days() == null ? List.of() : plan.days();
        int correctedCoordinates = countSwappedCoordinates(sourceDays);
        Set<String> usedAttractions = new HashSet<>();
        Set<String> usedRestaurants = collectMealNames(sourceDays);
        List<DayPlan> repairedDays = new ArrayList<>(sourceDays.size());
        int correctedMealTypes = 0;
        int addedMeals = 0;
        int removedRestaurantAttractions = 0;
        int removedDuplicateAttractions = 0;
        int addedAttractions = 0;

        for (DayPlan day : sourceDays) {
            MapCityContext cityContext = findCity(mapContext, day.city());
            MealRepair mealRepair = repairMeals(day.meals(), cityContext, usedRestaurants);
            correctedMealTypes += mealRepair.correctedTypes();
            addedMeals += mealRepair.addedMeals();

            Set<String> mealNames = new HashSet<>();
            for (Meal meal : mealRepair.meals()) {
                mealNames.add(normalizeName(meal.name()));
            }

            List<Attraction> attractions = new ArrayList<>();
            for (Attraction attraction : safeAttractions(day.attractions())) {
                if (isRestaurant(attraction, mealNames)) {
                    removedRestaurantAttractions++;
                    usedAttractions.remove(normalizeName(attraction.name()));
                    continue;
                }
                String normalizedName = normalizeName(attraction.name());
                if (!normalizedName.isEmpty() && !usedAttractions.add(normalizedName)) {
                    removedDuplicateAttractions++;
                    continue;
                }
                attractions.add(clearGeneratedImage(attraction));
            }

            int minimumAttractions = Boolean.TRUE.equals(day.is_transfer_day()) ? 1 : 2;
            if (cityContext != null) {
                for (MapPoi candidate : cityContext.safeAttractions()) {
                    if (attractions.size() >= minimumAttractions) {
                        break;
                    }
                    String normalizedName = normalizeName(candidate.name());
                    if (normalizedName.isEmpty()
                        || usedAttractions.contains(normalizedName)
                        || isRestaurantPoi(candidate)) {
                        continue;
                    }
                    attractions.add(toAttraction(candidate));
                    usedAttractions.add(normalizedName);
                    addedAttractions++;
                }
            }

            repairedDays.add(copyDay(day, List.copyOf(attractions), mealRepair.meals()));
        }

        Budget repairedBudget = recalculateBudget(plan.budget(), repairedDays);
        int budgetDelta = safeAmount(repairedBudget.total())
            - safeAmount(plan.budget() == null ? null : plan.budget().total());
        TripPlan repairedPlan = new TripPlan(
            plan.city(),
            plan.cities(),
            plan.start_date(),
            plan.end_date(),
            List.copyOf(repairedDays),
            plan.weather_info(),
            plan.overall_suggestions(),
            repairedBudget
        );
        return new RepairResult(
            repairedPlan,
            correctedMealTypes,
            addedMeals,
            removedRestaurantAttractions,
            removedDuplicateAttractions,
            addedAttractions,
            correctedCoordinates,
            budgetDelta
        );
    }

    private MealRepair repairMeals(
        List<Meal> sourceMeals,
        MapCityContext cityContext,
        Set<String> usedRestaurants
    ) {
        List<Meal> meals = sourceMeals == null ? List.of() : sourceMeals;
        Map<String, Meal> assigned = new LinkedHashMap<>();
        List<Meal> unassigned = new ArrayList<>();
        List<Meal> extras = new ArrayList<>();
        int correctedTypes = 0;

        for (Meal meal : meals) {
            String canonicalType = canonicalMealType(meal.type());
            if (canonicalType != null && !assigned.containsKey(canonicalType)) {
                assigned.put(canonicalType, copyMeal(meal, canonicalType));
                if (!canonicalType.equals(meal.type())) {
                    correctedTypes++;
                }
            } else if (isSnackType(meal.type())) {
                extras.add(copyMeal(meal, "snack"));
                if (!"snack".equals(meal.type())) {
                    correctedTypes++;
                }
            } else {
                unassigned.add(meal);
            }
        }

        for (String requiredType : REQUIRED_MEAL_TYPES) {
            if (assigned.containsKey(requiredType) || unassigned.isEmpty()) {
                continue;
            }
            Meal meal = unassigned.removeFirst();
            assigned.put(requiredType, copyMeal(meal, requiredType));
            correctedTypes++;
        }

        int addedMeals = 0;
        if (cityContext != null) {
            for (String requiredType : REQUIRED_MEAL_TYPES) {
                if (assigned.containsKey(requiredType)) {
                    continue;
                }
                MapPoi restaurant = nextRestaurant(cityContext.safeRestaurants(), usedRestaurants);
                if (restaurant == null) {
                    continue;
                }
                assigned.put(requiredType, toMeal(requiredType, restaurant));
                usedRestaurants.add(normalizeName(restaurant.name()));
                addedMeals++;
            }
        }

        List<Meal> repaired = new ArrayList<>();
        for (String requiredType : REQUIRED_MEAL_TYPES) {
            Meal meal = assigned.get(requiredType);
            if (meal != null) {
                repaired.add(meal);
                usedRestaurants.add(normalizeName(meal.name()));
            }
        }
        for (Meal extra : unassigned) {
            repaired.add(copyMeal(extra, "snack"));
            if (!"snack".equals(extra.type())) {
                correctedTypes++;
            }
            usedRestaurants.add(normalizeName(extra.name()));
        }
        repaired.addAll(extras);
        for (Meal extra : extras) {
            usedRestaurants.add(normalizeName(extra.name()));
        }
        return new MealRepair(List.copyOf(repaired), correctedTypes, addedMeals);
    }

    private String canonicalMealType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "breakfast", "早餐", "早饭" -> "breakfast";
            case "lunch", "午餐", "午饭" -> "lunch";
            case "dinner", "晚餐", "晚饭" -> "dinner";
            default -> null;
        };
    }

    private boolean isSnackType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return "snack".equals(normalized) || "加餐".equals(normalized) || "零食".equals(normalized);
    }

    private MapPoi nextRestaurant(List<MapPoi> candidates, Set<String> usedRestaurants) {
        for (MapPoi candidate : candidates == null ? List.<MapPoi>of() : candidates) {
            String name = normalizeName(candidate.name());
            if (!name.isEmpty() && !usedRestaurants.contains(name)) {
                return candidate;
            }
        }
        return null;
    }

    private Meal toMeal(String type, MapPoi poi) {
        return new Meal(
            type,
            poi.name(),
            poi.address(),
            toLocation(poi),
            "来自高德真实餐饮 POI 候选，建议出发前确认营业时间。",
            defaultMealCost(type)
        );
    }

    private int defaultMealCost(String type) {
        return switch (type) {
            case "breakfast" -> 30;
            case "lunch" -> 60;
            case "dinner" -> 80;
            default -> 40;
        };
    }

    private Attraction toAttraction(MapPoi poi) {
        return new Attraction(
            poi.name(),
            poi.address(),
            toLocation(poi),
            90,
            "来自高德真实景点 POI 候选，建议出发前确认开放时间。",
            poi.type(),
            parseRating(poi.rating()),
            "",
            0
        );
    }

    private Location toLocation(MapPoi poi) {
        if (poi.location() == null) {
            return null;
        }
        return new Location(poi.location().longitude(), poi.location().latitude());
    }

    private Double parseRating(String rating) {
        if (rating == null || rating.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(rating);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Budget recalculateBudget(Budget source, List<DayPlan> days) {
        int attractions = days.stream()
            .flatMap(day -> safeAttractions(day.attractions()).stream())
            .mapToInt(item -> safeAmount(item.ticket_price()))
            .sum();
        int hotels = days.stream()
            .map(DayPlan::hotel)
            .filter(hotel -> hotel != null)
            .mapToInt(hotel -> safeAmount(hotel.estimated_cost()))
            .sum();
        int meals = days.stream()
            .flatMap(day -> (day.meals() == null ? List.<Meal>of() : day.meals()).stream())
            .mapToInt(meal -> safeAmount(meal.estimated_cost()))
            .sum();
        int transportation = source == null ? 0 : safeAmount(source.total_transportation());
        int interCity = source == null ? 0 : safeAmount(source.total_inter_city_transport());
        return new Budget(
            attractions,
            hotels,
            meals,
            transportation,
            interCity,
            attractions + hotels + meals + transportation + interCity
        );
    }

    private Set<String> collectUsableAttractionNames(List<DayPlan> days) {
        Set<String> names = new HashSet<>();
        for (DayPlan day : days) {
            Set<String> mealNames = new HashSet<>();
            for (Meal meal : day.meals() == null ? List.<Meal>of() : day.meals()) {
                mealNames.add(normalizeName(meal.name()));
            }
            for (Attraction attraction : safeAttractions(day.attractions())) {
                if (!isRestaurant(attraction, mealNames)) {
                    names.add(normalizeName(attraction.name()));
                }
            }
        }
        names.remove("");
        return names;
    }

    private Set<String> collectMealNames(List<DayPlan> days) {
        Set<String> names = new HashSet<>();
        for (DayPlan day : days) {
            for (Meal meal : day.meals() == null ? List.<Meal>of() : day.meals()) {
                names.add(normalizeName(meal.name()));
            }
        }
        names.remove("");
        return names;
    }

    private boolean isRestaurant(Attraction attraction, Set<String> mealNames) {
        String name = normalizeName(attraction.name());
        return mealNames.contains(name)
            || containsRestaurantToken(attraction.category())
            || containsRestaurantNameToken(attraction.name());
    }

    public boolean isRestaurantPoi(MapPoi poi) {
        return poi != null
            && (containsRestaurantToken(poi.type()) || containsRestaurantNameToken(poi.name()));
    }

    private boolean containsRestaurantToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return RESTAURANT_TOKENS.stream().anyMatch(normalized::contains);
    }

    private boolean containsRestaurantNameToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return RESTAURANT_NAME_TOKENS.stream().anyMatch(normalized::contains);
    }

    private Attraction clearGeneratedImage(Attraction attraction) {
        return new Attraction(
            attraction.name(), attraction.address(), normalizeLocation(attraction.location()), attraction.visit_duration(),
            attraction.description(), attraction.category(), attraction.rating(), "", attraction.ticket_price()
        );
    }

    private Meal copyMeal(Meal meal, String type) {
        return new Meal(
            type, meal.name(), meal.address(), normalizeLocation(meal.location()), meal.description(), meal.estimated_cost()
        );
    }

    private DayPlan copyDay(DayPlan day, List<Attraction> attractions, List<Meal> meals) {
        return new DayPlan(
            day.date(), day.day_index(), day.city(), day.is_transfer_day(), day.transfer_info(),
            day.description(), day.transportation(), day.accommodation(), normalizeHotel(day.hotel()), attractions, meals
        );
    }

    private Hotel normalizeHotel(Hotel hotel) {
        if (hotel == null) {
            return null;
        }
        return new Hotel(
            hotel.name(), hotel.address(), normalizeLocation(hotel.location()), hotel.price_range(),
            hotel.rating(), hotel.distance(), hotel.type(), hotel.estimated_cost()
        );
    }

    private Location normalizeLocation(Location location) {
        return AmapCoordinateNormalizer.normalize(location).location();
    }

    private int countSwappedCoordinates(List<DayPlan> days) {
        int count = 0;
        for (DayPlan day : days) {
            if (day.hotel() != null && AmapCoordinateNormalizer.normalize(day.hotel().location()).swapped()) {
                count++;
            }
            for (Attraction attraction : safeAttractions(day.attractions())) {
                if (AmapCoordinateNormalizer.normalize(attraction.location()).swapped()) {
                    count++;
                }
            }
            for (Meal meal : day.meals() == null ? List.<Meal>of() : day.meals()) {
                if (AmapCoordinateNormalizer.normalize(meal.location()).swapped()) {
                    count++;
                }
            }
        }
        return count;
    }

    private List<Attraction> safeAttractions(List<Attraction> attractions) {
        return attractions == null ? List.of() : attractions;
    }

    private MapCityContext findCity(MapPlanningContext context, String city) {
        if (context == null || city == null || city.isBlank()) {
            return null;
        }
        return context.safeCities().stream()
            .filter(item -> city.trim().equalsIgnoreCase(item.city() == null ? "" : item.city().trim()))
            .findFirst()
            .orElse(null);
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("[\\s（）()·・]", "").toLowerCase(Locale.ROOT);
    }

    private int safeAmount(Integer amount) {
        return amount == null ? 0 : Math.max(amount, 0);
    }

    private record MealRepair(List<Meal> meals, int correctedTypes, int addedMeals) {
    }

    public record RepairResult(
        TripPlan plan,
        int correctedMealTypes,
        int addedMeals,
        int removedRestaurantAttractions,
        int removedDuplicateAttractions,
        int addedAttractions,
        int correctedCoordinates,
        int budgetDelta
    ) {
        public boolean changed() {
            return correctedMealTypes > 0
                || addedMeals > 0
                || removedRestaurantAttractions > 0
                || removedDuplicateAttractions > 0
                || addedAttractions > 0
                || correctedCoordinates > 0
                || budgetDelta != 0;
        }
    }
}
