package com.zkry.task;

import com.zkry.common.exception.BizException;
import com.zkry.domain.dto.AiModificationTaskRequest;
import com.zkry.domain.entity.TripPlan;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.vo.SubmitTripPlanResponse;
import com.zkry.security.VoyagePrincipal;
import com.zkry.service.TripAccessService;
import com.zkry.service.planning.IntentRouter;
import com.zkry.domain.dto.IntentDecision;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AiModificationTaskService {

    private final TripAccessService accessService;
    private final TripTaskStore taskStore;
    private final IntentRouter intentRouter;

    public AiModificationTaskService(TripAccessService accessService, TripTaskStore taskStore) {
        this(accessService, taskStore, new IntentRouter());
    }

    @Autowired
    public AiModificationTaskService(TripAccessService accessService, TripTaskStore taskStore, IntentRouter intentRouter) {
        this.accessService = accessService;
        this.taskStore = taskStore;
        this.intentRouter = intentRouter;
    }

    public SubmitTripPlanResponse submit(
        String planId,
        Integer baseVersion,
        String message,
        java.util.List<com.zkry.domain.dto.ChatMessage> history,
        VoyagePrincipal principal
    ) {
        return submit(planId, baseVersion, message, history, principal, null);
    }

    public SubmitTripPlanResponse submit(
        String planId,
        Integer baseVersion,
        String message,
        java.util.List<com.zkry.domain.dto.ChatMessage> history,
        VoyagePrincipal principal,
        String idempotencyKey
    ) {
        if (principal == null) throw new BizException("请先登录。");
        if (message == null || message.isBlank()) throw new BizException("请输入希望 AI 调整的内容。");
        TripPlan plan = accessService.requireEdit(planId, principal);
        if (baseVersion == null || !baseVersion.equals(plan.getVersion())) {
            throw new BizException("行程版本已变化，请刷新后重试。当前版本=" + plan.getVersion());
        }
        String safeKey = normalizeIdempotencyKey(idempotencyKey, planId, baseVersion, message);
        java.util.Optional<TripTask> existing = taskStore.findByIdempotency(
            principal.userId(), TaskType.AI_MODIFICATION, safeKey);
        if (existing.isPresent()) {
            TripTask task = existing.get();
            return new SubmitTripPlanResponse(task.getTaskId(), planId, task.getStatus(), task.getProgressText());
        }
        taskStore.activeModification(planId, principal.userId()).ifPresent(active -> {
            throw new BizException("该行程已有修改任务处理中，请等待当前任务完成后再提交");
        });
        IntentDecision decision = intentRouter.decide(message, true);
        AiModificationTaskRequest request = new AiModificationTaskRequest(
            planId,
            baseVersion,
            message.trim(),
            history == null ? java.util.List.of() : history.stream()
                .skip(Math.max(0, history.size() - 4L))
                .toList(),
            decision.mode(),
            decision.scope(),
            safeKey
        );
        TripTask task = taskStore.create(
            principal.userId(),
            TaskType.AI_MODIFICATION,
            planId,
            safeKey,
            request,
            "AI 行程修改已进入队列"
        );
        return new SubmitTripPlanResponse(
            task.getTaskId(), planId, task.getStatus(), task.getProgressText()
        );
    }

    private String normalizeIdempotencyKey(String key, String planId, Integer baseVersion, String message) {
        String value = key == null ? "" : key.trim();
        if (!value.isBlank()) return value.length() <= 128 ? value : value.substring(0, 128);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((planId + "|" + baseVersion + "|" + message.trim())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
