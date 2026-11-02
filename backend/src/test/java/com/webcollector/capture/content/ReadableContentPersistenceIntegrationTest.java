package com.webcollector.capture.content;

import com.webcollector.auth.entity.AppUser;
import com.webcollector.auth.mapper.AppUserMapper;
import com.webcollector.collection.entity.LinkCollection;
import com.webcollector.collection.mapper.LinkCollectionMapper;
import com.webcollector.link.entity.Link;
import com.webcollector.link.mapper.LinkMapper;
import com.webcollector.link.support.UrlNormalizer;
import com.webcollector.support.SharedTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReadableContentPersistenceIntegrationTest {

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.registerProperties(registry);
    }

    @TempDir
    Path tempDirectory;

    @Autowired
    private ReadableContentPersistenceService persistenceService;

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private LinkCollectionMapper collectionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM app_user");
    }

    // [测试意图] 验证迁移后的真实 MySQL 能持久化并读回中文抽取正文。
    @Test
    void shouldPersistReadableTextToMySql() throws IOException {
        Link link = newLink();
        Path html = tempDirectory.resolve("captured.html");
        Files.writeString(
                html,
                "<article><p>你好，正文可检索。</p><nav>导航污染</nav></article>",
                StandardCharsets.UTF_8
        );

        ReadableContent content = persistenceService.persist(link.getId(), html);
        Link loaded = linkMapper.selectById(link.getId());

        assertThat(content.text()).isEqualTo("你好，正文可检索。");
        assertThat(loaded.getTextContent()).isEqualTo(content.text());
    }

    private Link newLink() {
        LocalDateTime now = LocalDateTime.now()
                .truncatedTo(ChronoUnit.MILLIS);
        String suffix = UUID.randomUUID().toString().replace("-", "");

        AppUser user = new AppUser();
        user.setUsername("content-" + suffix.substring(0, 20));
        user.setEmail("content-" + suffix + "@example.com");
        user.setPasswordHash("$2a$10$placeholder");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        appUserMapper.insert(user);

        LinkCollection collection = new LinkCollection();
        collection.setUserId(user.getId());
        collection.setName("Reading");
        collection.setDescription("");
        collection.setIsInbox(false);
        collection.setCreatedAt(now);
        collection.setUpdatedAt(now);
        collectionMapper.insert(collection);

        String url = "https://example.com/" + suffix;
        Link link = new Link();
        link.setUserId(user.getId());
        link.setCollectionId(collection.getId());
        link.setOriginalUrl(url);
        link.setNormalizedUrl(UrlNormalizer.normalize(url));
        link.setUrlHash(UrlNormalizer.sha256(link.getNormalizedUrl()));
        link.setTitle("");
        link.setDescription("");
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        linkMapper.insert(link);
        return link;
    }
}
