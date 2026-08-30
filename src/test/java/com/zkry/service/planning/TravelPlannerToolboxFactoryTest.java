package com.zkry.service.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.TripRequest;
import com.zkry.integration.amap.service.AmapMapContextService;
import com.zkry.service.TripTaskStage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.JsonNode;

class TravelPlannerToolboxFactoryTest {

    @Test
    void distanceMatrixIsBoundedAndDoesNotNeedExternalApi() {
        PlanningResearchService research = mock(PlanningResearchService.class);
        AmapMapContextService amap = mock(AmapMapContextService.class);
        TripPlanPolicyValidator validator = new TripPlanPolicyValidator();
        TravelPlannerToolboxFactory factory =
            new TravelPlannerToolboxFactory(research, amap, validator, 8, 8000);
        TripRequest request = new TripRequest(
            "成都", List.of(), "2026-10-01", "2026-10-03", 3,
            "公共交通", "舒适型酒店", List.of(), "", "zh"
        );
        TravelPlannerToolboxFactory.PlanningToolSession tools =
            factory.bind("task-1", 1001L, request);

        String result = tools.getDistanceMatrix(
            List.of(
                new TravelPlannerToolboxFactory.Waypoint("武侯祠", 104.043D, 30.647D),
                new TravelPlannerToolboxFactory.Waypoint("锦里", 104.041D, 30.645D),
                new TravelPlannerToolboxFactory.Waypoint("宽窄巷子", 104.055D, 30.669D)
            ),
            "walking"
        );

        JsonNode root = JsonUtils.parseTree(result);
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("data").path("pairs").size()).isEqualTo(3);
        assertThat(tools.usage().tools()).contains(TravelPlannerToolNames.DISTANCE_MATRIX);
    }

    @Test
    void exposesExactlyTheEightBoundedPlanningTools() {
        PlanningResearchService research = mock(PlanningResearchService.class);
        AmapMapContextService amap = mock(AmapMapContextService.class);
        TravelPlannerToolboxFactory factory = new TravelPlannerToolboxFactory(
            research, amap, new TripPlanPolicyValidator(), 16, 24000);
        TripRequest request = new TripRequest(
            "成都", List.of(), "2026-10-01", "2026-10-03", 3,
            "公共交通", "舒适型酒店", List.of(), "", "zh"
        );
        TravelPlannerToolboxFactory.PlanningToolSession tools =
            factory.bind("task-2", 1001L, request);

        var callbacks = MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build()
            .getToolCallbacks();

        assertThat(callbacks).hasSize(8);
        assertThat(callbacks)
            .extracting(callback -> callback.getToolDefinition().name())
            .containsExactlyInAnyOrder(
                TravelPlannerToolNames.MATCH_PREFERENCE,
                TravelPlannerToolNames.VALIDATE_POI,
                TravelPlannerToolNames.PLAN_ROUTE,
                TravelPlannerToolNames.DISTANCE_MATRIX,
                TravelPlannerToolNames.SEARCH_NEARBY,
                TravelPlannerToolNames.GET_WEATHER,
                TravelPlannerToolNames.EVALUATE_PLAN,
                TravelPlannerToolNames.SEARCH_KNOWLEDGE
            );
    }

    @Test
    void reportsToolStartAndCompletionToTaskProgress() {
        PlanningResearchService research = mock(PlanningResearchService.class);
        AmapMapContextService amap = mock(AmapMapContextService.class);
        TravelPlannerToolboxFactory factory = new TravelPlannerToolboxFactory(
            research, amap, new TripPlanPolicyValidator(), 8, 8000);
        TripRequest request = new TripRequest(
            "成都", List.of(), "2026-10-01", "2026-10-03", 3,
            "公共交通", "舒适型酒店", List.of(), "", "zh"
        );
        List<String> events = new ArrayList<>();
        TravelPlannerToolboxFactory.PlanningToolSession tools = factory.bind(
            "task-progress",
            1001L,
            request,
            (stage, progress, message) -> events.add(stage + "|" + progress + "|" + message)
        );

        tools.getDistanceMatrix(
            List.of(
                new TravelPlannerToolboxFactory.Waypoint("武侯祠", 104.043D, 30.647D),
                new TravelPlannerToolboxFactory.Waypoint("锦里", 104.041D, 30.645D)
            ),
            "walking"
        );

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(event -> event.startsWith(TripTaskStage.PLANNING_TOOL + "|"));
        assertThat(events.getFirst()).contains("正在调用地点距离聚类");
        assertThat(events.getLast()).contains("地点距离聚类完成");
    }
}
