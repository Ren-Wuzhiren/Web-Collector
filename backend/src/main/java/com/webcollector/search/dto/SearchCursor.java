package com.webcollector.search.dto;

/**
 * 搜索游标。
 *
 * <p>[安全] 游标由客户端携带，不能承载用户身份，也不能替代服务端授权。</p>
 */
public record SearchCursor (
        int version,
        Long lastId,
        String fingerprint
) {
}
