package com.webcollector.search.dto;

import java.time.Instant;
import java.util.List;

/**
 * 搜索请求的内部参数对象。
 *
 * <p>[业务] HTTP 层只负责绑定参数，查询条件规范化由 Service 完成。</p>
 */
public record SearchRequest(
        String query,
        Long collectionId,
        List<Long> tagIds,
        Instant from,
        Instant to,
        String cursor,
        int limit
) {
}
