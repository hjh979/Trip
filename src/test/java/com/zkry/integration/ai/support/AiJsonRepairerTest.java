package com.zkry.integration.ai.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiJsonRepairerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void repairsUnescapedQuotesInsideToolSuppliedTitle() throws Exception {
        String malformed = "{\"cities\":[{\"notes\":[{\"note_id\":\"1\",\"title\":\"中环藏着革命的\"起点脚印\"\",\"xsec_source\":\"pc_search\"}]}]}";

        String repaired = AiJsonRepairer.repairUnescapedQuotes(malformed);
        JsonNode root = objectMapper.readTree(repaired);

        assertEquals("中环藏着革命的\"起点脚印\"", root.at("/cities/0/notes/0/title").asText());
    }

    @Test
    void keepsAlreadyValidEscapedQuotesUnchanged() throws Exception {
        String valid = "{\"title\":\"中环的\\\"起点脚印\\\"\",\"count\":15}";

        String repaired = AiJsonRepairer.repairUnescapedQuotes(valid);

        assertEquals(valid, repaired);
        assertEquals("中环的\"起点脚印\"", objectMapper.readTree(repaired).get("title").asText());
    }

    @Test
    void extractsJsonFromMarkdownFenceBeforeRepairing() throws Exception {
        String response = "```json\n{\"title\":\"维港的\"夜景\"\"}\n```";

        String repaired = AiJsonRepairer.repairUnescapedQuotes(response);

        assertEquals("维港的\"夜景\"", objectMapper.readTree(repaired).get("title").asText());
    }
}
