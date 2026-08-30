package com.zkry.service;

import com.zkry.common.exception.BizException;
import com.zkry.domain.dto.map.AmapRouteBatchRequest;
import com.zkry.domain.dto.map.AmapRouteSegmentRequest;
import com.zkry.domain.dto.map.MapPoint;
import com.zkry.domain.vo.AmapRouteBatchResponse;
import com.zkry.integration.amap.service.AmapMapContextService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RoutePlanningService {

    private static final Logger log = LoggerFactory.getLogger(RoutePlanningService.class);
    private static final int MAX_SEGMENTS_PER_REQUEST = 30;

    private final AmapMapContextService amapMapContextService;

    public RoutePlanningService(AmapMapContextService amapMapContextService) {
        this.amapMapContextService = amapMapContextService;
    }

    public AmapRouteBatchResponse plan(AmapRouteBatchRequest request) {
        if (request == null || request.segments() == null || request.segments().isEmpty()) {
            throw new BizException("路径规划至少需要一个路段。");
        }
        if (request.segments().size() > MAX_SEGMENTS_PER_REQUEST) {
            throw new BizException("单次最多规划 " + MAX_SEGMENTS_PER_REQUEST + " 个路段。");
        }

        List<AmapRouteBatchResponse.RouteSegmentResult> routes = new ArrayList<>();
        int successCount = 0;
        for (int index = 0; index < request.segments().size(); index++) {
            AmapRouteSegmentRequest segment = request.segments().get(index);
            String id = segment == null || segment.id() == null || segment.id().isBlank()
                ? "segment-" + (index + 1)
                : segment.id().trim();
            String mode = normalizeMode(segment == null ? null : segment.mode());
            MapPoint origin = segment == null ? null : segment.origin();
            MapPoint destination = segment == null ? null : segment.destination();

            try {
                AmapMapContextService.RouteResult route = amapMapContextService.route(
                    mode, origin, destination, segment == null ? "" : segment.city()
                );
                routes.add(new AmapRouteBatchResponse.RouteSegmentResult(
                    id, mode, true, "路径规划成功",
                    route.distanceMeters(), route.durationSeconds(), route.path()
                ));
                successCount++;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                routes.add(failure(id, mode, "路径规划被中断"));
            } catch (Exception ex) {
                String reason = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? "高德路径规划失败"
                    : ex.getMessage();
                log.warn("[AMap-Route] 路段失败 id={} mode={} reason={}", id, mode, reason);
                routes.add(failure(id, mode, reason));
            }
        }

        int failureCount = routes.size() - successCount;
        String message = failureCount == 0
            ? "全部路段规划成功"
            : "已完成 " + successCount + " 个路段，" + failureCount + " 个路段暂不可用";
        return new AmapRouteBatchResponse(successCount > 0, message, successCount, failureCount, routes);
    }

    private AmapRouteBatchResponse.RouteSegmentResult failure(String id, String mode, String message) {
        return new AmapRouteBatchResponse.RouteSegmentResult(id, mode, false, message, 0L, 0L, List.of());
    }

    private String normalizeMode(String mode) {
        if ("walking".equalsIgnoreCase(mode)) return "walking";
        if ("transit".equalsIgnoreCase(mode)) return "transit";
        return "driving";
    }
}
