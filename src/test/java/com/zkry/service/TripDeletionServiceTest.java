package com.zkry.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zkry.common.exception.BizException;
import com.zkry.domain.entity.TripDay;
import com.zkry.domain.entity.TripPlan;
import com.zkry.domain.entity.TripPlanRecord;
import com.zkry.domain.entity.TripPlanVersion;
import com.zkry.domain.entity.TripTask;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TripDeletionServiceTest {

    @Mock private TripAccessService accessService;
    @Mock private TripPlanMapper planMapper;
    @Mock private TripDayMapper dayMapper;
    @Mock private TripItemMapper itemMapper;
    @Mock private TripMemberMapper memberMapper;
    @Mock private TripCommentMapper commentMapper;
    @Mock private TripPlanRecordMapper recordMapper;
    @Mock private TripPlanVersionMapper versionMapper;
    @Mock private TripTaskMapper taskMapper;
    @Mock private TripTaskCheckpointMapper checkpointMapper;
    @Mock private AuditLogService auditLogService;

    private TripDeletionService service;
    private TripPlan plan;
    private VoyagePrincipal owner;

    @BeforeEach
    void setUp() {
        service = new TripDeletionService(
            accessService,
            planMapper,
            dayMapper,
            itemMapper,
            memberMapper,
            commentMapper,
            recordMapper,
            versionMapper,
            taskMapper,
            checkpointMapper,
            auditLogService
        );
        plan = new TripPlan();
        plan.setId(10L);
        plan.setPublicId("plan-1");
        plan.setOwnerId(1001L);
        plan.setTitle("测试行程");
        owner = new VoyagePrincipal(1001L, "owner", "USER");
        when(accessService.requireManageMembers("plan-1", owner)).thenReturn(plan);
    }

    @Test
    void rejectsDeletionWhileAPlanTaskIsStillRunning() {
        when(taskMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () -> service.delete("plan-1", owner));

        verify(dayMapper, never()).selectList(any());
        verify(planMapper, never()).deleteById(plan);
    }

    @Test
    @SuppressWarnings("unchecked")
    void logicallyDeletesThePlanAndAllDerivedArtifacts() {
        when(taskMapper.selectCount(any())).thenReturn(0L);
        TripDay day = new TripDay();
        day.setId(20L);
        day.setPlanId(10L);
        when(dayMapper.selectList(any())).thenReturn(List.of(day));

        TripPlanRecord record = new TripPlanRecord();
        record.setTaskId("task-plan");
        when(recordMapper.selectList(any())).thenReturn(List.of(record));
        TripPlanVersion version = new TripPlanVersion();
        version.setTaskId("task-edit");
        when(versionMapper.selectList(any())).thenReturn(List.of(version));
        TripTask task = new TripTask();
        task.setTaskId("task-plan");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        service.delete("plan-1", owner);

        verify(itemMapper).delete(any(Wrapper.class));
        verify(dayMapper).delete(any(Wrapper.class));
        verify(commentMapper).delete(any(Wrapper.class));
        verify(memberMapper).delete(any(Wrapper.class));
        verify(checkpointMapper).delete(any(Wrapper.class));
        verify(taskMapper).delete(any(Wrapper.class));
        verify(versionMapper).delete(any(Wrapper.class));
        verify(recordMapper).delete(any(Wrapper.class));
        verify(planMapper).deleteById(plan);
        verify(auditLogService).record(
            owner,
            "删除行程",
            "行程 plan-1（测试行程）",
            "行程管理",
            "SUCCESS"
        );
    }
}
