package com.zkry.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.domain.entity.TripMember;
import com.zkry.domain.entity.TripPlan;
import com.zkry.mapper.TripMemberMapper;
import com.zkry.mapper.TripPlanMapper;
import com.zkry.mapper.SystemUserMapper;
import com.zkry.domain.entity.SystemUser;
import com.zkry.security.VoyagePrincipal;
import org.springframework.stereotype.Service;

@Service
public class TripAccessService {

    private final TripPlanMapper planMapper;
    private final TripMemberMapper memberMapper;
    private final SystemUserMapper userMapper;

    public TripAccessService(
        TripPlanMapper planMapper,
        TripMemberMapper memberMapper,
        SystemUserMapper userMapper
    ) {
        this.planMapper = planMapper;
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
    }

    public TripPlan requireView(String publicId, VoyagePrincipal principal) {
        TripPlan plan = requiredPlan(publicId);
        if (isAdministrator(principal) || plan.getOwnerId().equals(requiredPrincipal(principal).userId())) return plan;
        if (membership(publicId, principal.userId()) != null) return plan;
        throw new BizException("你没有查看该行程的权限。");
    }

    public TripPlan requireEdit(String publicId, VoyagePrincipal principal) {
        TripPlan plan = requiredPlan(publicId);
        if (isAdministrator(principal) || plan.getOwnerId().equals(requiredPrincipal(principal).userId())) return plan;
        TripMember member = membership(publicId, principal.userId());
        if (member != null && ("EDITOR".equals(member.getMemberRole()) || "OWNER".equals(member.getMemberRole()))) {
            return plan;
        }
        throw new BizException("你没有编辑该行程的权限。");
    }

    public TripPlan requireEdit(String publicId, Long userId) {
        SystemUser user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BizException("用户不可用或登录已失效。");
        }
        return requireEdit(publicId, new VoyagePrincipal(user.getId(), user.getUsername(), user.getRole()));
    }

    public TripPlan requireManageMembers(String publicId, VoyagePrincipal principal) {
        TripPlan plan = requiredPlan(publicId);
        if (isAdministrator(principal) || plan.getOwnerId().equals(requiredPrincipal(principal).userId())) return plan;
        throw new BizException("只有行程所有者或管理员可以管理协作者。");
    }

    private TripPlan requiredPlan(String publicId) {
        if (publicId == null || publicId.isBlank()) throw new BizException("行程编号不能为空。");
        TripPlan plan = planMapper.selectOne(
            Wrappers.<TripPlan>lambdaQuery().eq(TripPlan::getPublicId, publicId.trim()).last("LIMIT 1")
        );
        if (plan == null) throw new BizException("行程不存在：" + publicId);
        return plan;
    }

    private TripMember membership(String publicId, Long userId) {
        return memberMapper.selectOne(
            Wrappers.<TripMember>lambdaQuery()
                .eq(TripMember::getPlanId, publicId.trim())
                .eq(TripMember::getUserId, userId)
                .last("LIMIT 1")
        );
    }

    private VoyagePrincipal requiredPrincipal(VoyagePrincipal principal) {
        if (principal == null) throw new BizException("请先登录。");
        return principal;
    }

    private boolean isAdministrator(VoyagePrincipal principal) {
        VoyagePrincipal authenticated = requiredPrincipal(principal);
        return "ADMIN".equals(authenticated.role()) || "SUPER_ADMIN".equals(authenticated.role());
    }
}
