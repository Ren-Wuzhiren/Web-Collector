package com.webcollector.capture.content;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** 从 HTML 中抽取正文并删除常见页面噪音。 */
@Component
public class ReadableContentExtractor {

    private static final List<String> NOISE_SELECTORS = List.of(
            "script",
            "style",
            "noscript",
            "nav",
            "header",
            "footer",
            "form",
            "広告",
            ".advertisement",
            ".ads",
            ".cookie-banner"
    );

    private static final Pattern HORIZONTAL_WHITESPACE =
            Pattern.compile("[\\t\\x0B\\f\\r ]+");

    private static final Pattern MULTIPLE_BLANK_LINES =
            Pattern.compile("\\n{3,}");

    /** 从 HTML 字符串抽取正文。 */
    public ReadableContent extract(String sourceHtml) {
        if (sourceHtml == null || sourceHtml.isBlank()) {
            throw new IllegalArgumentException("sourceHtml 不能为空");
        }

        Document document = Jsoup.parse(sourceHtml);
        removeNoise(document);

        Element contentRoot = chooseContentRoot(document);
        String text = normalizeText(contentRoot.wholeText());
        String html = contentRoot.html().trim();

        return new ReadableContent(text, html);
    }

    /** 从 HTML 文件读取并抽取正文。 */
    public ReadableContent extract(Path htmlPath) {
        if (htmlPath == null) {
            throw new IllegalArgumentException("html 不能为空");
        }

        try {
            return extract(Files.readString(htmlPath, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "读取 HTML 文件失败: " + htmlPath,
                    exception
            );
        }
    }

    /** 抽取并把纯文本和清洗 HTML 写入目标目录。 */
    public ReadableContent extractToFiles(
            Path htmlPath,
            Path outputDirectory
    ) {
        if (outputDirectory == null) {
            throw new IllegalArgumentException(
                    "outputDirectory 不能为空"
            );
        }

        ReadableContent content = extract(htmlPath);

        try {
            Files.createDirectories(outputDirectory);
            Files.writeString(
                    outputDirectory.resolve("page.readable.txt"),
                    content.text(),
                    StandardCharsets.UTF_8
            );
            Files.writeString(
                    outputDirectory.resolve("page.readable.html"),
                    content.html(),
                    StandardCharsets.UTF_8
            );
            return content;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "写入正文文件失败: " + outputDirectory,
                    exception
            );
        }
    }

    private void removeNoise(Document document) {
        for (String selector : NOISE_SELECTORS) {
            document.select(selector).remove();
        }
    }

    private Element chooseContentRoot(Document document) {
        Element article = document.selectFirst("article");
        if (article != null) {
            return article;
        }

        Element main = document.selectFirst("main");
        if (main != null) {
            return main;
        }

        return document.body();
    }

    private String normalizeText(String text) {
        String normalized = HORIZONTAL_WHITESPACE
                .matcher(text)
                .replaceAll(" ")
                .trim();

        return MULTIPLE_BLANK_LINES
                .matcher(normalized)
                .replaceAll("\\n\\n")
                .trim();
    }
}
