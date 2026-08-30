package com.zkry.memory.domain;

import java.util.List;

public record ResolvedMemoryItem(String type, String key, Object value, int priority,
                                 String source, String scope, double confidence,
                                 List<String> evidenceRefs) { }
