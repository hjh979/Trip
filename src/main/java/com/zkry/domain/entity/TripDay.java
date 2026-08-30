package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trip_day")
public class TripDay extends BaseEntity {
    private Long planId;
    private Integer dayNumber;
    private LocalDate tripDate;
    private String title;
}
