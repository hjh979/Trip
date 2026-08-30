package com.zkry.memory.domain;

import com.zkry.common.util.JsonUtils;
import java.util.List;

public record MemoryContextPack(List<ResolvedMemoryItem> active, List<String> suppressed) {
    public MemoryContextPack {
        active = active == null ? List.of() : List.copyOf(active);
        suppressed = suppressed == null ? List.of() : List.copyOf(suppressed);
    }
    public static MemoryContextPack empty() { return new MemoryContextPack(List.of(), List.of()); }
    public String promptContext() {
        if (active.isEmpty()) return "无已确认的长期用户记忆；以本次明确要求为准。";
        String text = JsonUtils.toJsonString(active);
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
