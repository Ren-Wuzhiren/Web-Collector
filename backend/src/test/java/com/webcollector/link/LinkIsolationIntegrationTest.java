package com.webcollector.link;

import com.jayway.jsonpath.JsonPath;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.mapper.CaptureJobMapper;
import com.webcollector.support.SharedTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [软件工程] M2.3 的验收标准是"同一用户下能存下链接且只看到自己的"。
 *
 * 这个证明只能在这里做：真实 MySQL + 真实 Sa-Token/Redis 会话 + 真实 LinkService。
 * 纯 Mockito 的单测拦不住"listLinks 忘记带 user_id"，因为 Mock 会把查询条件整个吞掉；
 * 而 Mapper 测试又只能证明"测试自己 new 出来的 wrapper 管用"，
 * 证明不了"Service 记得拼这个条件"。只有从 HTTP 入口一路打到数据库才能覆盖这段。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "app.auth.registration-enabled=true"
)
@AutoConfigureMockMvc
public class LinkIsolationIntegrationTest {
    @DynamicPropertySource
    static void registerContainerProperties(
            DynamicPropertyRegistry registry
    ) {
        SharedTestContainers.registerProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private CaptureJobMapper captureJobMapper;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM app_user");
    }

    // [测试意图] 验证两个用户各自的链接列表互不串味。
    @Test
    void shouldListOnlyOwnLinks() throws Exception {
        String aliceToken = registerAndLogin("link-alice");
        String bobToken = registerAndLogin("link-bob");

        saveLink(aliceToken, defaultInboxId(aliceToken), "https://a.example.com/1");
        saveLink(bobToken, defaultInboxId(bobToken), "https://b.example.com/1");

        mockMvc.perform(get("/api/v1/links")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].originalUrl")
                        .value("https://a.example.com/1"));

        mockMvc.perform(get("/api/v1/links")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].originalUrl")
                        .value("https://b.example.com/1"));
    }

    // [测试意图] 验证等价 URL 对同一用户返回 409，但其他用户仍可保存该地址。
    @Test
    void shouldRejectNormalizedDuplicateOnlyForSameUser() throws Exception {
        String aliceToken = registerAndLogin("link-dedup-alice");
        String bobToken = registerAndLogin("link-dedup-bob");
        long aliceInboxId = defaultInboxId(aliceToken);
        long bobInboxId = defaultInboxId(bobToken);

        saveLink(
                aliceToken,
                aliceInboxId,
                "HTTPS://Example.COM:443#first"
        );
        long aliceUserId = userId("link-dedup-alice");
        long bobUserId = userId("link-dedup-bob");
        assertThat(captureJobCount(aliceUserId)).isEqualTo(1);
        assertThat(captureJobCount(bobUserId)).isZero();

        mockMvc.perform(post("/api/v1/links")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://example.com/",
                                  "collectionId": %d
                                }
                                """.formatted(aliceInboxId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LINK_ALREADY_EXISTS"));
        assertThat(captureJobCount(aliceUserId)).isEqualTo(1);
        assertThat(captureJobCount(bobUserId)).isZero();

        mockMvc.perform(post("/api/v1/links")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://example.com/",
                                  "collectionId": %d
                                }
                                """.formatted(bobInboxId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.job.status").value("PENDING"));

        assertThat(captureJobCount(aliceUserId)).isEqualTo(1);
        assertThat(captureJobCount(bobUserId)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/links")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // [测试意图] 验证 HTTP 创建会同时保存链接、标签关联和一条字段完整的 PENDING 任务。
    @Test
    void shouldCreatePendingJobAlongsideLinkAndTags() throws Exception {
        String username = "capture-job-owner";
        String token = registerAndLogin(username);
        long userId = userId(username);
        long collectionId = defaultInboxId(token);
        long tagId = createTag(userId, "capture-job-tag");

        String body = mockMvc.perform(post("/api/v1/links")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://capture.example.com/article",
                                  "collectionId": %d,
                                  "tagIds": [%d],
                                  "title": "采集样例",
                                  "description": ""
                                }
                                """.formatted(collectionId, tagId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.link.originalUrl")
                        .value("https://capture.example.com/article"))
                .andExpect(jsonPath("$.data.job.status").value("PENDING"))
                .andExpect(jsonPath("$.data.job.attemptCount").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long linkId = ((Number) JsonPath.read(body, "$.data.link.id")).longValue();
        long jobId = ((Number) JsonPath.read(body, "$.data.job.id")).longValue();
        long responseLinkId = ((Number) JsonPath.read(body, "$.data.job.linkId")).longValue();
        String responseNextAttemptAt = JsonPath.read(body, "$.data.job.nextAttemptAt");

        assertThat(responseLinkId).isEqualTo(linkId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link WHERE id = ? AND user_id = ?",
                Integer.class,
                linkId,
                userId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link_tag WHERE link_id = ? AND tag_id = ?",
                Integer.class,
                linkId,
                tagId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_job WHERE id = ? AND link_id = ? "
                        + "AND requested_by = ? AND status = 'PENDING' "
                        + "AND attempt_count = 0 AND max_attempts = 3",
                Integer.class,
                jobId,
                linkId,
                userId
        )).isEqualTo(1);

        LocalDateTime storedNextAttemptAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM capture_job WHERE id = ?",
                (resultSet, rowNum) -> resultSet.getTimestamp(1).toLocalDateTime(),
                jobId
        );
        assertThat(storedNextAttemptAt.toInstant(ZoneOffset.UTC))
                .isEqualTo(Instant.parse(responseNextAttemptAt));
    }

    // [测试意图] 验证任务插入失败时，同一事务回滚新链接和它已写入的标签关联。
    @Test
    void shouldRollbackLinkAndTagsWhenCaptureJobInsertFails() throws Exception {
        String username = "capture-job-rollback-owner";
        String token = registerAndLogin(username);
        long userId = userId(username);
        long collectionId = defaultInboxId(token);
        long tagId = createTag(userId, "capture-rollback-tag");

        doThrow(new DataIntegrityViolationException(
                "forced capture job insert failure"
        )).when(captureJobMapper).insert(any(CaptureJob.class));

        mockMvc.perform(post("/api/v1/links")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://capture.example.com/rollback",
                                  "collectionId": %d,
                                  "tagIds": [%d]
                                }
                                """.formatted(collectionId, tagId)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link WHERE user_id = ? "
                        + "AND normalized_url = ?",
                Integer.class,
                userId,
                "https://capture.example.com/rollback"
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link_tag WHERE tag_id = ?",
                Integer.class,
                tagId
        )).isZero();
        assertThat(captureJobCount(userId)).isZero();
    }

    // [测试意图] 验证 A 既读不到、也改不掉、更删不掉 B 的链接，且 B 的链接完好无损。
    @Test
    void shouldNotReadModifyOrDeleteOtherUsersLink() throws Exception {
        String aliceToken = registerAndLogin("link-reader-alice");
        String bobToken = registerAndLogin("link-reader-bob");

        long bobLinkId = saveLink(
                bobToken,
                defaultInboxId(bobToken),
                "https://b.example.com/secret"
        );

        mockMvc.perform(get("/api/v1/links/" + bobLinkId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/links/" + bobLinkId)
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "collectionId": %d,
                                  "title": "被篡改",
                                  "description": ""
                                }
                                """.formatted(defaultInboxId(aliceToken))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/links/" + bobLinkId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());

        // [业务] 三次越权都没有产生副作用：B 依然能读到自己那条，标题也没被改。
        mockMvc.perform(get("/api/v1/links/" + bobLinkId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalUrl")
                        .value("https://b.example.com/secret"))
                .andExpect(jsonPath("$.data.collectionId")
                        .value(defaultInboxId(bobToken)))
                .andExpect(jsonPath("$.data.title").value(""));
    }

    // [测试意图] 验证不能把链接塞进别人的集合，且数据库里不会留下任何一条。
    @Test
    void shouldRejectLinkIntoOtherUsersCollection() throws Exception {
        String aliceToken = registerAndLogin("link-collection-alice");
        String bobToken = registerAndLogin("link-collection-bob");

        long bobInboxId = defaultInboxId(bobToken);

        mockMvc.perform(post("/api/v1/links")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://alice.example.com/intruder",
                                  "collectionId": %d
                                }
                                """.formatted(bobInboxId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/links")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/v1/links")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "email": "%s@example.com",
                                  "password": "correct-horse"
                                }
                                """.formatted(username, username)))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "correct-horse"
                                }
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.data.token");
    }

    // [业务] 注册时同事务会建好默认 Inbox，所以这里不用自己创建集合。
    private long defaultInboxId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/v1/collections")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Number id = JsonPath.read(body, "$.data[0].id");
        return id.longValue();
    }

    private long saveLink(
            String token,
            long collectionId,
            String url
    ) throws Exception {
        String body = mockMvc.perform(post("/api/v1/links")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "%s",
                                  "collectionId": %d,
                                  "title": "",
                                  "description": ""
                                }
                                """.formatted(url, collectionId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Number id = JsonPath.read(body, "$.data.link.id");
        return id.longValue();
    }

    private int captureJobCount(long requestedBy) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_job WHERE requested_by = ?",
                Integer.class,
                requestedBy
        );
    }

    private long userId(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?",
                Long.class,
                username
        );
    }

    private long createTag(long userId, String name) {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC())
                .truncatedTo(ChronoUnit.MILLIS);
        Timestamp timestamp = Timestamp.valueOf(now);
        jdbcTemplate.update(
                "INSERT INTO tag (user_id, name, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?)",
                userId,
                name,
                timestamp,
                timestamp
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tag WHERE user_id = ? AND name = ?",
                Long.class,
                userId,
                name
        );
    }
}
