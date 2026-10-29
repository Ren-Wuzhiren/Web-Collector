package com.webcollector.capture.content;

/** 正文抽取后的纯文本和清洗 HTML。 */
public record ReadableContent(
        String text,
        String html
) {
}