package com.zkry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.entity.TripDay;
import com.zkry.domain.entity.TripItem;
import com.zkry.domain.entity.TripMember;
import com.zkry.domain.entity.TripPlan;
import com.zkry.domain.vo.TripDayView;
import com.zkry.domain.vo.TripItemView;
import com.zkry.domain.vo.TripSummaryView;
import com.zkry.domain.vo.TripWorkspaceView;
import com.zkry.mapper.TripDayMapper;
import com.zkry.mapper.TripItemMapper;
import com.zkry.mapper.TripMemberMapper;
import com.zkry.mapper.TripPlanMapper;
import com.zkry.security.VoyagePrincipal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TripWorkspaceService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final TripPlanMapper planMapper;
    private final TripDayMapper dayMapper;
    private final TripItemMapper itemMapper;
    private final TripMemberMapper memberMapper;
    private final TripMemberService memberService;
    private final TripAccessService accessService;
    private final TripPlanPersistenceService persistenceService;

    public TripWorkspaceService(
        TripPlanMapper planMapper,
        TripDayMapper dayMapper,
        TripItemMapper itemMapper,
        TripMemberMapper memberMapper,
        TripMemberService memberService,
        TripAccessService accessService,
        TripPlanPersistenceService persistenceService
    ) {
        this.planMapper = planMapper;
        this.dayMapper = dayMapper;
        this.itemMapper = itemMapper;
        this.memberMapper = memberMapper;
        this.memberService = memberService;
        this.accessService = accessService;
        this.persistenceService = persistenceService;
    }

    public List<TripSummaryView> list(VoyagePrincipal principal, String keyword, String status) {
        if (principal == null) throw new BizException("请先登录。");
        LambdaQueryWrapper<TripPlan> query = Wrappers.lambdaQuery();
        boolean administrator = "ADMIN".equals(principal.role()) || "SUPER_ADMIN".equals(principal.role());
        if (!administrator) {
            List<String> memberPlanIds = memberMapper.selectList(
                Wrappers.<TripMember>lambdaQuery().eq(TripMember::getUserId, principal.userId())
            ).stream().map(TripMember::getPlanId).distinct().toList();
            if (memberPlanIds.isEmpty()) query.eq(TripPlan::getOwnerId, principal.userId());
            else query.and(group -> group.eq(TripPlan::getOwnerId, principal.userId())
                .or().in(TripPlan::getPublicId, memberPlanIds));
        }
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            query.and(group -> group.like(TripPlan::getTitle, value).or().like(TripPlan::getCity, value));
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            query.eq(TripPlan::getStatus, status.trim().toUpperCase());
        }
        query.orderByDesc(TripPlan::getUpdateTime);
        return planMapper.selectList(query).stream().map(plan -> new TripSummaryView(
            plan.getPublicId(), plan.getTitle(), plan.getCity(), plan.getStartDate().toString(),
            plan.getEndDate().toString(), plan.getTravelDays(), plan.getStatus(), plan.getVisibility(),
            itemCount(plan.getId()),
            memberMapper.selectCount(Wrappers.<TripMember>lambdaQuery().eq(TripMember::getPlanId, plan.getPublicId())),
            plan.getUpdateTime() == null ? "" : UPDATED.format(plan.getUpdateTime()),
            plan.getOwnerId().equals(principal.userId())
        )).toList();
    }

    public TripWorkspaceView get(String publicId, VoyagePrincipal principal) {
        TripPlan plan = accessService.requireView(publicId, principal);
        List<TripDayView> days = dayMapper.selectList(
                Wrappers.<TripDay>lambdaQuery().eq(TripDay::getPlanId, plan.getId()).orderByAsc(TripDay::getDayNumber)
            ).stream()
            .map(day -> new TripDayView(day.getId(), day.getDayNumber(), day.getTripDate().toString(),
                safe(day.getTitle()), items(day.getId())))
            .toList();
        return new TripWorkspaceView(
            plan.getPublicId(), plan.getVersion(), plan.getTitle(), plan.getCity(), plan.getCityCode(),
            plan.getStartDate().toString(), plan.getEndDate().toString(), plan.getTravelDays(),
            plan.getBudgetCents(), plan.getStatus(), plan.getVisibility(), days, memberService.list(publicId),
            fullPlan(plan)
        );
    }

    public com.zkry.domain.dto.TripPlan structuredPlanForEdit(String publicId, Long userId) {
        TripPlan plan = accessService.requireEdit(publicId, userId);
        return fullPlan(plan);
    }

    public TripWorkspaceView update(
        String publicId,
        com.zkry.domain.dto.TripPlan data,
        VoyagePrincipal principal
    ) {
        return update(publicId, data, null, principal);
    }

    public TripWorkspaceView update(
        String publicId,
        com.zkry.domain.dto.TripPlan data,
        Integer baseVersion,
        VoyagePrincipal principal
    ) {
        TripPlan plan = accessService.requireEdit(publicId, principal);
        persistenceService.saveWorkspace(publicId, plan.getOwnerId(), data, baseVersion);
        return get(publicId, principal);
    }

    private long itemCount(Long planId) {
        return dayMapper.selectList(Wrappers.<TripDay>lambdaQuery().eq(TripDay::getPlanId, planId)).stream()
            .mapToLong(day -> itemMapper.selectCount(Wrappers.<TripItem>lambdaQuery().eq(TripItem::getDayId, day.getId())))
            .sum();
    }

    private List<TripItemView> items(Long dayId) {
        return itemMapper.selectList(
                Wrappers.<TripItem>lambdaQuery().eq(TripItem::getDayId, dayId).orderByAsc(TripItem::getSortOrder)
            ).stream()
            .map(item -> new TripItemView(
                item.getId(), item.getSortOrder(), item.getPoiName(), safe(item.getAddress()),
                item.getLongitude().doubleValue(), item.getLatitude().doubleValue(),
                item.getStartTime().format(TIME), item.getStayMinutes(), item.getCategory(),
                safe(item.getNote()), safe(item.getPhotoUrl()), item.getCostCents()
            )).toList();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private com.zkry.domain.dto.TripPlan fullPlan(TripPlan plan) {
        if (plan.getCurrentSnapshotJson() != null && !plan.getCurrentSnapshotJson().isBlank()) {
            try {
                com.zkry.domain.dto.TripPlan parsed = JsonUtils.parseObject(
                    plan.getCurrentSnapshotJson(), com.zkry.domain.dto.TripPlan.class
                );
                if (parsed != null) return parsed;
            } catch (RuntimeException ignored) {
                // Fall through to the compatibility columns below.
            }
        }
        if (plan.getDetailJson() != null && !plan.getDetailJson().isBlank()) {
            try {
                com.zkry.domain.dto.TripPlan parsed = JsonUtils.parseObject(
                    plan.getDetailJson(), com.zkry.domain.dto.TripPlan.class
                );
                if (parsed != null) return parsed;
            } catch (RuntimeException ignored) {
                // Old or partially migrated rows fall back to the completed generation record.
            }
        }
        return persistenceService.findCompletedResult(plan.getPublicId())
            .map(response -> response.data())
            .orElse(null);
    }
}
