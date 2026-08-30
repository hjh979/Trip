package com.zkry.controller;

import com.zkry.integration.amap.service.AmapJsApiProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/_AMapService")
public class AmapJsApiProxyController {

    private final AmapJsApiProxyService proxyService;

    public AmapJsApiProxyController(AmapJsApiProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @GetMapping("/**")
    public ResponseEntity<byte[]> forward(HttpServletRequest request) {
        AmapJsApiProxyService.ProxyResponse response = proxyService.forward(
            request.getRequestURI(),
            request.getQueryString()
        );
        return ResponseEntity.status(response.statusCode())
            .header(HttpHeaders.CONTENT_TYPE, response.contentType())
            .header(HttpHeaders.CACHE_CONTROL, response.cacheControl())
            .body(response.body());
    }
}

