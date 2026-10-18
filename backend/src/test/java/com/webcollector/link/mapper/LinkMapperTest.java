package com.webcollector.link.mapper;

import com.webcollector.auth.entity.AppUser;
import com.webcollector.auth.mapper.AppUserMapper;
import com.webcollector.collection.entity.LinkCollection;
import com.webcollector.collection.mapper.LinkCollectionMapper;
import com.webcollector.link.entity.Link;
import com.webcollector.link.support.UrlNormalizer;
import com.webcollector.support.SharedTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class LinkMapperTest {
    @DynamicPropertySource
    static void registerContainerProperties(
            DynamicPropertyRegistry registry
    ) {
        SharedTestContainers.registerProperties(registry);
    }

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private LinkCollectionMapper linkCollectionMapper;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        // [软件工程] 一句 DELETE 就能清空三张表：app_user 的外键是 ON DELETE CASCADE，
        // 集合和链接会跟着一起消失。测试之间不互相污染靠的就是这条链。
        jdbcTemplate.update("DELETE FROM app_user");
    }

    // [测试意图] 验证链接插入后主键回写、字段映射与时间精度正确。
    @Test
    void shouldInsertAndSelectLink() {
        AppUser owner = newUser("link-owner");
        LinkCollection collection = newCollection(owner.getId());
        LocalDateTime now = now();

        Link link = newLink(
                owner.getId(),
                collection.getId(),
                "https://example.com/a",
                now
        );

        int affectedRows = linkMapper.insert(link);

        assertThat(affectedRows).isEqualTo(1);
        assertThat(link.getId()).isNotNull();

        Link loaded = linkMapper.selectById(link.getId());

        assertThat(loaded).isNotNull();
        assertThat(loaded.getUserId()).isEqualTo(owner.getId());
        assertThat(loaded.getCollectionId()).isEqualTo(collection.getId());
        assertThat(loaded.getOriginalUrl())
                .isEqualTo("https://example.com/a");
        assertThat(loaded.getNormalizedUrl())
                .isEqualTo("https://example.com/a");
        assertThat(loaded.getUrlHash())
                .isEqualTo(UrlNormalizer.sha256("https://example.com/a"));
        assertThat(loaded.getTitle()).isEmpty();
        assertThat(loaded.getDescription()).isEmpty();
        assertThat(loaded.getCreatedAt()).isEqualTo(now);
        assertThat(loaded.getUpdatedAt()).isEqualTo(now);
    }

    // [测试意图] 验证 4096 字符的长地址能完整存取，列宽边界处不发生截断。
    @Test
    void shouldStoreUrlAtColumnWidthBoundary() {
        AppUser owner = newUser("link-long-url-owner");
        LinkCollection collection = newCollection(owner.getId());

        String prefix = "https://example.com/";
        String longUrl = prefix + "a".repeat(4096 - prefix.length());
        assertThat(longUrl).hasSize(4096);

        Link link = newLink(
                owner.getId(),
                collection.getId(),
                longUrl,
                now()
        );

        linkMapper.insert(link);

        Link loaded = linkMapper.selectById(link.getId());

        assertThat(loaded.getOriginalUrl()).hasSize(4096);
        assertThat(loaded.getOriginalUrl()).isEqualTo(longUrl);
    }

    // [测试意图] 验证不存在的用户会被外键挡下，不会留下无主链接。
    @Test
    void shouldRejectLinkForUnknownUser() {
        AppUser owner = newUser("link-fk-user-owner");
        LinkCollection collection = newCollection(owner.getId());

        assertThatThrownBy(() -> linkMapper.insert(newLink(
                99999L,
                collection.getId(),
                "https://example.com/a",
                now()
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    // [测试意图] 验证不存在的集合会被外键挡下，链接不能落在集合之外。
    @Test
    void shouldRejectLinkForUnknownCollection() {
        AppUser owner = newUser("link-fk-collection-owner");

        assertThatThrownBy(() -> linkMapper.insert(newLink(
                owner.getId(),
                99999L,
                "https://example.com/a",
                now()
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    // [测试意图] 验证删除用户时链接随外键级联消失。
    @Test
    void shouldCascadeDeleteLinksWhenUserIsDeleted() {
        AppUser owner = newUser("link-cascade-user-owner");
        LinkCollection collection = newCollection(owner.getId());
        Link link = newLink(
                owner.getId(),
                collection.getId(),
                "https://example.com/a",
                now()
        );
        linkMapper.insert(link);

        jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?",
                owner.getId()
        );

        assertThat(linkMapper.selectById(link.getId())).isNull();
    }

    // [测试意图] 验证删除集合时其中的链接也会级联消失——这是集合"非空拒删"规则背后的兜底。
    @Test
    void shouldCascadeDeleteLinksWhenCollectionIsDeleted() {
        AppUser owner = newUser("link-cascade-collection-owner");
        LinkCollection collection = newCollection(owner.getId());
        Link link = newLink(
                owner.getId(),
                collection.getId(),
                "https://example.com/a",
                now()
        );
        linkMapper.insert(link);

        jdbcTemplate.update(
                "DELETE FROM collection WHERE id = ?",
                collection.getId()
        );

        assertThat(linkMapper.selectById(link.getId())).isNull();
    }

    // [测试意图] 验证 URL 哈希在用户范围内唯一，同一地址可由不同用户分别保存。
    @Test
    void shouldEnforceUrlHashUniquenessPerUser() {
        AppUser owner = newUser("link-duplicate-url-owner");
        LinkCollection collection = newCollection(owner.getId());
        LocalDateTime now = now();

        Link first = newLink(
                owner.getId(),
                collection.getId(),
                "https://example.com/a",
                now
        );
        Link second = newLink(
                owner.getId(),
                collection.getId(),
                "https://example.com/a",
                now
        );

        assertThat(linkMapper.insert(first)).isEqualTo(1);
        assertThatThrownBy(() -> linkMapper.insert(second))
                .isInstanceOf(DataIntegrityViolationException.class);

        AppUser anotherOwner = newUser("link-duplicate-url-other-owner");
        LinkCollection anotherCollection = newCollection(anotherOwner.getId());
        Link sameUrlForAnotherOwner = newLink(
                anotherOwner.getId(),
                anotherCollection.getId(),
                "https://example.com/a",
                now
        );

        assertThat(linkMapper.insert(sameUrlForAnotherOwner)).isEqualTo(1);
    }

    private AppUser newUser(String username) {
        LocalDateTime now = now();

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("$2a$10$placeholder");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        assertThat(appUserMapper.insert(user)).isEqualTo(1);
        return user;
    }

    private LinkCollection newCollection(Long userId) {
        LocalDateTime now = now();

        LinkCollection collection = new LinkCollection();
        collection.setUserId(userId);
        collection.setName("Reading");
        collection.setDescription("");
        collection.setIsInbox(false);
        collection.setCreatedAt(now);
        collection.setUpdatedAt(now);

        assertThat(linkCollectionMapper.insert(collection)).isEqualTo(1);
        return collection;
    }

    private static Link newLink(
            Long userId,
            Long collectionId,
            String url,
            LocalDateTime now
    ) {
        Link link = new Link();
        link.setUserId(userId);
        link.setCollectionId(collectionId);
        link.setOriginalUrl(url);
        link.setNormalizedUrl(UrlNormalizer.normalize(url));
        link.setUrlHash(UrlNormalizer.sha256(link.getNormalizedUrl()));
        link.setTitle("");
        link.setDescription("");
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        return link;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(Clock.systemUTC())
                .truncatedTo(ChronoUnit.MILLIS);
    }
}
