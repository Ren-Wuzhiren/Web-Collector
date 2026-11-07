package com.webcollector.admin;

import com.jayway.jsonpath.JsonPath;
import com.webcollector.support.SharedTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.auth.registration-enabled=true",
                "app.asset.cleanup.poll-interval-ms=3600000"
        }
)
@AutoConfigureMockMvc
class AdminOperationsIntegrationTest {

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.registerProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM admin_action_log");
        jdbcTemplate.update("DELETE FROM app_user");
    }

    @AfterEach
    void cleanAfter() {
        jdbcTemplate.update("DELETE FROM admin_action_log");
        jdbcTemplate.update("DELETE FROM app_user");
    }

    // [测试意图] 验证管理员概览只对 ADMIN 开放并返回跨用户聚合数字。
    @Test
    void shouldReturnOverviewOnlyForAdmin() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(get("/api/v1/admin/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/admin/overview")
                        .header("Authorization", bearer(fixture.ownerToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/overview")
                        .header("Authorization", bearer(fixture.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalUsers").value(3))
                .andExpect(jsonPath("$.data.totalLinks").value(3))
                .andExpect(jsonPath("$.data.totalAssets").value(1))
                .andExpect(jsonPath("$.data.pendingJobs").value(1))
                .andExpect(jsonPath("$.data.runningJobs").value(0))
                .andExpect(jsonPath("$.data.succeededJobs").value(1))
                .andExpect(jsonPath("$.data.failedJobs").value(1));
    }

    // [测试意图] 验证失败列表只返回每个链接最新任务、支持游标并隐藏内容字段。
    @Test
    void shouldReturnPrivacySafeLatestFailedJobsWithCursor() throws Exception {
        Fixture fixture = createFixture();
        CreatedLink secondFailed = createLink(
                fixture.ownerToken(),
                "https://developer.mozilla.org/en-US/docs/Web/Security",
                "SECOND-SECRET-TITLE",
                "SECOND-SECRET-DESCRIPTION"
        );
        jdbcTemplate.update(
                "UPDATE capture_job SET status = 'FAILED', attempt_count = 3, "
                        + "finished_at = updated_at, error_code = 'TIMEOUT', "
                        + "error_message = 'SECOND-SECRET-ERROR' WHERE id = ?",
                secondFailed.jobId()
        );

        MvcResult firstPage = mockMvc.perform(
                        get("/api/v1/admin/capture-jobs/failed")
                                .param("limit", "1")
                                .header(
                                        "Authorization",
                                        bearer(fixture.adminToken())
                                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].jobId")
                        .value(secondFailed.jobId()))
                .andExpect(jsonPath("$.data.items[0].linkId")
                        .value(secondFailed.linkId()))
                .andExpect(jsonPath("$.data.items[0].ownerUsername")
                        .value("owner-user"))
                .andExpect(jsonPath("$.data.items[0].sourceHost")
                        .value("developer.mozilla.org"))
                .andExpect(jsonPath("$.data.items[0].attemptCount").value(3))
                .andExpect(jsonPath("$.data.items[0].maxAttempts").value(3))
                .andExpect(jsonPath("$.data.items[0].errorCode")
                        .value("TIMEOUT"))
                .andExpect(jsonPath("$.data.items[0].originalUrl")
                        .doesNotExist())
                .andExpect(jsonPath("$.data.items[0].title").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].description")
                        .doesNotExist())
                .andExpect(jsonPath("$.data.items[0].errorMessage")
                        .doesNotExist())
                .andExpect(jsonPath("$.data.nextBeforeId")
                        .value(secondFailed.jobId()))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "SECOND-SECRET"
                                )
                        )
                ))
                .andReturn();

        Number nextBeforeId = JsonPath.read(
                firstPage.getResponse().getContentAsString(),
                "$.data.nextBeforeId"
        );

        mockMvc.perform(get("/api/v1/admin/capture-jobs/failed")
                        .param("limit", "1")
                        .param("beforeId", nextBeforeId.toString())
                        .header(
                                "Authorization",
                                bearer(fixture.adminToken())
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].jobId")
                        .value(fixture.oldFailedJobId()))
                .andExpect(jsonPath("$.data.nextBeforeId").value(nullValue()));
    }

    // [测试意图] 验证重试创建新任务、清理旧资产、保留所有者并写入持久审计。
    @Test
    void shouldRetryFailedJobTransactionally() throws Exception {
        Fixture fixture = createFixture();

        MvcResult retried = mockMvc.perform(
                        post("/api/v1/admin/capture-jobs/{jobId}/retry",
                                fixture.oldFailedJobId())
                                .header(
                                        "Authorization",
                                        bearer(fixture.adminToken())
                                ))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sourceJobId")
                        .value(fixture.oldFailedJobId()))
                .andExpect(jsonPath("$.data.job.status").value("PENDING"))
                .andExpect(jsonPath("$.data.job.attemptCount").value(0))
                .andReturn();
        Number newJobId = JsonPath.read(
                retried.getResponse().getContentAsString(),
                "$.data.job.id"
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM capture_job WHERE id = ?",
                String.class,
                fixture.oldFailedJobId()
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM capture_job WHERE id = ?",
                String.class,
                newJobId.longValue()
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM capture_job WHERE id = ?",
                Integer.class,
                newJobId.longValue()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT requested_by FROM capture_job WHERE id = ?",
                Long.class,
                newJobId.longValue()
        )).isEqualTo(fixture.ownerId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asset WHERE capture_job_id = ?",
                Integer.class,
                fixture.oldFailedJobId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asset_cleanup_job WHERE storage_path = ?",
                Integer.class,
                "admin-test/old-failed.html"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM admin_action_log
                WHERE admin_user_id = ?
                  AND action = 'RETRY_CAPTURE_JOB'
                  AND source_job_id = ?
                  AND created_job_id = ?
                """,
                Integer.class,
                fixture.adminId(),
                fixture.oldFailedJobId(),
                newJobId.longValue()
        )).isEqualTo(1);

        mockMvc.perform(post(
                                "/api/v1/admin/capture-jobs/{jobId}/retry",
                                fixture.oldFailedJobId()
                        )
                        .header(
                                "Authorization",
                                bearer(fixture.adminToken())
                        ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("CAPTURE_JOB_NOT_RETRYABLE"));
    }

    // [测试意图] 验证普通用户不能重试，管理员重试不存在的任务返回 404。
    @Test
    void shouldProtectRetryEndpoint() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(post(
                                "/api/v1/admin/capture-jobs/{jobId}/retry",
                                fixture.oldFailedJobId()
                        )
                        .header(
                                "Authorization",
                                bearer(fixture.ownerToken())
                        ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post(
                                "/api/v1/admin/capture-jobs/{jobId}/retry",
                                999999999L
                        )
                        .header(
                                "Authorization",
                                bearer(fixture.adminToken())
                        ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // [测试意图] 验证两个管理员并发重试同一任务时只有一个请求创建新任务。
    @Test
    void shouldSerializeConcurrentRetryRequests() throws Exception {
        Fixture fixture = createFixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            var retry = (java.util.concurrent.Callable<Integer>) () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post(
                                        "/api/v1/admin/capture-jobs/{jobId}/retry",
                                        fixture.oldFailedJobId()
                                )
                                .header(
                                        "Authorization",
                                        bearer(fixture.adminToken())
                                ))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            };
            List<Future<Integer>> futures = List.of(
                    executor.submit(retry),
                    executor.submit(retry)
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM capture_job WHERE link_id = ? AND id > ?",
                    Integer.class,
                    jdbcTemplate.queryForObject(
                            "SELECT link_id FROM capture_job WHERE id = ?",
                            Long.class,
                            fixture.oldFailedJobId()
                    ),
                    fixture.oldFailedJobId()
            )).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM admin_action_log WHERE source_job_id = ?",
                    Integer.class,
                    fixture.oldFailedJobId()
            )).isEqualTo(1);
        }
        finally {
            executor.shutdownNow();
        }
    }

    private Fixture createFixture() throws Exception {
        createUser("owner-user", "owner@example.com");
        createUser("admin-user", "admin@example.com");
        createUser("other-user", "other@example.com");
        jdbcTemplate.update(
                "UPDATE app_user SET role = 'ADMIN' WHERE username = ?",
                "admin-user"
        );

        String ownerToken = login("owner-user");
        String adminToken = login("admin-user");
        Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?",
                Long.class,
                "admin-user"
        );
        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?",
                Long.class,
                "owner-user"
        );

        CreatedLink oldFailed = createLink(
                ownerToken,
                "https://example.com/old-failed",
                "OLD-SECRET-TITLE",
                "OLD-SECRET-DESCRIPTION"
        );
        jdbcTemplate.update(
                "UPDATE capture_job SET status = 'FAILED', attempt_count = 3, "
                        + "finished_at = updated_at, error_code = 'DNS_ERROR', "
                        + "error_message = 'OLD-SECRET-ERROR' WHERE id = ?",
                oldFailed.jobId()
        );
        jdbcTemplate.update(
                "UPDATE link SET title = ?, description = ?, text_content = ? "
                        + "WHERE id = ?",
                "OLD-SECRET-TITLE",
                "OLD-SECRET-DESCRIPTION",
                "OLD-SECRET-CONTENT",
                oldFailed.linkId()
        );
        jdbcTemplate.update(
                """
                INSERT INTO asset (
                    link_id, capture_job_id, format, storage_path, mime_type,
                    file_size_bytes, sha256, created_at
                ) VALUES (?, ?, 'HTML', ?, 'text/html', 12, ?, CURRENT_TIMESTAMP(3))
                """,
                oldFailed.linkId(),
                oldFailed.jobId(),
                "admin-test/old-failed.html",
                "a".repeat(64)
        );

        CreatedLink succeeded = createLink(
                ownerToken,
                "https://example.com/succeeded",
                "Succeeded",
                "Succeeded description"
        );
        jdbcTemplate.update(
                "UPDATE capture_job SET status = 'SUCCEEDED', attempt_count = 1, "
                        + "finished_at = updated_at WHERE id = ?",
                succeeded.jobId()
        );

        createLink(
                ownerToken,
                "https://example.com/pending",
                "Pending",
                "Pending description"
        );

        return new Fixture(
                ownerToken,
                adminToken,
                adminId,
                ownerId,
                oldFailed.jobId()
        );
    }

    private void createUser(String username, String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "email": "%s",
                                  "password": "correct-horse"
                                }
                                """.formatted(username, email)))
                .andExpect(status().isCreated());
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "correct-horse"
                                }
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.data.token"
        );
    }

    private CreatedLink createLink(
            String token,
            String url,
            String title,
            String description
    ) throws Exception {
        MvcResult collections = mockMvc.perform(get("/api/v1/collections")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        Number collectionId = JsonPath.read(
                collections.getResponse().getContentAsString(),
                "$.data[0].id"
        );

        MvcResult created = mockMvc.perform(post("/api/v1/links")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "%s",
                                  "collectionId": %s,
                                  "title": "%s",
                                  "description": "%s",
                                  "tagIds": []
                                }
                                """.formatted(
                                url,
                                collectionId.longValue(),
                                title,
                                description
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        Number linkId = JsonPath.read(
                created.getResponse().getContentAsString(),
                "$.data.link.id"
        );
        Number jobId = JsonPath.read(
                created.getResponse().getContentAsString(),
                "$.data.job.id"
        );
        return new CreatedLink(
                linkId.longValue(),
                jobId.longValue(),
                collectionId.longValue()
        );
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record CreatedLink(long linkId, long jobId, long collectionId) {
    }

    private record Fixture(
            String ownerToken,
            String adminToken,
            Long adminId,
            Long ownerId,
            long oldFailedJobId
    ) {
    }
}
