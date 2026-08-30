package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("auth_session")
public class AuthSession extends BaseEntity {
    private String tokenHash;
    private Long userId;
    private LocalDateTime expiresAt;
}
