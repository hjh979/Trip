package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trip_item")
public class TripItem extends BaseEntity {
    private Long dayId;
    private Integer sortOrder;
    private String poiName;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private LocalTime startTime;
    private Integer stayMinutes;
    private String category;
    private String note;
    private String photoUrl;
    private Long costCents;
}
