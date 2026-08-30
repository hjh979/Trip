package com.zkry.service.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zkry.domain.dto.knowledge.RagSearchRequest;
import com.zkry.domain.entity.KnowledgeChunk;
import com.zkry.domain.entity.KnowledgeDocument;
import com.zkry.domain.entity.KnowledgeSource;
import com.zkry.domain.vo.RagSearchView;
import com.zkry.integration.ai.service.AiTextService;
import com.zkry.mapper.KnowledgeChunkMapper;
import com.zkry.mapper.KnowledgeDocumentMapper;
import com.zkry.mapper.KnowledgeSourceMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeRagServiceTest {

    @Test
    void removesCorruptedVectorEvidenceAndKeepsValidKeywordEvidence() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        MilvusVectorStoreService vectorStore = mock(MilvusVectorStoreService.class);
        AiTextService aiTextService = mock(AiTextService.class);
        RetrievalTraceService traceService = mock(RetrievalTraceService.class);
        KnowledgeRagService service = new KnowledgeRagService(chunkMapper, documentMapper, sourceMapper,
            embeddingService, vectorStore, aiTextService, traceService);

        when(embeddingService.isConfigured()).thenReturn(true);
        when(embeddingService.embed(anyList())).thenReturn(Optional.of(List.of(List.of(0.25D))));
        when(vectorStore.isAvailable()).thenReturn(true);
        when(vectorStore.search(anyList(), eq(24), anyList(), eq(""))).thenReturn(List.of(
            new MilvusVectorStoreService.VectorHit(99L, 0.92D, Map.of(
                "document_id", 98L,
                "source_id", 2003L,
                "source_name", "官方旅游资料",
                "title", "????????",
                "source_url", "",
                "content", "????????????????????????"
            ))
        ));

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(3104L);
        chunk.setDocumentId(3004L);
        chunk.setContent("外滩适合连续滨江步行，出发前应以景点官方公告核对开放信息。");
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));

        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(3004L);
        document.setSourceId(2003L);
        document.setTitle("上海经典城区游览提示");
        document.setSourceUrl("https://www.shanghai.gov.cn");
        when(documentMapper.selectById(3004L)).thenReturn(document);

        KnowledgeSource source = new KnowledgeSource();
        source.setId(2003L);
        source.setName("官方旅游资料");
        when(sourceMapper.selectById(2003L)).thenReturn(source);

        RagSearchView result = service.search(new RagSearchRequest(
            "请为上海的“外滩”生成深度攻略", 6, List.of()));

        assertEquals("KEYWORD_ONLY", result.retrieval_mode());
        assertEquals(1, result.citations().size());
        assertEquals(3104L, result.citations().getFirst().chunk_id());
        assertEquals("上海经典城区游览提示", result.citations().getFirst().title());
    }

    @Test
    void cityFilterRejectsChunksFromOtherCities() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeSourceMapper sourceMapper = mock(KnowledgeSourceMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        MilvusVectorStoreService vectorStore = mock(MilvusVectorStoreService.class);
        AiTextService aiTextService = mock(AiTextService.class);
        RetrievalTraceService traceService = mock(RetrievalTraceService.class);
        KnowledgeRagService service = new KnowledgeRagService(chunkMapper, documentMapper, sourceMapper,
            embeddingService, vectorStore, aiTextService, traceService);

        when(embeddingService.isConfigured()).thenReturn(false);
        when(vectorStore.isAvailable()).thenReturn(true);

        KnowledgeChunk hebei = chunk(4101L, 4001L, "河北历史人文路线包含正定古城和承德避暑山庄。", "河北");
        KnowledgeChunk hangzhou = chunk(4102L, 4002L, "杭州历史人文路线包含西湖与南宋御街。", "杭州");
        when(chunkMapper.selectList(any())).thenReturn(List.of(hebei, hangzhou));

        KnowledgeSource source = new KnowledgeSource();
        source.setId(2003L);
        source.setName("官方旅游资料");
        source.setStatus("READY");
        when(sourceMapper.selectById(2003L)).thenReturn(source);
        when(documentMapper.selectById(4001L)).thenReturn(document(4001L, "河北历史人文游览提示"));
        when(documentMapper.selectById(4002L)).thenReturn(document(4002L, "杭州历史人文游览提示"));

        RagSearchView result = service.search(new RagSearchRequest(
            "河北历史人文旅行", 5, List.of(), "河北", "", List.of("历史人文")
        ));

        assertEquals(1, result.citations().size());
        assertEquals(4101L, result.citations().getFirst().chunk_id());
        assertTrue(result.citations().getFirst().content().contains("河北"));
    }

    private KnowledgeChunk chunk(Long id, Long documentId, String content, String city) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setDocumentId(documentId);
        chunk.setContent(content);
        chunk.setKeywords(city + ",历史人文");
        chunk.setMetadataJson("{\"city\":\"" + city + "\"}");
        return chunk;
    }

    private KnowledgeDocument document(Long id, String title) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setSourceId(2003L);
        document.setTitle(title);
        document.setSourceUrl("https://example.test");
        document.setStatus("KEYWORD_ONLY");
        return document;
    }
}
