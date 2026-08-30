package com.zkry.task;

import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.AiModificationTaskRequest;
import com.zkry.domain.dto.TripChatRequest;
import com.zkry.domain.entity.TripPlan;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.entity.TripPlanVersion;
import com.zkry.domain.vo.TripChatResponse;
import com.zkry.service.TripAccessService;
import com.zkry.service.TripConversationService;
import com.zkry.service.TripTaskStage;
import com.zkry.service.TripWorkspaceService;
import com.zkry.mapper.TripPlanVersionMapper;
import com.zkry.memory.application.WorkingMemoryService;
import com.zkry.memory.application.UserMemoryQueryService;
import com.zkry.memory.domain.MemoryContextPack;
import com.zkry.domain.dto.IntentDecision;
import com.zkry.service.planning.IntentRouter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AiModificationWorkflow {

    public static final String RESULT_CHECKPOINT = "ai.modification.result";

    private final TripTaskStore taskStore;
    private final TaskCheckpointService checkpoints;
    private final TripAccessService accessService;
    private final TripWorkspaceService workspaceService;
    private final TripConversationService conversationService;
    private final AiModificationCompletionService completionService;
    private final TaskRealtimePublisher realtimePublisher;
    private final TripPlanVersionMapper versionMapper;
    private final WorkingMemoryService workingMemoryService;
    private final UserMemoryQueryService userMemoryQueryService;
    private final IntentRouter intentRouter;

    public AiModificationWorkflow(
        TripTaskStore taskStore,
        TaskCheckpointService checkpoints,
        TripAccessService accessService,
        TripWorkspaceService workspaceService,
        TripConversationService conversationService,
        AiModificationCompletionService completionService,
        TaskRealtimePublisher realtimePublisher
    ) {
        this(taskStore, checkpoints, accessService, workspaceService, conversationService,
            completionService, realtimePublisher, null, null, null, null);
    }

    @Autowired
    public AiModificationWorkflow(
        TripTaskStore taskStore,
        TaskCheckpointService checkpoints,
        TripAccessService accessService,
        TripWorkspaceService workspaceService,
        TripConversationService conversationService,
        AiModificationCompletionService completionService,
        TaskRealtimePublisher realtimePublisher,
        TripPlanVersionMapper versionMapper,
        WorkingMemoryService workingMemoryService,
        UserMemoryQueryService userMemoryQueryService,
        IntentRouter intentRouter
    ) {
        this.taskStore = taskStore;
        this.checkpoints = checkpoints;
        this.accessService = accessService;
        this.workspaceService = workspaceService;
        this.conversationService = conversationService;
        this.completionService = completionService;
        this.realtimePublisher = realtimePublisher;
        this.versionMapper = versionMapper;
        this.workingMemoryService = workingMemoryService;
        this.userMemoryQueryService = userMemoryQueryService;
        this.intentRouter = intentRouter;
    }

    public TripTask execute(TripTask task, String token) {
        AiModificationTaskRequest request =
            JsonUtils.parseObject(task.getRequestJson(), AiModificationTaskRequest.class);
        progress(task, token, "ai_authorizing", 10, "正在验证行程权限与版本");
        TripPlan entity = accessService.requireEdit(request.planId(), task.getOwnerId());
        if (!request.baseVersion().equals(entity.getVersion())) {
            throw new com.zkry.common.exception.BizException(
                "AI 修改基于的行程版本已过期，请刷新后重试。当前版本=" + entity.getVersion()
            );
        }
        if (workingMemoryService != null) {
            workingMemoryService.captureModification(task, request.planId(), entity.getVersion(), request.message(), request.history());
        }
        MemoryContextPack memories = userMemoryQueryService == null ? MemoryContextPack.empty()
            : userMemoryQueryService.context(task.getOwnerId(), entity.getCity(), request.planId());
        IntentDecision.Mode mode = request.mode() == null ? new IntentRouter().decide(request.message(), true).mode() : request.mode();
        TripChatResponse result = checkpoints
            .load(task.getTaskId(), RESULT_CHECKPOINT, TripChatResponse.class)
            .orElseGet(() -> {
                progress(task, token, TripTaskStage.PLANNING, 35, "AI 正在生成受控行程修改");
                com.zkry.domain.dto.TripPlan plan =
                    workspaceService.structuredPlanForEdit(request.planId(), task.getOwnerId());
                TripChatRequest chatRequest = new TripChatRequest(request.message(), plan, request.history(),
                    latestSnapshot(request.planId()), "", "");
                TripChatResponse generated = switch (mode) {
                    case LOCAL_PATCH -> conversationService.adjustLocal(chatRequest);
                    case RAG_QA -> conversationService.answerQuestion(chatRequest);
                    default -> conversationService.adjust(chatRequest, memories);
                };
                checkpoints.save(task.getTaskId(), RESULT_CHECKPOINT, generated);
                return generated;
            });
        progress(task, token, "ai_persisting", 85, mode == IntentDecision.Mode.RAG_QA
            ? "查询已完成，正在返回结果" : "正在校验版本并保存修改");
        if (mode == IntentDecision.Mode.RAG_QA) {
            TripTask completed = taskStore.completeGeneric(task.getTaskId(), token, request.planId(), entity.getVersion(),
                "/api/trips/" + request.planId() + "/workspace", result.reply());
            realtimePublisher.publish(completed);
            return completed;
        }
        TripTask completed = completionService.complete(
            task, token, request, result, entity.getOwnerId()
        );
        realtimePublisher.publish(completed);
        return completed;
    }

    private com.zkry.domain.dto.TripPlanSnapshot latestSnapshot(String planId) {
        if (versionMapper == null) return null;
        TripPlanVersion version = versionMapper.selectOne(
            Wrappers.<TripPlanVersion>lambdaQuery()
                .eq(TripPlanVersion::getPlanId, planId)
                .orderByDesc(TripPlanVersion::getVersion)
                .last("LIMIT 1")
        );
        if (version == null || version.getSnapshotJson() == null || version.getSnapshotJson().isBlank()) return null;
        try {
            return JsonUtils.parseObject(version.getSnapshotJson(), com.zkry.domain.dto.TripPlanSnapshot.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void progress(TripTask task, String token, String stage, int value, String message) {
        realtimePublisher.publish(taskStore.progress(task.getTaskId(), token, stage, value, message));
    }
}
