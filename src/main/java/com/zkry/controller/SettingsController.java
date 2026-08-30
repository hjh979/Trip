package com.zkry.controller;

import com.zkry.service.TripstarRuntimeSettingsService;
import com.zkry.security.VoyagePrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final TripstarRuntimeSettingsService runtimeSettingsService;
    public SettingsController(TripstarRuntimeSettingsService runtimeSettingsService) {
        this.runtimeSettingsService = runtimeSettingsService;
    }

    @GetMapping
    public Map<String, Object> get() {
        log.info("[Settings] 读取运行时配置 snapshotKeys={}", runtimeSettingsService.snapshot().keySet());
        return response("配置读取成功");
    }

    @PutMapping
    public Map<String, Object> save(
        @RequestBody Map<String, Object> updates,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        Set<String> keys = updates == null ? Set.of() : updates.keySet();
        // 只记录被更新的配置项名称，避免把 Cookie、API Key 等敏感值打印到控制台。
        log.info("[Settings] 保存运行时配置 updateKeys={}", keys);
        runtimeSettingsService.update(updates, principal == null ? null : principal.userId());
        return response("配置已加密持久化，服务重启后仍会自动加载");
    }

    private Map<String, Object> response(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", message);
        body.put("data", runtimeSettingsService.publicSnapshot());
        return body;
    }
}
