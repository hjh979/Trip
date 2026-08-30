package com.zkry.service;

import com.zkry.common.constant.TripTaskMessages;
import com.zkry.common.constant.TripstarSettingKeys;
import com.zkry.common.exception.BizException;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.TripRequest;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.vo.SubmitTripPlanResponse;
import com.zkry.domain.vo.TripHistoryItem;
import com.zkry.task.TaskType;
import com.zkry.task.TaskCheckpointService;
import com.zkry.task.AiModificationWorkflow;
import com.zkry.domain.vo.TripChatResponse;
import com.zkry.task.KnowledgeIngestionWorkflow;
import com.zkry.domain.vo.KnowledgeDocumentView;
import com.zkry.task.TripTaskStore;
import com.zkry.service.planning.RequestCompletenessChecker;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * HTTP-facing task facade. Execution is performed only by RabbitMQ consumers.
 */
@Service
public class TripTaskService {

    private static final Logger log = LoggerFactory.getLogger(TripTaskService.class);

    private final TripTaskStore taskStore;
    private final TripAiPlannerService plannerService;
    private final TripstarRuntimeSettingsService runtimeSettingsService;
    private final TripPlanPersistenceService persistenceService;
    private final TaskCheckpointService checkpoints;
    private final RequestCompletenessChecker completenessChecker;

    public TripTaskService(
        TripTaskStore taskStore,
        TripAiPlannerService plannerService,
        TripstarRuntimeSettingsService runtimeSettingsService,
        TripPlanPersistenceService persistenceService,
        TaskCheckpointService checkpoints
    ) {
        this(taskStore, plannerService, runtimeSettingsService, persistenceService, checkpoints,
            new RequestCompletenessChecker());
    }

    @Autowired
    public TripTaskService(
        TripTaskStore taskStore,
        TripAiPlannerService plannerService,
        TripstarRuntimeSettingsService runtimeSettingsService,
        TripPlanPersistenceService persistenceService,
        TaskCheckpointService checkpoints,
        RequestCompletenessChecker completenessChecker
    ) {
        this.taskStore = taskStore;
        this.plannerService = plannerService;
        this.runtimeSettingsService = runtimeSettingsService;
        this.persistenceService = persistenceService;
        this.checkpoints = checkpoints;
        this.completenessChecker = completenessChecker;
    }

    public SubmitTripPlanResponse submit(TripRequest request, Long ownerId) {
        validateTripRequest(request);
        RequestCompletenessChecker.CompletenessResult completeness = completenessChecker.check(request);
        if (!completeness.complete()) throw new BizException(completeness.followUpQuestion());
        validateRuntimeSettings();
        TripTask task = taskStore.create(ownerId, TaskType.TRIP_PLAN, request, TripTaskMessages.SUBMITTED);
        log.info("[TripTask] durable planning task created taskId={} ownerId={} cities={}",
            task.getTaskId(), ownerId, request.normalizedCities().stream().map(value -> value.city()).toList());
        return response(task);
    }

    public Map<String, Object> status(String taskId, Long ownerId) {
        TripTask task = taskStore.requireOwned(taskId, ownerId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", task.getTaskId());
        payload.put("plan_id", firstNonBlank(task.getResultPlanId(), task.getTaskId()));
        payload.put("status", task.getStatus());
        payload.put("stage", task.getStage());
        payload.put("progress", task.getProgress());
        payload.put("progress_text", task.getProgressText());
        payload.put("seq", task.getLastSeq());
        payload.put("attempt", task.getAttempt());
        payload.put("max_attempts", task.getMaxAttempts());
        if (task.getResultVersion() != null) payload.put("version", task.getResultVersion());
        if (task.getResultUrl() != null) payload.put("result_url", task.getResultUrl());
        if (TripTaskStatus.COMPLETED.equals(task.getStatus())) {
            if (TaskType.AI_MODIFICATION.equals(task.getTaskType())) {
                checkpoints.load(task.getTaskId(), AiModificationWorkflow.RESULT_CHECKPOINT, TripChatResponse.class)
                    .ifPresent(result -> payload.put("result", result));
            } else if (TaskType.KNOWLEDGE_INGESTION.equals(task.getTaskType())) {
                checkpoints.load(
                    task.getTaskId(),
                    KnowledgeIngestionWorkflow.RESULT_CHECKPOINT,
                    KnowledgeDocumentView.class
                ).ifPresent(result -> payload.put("result", result));
            } else {
                persistenceService.findCompletedResult(task.getTaskId())
                    .ifPresent(result -> payload.put("result", result));
            }
        } else if (TripTaskStatus.FAILED.equals(task.getStatus())
            || TripTaskStatus.DEAD_LETTERED.equals(task.getStatus())) {
            payload.put("error", task.getErrorMessage());
            payload.put("error_code", task.getErrorCode());
            payload.put("request_payload", JsonUtils.parseMap(task.getRequestJson()));
        } else if (TripTaskStatus.RETRYING.equals(task.getStatus()) && task.getNextRetryAt() != null) {
            payload.put("next_retry_at", task.getNextRetryAt());
            payload.put("last_error", task.getErrorMessage());
        }
        return payload;
    }

    public List<TripHistoryItem> history(int limit) {
        return persistenceService.history(limit);
    }

    private SubmitTripPlanResponse response(TripTask task) {
        return new SubmitTripPlanResponse(
            task.getTaskId(),
            task.getTaskId(),
            task.getStatus(),
            task.getProgressText()
        );
    }

    private void validateTripRequest(TripRequest request) {
        if (request == null) throw new BizException("行程请求不能为空。");
        if (request.normalizedCities().isEmpty()) throw new BizException("请至少填写一个目的地城市。");
        if (request.safeTravelDays() <= 0) throw new BizException("旅行天数必须大于 0。");
    }

    private void validateRuntimeSettings() {
        List<String> missing = new ArrayList<>();
        if (!runtimeSettingsService.hasText(TripstarSettingKeys.AMAP_WEB_KEY)) {
            missing.add("高德地图 Web Service Key");
        }
        if (!runtimeSettingsService.hasText(TripstarSettingKeys.OPENAI_API_KEY)) missing.add("AI API Key");
        if (!runtimeSettingsService.hasText(TripstarSettingKeys.OPENAI_MODEL)) missing.add("AI 模型名称");
        if (!missing.isEmpty()) {
            throw new BizException("缺少运行时配置：" + String.join("、", missing) + "。请先在设置页保存。");
        }
        if (!plannerService.isAvailable()) throw new BizException("AI 规划服务当前不可用。");
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
