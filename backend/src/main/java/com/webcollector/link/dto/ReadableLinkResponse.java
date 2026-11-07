package com.webcollector.link.dto;

/**
 * 清理正文阅读响应。
 *
 * <p>[业务] link 提供标题、来源地址和时间等元数据，textContent 是 M4.3
 * 已保存到 link.text_content 的纯文本正文。</p>
 *
 * <p>[安全] 不直接返回 Link 实体，也不返回原始页面 HTML。</p>
 */
public record ReadableLinkResponse(
        LinkSummary link,
        String textContent
) {
}
