package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("system_runtime_setting")
public class SystemRuntimeSetting extends BaseEntity {
    private String settingKey;
    private String encryptedValue;
    private String status;
    private String lastError;
    private LocalDateTime lastValidatedAt;
    private Long updatedBy;
}
