package com.zkry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zkry.domain.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * 知识文档重建分块时必须物理删除旧行，否则逻辑删除行仍会占用
     * (document_id, chunk_index) 唯一键，导致后续同步无法扩展分块。
     */
    @Delete("DELETE FROM knowledge_chunk WHERE document_id = #{documentId}")
    int hardDeleteByDocumentId(@Param("documentId") Long documentId);
}
