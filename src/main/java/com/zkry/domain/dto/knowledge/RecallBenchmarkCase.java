package com.zkry.domain.dto.knowledge;

import java.util.List;

public record RecallBenchmarkCase(
    String id,
    String query,
    String city,
    List<String> expected_terms
) {
}
