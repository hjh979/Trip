package com.zkry.controller;

import com.zkry.domain.vo.TripWorkspaceView;
import com.zkry.domain.vo.TripSummaryView;
import com.zkry.domain.vo.TripPlaceGuideView;
import com.zkry.domain.dto.TripPlan;
import com.zkry.security.VoyagePrincipal;
import com.zkry.service.TripDeletionService;
import com.zkry.service.TripPlaceGuideService;
import com.zkry.service.TripWorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/trips")
public class TripWorkspaceController {

    private final TripWorkspaceService workspaceService;
    private final TripPlaceGuideService placeGuideService;
    private final TripDeletionService deletionService;

    public TripWorkspaceController(
        TripWorkspaceService workspaceService,
        TripPlaceGuideService placeGuideService,
        TripDeletionService deletionService
    ) {
        this.workspaceService = workspaceService;
        this.placeGuideService = placeGuideService;
        this.deletionService = deletionService;
    }

    @GetMapping("/{planId}/workspace")
    public TripWorkspaceView workspace(
        @PathVariable String planId,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        return workspaceService.get(planId, principal);
    }

    /**
     * 读取并按需刷新工作区右侧的景点介绍和深度攻略。
     *
     * <p>refresh=false 时优先复用已经写入 RAG 的知识库资料；没有相关资料时会自动
     * 实时检索。refresh=true 用于用户主动点击“更新旅行攻略”。
     */
    @PostMapping("/{planId}/items/{itemId}/guide")
    public TripPlaceGuideView placeGuide(
        @PathVariable String planId,
        @PathVariable Long itemId,
        @RequestParam(defaultValue = "false") boolean refresh,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        return placeGuideService.guide(planId, itemId, refresh, principal);
    }

    @GetMapping
    public List<TripSummaryView> list(
        @AuthenticationPrincipal VoyagePrincipal principal,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "ALL") String status
    ) {
        return workspaceService.list(principal, keyword, status);
    }

    @PutMapping("/{planId}")
    public TripWorkspaceView update(
        @PathVariable String planId,
        @RequestBody TripPlan data,
        @RequestParam(required = false) Integer baseVersion,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        return workspaceService.update(planId, data, baseVersion, principal);
    }

    @DeleteMapping("/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
        @PathVariable String planId,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        deletionService.delete(planId, principal);
    }
}
