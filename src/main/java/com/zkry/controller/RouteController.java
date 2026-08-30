package com.zkry.controller;

import com.zkry.domain.dto.map.AmapRouteBatchRequest;
import com.zkry.domain.vo.AmapRouteBatchResponse;
import com.zkry.service.RoutePlanningService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final RoutePlanningService routePlanningService;

    public RouteController(RoutePlanningService routePlanningService) {
        this.routePlanningService = routePlanningService;
    }

    @PostMapping("/amap")
    public AmapRouteBatchResponse amap(@RequestBody AmapRouteBatchRequest request) {
        return routePlanningService.plan(request);
    }
}
