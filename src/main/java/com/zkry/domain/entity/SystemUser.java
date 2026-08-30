package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SystemUser extends BaseEntity {
    private String username;
    private String displayName;
    private String email;
    private String avatarUrl;
    private String role;
    private String status;
    private String bio;
}
