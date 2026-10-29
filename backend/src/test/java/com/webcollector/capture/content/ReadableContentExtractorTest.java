package com.webcollector.capture.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadableContentExtractorTest {

    private final ReadableContentExtractor extractor =
            new ReadableContentExtractor();

    // [测试意图] 验证 article 正文保留，同时删除导航、脚本、样式和广告。
    @Test
    void shouldExtractArticleAndRemoveNoise() {
        String html = """
                <html>
                <head><style>.hidden { display: none; }</style></head>
                <body>
                    <nav>导航菜单</nav>
                    <article>
                        <h1>网页标题</h1>
                        <p>这是中文正文。</p>
                        <script>alert('noise')</script>
                        <div class="ads">广告内容</div>
                    </article>
                    <footer>页脚信息</footer>
                </body>
                </html>
                """;

        ReadableContent content = extractor.extract(html);

        assertThat(content.text())
                .contains("网页标题")
                .contains("这是中文正文")
                .doesNotContain("导航菜单")
                .doesNotContain("alert")
                .doesNotContain("广告内容")
                .doesNotContain("页脚信息");
        assertThat(content.html()).contains("这是中文正文");
    }

    // [测试意图] 验证没有 article 时回退到 main，再没有 main 时使用 body。
    @Test
    void shouldFallbackFromMainToBody() {
        ReadableContent mainContent = extractor.extract("""
                <body><main><p>主要正文</p></main><div>其他内容</div></body>
                """);
        ReadableContent bodyContent = extractor.extract("""
                <body><p>Body 正文</p><div>补充内容</div></body>
                """);

        assertThat(mainContent.text()).contains("主要正文");
        assertThat(mainContent.text()).doesNotContain("其他内容");
        assertThat(bodyContent.text())
                .contains("Body 正文")
                .contains("补充内容");
    }

    // [测试意图] 验证中文 UTF-8 文件读取、抽取和写回不会乱码。
    @Test
    void shouldReadAndWriteUtf8Files(@TempDir Path tempDir)
            throws IOException {
        Path input = tempDir.resolve("page.html");
        Path output = tempDir.resolve("readable");
        Files.writeString(
                input,
                "<article><p>你好，正文检索。</p></article>",
                StandardCharsets.UTF_8
        );

        ReadableContent content = extractor.extractToFiles(input, output);

        assertThat(content.text()).isEqualTo("你好，正文检索。");
        assertThat(Files.readString(
                output.resolve("page.readable.txt"),
                StandardCharsets.UTF_8
        )).isEqualTo("你好，正文检索。");
        assertThat(Files.readString(
                output.resolve("page.readable.html"),
                StandardCharsets.UTF_8
        )).contains("你好，正文检索");
    }

    // [测试意图] 验证空输入和不存在文件会被明确拒绝。
    @Test
    void shouldRejectBlankHtmlAndMissingFile(@TempDir Path tempDir) {
        assertThatThrownBy(() -> extractor.extract("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceHtml");

        assertThatThrownBy(() -> extractor.extract(
                tempDir.resolve("missing.html")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("读取 HTML 文件失败");
    }
}