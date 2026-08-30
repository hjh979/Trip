package com.zkry.service.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.zkry.domain.dto.planning.PlannerContextPack;
import com.zkry.domain.dto.planning.PlannerEvidenceItem;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlannerContextPackBuilderTest {

    @Test
    void enforcesTotalBudgetAndKeepsMultipleSources() {
        PlannerContextPackBuilder builder = new PlannerContextPackBuilder(6000);
        List<PlannerEvidenceItem> evidence = new ArrayList<>();
        for (String source : List.of("USER_PREFERENCE", "KNOWLEDGE")) {
            for (int index = 0; index < 5; index++) {
                evidence.add(new PlannerEvidenceItem(
                    source,
                    source + "-" + index,
                    "杭州",
                    "标题" + index,
                    "内容".repeat(1000),
                    "",
                    1D - index * 0.1D
                ));
            }
        }

        PlannerContextPack pack = builder.build(evidence, List.of("trace-1"), List.of());

        assertThat(pack.characterCount()).isLessThanOrEqualTo(6000);
        assertThat(pack.context()).contains("用户历史偏好", "自建旅行知识库");
        assertThat(pack.sourceCounts()).containsOnlyKeys("USER_PREFERENCE", "KNOWLEDGE");
        assertThat(pack.safeTraceIds()).containsExactly("trace-1");
    }

    @Test
    void deduplicatesSameEvidenceIdentity() {
        PlannerContextPackBuilder builder = new PlannerContextPackBuilder(6000);
        PlannerEvidenceItem low = new PlannerEvidenceItem(
            "KNOWLEDGE", "chunk-1", "成都", "攻略", "低质量", "", 0.2D);
        PlannerEvidenceItem high = new PlannerEvidenceItem(
            "KNOWLEDGE", "chunk-1", "成都", "攻略", "高质量", "", 0.9D);

        PlannerContextPack pack = builder.build(List.of(low, high), List.of(), List.of());

        assertThat(pack.context()).contains("高质量").doesNotContain("低质量");
        assertThat(pack.count("KNOWLEDGE")).isEqualTo(1);
    }
}
