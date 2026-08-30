package com.zkry.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.Attraction;
import com.zkry.domain.dto.DayPlan;
import com.zkry.domain.dto.TripPlan;
import com.zkry.domain.dto.TripPlanSnapshot;
import com.zkry.domain.dto.TripRequest;
import com.zkry.domain.entity.TripDay;
import com.zkry.domain.entity.TripItem;
import com.zkry.domain.entity.TripMember;
import com.zkry.domain.entity.TripPlanRecord;
import com.zkry.domain.vo.TripHistoryItem;
import com.zkry.domain.vo.TripPlanResponse;
import com.zkry.mapper.TripDayMapper;
import com.zkry.mapper.TripItemMapper;
import com.zkry.mapper.TripMemberMapper;
import com.zkry.mapper.TripPlanMapper;
import com.zkry.mapper.TripPlanRecordMapper;
import com.zkry.mapper.TripPlanVersionMapper;
import com.zkry.domain.entity.TripPlanVersion;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.zkry.service.planning.TripPlanSnapshotFactory;

/** 把生成记录与用户可再次打开的工作台数据保存在同一个事务中。 */
@Service
public class TripPlanPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(TripPlanPersistenceService.class);
    private static final DateTimeFormatter HISTORY_TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final TripPlanRecordMapper recordMapper;
    private final TripPlanMapper planMapper;
    private final TripDayMapper dayMapper;
    private final TripItemMapper itemMapper;
    private final TripMemberMapper memberMapper;
    private final TripPlanSnapshotFactory snapshotFactory;
    private final TripPlanVersionMapper versionMapper;

    public TripPlanPersistenceService(
        TripPlanRecordMapper recordMapper,
        TripPlanMapper planMapper,
        TripDayMapper dayMapper,
        TripItemMapper itemMapper,
        TripMemberMapper memberMapper
    ) {
        this(recordMapper, planMapper, dayMapper, itemMapper, memberMapper, new TripPlanSnapshotFactory(), null);
    }

    @Autowired
    public TripPlanPersistenceService(
        TripPlanRecordMapper recordMapper,
        TripPlanMapper planMapper,
        TripDayMapper dayMapper,
        TripItemMapper itemMapper,
        TripMemberMapper memberMapper,
        TripPlanSnapshotFactory snapshotFactory,
        TripPlanVersionMapper versionMapper
    ) {
        this.recordMapper = recordMapper;
        this.planMapper = planMapper;
        this.dayMapper = dayMapper;
        this.itemMapper = itemMapper;
        this.memberMapper = memberMapper;
        this.snapshotFactory = snapshotFactory;
        this.versionMapper = versionMapper;
    }

    @Transactional
    public void saveCompleted(String taskId, TripRequest request, TripPlanResponse response, Long ownerId) {
        TripPlan plan = response == null ? null : response.data();
        if (plan == null) throw new IllegalArgumentException("不能保存空行程");
        if (ownerId == null) throw new IllegalArgumentException("保存行程需要登录用户");

        String planId = firstNonBlank(response.plan_id(), taskId);
        saveWorkspace(planId, ownerId, plan);

        TripPlanRecord record = findRecord(taskId).orElseGet(TripPlanRecord::new);
        record.setPlanId(planId);
        record.setTaskId(taskId);
        record.setStatus(TripTaskStatus.COMPLETED);
        record.setCity(firstNonBlank(plan.city(), request == null ? null : request.primaryCity()));
        record.setStartDate(firstNonBlank(plan.start_date(), request == null ? null : request.start_date()));
        record.setEndDate(firstNonBlank(plan.end_date(), request == null ? null : request.end_date()));
        record.setTravelDays(plan.days() == null ? 0 : plan.days().size());
        record.setOverallSuggestions(safe(plan.overall_suggestions()));
        record.setResultJson(JsonUtils.toJsonString(response));
        record.setSnapshotJson(JsonUtils.toJsonString(snapshotFactory.create(
            plan, 1,
            response.research_evidence() == null ? java.util.List.of() : response.research_evidence().rag_trace_ids(),
            request == null ? java.util.List.of() : request.safePreferences())));
        record.setErrorMessage("");
        if (record.getId() == null) recordMapper.insert(record); else recordMapper.updateById(record);
        appendVersion(planId, ownerId, taskId, "PLANNER", plan, 1, record.getSnapshotJson());
        log.info("[TripPersistence] 行程记录与工作台数据已保存 taskId={} planId={} ownerId={} days={}",
            taskId, planId, ownerId, record.getTravelDays());
    }

    /** AI 调整后用完整结构替换行程明细，刷新页面仍能看到最新版本。 */
    @Transactional
    public void saveWorkspace(String planId, Long ownerId, TripPlan data) {
        saveWorkspace(planId, ownerId, data, null);
    }

    @Transactional
    public void saveWorkspace(String planId, Long ownerId, TripPlan data, Integer expectedVersion) {
        if (data == null || data.days() == null || data.days().isEmpty()) {
            throw new IllegalArgumentException("行程至少需要包含一天安排");
        }
        com.zkry.domain.entity.TripPlan entity = planMapper.selectOne(
            Wrappers.<com.zkry.domain.entity.TripPlan>lambdaQuery()
                .eq(com.zkry.domain.entity.TripPlan::getPublicId, planId).last("LIMIT 1")
        );
        boolean created = entity == null;
        if (created) entity = new com.zkry.domain.entity.TripPlan();
        if (!created && expectedVersion != null && !expectedVersion.equals(entity.getVersion())) {
            throw new com.zkry.common.exception.BizException(
                "行程已被其他操作更新，请刷新后重试。当前版本=" + entity.getVersion()
            );
        }

        LocalDate startDate = parseDate(data.start_date(), LocalDate.now());
        LocalDate endDate = parseDate(data.end_date(), startDate.plusDays(data.days().size() - 1L));
        entity.setPublicId(planId);
        entity.setOwnerId(ownerId);
        entity.setCity(firstNonBlank(data.city(), "待定城市"));
        entity.setTitle(entity.getCity() + " · AI 智能行程");
        if (created) entity.setCityCode("");
        entity.setStartDate(startDate);
        entity.setEndDate(endDate);
        entity.setTravelDays(data.days().size());
        entity.setBudgetCents(data.budget() == null || data.budget().total() == null
            ? 0L : Math.max(0L, data.budget().total().longValue() * 100L));
        entity.setStatus("PLANNING");
        entity.setVisibility("PRIVATE");
        entity.setDetailJson(JsonUtils.toJsonString(data));
        entity.setCurrentSnapshotJson(JsonUtils.toJsonString(data));
        if (created) {
            entity.setVersion(1);
            planMapper.insert(entity);
        } else {
            // Every successful mutable command creates a new aggregate version. The old code
            // checked the optimistic lock but kept the same version, making manual edits
            // indistinguishable from stale writes and allowing AI edits to overwrite them.
            entity.setVersion(Math.max(1, entity.getVersion() == null ? 1 : entity.getVersion() + 1));
            if (planMapper.updateById(entity) != 1) {
            throw new com.zkry.common.exception.BizException("行程版本冲突，请刷新后重试。");
            }
        }

        itemMapper.deletePhysicallyByPlanId(entity.getId());
        dayMapper.deletePhysicallyByPlanId(entity.getId());
        for (int dayOffset = 0; dayOffset < data.days().size(); dayOffset++) {
            DayPlan source = data.days().get(dayOffset);
            TripDay day = new TripDay();
            day.setPlanId(entity.getId());
            day.setDayNumber(source.day_index() == null ? dayOffset + 1 : source.day_index());
            day.setTripDate(parseDate(source.date(), startDate.plusDays(dayOffset)));
            day.setTitle(dayTitle(source, day.getDayNumber()));
            dayMapper.insert(day);
            saveItems(day.getId(), source.attractions());
        }

        if (memberMapper.selectCount(Wrappers.<TripMember>lambdaQuery()
            .eq(TripMember::getPlanId, planId).eq(TripMember::getUserId, ownerId)) == 0) {
            TripMember owner = new TripMember();
            owner.setPlanId(planId);
            owner.setUserId(ownerId);
            owner.setMemberRole("OWNER");
            memberMapper.insert(owner);
        }

        // Keep the generated-record payload in sync after conversational AI edits.
        Integer currentVersion = entity.getVersion();
        findRecord(planId).ifPresent(record -> {
            TripPlanResponse previous = JsonUtils.parseObject(record.getResultJson(), TripPlanResponse.class);
            record.setTravelDays(data.days().size());
            record.setCity(firstNonBlank(data.city(), record.getCity()));
            record.setStartDate(firstNonBlank(data.start_date(), record.getStartDate()));
            record.setEndDate(firstNonBlank(data.end_date(), record.getEndDate()));
            record.setOverallSuggestions(safe(data.overall_suggestions()));
            record.setResultJson(JsonUtils.toJsonString(new TripPlanResponse(
                true,
                previous == null ? "行程已保存" : previous.message(),
                planId,
                data,
                previous == null ? null : previous.graph_data(),
                previous == null ? null : previous.research_evidence()
            )));
            TripPlanSnapshot previousSnapshot = parseSnapshot(record.getSnapshotJson());
            record.setSnapshotJson(JsonUtils.toJsonString(snapshotFactory.create(
                data,
                currentVersion,
                previousSnapshot == null ? java.util.List.of() : previousSnapshot.rag_trace_ids(),
                previousSnapshot == null || previousSnapshot.constraints() == null
                    ? java.util.List.of() : previousSnapshot.constraints().preferences())));
            recordMapper.updateById(record);
        });
        if (!created) appendVersion(planId, ownerId, null, "EDIT", data, currentVersion, entity.getCurrentSnapshotJson());
    }

    public Optional<TripPlanResponse> findCompletedResult(String planId) {
        return findRecord(planId)
            .filter(record -> TripTaskStatus.COMPLETED.equals(record.getStatus()))
            .map(TripPlanRecord::getResultJson)
            .map(json -> JsonUtils.parseObject(json, TripPlanResponse.class));
    }

    public List<TripHistoryItem> history(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return recordMapper.selectList(
                Wrappers.<TripPlanRecord>lambdaQuery()
                    .eq(TripPlanRecord::getStatus, TripTaskStatus.COMPLETED)
                    .orderByDesc(TripPlanRecord::getUpdateTime)
                    .last("LIMIT " + safeLimit)
            ).stream().map(this::toHistoryItem).toList();
    }

    private void saveItems(Long dayId, List<Attraction> attractions) {
        List<Attraction> values = attractions == null ? List.of() : attractions;
        LocalTime cursor = LocalTime.of(9, 30);
        int order = 1;
        for (Attraction attraction : values) {
            if (!hasLocation(attraction)) continue;
            int stayMinutes = attraction.visit_duration() == null ? 90 : Math.max(15, attraction.visit_duration());
            TripItem item = new TripItem();
            item.setDayId(dayId);
            item.setSortOrder(order++);
            item.setPoiName(attraction.name().trim());
            item.setAddress(safe(attraction.address()));
            item.setLongitude(BigDecimal.valueOf(attraction.location().longitude()));
            item.setLatitude(BigDecimal.valueOf(attraction.location().latitude()));
            item.setStartTime(cursor);
            item.setStayMinutes(stayMinutes);
            item.setCategory(firstNonBlank(attraction.category(), "景点"));
            item.setNote(safe(attraction.description()));
            item.setPhotoUrl(safe(attraction.image_url()));
            item.setCostCents(attraction.ticket_price() == null ? 0L : Math.max(0L, attraction.ticket_price().longValue() * 100L));
            itemMapper.insert(item);
            cursor = cursor.plusMinutes(stayMinutes + 30L);
        }
    }

    private boolean hasLocation(Attraction attraction) {
        return attraction != null && attraction.name() != null && !attraction.name().isBlank()
            && attraction.location() != null && attraction.location().longitude() != null
            && attraction.location().latitude() != null;
    }

    private String dayTitle(DayPlan day, int dayNumber) {
        if (day == null || day.attractions() == null || day.attractions().isEmpty()) return "第 " + dayNumber + " 天";
        String title = day.attractions().stream().filter(item -> item != null && item.name() != null && !item.name().isBlank())
            .limit(3).map(Attraction::name).reduce((left, right) -> left + " · " + right).orElse("");
        return title.isBlank() ? "第 " + dayNumber + " 天" : title;
    }

    private Optional<TripPlanRecord> findRecord(String planOrTaskId) {
        if (planOrTaskId == null || planOrTaskId.isBlank()) return Optional.empty();
        return Optional.ofNullable(recordMapper.selectOne(
            Wrappers.<TripPlanRecord>lambdaQuery()
                .and(query -> query.eq(TripPlanRecord::getPlanId, planOrTaskId)
                    .or().eq(TripPlanRecord::getTaskId, planOrTaskId))
                .last("LIMIT 1")
        ));
    }

    private TripHistoryItem toHistoryItem(TripPlanRecord record) {
        return new TripHistoryItem(record.getPlanId(), record.getTaskId(), safe(record.getCity()),
            safe(record.getStartDate()), safe(record.getEndDate()),
            record.getTravelDays() == null ? 0 : record.getTravelDays(),
            formatTime(record.getUpdateTime()), safe(record.getOverallSuggestions()));
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        try {
            return value == null || value.isBlank() ? fallback : LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? "" : HISTORY_TIME_FORMAT.format(value);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : safe(second);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private TripPlanSnapshot parseSnapshot(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JsonUtils.parseObject(json, TripPlanSnapshot.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void appendVersion(String planId, Long userId, String taskId, String source,
                               TripPlan data, Integer version, String snapshotJson) {
        if (versionMapper == null || planId == null || version == null) return;
        TripPlanVersion row = new TripPlanVersion();
        row.setPlanId(planId);
        row.setVersion(version);
        row.setCreatedBy(userId);
        row.setTaskId(taskId);
        row.setSourceType(source);
        row.setResultJson(JsonUtils.toJsonString(data));
        row.setSnapshotJson(snapshotJson == null || snapshotJson.isBlank() ? JsonUtils.toJsonString(data) : snapshotJson);
        try { versionMapper.insert(row); }
        catch (RuntimeException duplicate) { log.debug("version already exists planId={} version={}", planId, version); }
    }
}
