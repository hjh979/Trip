package com.zkry.service;

import com.zkry.common.exception.BizException;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.Budget;
import com.zkry.domain.dto.CityStay;
import com.zkry.domain.dto.TripPlan;
import com.zkry.domain.dto.TripRequest;
import com.zkry.domain.dto.planning.PlannerContextPack;
import com.zkry.domain.dto.planning.PlanningFactPack;
import com.zkry.domain.vo.TripPlanResponse;
import com.zkry.domain.vo.TripResearchEvidence;
import com.zkry.integration.ai.agent.TripstarAgent;
import com.zkry.integration.ai.prompt.TripPlannerPrompts;
import com.zkry.integration.ai.prompt.TripstarPrompt;
import com.zkry.integration.ai.prompt.TripstarPromptVariable;
import com.zkry.integration.ai.service.AiAgentService;
import com.zkry.integration.ai.service.AiStructuredOutputService;
import com.zkry.integration.ai.service.PromptResourceService;
import com.zkry.service.planning.TravelPlannerToolNames;
import com.zkry.service.planning.TravelPlannerToolboxFactory;
import com.zkry.service.planning.TripPlanPolicyValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Single standard planner. Facts are recalled before this service and validated before save. */
@Service
public class TripAiPlannerService {
    private static final Logger log = LoggerFactory.getLogger(TripAiPlannerService.class);

    private final AiAgentService aiAgentService;
    private final AiStructuredOutputService structuredOutputService;
    private final PromptResourceService promptResourceService;
    private final TripPlanRepairService repairService;
    private final TravelPlannerToolboxFactory toolboxFactory;
    private final TripPlanPolicyValidator policyValidator;

    public TripAiPlannerService(
        AiAgentService aiAgentService,
        AiStructuredOutputService structuredOutputService,
        PromptResourceService promptResourceService,
        com.zkry.service.rag.KnowledgeRagService ignoredRagService,
        TripPlanRepairService repairService,
        TravelPlannerToolboxFactory toolboxFactory,
        TripPlanPolicyValidator policyValidator
    ) {
        this.aiAgentService = aiAgentService;
        this.structuredOutputService = structuredOutputService;
        this.promptResourceService = promptResourceService;
        this.repairService = repairService;
        this.toolboxFactory = toolboxFactory;
        this.policyValidator = policyValidator;
    }

    public boolean isAvailable() {
        return aiAgentService.isAvailable();
    }

    public TripPlanResponse planWithTools(
        String planId,
        Long ownerId,
        TripRequest request,
        PlannerContextPack contextPack
    ) {
        return planWithTools(planId, ownerId, request, contextPack, TripResearchProgressReporter.noop());
    }

    public TripPlanResponse planWithTools(
        String planId,
        Long ownerId,
        TripRequest request,
        PlannerContextPack contextPack,
        TripResearchProgressReporter progress,
        PlanningFactPack verifiedFacts
    ) {
        return planWithToolsInternal(planId, ownerId, request, contextPack, progress, verifiedFacts);
    }

    public TripPlanResponse planWithTools(
        String planId,
        Long ownerId,
        TripRequest request,
        PlannerContextPack contextPack,
        TripResearchProgressReporter progress
    ) {
        return planWithToolsInternal(planId, ownerId, request, contextPack, progress, null);
    }

    private TripPlanResponse planWithToolsInternal(
        String planId,
        Long ownerId,
        TripRequest request,
        PlannerContextPack contextPack,
        TripResearchProgressReporter progress,
        PlanningFactPack verifiedFacts
    ) {
        if (request == null) throw new BizException("旅行请求不能为空");
        TripResearchProgressReporter reporter = progress == null
            ? TripResearchProgressReporter.noop() : progress;
        TravelPlannerToolboxFactory.PlanningToolSession tools =
            toolboxFactory.bind(planId, ownerId, request, reporter);
        // Fact verification runs before planning and is checkpointed. Some OpenAI-compatible
        // providers end a tool round without a final assistant message even when those facts
        // are already present, so the normal workflow composes from the verified package.
        boolean usePlannerTools = contextPack == null || !contextPack.factsVerified();
        Map<String, String> variables = new LinkedHashMap<>(TripPlannerPrompts.requestVariables(request));
        variables.put("planning_context", contextPack == null ? "无可用证据" : contextPack.context());
        variables.put(TripstarPromptVariable.FORMAT, structuredOutputService.format(TripPlan.class));
        String prompt = promptResourceService.render(TripstarPrompt.PLAN_EXECUTE_USER, variables);
        reporter.report(TripTaskStage.PLANNING, TripTaskProgress.PLANNING, "Planner Agent 正在生成结构化行程");
        TripPlan plan = (usePlannerTools
            ? structuredOutputService.callForObject(
                TripstarAgent.TRIP_PLANNER,
                TripPlan.class,
                promptResourceService.load(TripstarPrompt.PLAN_EXECUTE_SYSTEM),
                prompt,
                planId + "-planner",
                tools
            )
            : structuredOutputService.callForObject(
                TripstarAgent.TRIP_PLANNER,
                TripPlan.class,
                promptResourceService.load(TripstarPrompt.PLAN_EXECUTE_SYSTEM),
                prompt,
                planId + "-planner"
            )
        ).orElseThrow(() -> new BizException("Planner Agent 未返回有效 TripPlan JSON"));
        plan = repairService.repair(plan, null).plan();
        TripPlanPolicyValidator.Evaluation evaluation = policyValidator.evaluate(request, plan, verifiedFacts);
        if (!evaluation.passed()) {
            log.warn("[AI-PLAN] deterministic validation failed planId={} issues={}", planId, evaluation.issues());
            throw new BizException("行程未通过确定性校验：" + String.join(";", evaluation.issues()));
        }
        reporter.report(TripTaskStage.PLANNING_VALIDATION, TripTaskProgress.PLAN_READY, "行程已通过确定性校验");
        TravelPlannerToolboxFactory.ToolUsage usage = tools.usage();
        if (usePlannerTools && !usage.tools().contains(TravelPlannerToolNames.VALIDATE_POI)) {
            throw new BizException("Planner 未调用 validate_poi，拒绝保存未经核验的景点");
        }
        TripPlanResponse base = TripPlanResponseFactory.fromPlan(planId, plan);
        TripResearchEvidence evidence = new TripResearchEvidence(
            usage.amapUsed(), usage.amapUsed() ? request.normalizedCities().size() : 0,
            "自建知识库 RAG", "证据已召回并通过 AMap 核验；trace="
                + (contextPack == null ? List.of() : contextPack.safeTraceIds())
        );
        return new TripPlanResponse(base.success(), base.message(), base.plan_id(), base.data(), base.graph_data(), evidence);
    }
}
