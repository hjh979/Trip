package com.zkry.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.AiModificationTaskRequest;
import com.zkry.domain.entity.TripPlan;
import com.zkry.domain.entity.TripPlanVersion;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.dto.TripPlanSnapshot;
import com.zkry.domain.vo.TripChatResponse;
import com.zkry.mapper.TripPlanMapper;
import com.zkry.mapper.TripPlanVersionMapper;
import com.zkry.service.TripPlanPersistenceService;
import com.zkry.service.planning.TripPlanSnapshotFactory;
import com.zkry.memory.application.TripMemoryEventWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AiModificationCompletionService {

    private final TripPlanPersistenceService persistenceService;
    private final TripPlanMapper planMapper;
    private final TripPlanVersionMapper versionMapper;
    private final TripTaskStore taskStore;
    private final TripPlanSnapshotFactory snapshotFactory;
    private final TripMemoryEventWriter memoryEventWriter;

    public AiModificationCompletionService(
        TripPlanPersistenceService persistenceService,
        TripPlanMapper planMapper,
        TripPlanVersionMapper versionMapper,
        TripTaskStore taskStore
    ) {
        this(persistenceService, planMapper, versionMapper, taskStore, new TripPlanSnapshotFactory(), null);
    }

    @Autowired
    public AiModificationCompletionService(
        TripPlanPersistenceService persistenceService,
        TripPlanMapper planMapper,
        TripPlanVersionMapper versionMapper,
        TripTaskStore taskStore,
        TripPlanSnapshotFactory snapshotFactory,
        TripMemoryEventWriter memoryEventWriter
    ) {
        this.persistenceService = persistenceService;
        this.planMapper = planMapper;
        this.versionMapper = versionMapper;
        this.taskStore = taskStore;
        this.snapshotFactory = snapshotFactory;
        this.memoryEventWriter = memoryEventWriter;
    }

    @Transactional
    public TripTask complete(
        TripTask task,
        String token,
        AiModificationTaskRequest request,
        TripChatResponse result,
        Long planOwnerId
    ) {
        persistenceService.saveWorkspace(
            request.planId(),
            planOwnerId,
            result.trip_plan(),
            request.baseVersion()
        );
        TripPlan updated = planMapper.selectOne(
            Wrappers.<TripPlan>lambdaQuery()
                .eq(TripPlan::getPublicId, request.planId())
                .last("LIMIT 1")
        );
        TripPlanVersion existing = versionMapper.selectOne(
            Wrappers.<TripPlanVersion>lambdaQuery()
                .eq(TripPlanVersion::getTaskId, task.getTaskId())
                .last("LIMIT 1")
        );
        if (existing == null) {
            TripPlanSnapshot previousSnapshot = latestSnapshot(request.planId());
            TripPlanVersion version = new TripPlanVersion();
            version.setPlanId(request.planId());
            version.setVersion(updated.getVersion());
            version.setCreatedBy(task.getOwnerId());
            version.setSourceType(result.operations() != null && !result.operations().isEmpty()
                ? "LOCAL_PATCH" : TaskType.AI_MODIFICATION);
            version.setTaskId(task.getTaskId());
            version.setResultJson(JsonUtils.toJsonString(result));
            TripPlanSnapshot currentSnapshot = snapshotFactory.create(
                result.trip_plan(), updated.getVersion(),
                result.retrieval_trace_id() == null || result.retrieval_trace_id().isBlank()
                    ? previousSnapshot == null ? java.util.List.of() : previousSnapshot.rag_trace_ids()
                    : java.util.List.of(result.retrieval_trace_id()),
                previousSnapshot == null || previousSnapshot.constraints() == null
                    ? java.util.List.of() : previousSnapshot.constraints().preferences());
            version.setSnapshotJson(JsonUtils.toJsonString(currentSnapshot));
            versionMapper.insert(version);
            if (memoryEventWriter != null) {
                memoryEventWriter.modification(task, request.planId(), updated.getVersion(), previousSnapshot,
                    currentSnapshot, result, request.message());
            }
        }
        return taskStore.completeGeneric(
            task.getTaskId(),
            token,
            request.planId(),
            updated.getVersion(),
            "/api/trips/" + request.planId() + "/workspace",
            "AI 行程修改已保存"
        );
    }

    private TripPlanSnapshot latestSnapshot(String planId) {
        TripPlanVersion previous = versionMapper.selectOne(
            Wrappers.<TripPlanVersion>lambdaQuery()
                .eq(TripPlanVersion::getPlanId, planId)
                .orderByDesc(TripPlanVersion::getVersion)
                .last("LIMIT 1")
        );
        if (previous == null || previous.getSnapshotJson() == null || previous.getSnapshotJson().isBlank()) return null;
        try {
            return JsonUtils.parseObject(previous.getSnapshotJson(), TripPlanSnapshot.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
