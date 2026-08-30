package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_credential")
public class UserCredential extends BaseEntity {
    private Long userId;
    private String passwordHash;
    private LocalDateTime passwordUpdatedAt;
}
