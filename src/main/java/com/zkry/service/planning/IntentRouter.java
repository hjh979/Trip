package com.zkry.service.planning;

import com.zkry.domain.dto.IntentDecision;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Routes edit requests before any model or external tool is called. */
@Service
public class IntentRouter {
    private static final Pattern DAY = Pattern.compile("第\\s*(\\d+)\\s*天");

    public IntentDecision decide(String message, boolean hasTripPlan) {
        String value = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return new IntentDecision(IntentDecision.Intent.UNKNOWN, IntentDecision.Mode.UNKNOWN,
            0.1, "FULL_TRIP", List.of(), false, false);
        if (!hasTripPlan) return new IntentDecision(IntentDecision.Intent.CREATE_TRIP, IntentDecision.Mode.FULL_PLAN,
            0.98, "FULL_TRIP", List.of(), true, true);
        if (containsAny(value, "cancel", "取消任务", "取消行程")) return new IntentDecision(
            IntentDecision.Intent.CANCEL_TASK, IntentDecision.Mode.CANCEL, 0.98, "FULL_TRIP", List.of(), false, false);
        if (containsAny(value, "what", "where", "when", "查询", "问一下", "介绍")) return new IntentDecision(
            IntentDecision.Intent.ASK_TRIP, IntentDecision.Mode.RAG_QA, 0.86, "FULL_TRIP", List.of(), true, false);
        TripModificationLevel level = route(value);
        if (level == TripModificationLevel.RESEARCH) return new IntentDecision(
            IntentDecision.Intent.REPLAN_TRIP, IntentDecision.Mode.SCOPED_REPLAN, 0.9, scope(value), List.of(), true, true);
        if (level == TripModificationLevel.SIMPLE) return new IntentDecision(
            IntentDecision.Intent.EDIT_TRIP, IntentDecision.Mode.LOCAL_PATCH, 0.96, scope(value), List.of(), false, false);
        return new IntentDecision(IntentDecision.Intent.EDIT_TRIP, IntentDecision.Mode.SEMANTIC_PATCH,
            0.82, scope(value), List.of(), false, false);
    }

    public TripModificationLevel route(String message) {
        String value = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return TripModificationLevel.ORDINARY;
        boolean structural = containsAny(value, "删除", "移除", "去掉", "调换顺序", "交换顺序", "顺序调换",
            "标记为轻松", "轻松一点", "少走路", "减少步行");
        boolean factual = containsAny(value, "增加", "添加", "新景点", "景点", "酒店", "住宿", "城市",
            "出发日期", "日期", "营业时间", "天气");
        if (factual && (containsAny(value, "增加", "添加", "新", "更换", "修改", "日期", "城市", "酒店"))) {
            return TripModificationLevel.RESEARCH;
        }
        return structural ? TripModificationLevel.SIMPLE : TripModificationLevel.ORDINARY;
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private String scope(String value) {
        Matcher matcher = DAY.matcher(value);
        return matcher.find() ? "DAY_" + matcher.group(1) : "FULL_TRIP";
    }
}
