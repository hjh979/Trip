package com.zkry.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zkry.domain.dto.ChatMessage;
import com.zkry.domain.dto.DayPlan;
import com.zkry.domain.dto.TripChatRequest;
import com.zkry.domain.dto.TripPlan;
import com.zkry.integration.ai.service.AiStructuredOutputService;
import com.zkry.integration.ai.service.PromptResourceService;
import com.zkry.service.rag.KnowledgeRagService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TripConversationServiceTest {

    @Test
    void ragQueryContainsCityCurrentRequestAndLastThreeUserTurns() {
        AiStructuredOutputService structuredOutputService = mock(AiStructuredOutputService.class);
        PromptResourceService promptResourceService = mock(PromptResourceService.class);
        KnowledgeRagService knowledgeRagService = mock(KnowledgeRagService.class);
        TripPlanRepairService tripPlanRepairService = mock(TripPlanRepairService.class);
        TripConversationService service = new TripConversationService(
            structuredOutputService,
            promptResourceService,
            knowledgeRagService,
            tripPlanRepairService
        );
        TripPlan plan = new TripPlan(
            "上海",
            List.of("上海"),
            "2026-10-01",
            "2026-10-02",
            List.of(mock(DayPlan.class)),
            List.of(),
            "",
            null
        );
        List<ChatMessage> history = List.of(
            new ChatMessage("user", "这条很早的要求不应进入检索"),
            new ChatMessage("assistant", "好的"),
            new ChatMessage("user", "想带孩子"),
            new ChatMessage("assistant", "已记录"),
            new ChatMessage("user", "还要下雨备选"),
            new ChatMessage("user", "老人少走路")
        );
        when(knowledgeRagService.groundingContext(anyString(), eq(5), eq("上海")))
            .thenThrow(new RetrievalReached());

        assertThrows(
            RetrievalReached.class,
            () -> service.adjust(new TripChatRequest("那加一个无障碍餐厅", plan, history))
        );

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(knowledgeRagService).groundingContext(query.capture(), eq(5), eq("上海"));
        assertEquals(
            "上海 想带孩子 还要下雨备选 老人少走路 那加一个无障碍餐厅",
            query.getValue()
        );
    }

    private static final class RetrievalReached extends RuntimeException {
    }
}
