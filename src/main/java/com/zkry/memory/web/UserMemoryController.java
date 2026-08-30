package com.zkry.memory.web;

import com.zkry.memory.application.UserMemoryCommandService;
import com.zkry.memory.application.UserMemoryCommandService.MemoryInput;
import com.zkry.memory.application.UserMemoryQueryService;
import com.zkry.memory.domain.UserMemoryFact;
import com.zkry.security.VoyagePrincipal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** User-controlled, structured profile memory. Identity always comes from the server session. */
@RestController
@RequestMapping("/api/me/memories")
public class UserMemoryController {
    private final UserMemoryQueryService query;
    private final UserMemoryCommandService command;
    public UserMemoryController(UserMemoryQueryService query, UserMemoryCommandService command) { this.query = query; this.command = command; }

    @GetMapping
    public List<MemoryView> list(@RequestParam(defaultValue = "true") boolean includeCandidates,
                                 @AuthenticationPrincipal VoyagePrincipal principal) {
        return query.list(userId(principal), includeCandidates).stream().map(MemoryView::of).toList();
    }
    @PostMapping
    public MemoryView create(@RequestBody MemoryInput input, @AuthenticationPrincipal VoyagePrincipal principal) {
        return MemoryView.of(command.createExplicit(userId(principal), normalize(input)));
    }
    @PutMapping("/{id}")
    public MemoryView update(@PathVariable Long id, @RequestBody MemoryInput input, @AuthenticationPrincipal VoyagePrincipal principal) {
        return MemoryView.of(command.update(userId(principal), id, normalize(input)));
    }
    @PostMapping("/{id}/confirm")
    public MemoryView confirm(@PathVariable Long id, @AuthenticationPrincipal VoyagePrincipal principal) { return MemoryView.of(command.confirm(userId(principal), id)); }
    @PostMapping("/{id}/reject")
    public void reject(@PathVariable Long id, @AuthenticationPrincipal VoyagePrincipal principal) { command.reject(userId(principal), id); }
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal VoyagePrincipal principal) { command.delete(userId(principal), id); }
    @DeleteMapping
    public void deleteAll(@AuthenticationPrincipal VoyagePrincipal principal) { command.deleteAll(userId(principal)); }
    private Long userId(VoyagePrincipal p) { if (p == null) throw new IllegalStateException("请先登录"); return p.userId(); }
    private MemoryInput normalize(MemoryInput i) { return new MemoryInput(i.memoryType(), i.memoryKey(), i.memoryValue(),
        i.scopeType() == null || i.scopeType().isBlank() ? "GLOBAL" : i.scopeType(), i.scopeValue(), i.hardConstraint(), i.evidenceRefs()); }
    public record MemoryView(Long id, String memory_type, String memory_key, Object memory_value, String scope_type,
                             String scope_value, String source, Double confidence, Boolean hard_constraint, String status,
                             LocalDateTime expires_at, List<String> evidence_refs) {
        static MemoryView of(UserMemoryFact f) {
            Object value; List<String> refs;
            try { value = com.zkry.common.util.JsonUtils.parseObject(f.getMemoryValueJson(), Object.class); } catch (Exception e) { value = f.getMemoryValueJson(); }
            try { refs = com.zkry.common.util.JsonUtils.parseObject(f.getEvidenceRefsJson(), List.class); } catch (Exception e) { refs = List.of(); }
            return new MemoryView(f.getId(), f.getMemoryType(), f.getMemoryKey(), value, f.getScopeType(), f.getScopeValue(), f.getSource(), f.getConfidence(), f.getHardConstraint(), f.getStatus(), f.getExpiresAt(), refs);
        }
    }
}
