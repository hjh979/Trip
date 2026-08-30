package com.zkry.controller;

import com.zkry.domain.vo.SubmitTripPlanResponse;
import com.zkry.domain.dto.TripRequest;
import com.zkry.domain.vo.TripHistoryItem;
import com.zkry.service.TripTaskService;
import com.zkry.service.TripWorkspaceService;
import com.zkry.security.VoyagePrincipal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 旅行规划 HTTP 入口。
 *
 * <p>{@code /plan} 会创建基于自建知识库的异步规划任务，并通过统一的
 * 状态查询协议返回进度。
 */
@RestController
@RequestMapping("/api/trip")
public class TripController {

    private static final Logger log = LoggerFactory.getLogger(TripController.class);

    private final TripTaskService tripTaskService;
    private final TripWorkspaceService workspaceService;

    public TripController(TripTaskService tripTaskService, TripWorkspaceService workspaceService) {
        this.tripTaskService = tripTaskService;
        this.workspaceService = workspaceService;
    }

    /**
     * 自主规划入口：城市、天数等参数由用户填写，继续进入现有多 Agent Graph 流程。
     */
    @PostMapping("/plan")
    public SubmitTripPlanResponse plan(
        @RequestBody TripRequest request,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        log.info("[TripAPI] 收到行程规划请求 city={} cities={} days={} date={}~{} preferences={}",
            request.primaryCity(),
            request.normalizedCities().stream().map(city -> city.city() + ":" + city.safeDays() + "天").toList(),
            request.safeTravelDays(),
            safe(request.start_date()),
            safe(request.end_date()),
            request.safePreferences());
        SubmitTripPlanResponse response = tripTaskService.submit(request, requiredUserId(principal));
        log.info("[TripAPI] 行程规划任务已提交 taskId={}", response.task_id());
        return response;
    }

    @GetMapping("/status/{taskId}")
    public Map<String, Object> status(
        @PathVariable String taskId,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        Map<String, Object> payload = tripTaskService.status(taskId, requiredUserId(principal));
        log.info("[TripAPI] 查询任务状态 taskId={} status={} stage={} progress={}",
            taskId, payload.get("status"), payload.getOrDefault("stage", "-"), payload.getOrDefault("progress", "-"));
        return payload;
    }

    @GetMapping("/history")
    public Map<String, Object> history(
        @RequestParam(defaultValue = "8") int limit,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<TripHistoryItem> items = workspaceService.list(principal, "", "ALL").stream().limit(safeLimit)
            .map(item -> new TripHistoryItem(item.plan_id(), item.plan_id(), item.city(), item.start_date(),
                item.end_date(), item.travel_days(), item.updated_at(), ""))
            .toList();
        log.info("[TripAPI] 查询历史行程 limit={} resultCount={}", limit, items.size());
        return Map.of("items", items);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private Long requiredUserId(VoyagePrincipal principal) {
        if (principal == null) throw new IllegalStateException("请先登录后再创建行程");
        return principal.userId();
    }
}
