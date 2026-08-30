package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trip_comment")
public class TripComment extends BaseEntity {
    private String planId;
    private Long userId;
    private Long parentId;
    private String targetType;
    private String targetRef;
    private String content;
    private String status;
    private Integer likeCount;
}
