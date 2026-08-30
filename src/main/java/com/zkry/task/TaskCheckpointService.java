package com.zkry.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.entity.TripTaskCheckpoint;
import com.zkry.mapper.TripTaskCheckpointMapper;
import com.zkry.memory.domain.CheckpointRetentionClass;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskCheckpointService {

    private final TripTaskCheckpointMapper mapper;

    public TaskCheckpointService(TripTaskCheckpointMapper mapper) {
        this.mapper = mapper;
    }

    public <T> Optional<T> load(String taskId, String key, Class<T> type) {
        TripTaskCheckpoint checkpoint = mapper.selectOne(
            Wrappers.<TripTaskCheckpoint>lambdaQuery()
                .eq(TripTaskCheckpoint::getTaskId, taskId)
                .eq(TripTaskCheckpoint::getCheckpointKey, key)
                .and(w -> w.isNull(TripTaskCheckpoint::getExpiresAt)
                    .or().gt(TripTaskCheckpoint::getExpiresAt, LocalDateTime.now()))
                .last("LIMIT 1")
        );
        return checkpoint == null ? Optional.empty() : Optional.ofNullable(
            JsonUtils.parseObject(checkpoint.getPayloadJson(), type)
        );
    }

    @Transactional
    public void save(String taskId, String key, Object payload) {
        save(taskId, key, payload, defaultRetention(key));
    }

    @Transactional
    public void save(String taskId, String key, Object payload, CheckpointRetentionClass retentionClass) {
        String json = JsonUtils.toJsonString(payload);
        TripTaskCheckpoint existing = mapper.selectOne(
            Wrappers.<TripTaskCheckpoint>lambdaQuery()
                .eq(TripTaskCheckpoint::getTaskId, taskId)
                .eq(TripTaskCheckpoint::getCheckpointKey, key)
                .last("LIMIT 1")
        );
        TripTaskCheckpoint checkpoint = existing == null ? new TripTaskCheckpoint() : existing;
        checkpoint.setTaskId(taskId);
        checkpoint.setCheckpointKey(key);
        checkpoint.setPayloadJson(json);
        checkpoint.setPayloadHash(sha256(json));
        checkpoint.setSchemaVersion(1);
        checkpoint.setRetentionClass(retentionClass.name());
        checkpoint.setExpiresAt(expiry(retentionClass));
        if (existing == null) mapper.insert(checkpoint); else mapper.updateById(checkpoint);
    }

    private CheckpointRetentionClass defaultRetention(String key) {
        return "working.memory.v1".equals(key)
            ? CheckpointRetentionClass.WORKING_MEMORY : CheckpointRetentionClass.RECOVERY_CHECKPOINT;
    }

    private LocalDateTime expiry(CheckpointRetentionClass retentionClass) {
        return switch (retentionClass) {
            case WORKING_MEMORY -> LocalDateTime.now().plusHours(24);
            case RECOVERY_CHECKPOINT -> LocalDateTime.now().plusDays(14);
            case AUDIT_REFERENCE -> null;
        };
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot hash task checkpoint", ex);
        }
    }
}
