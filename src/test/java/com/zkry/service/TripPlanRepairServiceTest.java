package com.zkry.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zkry.domain.dto.Attraction;
import com.zkry.domain.dto.Budget;
import com.zkry.domain.dto.DayPlan;
import com.zkry.domain.dto.Hotel;
import com.zkry.domain.dto.Location;
import com.zkry.domain.dto.Meal;
import com.zkry.domain.dto.TripPlan;
import com.zkry.domain.dto.map.MapCityContext;
import com.zkry.domain.dto.map.MapPlanningContext;
import com.zkry.domain.dto.map.MapPoint;
import com.zkry.domain.dto.map.MapPoi;
import java.util.List;
import org.junit.jupiter.api.Test;

class TripPlanRepairServiceTest {

    private final TripPlanRepairService service = new TripPlanRepairService();

    @Test
    void repairsInvalidMealTypesRestaurantAttractionAndBudget() {
        List<Meal> meals = List.of(
            meal("中餐", "知味观", 30),
            meal("本帮菜", "楼外楼", 60),
            meal("中餐厅", "新白鹿", 80)
        );
        List<Attraction> attractions = List.of(
            attraction("西湖风景名胜区", "风景名胜"),
            attraction("杭州酒家(延安路店)", "餐饮服务;中餐厅")
        );
        DayPlan day = day(attractions, meals);
        TripPlan plan = plan(day, new Budget(0, 300, 999, 20, 0, 9999));

        MapPlanningContext mapContext = new MapPlanningContext(
            List.of(new MapCityContext(
                "杭州",
                new MapPoint(120.15, 30.25),
                List.of(
                    poi("西湖风景名胜区", "风景名胜"),
                    poi("浙江省博物馆", "科教文化服务;博物馆")
                ),
                List.of(),
                List.of(),
                List.of()
            )),
            true,
            "amap",
            "ok"
        );

        TripPlanRepairService.RepairResult result = service.repair(plan, mapContext);

        assertThat(result.correctedMealTypes()).isEqualTo(3);
        assertThat(result.removedRestaurantAttractions()).isEqualTo(1);
        assertThat(result.addedAttractions()).isEqualTo(1);
        assertThat(result.plan().days().getFirst().meals())
            .extracting(Meal::type)
            .containsExactly("breakfast", "lunch", "dinner");
        assertThat(result.plan().days().getFirst().attractions())
            .extracting(Attraction::name)
            .containsExactly("西湖风景名胜区", "浙江省博物馆");
        assertThat(result.plan().budget().total_meals()).isEqualTo(170);
        assertThat(result.plan().budget().total_hotels()).isEqualTo(300);
        assertThat(result.plan().budget().total_transportation()).isEqualTo(20);
        assertThat(result.plan().budget().total()).isEqualTo(490);
    }

    @Test
    void addsMissingMealsFromAmapRestaurantCandidates() {
        DayPlan day = day(
            List.of(attraction("西湖", "风景名胜"), attraction("灵隐寺", "寺庙")),
            List.of(meal("breakfast", "酒店早餐", 20))
        );
        TripPlan plan = plan(day, null);
        MapPlanningContext mapContext = new MapPlanningContext(
            List.of(new MapCityContext(
                "杭州",
                new MapPoint(120.15, 30.25),
                List.of(),
                List.of(),
                List.of(poi("知味观", "餐饮服务"), poi("楼外楼", "餐饮服务")),
                List.of()
            )),
            true,
            "amap",
            "ok"
        );

        TripPlanRepairService.RepairResult result = service.repair(plan, mapContext);

        assertThat(result.addedMeals()).isEqualTo(2);
        assertThat(result.plan().days().getFirst().meals())
            .extracting(Meal::type)
            .containsExactly("breakfast", "lunch", "dinner");
        assertThat(result.plan().days().getFirst().meals())
            .extracting(Meal::name)
            .containsExactly("酒店早餐", "知味观", "楼外楼");
    }

    @Test
    void normalizesCoordinatesBeforePlanIsReturned() {
        Attraction reversed = new Attraction(
            "西湖", "杭州市", new Location(30.240826, 120.101406), 90,
            "景点", "风景名胜", 4.8, "", 0
        );
        DayPlan day = day(
            List.of(reversed, attraction("灵隐寺", "寺庙")),
            List.of(
                meal("breakfast", "早餐", 20),
                meal("lunch", "午餐", 50),
                meal("dinner", "晚餐", 80)
            )
        );

        TripPlanRepairService.RepairResult result = service.repair(plan(day, null), null);

        Location location = result.plan().days().getFirst().attractions().getFirst().location();
        assertThat(result.correctedCoordinates()).isEqualTo(1);
        assertThat(location.longitude()).isEqualTo(120.101406);
        assertThat(location.latitude()).isEqualTo(30.240826);
    }

    @Test
    void removesAttractionsRepeatedAcrossDaysWithoutAnotherModelCall() {
        Attraction repeated = attraction("杭州西湖风景名胜区", "风景名胜");
        DayPlan first = day(
            List.of(repeated, attraction("雷峰塔景区", "历史建筑")),
            List.of()
        );
        DayPlan second = day(
            List.of(repeated, attraction("净慈禅寺", "寺庙")),
            List.of()
        );
        TripPlan plan = new TripPlan(
            "杭州",
            List.of("杭州"),
            "2026-08-18",
            "2026-08-19",
            List.of(first, second),
            List.of(),
            "",
            null
        );

        TripPlanRepairService.RepairResult result = service.repair(plan, null);

        assertThat(result.removedDuplicateAttractions()).isEqualTo(1);
        assertThat(result.plan().days().getFirst().attractions())
            .extracting(Attraction::name)
            .containsExactly("杭州西湖风景名胜区", "雷峰塔景区");
        assertThat(result.plan().days().get(1).attractions())
            .extracting(Attraction::name)
            .containsExactly("净慈禅寺");
    }

    private TripPlan plan(DayPlan day, Budget budget) {
        return new TripPlan(
            "杭州",
            List.of("杭州"),
            "2026-08-01",
            "2026-08-01",
            List.of(day),
            List.of(),
            "建议",
            budget
        );
    }

    private DayPlan day(List<Attraction> attractions, List<Meal> meals) {
        return new DayPlan(
            "2026-08-01",
            1,
            "杭州",
            false,
            "",
            "杭州一日游",
            "公共交通",
            "舒适型酒店",
            new Hotel("杭州酒店", "杭州市", new Location(120.15, 30.25), "300", "4.5", "1km", "酒店", 300),
            attractions,
            meals
        );
    }

    private Meal meal(String type, String name, int cost) {
        return new Meal(type, name, "杭州市", new Location(120.15, 30.25), "餐饮", cost);
    }

    private Attraction attraction(String name, String category) {
        return new Attraction(name, "杭州市", new Location(120.15, 30.25), 90, "景点", category, 4.5, "fake", 0);
    }

    private MapPoi poi(String name, String type) {
        return new MapPoi(name, "杭州市", new MapPoint(120.15, 30.25), type, "4.6", "1km", "");
    }
}
