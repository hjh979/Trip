package com.zkry.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.domain.entity.TripComment;
import com.zkry.domain.entity.TripDay;
import com.zkry.domain.entity.TripItem;
import com.zkry.domain.entity.TripMember;
import com.zkry.domain.entity.TripPlan;
import com.zkry.domain.entity.TripPlanRecord;
import com.zkry.domain.entity.TripPlanVersion;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.entity.TripTaskCheckpoint;
import com.zkry.mapper.TripCommentMapper;
import com.zkry.mapper.TripDayMapper;
import com.zkry.mapper.TripItemMapper;
import com.zkry.mapper.TripMemberMapper;
import com.zkry.mapper.TripPlanMapper;
import com.zkry.mapper.TripPlanRecordMapper;
import com.zkry.mapper.TripPlanVersionMapper;
import com.zkry.mapper.TripTaskCheckpointMapper;
import com.zkry.mapper.TripTaskMapper;
import com.zkry.security.VoyagePrincipal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes a saved itinerary and all user-visible derived data in one transaction.
 *
 * <p>Entities use MyBatis-Plus logical deletion, so an accidental deletion remains recoverable
 * by an administrator. A running AI modification is rejected instead of racing a deleted plan.
 */
@Service
public class TripDeletionService {

    private static final List<String> ACTIVE_TASK_STATUSES = List.of(
        TripTaskStatus.QUEUED,
        TripTaskStatus.PROCESSING,
        TripTaskStatus.RETRYING
    );

    private final TripAccessService accessService;
    private final TripPlanMapper planMapper;
    private final TripDayMapper dayMapper;
    private final TripItemMapper itemMapper;
    private final TripMemberMapper memberMapper;
    private final TripCommentMapper commentMapper;
    private final TripPlanRecordMapper recordMapper;
    private final TripPlanVersionMapper versionMapper;
    private final TripTaskMapper taskMapper;
    private final TripTaskCheckpointMapper checkpointMapper;
    private final AuditLogService auditLogService;

    public TripDeletionService(
        TripAccessService accessService,
        TripPlanMapper planMapper,
        TripDayMapper dayMapper,
        TripItemMapper itemMapper,
        TripMemberMapper memberMapper,
        TripCommentMapper commentMapper,
        TripPlanRecordMapper recordMapper,
        TripPlanVersionMapper versionMapper,
        TripTaskMapper taskMapper,
        TripTaskCheckpointMapper checkpointMapper,
        AuditLogService auditLogService
    ) {
        this.accessService = accessService;
        this.planMapper = planMapper;
        this.dayMapper = dayMapper;
        this.itemMapper = itemMapper;
        this.memberMapper = memberMapper;
        this.commentMapper = commentMapper;
        this.recordMapper = recordMapper;
        this.versionMapper = versionMapper;
        this.taskMapper = taskMapper;
        this.checkpointMapper = checkpointMapper;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void delete(String publicId, VoyagePrincipal principal) {
        TripPlan plan = accessService.requireManageMembers(publicId, principal);
        String planId = plan.getPublicId();
        rejectActiveModification(planId);

        List<TripDay> days = dayMapper.selectList(
            Wrappers.<TripDay>lambdaQuery().eq(TripDay::getPlanId, plan.getId())
        );
        List<Long> dayIds = days.stream().map(TripDay::getId).toList();
        if (!dayIds.isEmpty()) {
            itemMapper.delete(Wrappers.<TripItem>lambdaQuery().in(TripItem::getDayId, dayIds));
        }
        dayMapper.delete(Wrappers.<TripDay>lambdaQuery().eq(TripDay::getPlanId, plan.getId()));
        commentMapper.delete(
            Wrappers.<TripComment>lambdaQuery().eq(TripComment::getPlanId, planId)
        );
        memberMapper.delete(
            Wrappers.<TripMember>lambdaQuery().eq(TripMember::getPlanId, planId)
        );

        List<TripPlanRecord> records = recordMapper.selectList(
            Wrappers.<TripPlanRecord>lambdaQuery().eq(TripPlanRecord::getPlanId, planId)
        );
        List<TripPlanVersion> versions = versionMapper.selectList(
            Wrappers.<TripPlanVersion>lambdaQuery().eq(TripPlanVersion::getPlanId, planId)
        );
        List<TripTask> completedTasks = taskMapper.selectList(
            Wrappers.<TripTask>lambdaQuery().eq(TripTask::getResultPlanId, planId)
        );
        Set<String> taskIds = new LinkedHashSet<>();
        records.stream().map(TripPlanRecord::getTaskId).filter(this::hasText).forEach(taskIds::add);
        versions.stream().map(TripPlanVersion::getTaskId).filter(this::hasText).forEach(taskIds::add);
        completedTasks.stream().map(TripTask::getTaskId).filter(this::hasText).forEach(taskIds::add);
        if (!taskIds.isEmpty()) {
            checkpointMapper.delete(
                Wrappers.<TripTaskCheckpoint>lambdaQuery()
                    .in(TripTaskCheckpoint::getTaskId, taskIds)
            );
            taskMapper.delete(Wrappers.<TripTask>lambdaQuery().in(TripTask::getTaskId, taskIds));
        }
        versionMapper.delete(
            Wrappers.<TripPlanVersion>lambdaQuery().eq(TripPlanVersion::getPlanId, planId)
        );
        recordMapper.delete(
            Wrappers.<TripPlanRecord>lambdaQuery().eq(TripPlanRecord::getPlanId, planId)
        );
        planMapper.deleteById(plan);

        auditLogService.record(
            principal,
            "删除行程",
            "行程 " + planId + "（" + plan.getTitle() + "）",
            "行程管理",
            "SUCCESS"
        );
    }

    private void rejectActiveModification(String planId) {
        long active = taskMapper.selectCount(
            Wrappers.<TripTask>lambdaQuery()
                .in(TripTask::getStatus, ACTIVE_TASK_STATUSES)
                .and(query -> query
                    .eq(TripTask::getResultPlanId, planId)
                    .or()
                    .like(TripTask::getRequestJson, "\"planId\":\"" + planId + "\""))
        );
        if (active > 0) {
            throw new BizException("该行程仍有执行中的生成或 AI 修改任务，请等待任务结束后再删除。");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
