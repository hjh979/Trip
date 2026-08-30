package com.zkry.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.TripRequest;
import com.zkry.domain.entity.TripPlanVersion;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.vo.TripPlanResponse;
import com.zkry.mapper.TripPlanVersionMapper;
import com.zkry.mapper.TripTaskMapper;
import com.zkry.service.TripPlanPersistenceService;
import com.zkry.service.TripTaskStage;
import com.zkry.service.TripTaskStatus;
import com.zkry.service.planning.TripPlanSnapshotFactory;
import com.zkry.memory.application.TripMemoryEventWriter;
import com.zkry.domain.dto.TripPlanSnapshot;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class TaskCompletionService {

    private final TripPlanPersistenceService persistenceService;
    private final TripPlanVersionMapper versionMapper;
    private final TripTaskMapper taskMapper;
    private final TripPlanSnapshotFactory snapshotFactory;
    private final TripMemoryEventWriter memoryEventWriter;

    public TaskCompletionService(
        TripPlanPersistenceService persistenceService,
        TripPlanVersionMapper versionMapper,
        TripTaskMapper taskMapper
    ) {
        this(persistenceService, versionMapper, taskMapper, new TripPlanSnapshotFactory(), null);
    }

    @Autowired
    public TaskCompletionService(
        TripPlanPersistenceService persistenceService,
        TripPlanVersionMapper versionMapper,
        TripTaskMapper taskMapper,
        TripPlanSnapshotFactory snapshotFactory,
        TripMemoryEventWriter memoryEventWriter
    ) {
        this.persistenceService = persistenceService;
        this.versionMapper = versionMapper;
        this.taskMapper = taskMapper;
        this.snapshotFactory = snapshotFactory;
        this.memoryEventWriter = memoryEventWriter;
    }

    /**
     * The itinerary, immutable version and P0 task state are committed atomically.
     */
    @Transactional
    public TripTask complete(
        TripTask task,
        String processingToken,
        TripRequest request,
        TripPlanResponse response
    ) {
        String planId = response.plan_id() == null || response.plan_id().isBlank()
            ? task.getTaskId() : response.plan_id();
        persistenceService.saveCompleted(task.getTaskId(), request, response, task.getOwnerId());
        TripPlanVersion existing = versionMapper.selectOne(
            Wrappers.<TripPlanVersion>lambdaQuery()
                .eq(TripPlanVersion::getTaskId, task.getTaskId())
                .last("LIMIT 1")
        );
        int versionNumber;
        if (existing == null) {
            TripPlanVersion latest = versionMapper.selectOne(
                Wrappers.<TripPlanVersion>lambdaQuery()
                    .eq(TripPlanVersion::getPlanId, planId)
                    .orderByDesc(TripPlanVersion::getVersion)
                    .last("LIMIT 1")
            );
            versionNumber = latest == null ? 1 : latest.getVersion() + 1;
            TripPlanVersion version = new TripPlanVersion();
            version.setPlanId(planId);
            version.setVersion(versionNumber);
            version.setCreatedBy(task.getOwnerId());
            version.setSourceType(task.getTaskType());
            version.setTaskId(task.getTaskId());
            version.setResultJson(JsonUtils.toJsonString(response));
            TripPlanSnapshot snapshot = snapshotFactory.create(
                response.data(), versionNumber,
                response.research_evidence() == null ? java.util.List.of() : response.research_evidence().rag_trace_ids(),
                request == null ? java.util.List.of() : request.safePreferences());
            version.setSnapshotJson(JsonUtils.toJsonString(snapshot));
            versionMapper.insert(version);
            if (memoryEventWriter != null) memoryEventWriter.planCreated(task, planId, versionNumber, snapshot);
        } else {
            versionNumber = existing.getVersion();
        }

        int changed = taskMapper.update(
            null,
            Wrappers.<TripTask>lambdaUpdate()
                .eq(TripTask::getTaskId, task.getTaskId())
                .eq(TripTask::getProcessingToken, processingToken)
                .eq(TripTask::getStatus, TripTaskStatus.PROCESSING)
                .set(TripTask::getStatus, TripTaskStatus.COMPLETED)
                .set(TripTask::getStage, TripTaskStage.COMPLETED)
                .set(TripTask::getProgress, 100)
                .set(TripTask::getProgressText, "行程规划已完成")
                .set(TripTask::getResultPlanId, planId)
                .set(TripTask::getResultVersion, versionNumber)
                .set(TripTask::getResultUrl, "/api/trip/status/" + task.getTaskId())
                .set(TripTask::getErrorCode, "")
                .set(TripTask::getErrorMessage, "")
                .set(TripTask::getProcessingToken, null)
                .set(TripTask::getLeaseUntil, null)
                .set(TripTask::getCompletedAt, LocalDateTime.now())
                .setSql("last_seq = last_seq + 1, lock_version = lock_version + 1")
        );
        if (changed != 1) throw new IllegalStateException("Task lease was lost before completion: " + task.getTaskId());
        return taskMapper.selectOne(
            Wrappers.<TripTask>lambdaQuery().eq(TripTask::getTaskId, task.getTaskId()).last("LIMIT 1")
        );
    }
}
