package com.zkry.controller;

import com.zkry.domain.dto.collaboration.CreateTripCommentRequest;
import com.zkry.domain.dto.collaboration.UpdateCommentStatusRequest;
import com.zkry.domain.vo.TripCommentView;
import com.zkry.security.VoyagePrincipal;
import com.zkry.service.TripAccessService;
import com.zkry.service.TripCommentService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips/{planId}/comments")
public class TripCommentController {

    private final TripCommentService commentService;
    private final TripAccessService accessService;

    public TripCommentController(TripCommentService commentService, TripAccessService accessService) {
        this.commentService = commentService;
        this.accessService = accessService;
    }

    @GetMapping
    public List<TripCommentView> list(
        @PathVariable String planId,
        @RequestParam(required = false) String targetType,
        @RequestParam(required = false) String targetRef,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        accessService.requireView(planId, principal);
        return commentService.list(planId, targetType, targetRef);
    }

    @PostMapping
    public TripCommentView create(
        @PathVariable String planId,
        @RequestBody CreateTripCommentRequest request,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        accessService.requireView(planId, principal);
        return commentService.create(planId, principal.userId(), request);
    }

    @PutMapping("/{commentId}/status")
    public TripCommentView updateStatus(
        @PathVariable String planId,
        @PathVariable Long commentId,
        @RequestBody UpdateCommentStatusRequest request,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        accessService.requireEdit(planId, principal);
        return commentService.updateStatus(planId, commentId, request);
    }

    @PostMapping("/{commentId}/like")
    public TripCommentView like(
        @PathVariable String planId,
        @PathVariable Long commentId,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        accessService.requireView(planId, principal);
        return commentService.like(planId, commentId);
    }

    @DeleteMapping("/{commentId}")
    public Map<String, Object> delete(
        @PathVariable String planId,
        @PathVariable Long commentId,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        accessService.requireEdit(planId, principal);
        commentService.delete(planId, commentId);
        return Map.of("success", true);
    }
}
