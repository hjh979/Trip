package com.zkry.domain.vo;

import java.util.List;

public record RecallBenchmarkCaseView(
    String id,
    String query,
    String city,
    boolean hit,
    boolean city_clean,
    List<String> expected_terms,
    List<String> returned_titles
) {
}
