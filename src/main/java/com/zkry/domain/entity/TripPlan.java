package com.zkry.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trip_plan")
public class TripPlan extends BaseEntity {
    private String publicId;
    private Long ownerId;
    private String title;
    private String city;
    private String cityCode;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer travelDays;
    private Long budgetCents;
    private String status;
    private String visibility;
    @Version
    private Integer version;
    /** Full structured itinerary used by AI editing and the reopened workspace. */
    private String detailJson;
    /** Canonical current aggregate snapshot. Legacy detail_json remains a read fallback during migration. */
    private String currentSnapshotJson;
}
