package com.webcollector.link.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webcollector.link.entity.Link;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;


/**
 * [软件工程] BaseMapper 已提供 insert / selectById / selectCount / updateById / deleteById。
 * 链接当前只需要这些基础方法，所以先保持空接口。
 *
 * [排错] 该接口没有实现类，由 MyBatis 在启动时用动态代理生成并注入；
 * 但必须落在 @MapperScan("com.webcollector.**.mapper") 的扫描范围内，否则容器里没有 Bean。
 */
public interface LinkMapper extends BaseMapper<Link> {

    @Update("UPDATE link SET text_content = #{textContent} WHERE id = #{linkId}")
    int updateTextContentById(
            @Param("linkId") Long linkId,
            @Param("textContent") String textContent
    );

    @Select("""
        SELECT id, user_id, collection_id, original_url, normalized_url,
               url_hash, title, description, created_at, updated_at
        FROM link
        WHERE user_id = #{userId}
          AND MATCH(title, description, text_content)
              AGAINST (#{query} IN NATURAL LANGUAGE MODE)
        ORDER BY id DESC
        """)
    List<Link> searchOwnedLinks(
            @Param("userId") Long userId,
            @Param("query") String query
    );

}
