package com.zkry.controller;

import com.zkry.domain.dto.ChatMessage;
import com.zkry.domain.vo.SubmitTripPlanResponse;
import com.zkry.security.VoyagePrincipal;
import com.zkry.task.AiModificationTaskService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips")
public class AiModificationTaskController {

    private final AiModificationTaskService service;

    public AiModificationTaskController(AiModificationTaskService service) {
        this.service = service;
    }

    @PostMapping("/{planId}/ai-modifications")
    public SubmitTripPlanResponse submit(
        @PathVariable String planId,
        @RequestBody Request request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @AuthenticationPrincipal VoyagePrincipal principal
    ) {
        return service.submit(
            planId,
            request.baseVersion(),
            request.message(),
            request.history(),
            principal,
            idempotencyKey
        );
    }

    public record Request(Integer baseVersion, String message, List<ChatMessage> history) {
    }
}
