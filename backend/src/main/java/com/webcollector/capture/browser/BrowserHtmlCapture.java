package com.webcollector.capture.browser;

import java.nio.file.Path;

/**
 * [业务] 使用浏览器打开网页，并把渲染后的 HTML 保存到文件。
 *
 * [M4.1] 本组件只负责浏览器导航和 HTML 落盘。
 * SSRF、重定向复检、PDF、截图、正文抽取和 asset 表属于后续阶段。
 */
public interface BrowserHtmlCapture {

    /**
     * 打开 URL，读取浏览器渲染后的 DOM，并写入目标路径。
     *
     * @param url        目标 HTTP/HTTPS 地址
     * @param outputPath HTML 输出文件路径
     * @return 实际写入的文件路径
     */
    Path capture(String url, Path outputPath);
}
