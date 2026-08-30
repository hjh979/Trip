package com.zkry.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping({"/health", "/api/health"})
    public Map<String, Object> health() {
        return Map.of(
            "status", "healthy",
            "service", "voyagemind-server",
            "mode", "real-config-required"
        );
    }
}
