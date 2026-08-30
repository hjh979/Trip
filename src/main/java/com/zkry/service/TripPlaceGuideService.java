package com.zkry.service;

import com.zkry.common.exception.BizException;
import com.zkry.domain.dto.knowledge.RagAnswerRequest;
import com.zkry.domain.entity.TripDay;
import com.zkry.domain.entity.TripItem;
import com.zkry.domain.entity.TripPlan;
import com.zkry.domain.vo.RagAnswerView;
import com.zkry.domain.vo.TripPlaceGuideView;
import com.zkry.mapper.TripDayMapper;
import com.zkry.mapper.TripItemMapper;
import com.zkry.security.VoyagePrincipal;
import com.zkry.service.rag.KnowledgeRagService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generates a place guide exclusively from the self-managed knowledge base.
 *
 * <p>No live content-platform collection happens on this path. The saved attraction has already
 * been validated by AMap during planning; this service only retrieves attributable travel
 * knowledge and lets the generation model organize that evidence.
 */
@Service
public class TripPlaceGuideService {

    private static final Logger log = LoggerFactory.getLogger(TripPlaceGuideService.class);
    private static final int TOP_K = 6;

    private final TripAccessService accessService;
    private final TripDayMapper dayMapper;
    private final TripItemMapper itemMapper;
    private final KnowledgeRagService ragService;

    public TripPlaceGuideService(
        TripAccessService accessService,
        TripDayMapper dayMapper,
        TripItemMapper itemMapper,
        KnowledgeRagService ragService
    ) {
        this.accessService = accessService;
        this.dayMapper = dayMapper;
        this.itemMapper = itemMapper;
        this.ragService = ragService;
    }

    public TripPlaceGuideView guide(
        String publicId,
        Long itemId,
        boolean refresh,
        VoyagePrincipal principal
    ) {
        TripPlan plan = accessService.requireView(publicId, principal);
        TripItem item = requirePlanItem(plan, itemId);
        List<String> topics = isBlank(item.getCategory()) ? List.of() : List.of(item.getCategory());
        RagAnswerView answer = ragService.answer(new RagAnswerRequest(
            guideQuestion(plan, item), TOP_K, List.of(), plan.getCity(), item.getPoiName(), topics
        ));

        String introduction = introduction(item, answer);
        if (isBlank(item.getNote()) && !isBlank(introduction)) {
            item.setNote(introduction);
            itemMapper.updateById(item);
        }
        String guide = answer.citations().isEmpty()
            ? fallbackGuide(item, introduction)
            : answer.answer();
        log.info(
            "[PlaceGuide] knowledge-base guide completed planId={} itemId={} place={} citations={} mode={} refresh={}",
            publicId,
            itemId,
            item.getPoiName(),
            answer.citations().size(),
            answer.retrieval_mode(),
            refresh
        );
        return new TripPlaceGuideView(
            item.getId(),
            item.getPoiName(),
            introduction,
            guide,
            answer.retrieval_mode(),
            answer.generated_by_ai(),
            answer.citations()
        );
    }

    private TripItem requirePlanItem(TripPlan plan, Long itemId) {
        if (itemId == null) throw new BizException("地点编号不能为空。");
        TripItem item = itemMapper.selectById(itemId);
        if (item == null) throw new BizException("行程地点不存在，id=" + itemId);
        TripDay day = dayMapper.selectById(item.getDayId());
        if (day == null || !plan.getId().equals(day.getPlanId())) {
            throw new BizException("该地点不属于当前行程。");
        }
        return item;
    }

    private String guideQuestion(TripPlan plan, TripItem item) {
        return "请为" + plan.getCity() + "的“" + item.getPoiName() + "”生成可直接执行的景点深度攻略。"
            + "当前计划在" + plan.getStartDate() + "至" + plan.getEndDate() + "期间到访，"
            + "安排" + safeTime(item) + "到达，停留" + item.getStayMinutes() + "分钟。"
            + "请按“为什么值得去、推荐游览顺序、实用提示、预约与避坑”组织，"
            + "只依据自建知识库证据；无法确认的开放时间、票价或预约规则必须说明待核实。";
    }

    private String introduction(TripItem item, RagAnswerView answer) {
        if (!isBlank(item.getNote())) return item.getNote().trim();
        if (answer != null && !answer.citations().isEmpty()) {
            String content = answer.citations().getFirst().content();
            if (!isBlank(content)) return shorten(content.trim(), 220);
        }
        String address = isBlank(item.getAddress()) ? "" : "，位于" + item.getAddress();
        return item.getPoiName() + address + "。当前已纳入本次行程，建议结合官方公告和实时交通安排游览。";
    }

    private String fallbackGuide(TripItem item, String introduction) {
        return """
            为什么值得去
            %s

            推荐游览顺序
            按当前行程在%s到达，先完成核心看点，再根据现场人流补充周边区域；总停留时间控制在%s分钟。

            资料状态
            自建知识库暂未检索到足够的可引用资料。门票、开放时间和预约规则请以景点官方公告为准。
            """.formatted(introduction, safeTime(item), item.getStayMinutes()).trim();
    }

    private String safeTime(TripItem item) {
        return item.getStartTime() == null ? "待安排时间" : item.getStartTime().toString();
    }

    private String shorten(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, maxLength) + "…";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
