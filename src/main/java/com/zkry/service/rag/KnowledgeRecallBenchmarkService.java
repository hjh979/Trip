package com.zkry.service.rag;

import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.knowledge.RagSearchRequest;
import com.zkry.domain.dto.knowledge.RecallBenchmarkCase;
import com.zkry.domain.dto.knowledge.RecallBenchmarkRequest;
import com.zkry.domain.entity.KnowledgeChunk;
import com.zkry.domain.vo.RagCitationView;
import com.zkry.domain.vo.RagSearchView;
import com.zkry.domain.vo.RecallBenchmarkCaseView;
import com.zkry.domain.vo.RecallBenchmarkView;
import com.zkry.mapper.KnowledgeChunkMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRecallBenchmarkService {

    private static final List<RecallBenchmarkCase> DEFAULT_CASES = List.of(
        test("beijing-forbidden-city", "北京故宫参观顺序和交通建议", "北京", "故宫"),
        test("shanghai-bund", "上海外滩适合怎样安排步行路线", "上海", "外滩"),
        test("hangzhou-west-lake", "杭州西湖一日游怎么少走回头路", "杭州", "西湖"),
        test("xian-terracotta", "西安兵马俑历史人文游览建议", "西安", "兵马俑", "秦始皇"),
        test("chengdu-panda", "成都大熊猫基地游览建议", "成都", "熊猫"),
        test("nanjing-mausoleum", "南京中山陵历史路线", "南京", "中山陵"),
        test("suzhou-gardens", "苏州园林经典游览建议", "苏州", "园林"),
        test("changsha-yuelu", "长沙岳麓山和橘子洲怎么安排", "长沙", "岳麓", "橘子洲"),
        test("xiamen-gulangyu", "厦门鼓浪屿交通和游览建议", "厦门", "鼓浪屿"),
        test("qingdao-zhanqiao", "青岛栈桥周边怎么玩", "青岛", "栈桥"),
        test("chengde-resort", "承德避暑山庄历史人文攻略", "承德", "避暑山庄"),
        test("qinhuangdao-shanhaiguan", "秦皇岛山海关长城攻略", "秦皇岛", "山海关"),
        test("shijiazhuang-zhengding", "石家庄正定古城历史路线", "石家庄", "正定"),
        test("hebei-history", "河北历史人文旅行有哪些代表地点", "河北", "河北"),
        test("wuxi-taihu", "无锡太湖和鼋头渚怎样安排", "无锡", "太湖"),
        test("dunhuang-mogao", "敦煌莫高窟和鸣沙山游览建议", "敦煌", "莫高窟"),
        test("tokyo-shinjuku", "東京新宿交通和城市游览建议", "東京", "新宿"),
        test("macau-ruins", "澳門大三巴和历史城区游览建议", "澳門", "大三巴"),
        test("vienna-schonbrunn", "維也納美泉宫和博物馆怎么安排", "維也納", "美泉宫"),
        test("toronto-cn-tower", "多伦多加拿大国家电视塔及市中心游览", "多伦多", "加拿大國家電視塔")
    );

    private final KnowledgeRagService ragService;
    private final KnowledgeChunkMapper chunkMapper;

    public KnowledgeRecallBenchmarkService(KnowledgeRagService ragService, KnowledgeChunkMapper chunkMapper) {
        this.ragService = ragService;
        this.chunkMapper = chunkMapper;
    }

    public RecallBenchmarkView evaluate(RecallBenchmarkRequest request) {
        int topK = Math.max(1, Math.min(request == null || request.top_k() == null ? 5 : request.top_k(), 20));
        List<RecallBenchmarkCase> cases = request == null || request.cases() == null || request.cases().isEmpty()
            ? DEFAULT_CASES : request.cases().stream().limit(100).toList();
        List<RecallBenchmarkCaseView> results = new ArrayList<>();
        int hits = 0;
        int cleanCities = 0;
        for (RecallBenchmarkCase benchmark : cases) {
            String query = text(benchmark.query());
            String city = text(benchmark.city());
            List<String> expected = benchmark.expected_terms() == null ? List.of() : benchmark.expected_terms()
                .stream().map(this::text).filter(value -> !value.isBlank()).toList();
            RagSearchView search = ragService.search(new RagSearchRequest(
                query, topK, List.of(), city, "", List.of()
            ));
            boolean hit = search.citations().stream().anyMatch(citation -> containsAny(citation, expected));
            boolean cityClean = !search.citations().isEmpty() && search.citations().stream()
                .allMatch(citation -> city.equals(chunkCity(citation.chunk_id())));
            if (hit) hits++;
            if (cityClean) cleanCities++;
            results.add(new RecallBenchmarkCaseView(
                text(benchmark.id()), query, city, hit, cityClean, expected,
                search.citations().stream().map(RagCitationView::title).distinct().toList()
            ));
        }
        int total = cases.size();
        return new RecallBenchmarkView(topK, total, hits, ratio(hits, total), ratio(cleanCities, total), results);
    }

    public List<RecallBenchmarkCase> defaultCases() {
        return DEFAULT_CASES;
    }

    private boolean containsAny(RagCitationView citation, List<String> expected) {
        if (expected.isEmpty()) return !text(citation.content()).isBlank();
        String value = text(citation.title()) + " " + text(citation.content());
        return expected.stream().anyMatch(value::contains);
    }

    private String chunkCity(Long chunkId) {
        KnowledgeChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || text(chunk.getMetadataJson()).isBlank()) return "";
        try {
            Map<String, Object> metadata = JsonUtils.parseMap(chunk.getMetadataJson());
            return metadata == null ? "" : text(metadata.get("city"));
        } catch (Exception ignored) {
            return "";
        }
    }

    private double ratio(int value, int total) {
        if (total == 0) return 0D;
        return BigDecimal.valueOf(value * 100D / total)
            .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static RecallBenchmarkCase test(String id, String query, String city, String... expected) {
        return new RecallBenchmarkCase(id, query, city, List.of(expected));
    }
}
