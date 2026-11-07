package com.webcollector.link.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webcollector.link.entity.Link;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [软件工程] BaseMapper 提供实体基础 CRUD；组合搜索使用显式参数化 SQL。
 *
 * [排错] 该接口没有实现类，由 MyBatis 动态代理生成；必须位于
 * @MapperScan("com.webcollector.**.mapper") 的扫描范围内。
 */
public interface LinkMapper extends BaseMapper<Link> {

    @Update("UPDATE link SET text_content = #{textContent} WHERE id = #{linkId}")
    int updateTextContentById(
            @Param("linkId") Long linkId,
            @Param("textContent") String textContent
    );

    @Select("""
            <script>
            SELECT l.id, l.user_id, l.collection_id, l.original_url,
                   l.normalized_url, l.url_hash, l.title, l.description,
                   l.created_at, l.updated_at
            FROM link l
            WHERE l.user_id = #{userId}
            <if test="query != null">
              AND MATCH(l.title, l.description, l.text_content)
                  AGAINST (#{query} IN NATURAL LANGUAGE MODE)
            </if>
            <if test="collectionId != null">
              AND l.collection_id = #{collectionId}
            </if>
            <if test="from != null">
              AND l.created_at &gt;= #{from}
            </if>
            <if test="to != null">
              AND l.created_at &lt; #{to}
            </if>
            <if test="tagIds != null and tagIds.size() > 0">
              AND EXISTS (
                  SELECT 1
                  FROM link_tag lt
                  WHERE lt.link_id = l.id
                    AND lt.tag_id IN
                    <foreach collection="tagIds"
                             item="tagId"
                             open="("
                             separator=","
                             close=")">
                      #{tagId}
                    </foreach>
              )
            </if>
            <if test="lastId != null">
              AND l.id &lt; #{lastId}
            </if>
            ORDER BY l.id DESC
            LIMIT #{fetchLimit}
            </script>
            """)
    List<Link> searchOwnedLinkPage(
            @Param("userId") Long userId,
            @Param("query") String query,
            @Param("collectionId") Long collectionId,
            @Param("tagIds") List<Long> tagIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("lastId") Long lastId,
            @Param("fetchLimit") int fetchLimit
    );
}