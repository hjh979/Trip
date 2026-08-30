package com.zkry.service.planning;

import com.zkry.domain.dto.TripPatch;
import com.zkry.domain.dto.TripPatchOperation;
import com.zkry.domain.dto.TripPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Generates deterministic patches for edits that do not require new facts. */
@Service
public class LocalPatchEngine {
    private static final Pattern DAY_PATTERN = Pattern.compile("第\\s*([0-9一二两三四五六七八九十]+)\\s*天");
    private static final Pattern REMOVE_TARGET = Pattern.compile("(?:删除|移除|去掉|删掉)\\s*(?:第\\s*[0-9一二两三四五六七八九十]+\\s*天)?\\s*([^，。；;、和并]+)");

    public TripPatch generate(String message, TripPlan plan) {
        if (message == null || plan == null || plan.days() == null || plan.days().isEmpty()) {
            return new TripPatch(List.of());
        }
        String value = message.trim().toLowerCase(Locale.ROOT);
        int day = dayIndex(value, plan.days().size());
        List<TripPatchOperation> operations = new ArrayList<>();
        List<Integer> affected = new ArrayList<>();
        boolean recalculate = false;
        if (containsAny(value, "删除", "移除", "去掉", "删掉")) {
            var attractions = plan.days().get(day).attractions();
            if (attractions != null && !attractions.isEmpty()) {
                int targetIndex = removalIndex(value, attractions);
                if (targetIndex >= 0) {
                    operations.add(new TripPatchOperation("remove", "/days/" + day + "/attractions/" + targetIndex,
                        null, null, "用户明确删除第 " + (day + 1) + " 天的指定地点"));
                    affected.add(day);
                    recalculate = true;
                }
            }
        } else if (containsAny(value, "调换顺序", "交换顺序", "顺序调换", "景点顺序", "换一下顺序")) {
            var attractions = plan.days().get(day).attractions();
            if (attractions != null && attractions.size() >= 2) {
                operations.add(new TripPatchOperation("move", "/days/" + day + "/attractions/1", null,
                    "/days/" + day + "/attractions/0"));
                affected.add(day);
                recalculate = true;
            }
        } else if (containsAny(value, "标记为轻松", "轻松一点", "节奏轻松", "少走路", "减少步行")) {
            operations.add(new TripPatchOperation("replace", "/overall_suggestions",
                "行程节奏轻松，减少步行和赶路，保留充足休息时间", null));
            affected.add(day);
        }
        return new TripPatch(operations, recalculate, affected);
    }

    private int removalIndex(String message, List<com.zkry.domain.dto.Attraction> attractions) {
        if (message.contains("最后一个") || message.contains("最后一处")) return attractions.size() - 1;
        Matcher matcher = REMOVE_TARGET.matcher(message);
        if (!matcher.find()) return -1;
        String target = matcher.group(1).replaceAll("(景点|安排)$", "").trim();
        if (target.isBlank()) return -1;
        String normalized = target.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        for (int index = 0; index < attractions.size(); index++) {
            String name = attractions.get(index) == null ? "" : attractions.get(index).name();
            String candidate = name == null ? "" : name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (candidate.equals(normalized) || candidate.contains(normalized) || normalized.contains(candidate)) return index;
        }
        return -1;
    }

    private int dayIndex(String message, int count) {
        Matcher matcher = DAY_PATTERN.matcher(message);
        if (!matcher.find()) return 0;
        try {
            int parsed = Integer.parseInt(matcher.group(1));
            return Math.max(0, Math.min(count - 1, parsed - 1));
        } catch (NumberFormatException ignored) {
            String digits = matcher.group(1);
            String numerals = "一二三四五六七八九";
            int value = numerals.indexOf(digits);
            return value >= 0 ? Math.min(count - 1, value) : 0;
        }
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
