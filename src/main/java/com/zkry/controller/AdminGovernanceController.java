package com.zkry.controller;

import com.zkry.domain.dto.collaboration.UpdateCommentStatusRequest;
import com.zkry.domain.vo.AuditLogView;
import com.zkry.security.VoyagePrincipal;
import com.zkry.service.AuditLogService;
import com.zkry.service.TripCommentService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminGovernanceController {
    private final AuditLogService auditLogService;
    private final TripCommentService commentService;

    public AdminGovernanceController(AuditLogService auditLogService, TripCommentService commentService) {
        this.auditLogService = auditLogService;
        this.commentService = commentService;
    }

    @GetMapping("/audit-logs")
    public List<AuditLogView> auditLogs(@RequestParam(defaultValue = "100") int limit) {
        return auditLogService.list(limit);
    }

    @PutMapping("/moderation/comments/{planId}/{commentId}/resolve")
    public Map<String, Object> resolve(
        @PathVariable String planId,
        @PathVariable Long commentId,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        commentService.updateStatus(planId, commentId, new UpdateCommentStatusRequest("RESOLVED"));
        auditLogService.record(principal, "保留并关闭评论", "行程 " + planId + "，评论 " + commentId, "内容审核", "SUCCESS");
        return Map.of("success", true);
    }

    @DeleteMapping("/moderation/comments/{planId}/{commentId}")
    public Map<String, Object> delete(
        @PathVariable String planId,
        @PathVariable Long commentId,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        commentService.delete(planId, commentId);
        auditLogService.record(principal, "下架评论", "行程 " + planId + "，评论 " + commentId, "内容审核", "SUCCESS");
        return Map.of("success", true);
    }
}
