package com.zkry.controller;

import com.zkry.domain.dto.map.AmapStaticMapRequest;
import com.zkry.integration.amap.service.AmapStaticMapService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/map/amap/static")
public class AmapStaticMapController {

    private final AmapStaticMapService staticMapService;

    public AmapStaticMapController(AmapStaticMapService staticMapService) {
        this.staticMapService = staticMapService;
    }

    @PostMapping(produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> render(@RequestBody AmapStaticMapRequest request) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.IMAGE_PNG)
            .body(staticMapService.render(request));
    }
}
