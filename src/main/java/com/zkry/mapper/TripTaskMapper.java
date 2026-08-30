package com.zkry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zkry.domain.entity.TripTask;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TripTaskMapper extends BaseMapper<TripTask> {

    @Update("""
        UPDATE trip_task
        SET status = 'processing',
            attempt = attempt + 1,
            processing_token = #{token},
            lease_until = #{leaseUntil},
            started_at = COALESCE(started_at, #{now}),
            next_retry_at = NULL,
            lock_version = lock_version + 1,
            update_time = #{now}
        WHERE task_id = #{taskId}
          AND deleted = 0
          AND status IN ('queued', 'retrying')
          AND (next_retry_at IS NULL OR next_retry_at <= #{now})
        """)
    int claim(
        @Param("taskId") String taskId,
        @Param("token") String token,
        @Param("leaseUntil") LocalDateTime leaseUntil,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE trip_task
        SET lease_until = #{leaseUntil}, update_time = #{now}
        WHERE task_id = #{taskId}
          AND processing_token = #{token}
          AND status = 'processing'
          AND deleted = 0
        """)
    int heartbeat(
        @Param("taskId") String taskId,
        @Param("token") String token,
        @Param("leaseUntil") LocalDateTime leaseUntil,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE trip_task
        SET stage = #{stage},
            progress = #{progress},
            progress_text = #{message},
            last_seq = last_seq + 1,
            lease_until = #{leaseUntil},
            lock_version = lock_version + 1,
            update_time = #{now}
        WHERE task_id = #{taskId}
          AND processing_token = #{token}
          AND status = 'processing'
          AND deleted = 0
        """)
    int updateProgress(
        @Param("taskId") String taskId,
        @Param("token") String token,
        @Param("stage") String stage,
        @Param("progress") int progress,
        @Param("message") String message,
        @Param("leaseUntil") LocalDateTime leaseUntil,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE trip_task
        SET stage = 'submitted',
            progress_text = #{message},
            last_seq = last_seq + 1,
            lock_version = lock_version + 1,
            update_time = #{now}
        WHERE task_id = #{taskId}
          AND status = 'queued'
          AND deleted = 0
        """)
    int updateQueuedMessage(
        @Param("taskId") String taskId,
        @Param("message") String message,
        @Param("now") LocalDateTime now
    );
}
