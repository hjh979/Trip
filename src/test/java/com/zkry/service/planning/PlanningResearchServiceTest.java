package com.zkry.service.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zkry.domain.dto.TripRequest;
import com.zkry.integration.amap.service.AmapMapContextService;
import com.zkry.mapper.KnowledgeSourceMapper;
import com.zkry.mapper.TripPlanMapper;
import com.zkry.service.rag.KnowledgeRagService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class PlanningResearchServiceTest {

    @Test
    void reportsEachParallelSourceAndFinalContextProgress() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            PlanningResearchService service = new PlanningResearchService(
                mock(KnowledgeRagService.class),
                mock(KnowledgeSourceMapper.class),
                mock(TripPlanMapper.class),
                mock(AmapMapContextService.class),
                new PlannerContextPackBuilder(6000),
                executor,
                10
            );
            TripRequest request = new TripRequest(
                "成都",
                List.of(),
                "2026-10-01",
                "2026-10-03",
                3,
                "公共交通",
                "舒适型酒店",
                List.of("历史人文"),
                "",
                "zh"
            );
            List<Integer> progressValues = new ArrayList<>();

            var context = service.build(
                1001L,
                request,
                (stage, progress, message) -> progressValues.add(progress)
            );

            assertThat(progressValues)
                .containsExactly(22, 30, 60);
            assertThat(context.safeWarnings()).isNotEmpty();
        }
    }
}
