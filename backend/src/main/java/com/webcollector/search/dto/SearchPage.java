package com.webcollector.search.dto;

import com.webcollector.link.dto.LinkSummary;

import java.util.List;

/**
 * 搜索分页结果。
 *
 * <p>[业务] nextCursor 为空表示没有下一页；hasMore 让客户端不必解析游标语义。</p>
 */
public record SearchPage (
        List<LinkSummary> items,
        String nextCursor,
        boolean hasMore
) {
}