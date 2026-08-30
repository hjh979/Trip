package com.zkry.service;

import com.zkry.common.exception.BizException;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.ChatMessage;
import com.zkry.domain.dto.TripChatRequest;
import com.zkry.domain.dto.TripPlan;
import com.zkry.domain.dto.TripPatch;
import com.zkry.domain.dto.TripPatchOperation;
import com.zkry.domain.vo.TripChatResponse;
import com.zkry.integration.ai.agent.TripstarAgent;
import com.zkry.integration.ai.prompt.TripstarPrompt;
import com.zkry.integration.ai.prompt.TripstarPromptVariable;
import com.zkry.integration.ai.service.AiStructuredOutputService;
import com.zkry.integration.ai.service.PromptResourceService;
import com.zkry.service.rag.KnowledgeRagService;
import com.zkry.service.planning.IntentRouter;
import com.zkry.service.planning.LocalPatchEngine;
import com.zkry.service.planning.RAGContextCache;
import com.zkry.service.planning.TripModificationLevel;
import com.zkry.service.planning.TripPatchApplier;
import com.zkry.service.planning.TripPlanPolicyValidator;
import com.zkry.memory.domain.MemoryContextPack;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** 在当前计划和最近对话的上下文中增量调整行程。 */
@Service
public class TripConversationService {

    private final AiStructuredOutputService structuredOutputService;
    private final PromptResourceService promptResourceService;
    private final KnowledgeRagService knowledgeRagService;
    private final TripPlanRepairService tripPlanRepairService;
    private final IntentRouter intentRouter;
    private final LocalPatchEngine localPatchEngine;
    private final TripPatchApplier patchApplier;
    private final RAGContextCache ragContextCache;
    private final TripPlanPolicyValidator policyValidator;

    public TripConversationService(
        AiStructuredOutputService structuredOutputService,
        PromptResourceService promptResourceService,
        KnowledgeRagService knowledgeRagService,
        TripPlanRepairService tripPlanRepairService
    ) {
        this(structuredOutputService, promptResourceService, knowledgeRagService, tripPlanRepairService,
            new IntentRouter(), new LocalPatchEngine(), new TripPatchApplier(), new RAGContextCache("v1"),
            new TripPlanPolicyValidator());
    }

    @Autowired
    public TripConversationService(
        AiStructuredOutputService structuredOutputService,
        PromptResourceService promptResourceService,
        KnowledgeRagService knowledgeRagService,
        TripPlanRepairService tripPlanRepairService,
        IntentRouter intentRouter,
        LocalPatchEngine localPatchEngine,
        TripPatchApplier patchApplier,
        RAGContextCache ragContextCache,
        TripPlanPolicyValidator policyValidator
    ) {
        this.structuredOutputService = structuredOutputService;
        this.promptResourceService = promptResourceService;
        this.knowledgeRagService = knowledgeRagService;
        this.tripPlanRepairService = tripPlanRepairService;
        this.intentRouter = intentRouter;
        this.localPatchEngine = localPatchEngine;
        this.patchApplier = patchApplier;
        this.ragContextCache = ragContextCache;
        this.policyValidator = policyValidator;
    }

    public TripChatResponse adjust(TripChatRequest request) {
        return adjust(request, MemoryContextPack.empty());
    }

    /** Executes only the deterministic domain patch selected at task submission time. */
    public TripChatResponse adjustLocal(TripChatRequest request) {
        if (request == null || request.trip_plan() == null || request.trip_plan().days() == null
            || request.trip_plan().days().isEmpty()) throw new BizException("请先生成行程，再通过对话进行修改。");
        TripPatch patch = localPatchEngine.generate(request.message(), request.trip_plan());
        if (patch.operations().isEmpty()) throw new BizException("该请求无法转换为确定性行程命令，请改用更明确的景点或日期。");
        TripPlan adjusted = patchApplier.apply(request.trip_plan(), patch);
        for (Integer affectedDay : patch.affected_days()) {
            TripPlanPolicyValidator.DayEvaluation evaluation = policyValidator.evaluateDay(adjusted, affectedDay);
            if (!evaluation.passed()) throw new BizException("局部行程校验失败: " + String.join("、", evaluation.issues()));
        }
        return new TripChatResponse(true, "已按规则更新行程。", "已应用局部 Patch", adjusted, "", List.of(),
            patch.operations(), patch.need_route_recalculate());
    }

    /** Read-only question mode never writes a new itinerary version. */
    public TripChatResponse answerQuestion(TripChatRequest request) {
        if (request == null || request.trip_plan() == null) throw new BizException("当前行程不存在。");
        KnowledgeRagService.GroundingContext grounding = knowledgeRagService.groundingContext(
            request.message(), 5, request.trip_plan().city());
        return new TripChatResponse(true, grounding.content(), "仅查询，未修改行程", request.trip_plan(),
            grounding.traceId(), grounding.citations(), List.of(), false);
    }

    /** Server-resolved memory is passed separately so request payload identity is never authoritative. */
    public TripChatResponse adjust(TripChatRequest request, MemoryContextPack memoryContext) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new BizException("请输入希望 AI 调整的内容。");
        }
        if (request.trip_plan() == null || request.trip_plan().days() == null || request.trip_plan().days().isEmpty()) {
            throw new BizException("请先生成行程，再通过对话进行修改。");
        }

        // Deterministic edits never spend a model or retrieval call. The response still carries
        // the full plan for backwards-compatible clients; the persisted version stores the patch.
        TripModificationLevel level = intentRouter.route(request.message());
        if (level == TripModificationLevel.SIMPLE) {
            TripPatch patch = localPatchEngine.generate(request.message(), request.trip_plan());
            if (!patch.operations().isEmpty()) {
                TripPlan adjusted = patchApplier.apply(request.trip_plan(), patch);
                for (Integer affectedDay : patch.affected_days()) {
                    TripPlanPolicyValidator.DayEvaluation evaluation = policyValidator.evaluateDay(adjusted, affectedDay);
                    if (!evaluation.passed()) {
                        throw new BizException("局部行程校验失败: " + String.join("、", evaluation.issues()));
                    }
                }
                return new TripChatResponse(true, "已按规则更新行程。", "已应用局部 Patch", adjusted, "", List.of(),
                    patch.operations(), patch.need_route_recalculate());
            }
        }

        // The persisted snapshot already carries durable context; tool output is deliberately excluded.
        List<ChatMessage> history = request.history() == null
            ? List.of()
            : request.history().stream()
                .filter(turn -> turn != null && ("user".equalsIgnoreCase(turn.role()) || "assistant".equalsIgnoreCase(turn.role())))
                .skip(Math.max(0, request.history().size() - 8L))
                .map(turn -> new ChatMessage(turn.role(), compactTurn(turn.content())))
                .toList();
        String retrievalQuery = conversationRetrievalQuery(
            request.trip_plan().city(), request.message(), history);
        Integer targetDay = affectedDay(request.message(), request.trip_plan().days().size());
        KnowledgeRagService.GroundingContext grounding = ragContextCache.getOrLoad(
            request.trip_plan().city(), retrievalIntent(request.message()),
            () -> knowledgeRagService.groundingContext(retrievalQuery, 5, request.trip_plan().city()));
        String prompt = promptResourceService.render(
            TripstarPrompt.CHAT_ADJUST_USER,
            Map.of(
                TripstarPromptVariable.MESSAGE, request.message().trim(),
                TripstarPromptVariable.TRIP_PLAN, JsonUtils.toJsonString(promptPlan(request.trip_plan(), targetDay)),
                TripstarPromptVariable.CHAT_HISTORY, JsonUtils.toJsonString(history),
                TripstarPromptVariable.MEMORY_CONTEXT, memoryContext == null ? MemoryContextPack.empty().promptContext() : memoryContext.promptContext(),
                TripstarPromptVariable.RAG_CONTEXT, grounding.content(),
                TripstarPromptVariable.FORMAT, structuredOutputService.format(AdjustmentResult.class)
            )
        );

        AdjustmentResult result = structuredOutputService.callForObject(
            TripstarAgent.TRIP_CHAT,
            AdjustmentResult.class,
            promptResourceService.load(TripstarPrompt.CHAT_ADJUST_SYSTEM),
            prompt,
            "trip-adjust-" + Integer.toUnsignedString(request.trip_plan().hashCode())
        ).orElseThrow(() -> new BizException("AI 未返回可解析的行程修改结果，请换一种说法后重试。"));

        List<TripPatchOperation> operations = result.operations() == null ? List.of() : result.operations();
        TripPlan adjusted = request.trip_plan();
        if (!operations.isEmpty()) {
            adjusted = patchApplier.apply(request.trip_plan(), new TripPatch(
                operations, result.need_route_recalculate(), List.of()));
        }
        if (adjusted == null || adjusted.days() == null || adjusted.days().isEmpty()) {
            throw new BizException("AI 返回的修改结果缺少每日行程，已保留原计划。");
        }
        adjusted = tripPlanRepairService.repair(adjusted, null).plan();
        TripModificationLevel routedLevel = intentRouter.route(request.message());
        if (routedLevel != TripModificationLevel.RESEARCH) {
            Integer day = affectedDay(request.message(), adjusted.days() == null ? 0 : adjusted.days().size());
            if (day != null) {
                TripPlanPolicyValidator.DayEvaluation evaluation = policyValidator.evaluateDay(adjusted, day);
                if (!evaluation.passed()) throw new BizException("局部行程校验失败：" + String.join(";", evaluation.issues()));
            }
        }
        return new TripChatResponse(
            true,
            blankToDefault(result.reply(), "已按你的要求更新行程。"),
            blankToDefault(result.change_summary(), "行程已更新"),
            adjusted,
            grounding.traceId(),
            grounding.citations(),
            operations,
            result.need_route_recalculate()
        );
    }

    private String compactTurn(String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() <= 300 ? clean : clean.substring(0, 300);
    }

    private String conversationRetrievalQuery(
        String city,
        String currentMessage,
        List<ChatMessage> history
    ) {
        List<String> recentUserTurns = history.stream()
            .filter(message -> message != null && "user".equalsIgnoreCase(message.role()))
            .map(ChatMessage::content)
            .filter(content -> content != null && !content.isBlank())
            .toList();
        int from = Math.max(0, recentUserTurns.size() - 3);
        StringBuilder query = new StringBuilder(city == null ? "" : city.trim());
        recentUserTurns.subList(from, recentUserTurns.size())
            .forEach(content -> query.append(' ').append(content.trim()));
        query.append(' ').append(currentMessage.trim());
        return query.toString().trim();
    }

    /** Keep ordinary day edits scoped to the affected day while preserving the original index. */
    private Map<String, Object> promptPlan(TripPlan plan, Integer targetDay) {
        Map<String, Object> scoped = new LinkedHashMap<>();
        scoped.put("city", plan.city());
        scoped.put("cities", plan.cities());
        scoped.put("start_date", plan.start_date());
        scoped.put("end_date", plan.end_date());
        scoped.put("weather_info", plan.weather_info());
        scoped.put("overall_suggestions", plan.overall_suggestions());
        scoped.put("budget", plan.budget());
        if (targetDay != null && targetDay >= 0 && targetDay < plan.days().size()) {
            scoped.put("scoped_day_index", targetDay);
            scoped.put("days", List.of(plan.days().get(targetDay)));
        } else {
            scoped.put("days", plan.days());
        }
        return scoped;
    }

    private String retrievalIntent(String message) {
        String value = message == null ? "" : message.toLowerCase();
        if (value.contains("顺序") || value.contains("轻松") || value.contains("步行") || value.contains("节奏")) {
            return "itinerary-structure-and-pace";
        }
        if (value.contains("天气") || value.contains("营业") || value.contains("开放")) return "opening-hours-and-weather";
        return "trip-edit:" + value.replaceAll("\\s+", " ").trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Integer affectedDay(String message, int dayCount) {
        if (dayCount <= 0 || message == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("第\\s*([0-9一二两三四五六七八九十]+)\\s*天")
            .matcher(message);
        if (!matcher.find()) return null;
        try { return Math.max(0, Math.min(dayCount - 1, Integer.parseInt(matcher.group(1)) - 1)); }
        catch (NumberFormatException ignored) {
            int value = "一二三四五六七八九".indexOf(matcher.group(1));
            return value < 0 ? null : Math.min(dayCount - 1, value);
        }
    }

    private record AdjustmentResult(
        String reply,
        String change_summary,
        List<TripPatchOperation> operations,
        boolean need_route_recalculate
    ) {
    }
}
