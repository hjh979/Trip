package com.zkry.controller;

import com.zkry.domain.dto.collaboration.InviteTripMemberRequest;
import com.zkry.domain.vo.TripMemberView;
import com.zkry.security.VoyagePrincipal;
import com.zkry.service.TripAccessService;
import com.zkry.service.TripMemberService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips/{planId}/members")
public class TripMemberController {

    private final TripMemberService memberService;
    private final TripAccessService accessService;

    public TripMemberController(TripMemberService memberService, TripAccessService accessService) {
        this.memberService = memberService;
        this.accessService = accessService;
    }

    @GetMapping
    public List<TripMemberView> list(
        @PathVariable String planId,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        accessService.requireView(planId, principal);
        return memberService.list(planId);
    }

    @PostMapping
    public TripMemberView invite(
        @PathVariable String planId,
        @RequestBody InviteTripMemberRequest request,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        accessService.requireManageMembers(planId, principal);
        return memberService.invite(planId, request);
    }

    @DeleteMapping("/{userId}")
    public Map<String, Object> remove(
        @PathVariable String planId,
        @PathVariable Long userId,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        accessService.requireManageMembers(planId, principal);
        memberService.remove(planId, userId);
        return Map.of("success", true);
    }
}
