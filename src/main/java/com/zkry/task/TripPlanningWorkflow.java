package com.zkry.task;

import com.zkry.common.constant.TripTaskMessages;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.TripRequest;
import com.zkry.domain.dto.planning.PlannerContextPack;
import com.zkry.domain.dto.planning.PlanningFactPack;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.vo.TripPlanResponse;
import com.zkry.service.TripAiPlannerService;
import com.zkry.service.TripResearchProgressReporter;
import com.zkry.service.TripTaskProgress;
import com.zkry.service.TripTaskStage;
import com.zkry.service.planning.PlanningResearchService;
import com.zkry.service.planning.PlanningFactVerificationService;
import com.zkry.memory.application.WorkingMemoryService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Restartable standard planning workflow with durable checkpoints. */
@Service
public class TripPlanningWorkflow {

    private static final Logger log = LoggerFactory.getLogger(TripPlanningWorkflow.class);
    private static final String CONTEXT_CHECKPOINT = "evidence.recalled";
    private static final String PLAN_CHECKPOINT = "plan.draft";

    private final TripTaskStore taskStore;
    private final TaskCheckpointService checkpoints;
    private final PlanningResearchService researchService;
    private final PlanningFactVerificationService factVerificationService;
    private final TripAiPlannerService plannerService;
    private final TaskCompletionService completionService;
    private final TaskRealtimePublisher realtimePublisher;
    private final ScheduledExecutorService planningHeartbeatExecutor;
    private final WorkingMemoryService workingMemoryService;

    public TripPlanningWorkflow(
        TripTaskStore taskStore,
        TaskCheckpointService checkpoints,
        PlanningResearchService researchService,
        PlanningFactVerificationService factVerificationService,
        TripAiPlannerService plannerService,
        TaskCompletionService completionService,
        TaskRealtimePublisher realtimePublisher,
        @Qualifier("planningProgressHeartbeatExecutor")
        ScheduledExecutorService planningHeartbeatExecutor
    ) {
        this(taskStore, checkpoints, researchService, factVerificationService, plannerService, completionService,
            realtimePublisher, planningHeartbeatExecutor, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TripPlanningWorkflow(
        TripTaskStore taskStore,
        TaskCheckpointService checkpoints,
        PlanningResearchService researchService,
        PlanningFactVerificationService factVerificationService,
        TripAiPlannerService plannerService,
        TaskCompletionService completionService,
        TaskRealtimePublisher realtimePublisher,
        @Qualifier("planningProgressHeartbeatExecutor") ScheduledExecutorService planningHeartbeatExecutor,
        WorkingMemoryService workingMemoryService
    ) {
        this.taskStore = taskStore;
        this.checkpoints = checkpoints;
        this.researchService = researchService;
        this.factVerificationService = factVerificationService;
        this.plannerService = plannerService;
        this.completionService = completionService;
        this.realtimePublisher = realtimePublisher;
        this.planningHeartbeatExecutor = planningHeartbeatExecutor;
        this.workingMemoryService = workingMemoryService;
    }

    public TripTask execute(TripTask task, String processingToken) {
        if (!TaskType.TRIP_PLAN.equals(task.getTaskType())) {
            throw new IllegalArgumentException("Unsupported planning task type: " + task.getTaskType());
        }
        TripRequest request = JsonUtils.parseObject(task.getRequestJson(), TripRequest.class);
        try (ProgressSession progress = new ProgressSession(task, processingToken)) {
            if (workingMemoryService != null) workingMemoryService.initializePlanning(task, request);
            progress.report(TripTaskStage.INITIALIZING, TripTaskProgress.INITIALIZING,
                TripTaskMessages.INITIALIZING);

            PlannerContextPack context = checkpoints
                .load(task.getTaskId(), CONTEXT_CHECKPOINT, PlannerContextPack.class)
                .orElseGet(() -> {
                    progress.report(TripTaskStage.TRAVEL_RESEARCH, TripTaskProgress.TRAVEL_RESEARCH,
                        "正在召回用户偏好、历史行程和知识库证据");
                    PlannerContextPack loaded = researchService.build(task.getOwnerId(), request, progress);
                    checkpoints.save(task.getTaskId(), CONTEXT_CHECKPOINT, loaded);
                    return loaded;
                });

            PlanningFactPack facts = checkpoints.load(task.getTaskId(), "facts.verified", PlanningFactPack.class)
                .orElseGet(() -> {
                    PlanningFactPack verified = factVerificationService.verify(request, context, progress);
                    checkpoints.save(task.getTaskId(), "facts.verified", verified);
                    return verified;
                });
            PlannerContextPack planningContext = context.withFacts(facts,
                request.normalizedCities().stream().map(city -> city.city()).toList());
            if (workingMemoryService != null) workingMemoryService.updatePlanningEvidence(task, context, facts);

            TripPlanResponse response = checkpoints
                .load(task.getTaskId(), PLAN_CHECKPOINT, TripPlanResponse.class)
                .orElseGet(() -> {
                    progress.report(TripTaskStage.PLANNING, TripTaskProgress.PLANNING,
                        TripTaskMessages.PLANNING);
                    progress.startPlanningHeartbeat();
                    try {
                        TripPlanResponse planned = plannerService.planWithTools(
                            task.getTaskId(), task.getOwnerId(), request, planningContext, progress, facts);
                        checkpoints.save(task.getTaskId(), PLAN_CHECKPOINT, planned);
                        return planned;
                    } finally {
                        progress.stopPlanningHeartbeat();
                    }
                });

            progress.report(TripTaskStage.PERSISTING, TripTaskProgress.PERSISTING,
                "正在保存当前行程版本");
            TripTask completed = completionService.complete(task, processingToken, request, response);
            realtimePublisher.publish(completed);
            return completed;
        }
    }

    private void publishProgress(TripTask task, String token, String stage, int value, String message) {
        realtimePublisher.publish(taskStore.progress(task.getTaskId(), token, stage, value, message));
    }

    private final class ProgressSession implements TripResearchProgressReporter, AutoCloseable {
        private final TripTask task;
        private final String token;
        private final AtomicInteger lastProgress = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> heartbeat;
        private volatile long startedNanos;

        private ProgressSession(TripTask task, String token) {
            this.task = task;
            this.token = token;
            this.lastProgress.set(task.getProgress() == null ? 0 : task.getProgress());
        }

        @Override
        public synchronized void report(String stage, int progress, String message) {
            if (closed.get()) return;
            int safe = Math.max(0, Math.min(progress, TripTaskProgress.DONE));
            if (safe < lastProgress.get()) return;
            lastProgress.set(safe);
            publishProgress(task, token, stage, safe, message);
        }

        private synchronized void startPlanningHeartbeat() {
            if (heartbeat != null) return;
            startedNanos = System.nanoTime();
            heartbeat = planningHeartbeatExecutor.scheduleAtFixedRate(() -> {
                if (closed.get() || lastProgress.get() >= TripTaskProgress.PLAN_READY) return;
                long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos);
                report(TripTaskStage.PLANNING, Math.min(TripTaskProgress.PLANNING_HEARTBEAT_MAX,
                    lastProgress.get() + 1), "AI 正在生成行程草案，已等待 " + elapsed + " 秒");
            }, 6, 8, TimeUnit.SECONDS);
        }

        private synchronized void stopPlanningHeartbeat() {
            if (heartbeat != null) {
                heartbeat.cancel(false);
                heartbeat = null;
            }
        }

        @Override
        public void close() {
            closed.set(true);
            stopPlanningHeartbeat();
        }
    }
}
