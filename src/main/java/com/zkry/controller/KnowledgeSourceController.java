package com.zkry.controller;

import com.zkry.domain.dto.knowledge.CreateKnowledgeSourceRequest;
import com.zkry.domain.dto.knowledge.UpdateKnowledgeSourceRequest;
import com.zkry.domain.vo.KnowledgeSourceView;
import com.zkry.service.KnowledgeSourceService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge/sources")
public class KnowledgeSourceController {

    private final KnowledgeSourceService sourceService;

    public KnowledgeSourceController(KnowledgeSourceService sourceService) {
        this.sourceService = sourceService;
    }

    @GetMapping
    public List<KnowledgeSourceView> list() {
        return sourceService.list();
    }

    @PostMapping
    public KnowledgeSourceView create(@RequestBody CreateKnowledgeSourceRequest request) {
        return sourceService.create(request);
    }

    @PutMapping("/{id}")
    public KnowledgeSourceView update(@PathVariable Long id, @RequestBody UpdateKnowledgeSourceRequest request) {
        return sourceService.update(id, request);
    }

    @PostMapping("/{id}/sync")
    public KnowledgeSourceView sync(@PathVariable Long id) {
        return sourceService.sync(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        sourceService.delete(id);
        return Map.of("success", true);
    }
}
