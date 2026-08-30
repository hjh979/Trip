package com.zkry.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.domain.dto.collaboration.InviteTripMemberRequest;
import com.zkry.domain.entity.TripMember;
import com.zkry.domain.vo.TripMemberView;
import com.zkry.mapper.TripMemberMapper;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripMemberService {

    private static final List<String> MEMBER_ROLES = List.of("OWNER", "EDITOR", "VIEWER");
    private final TripMemberMapper mapper;
    private final SystemUserService userService;

    public TripMemberService(TripMemberMapper mapper, SystemUserService userService) {
        this.mapper = mapper;
        this.userService = userService;
    }

    public List<TripMemberView> list(String planId) {
        String safePlanId = requiredPlanId(planId);
        return mapper.selectList(Wrappers.<TripMember>lambdaQuery()
                .eq(TripMember::getPlanId, safePlanId)
                .orderByAsc(TripMember::getCreateTime))
            .stream().map(this::toView).toList();
    }

    @Transactional
    public TripMemberView invite(String planId, InviteTripMemberRequest request) {
        if (request == null || request.user_id() == null) throw new BizException("请选择要邀请的用户。");
        String safePlanId = requiredPlanId(planId);
        userService.getEntity(request.user_id());
        String role = normalizeRole(request.role());
        TripMember member = mapper.selectOne(Wrappers.<TripMember>lambdaQuery()
            .eq(TripMember::getPlanId, safePlanId)
            .eq(TripMember::getUserId, request.user_id())
            .last("LIMIT 1"));
        if (member == null) {
            member = new TripMember();
            member.setPlanId(safePlanId);
            member.setUserId(request.user_id());
            member.setMemberRole(role);
            mapper.insert(member);
        } else {
            member.setMemberRole(role);
            mapper.updateById(member);
        }
        return toView(member);
    }

    @Transactional
    public void remove(String planId, Long userId) {
        TripMember member = mapper.selectOne(Wrappers.<TripMember>lambdaQuery()
            .eq(TripMember::getPlanId, requiredPlanId(planId))
            .eq(TripMember::getUserId, userId)
            .last("LIMIT 1"));
        if (member == null) throw new BizException("该用户不是当前行程成员。");
        if ("OWNER".equals(member.getMemberRole())) throw new BizException("不能直接移除行程所有者。");
        mapper.deleteById(member.getId());
    }

    private TripMemberView toView(TripMember member) {
        return new TripMemberView(
            member.getId(), member.getPlanId(), member.getMemberRole(),
            userService.get(member.getUserId()),
            member.getCreateTime() == null ? "" : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(member.getCreateTime())
        );
    }

    private String normalizeRole(String value) {
        String role = value == null || value.isBlank() ? "VIEWER" : value.trim().toUpperCase(Locale.ROOT);
        if (!MEMBER_ROLES.contains(role)) throw new BizException("行程成员角色无效：" + value);
        return role;
    }

    private String requiredPlanId(String value) {
        if (value == null || value.isBlank()) throw new BizException("行程 ID 不能为空。");
        return value.trim();
    }
}
