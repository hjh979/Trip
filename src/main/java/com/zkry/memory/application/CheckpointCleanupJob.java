package com.zkry.memory.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.domain.entity.TripTask;
import com.zkry.domain.entity.TripTaskCheckpoint;
import com.zkry.mapper.TripTaskCheckpointMapper;
import com.zkry.mapper.TripTaskMapper;
import com.zkry.service.TripTaskStatus;
import java.time.LocalDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CheckpointCleanupJob {
    private final TripTaskCheckpointMapper checkpoints;
    private final TripTaskMapper tasks;
    public CheckpointCleanupJob(TripTaskCheckpointMapper checkpoints, TripTaskMapper tasks) {
        this.checkpoints = checkpoints; this.tasks = tasks;
    }
    @Scheduled(cron = "0 17 * * * *")
    public void cleanupExpired() {
        for (TripTaskCheckpoint checkpoint : checkpoints.selectList(Wrappers.<TripTaskCheckpoint>lambdaQuery()
            .isNotNull(TripTaskCheckpoint::getExpiresAt).lt(TripTaskCheckpoint::getExpiresAt, LocalDateTime.now()))) {
            TripTask task = tasks.selectOne(Wrappers.<TripTask>lambdaQuery()
                .eq(TripTask::getTaskId, checkpoint.getTaskId()).last("LIMIT 1"));
            if (task == null || !TripTaskStatus.DEAD_LETTERED.equals(task.getStatus())) checkpoints.deleteById(checkpoint.getId());
        }
    }
}
