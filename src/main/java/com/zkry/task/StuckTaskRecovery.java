package com.zkry.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.domain.entity.TripTask;
import com.zkry.mapper.TripTaskMapper;
import com.zkry.service.TripTaskStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StuckTaskRecovery {

    private static final Logger log = LoggerFactory.getLogger(StuckTaskRecovery.class);

    private final TripTaskMapper mapper;
    private final TripTaskStore taskStore;
    private final boolean singleInstanceRecovery;

    public StuckTaskRecovery(
        TripTaskMapper mapper,
        TripTaskStore taskStore,
        @Value("${tripstar.tasks.single-instance-recover-on-startup:true}") boolean singleInstanceRecovery
    ) {
        this.mapper = mapper;
        this.taskStore = taskStore;
        this.singleInstanceRecovery = singleInstanceRecovery;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverPreviousProcessTasks() {
        if (!singleInstanceRecovery) return;
        List<TripTask> interrupted = mapper.selectList(
            Wrappers.<TripTask>lambdaQuery()
                .eq(TripTask::getStatus, TripTaskStatus.PROCESSING)
                .isNotNull(TripTask::getProcessingToken)
                .orderByAsc(TripTask::getLeaseUntil)
                .last("LIMIT 200")
        );
        for (TripTask task : interrupted) {
            if (taskStore.recoverInterruptedAtStartup(task.getTaskId(), task.getProcessingToken())) {
                log.warn("[TaskRecovery] single-node restart requeued taskId={} attempt={}",
                    task.getTaskId(), task.getAttempt());
            }
        }
    }

    @Scheduled(fixedDelayString = "${tripstar.tasks.recovery-poll-ms:30000}")
    public void recoverExpiredLeases() {
        List<TripTask> expired = mapper.selectList(
            Wrappers.<TripTask>lambdaQuery()
                .eq(TripTask::getStatus, TripTaskStatus.PROCESSING)
                .lt(TripTask::getLeaseUntil, LocalDateTime.now())
                .isNotNull(TripTask::getProcessingToken)
                .orderByAsc(TripTask::getLeaseUntil)
                .last("LIMIT 50")
        );
        for (TripTask task : expired) {
            if (taskStore.recoverExpired(task.getTaskId(), task.getProcessingToken())) {
                log.warn("[TaskRecovery] requeued expired task lease taskId={} attempt={}",
                    task.getTaskId(), task.getAttempt());
            }
        }
    }
}
