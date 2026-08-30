package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trip_member")
public class TripMember extends BaseEntity {
    private String planId;
    private Long userId;
    private String memberRole;
}
