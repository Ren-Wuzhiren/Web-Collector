package com.webcollector.link;

import com.jayway.jsonpath.JsonPath;
import com.webcollector.support.SharedTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "app.auth.registration-enabled=true"
)
@AutoConfigureMockMvc
class ReadableLinkIntegrationTest {
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
        jdbcTemplate.update("DELETE FROM app_user");
    }

    // [测试意图] 验证本人链接通过真实 HTTP、MySQL 和 Redis 会话读回完整元数据与正文。
    @Test
    void shouldReadOwnedContentThroughHttp() throws Exception {
        String token = registerAndLogin("readable-owner");
        long linkId = saveLink(
                token,
                defaultInboxId(token),
                "https://example.com/readable-owner"
        );
        jdbcTemplate.update(
                "UPDATE link SET text_content = ? WHERE id = ?",
                "第一段\n\n第二段",
                linkId
        );

        mockMvc.perform(get("/api/v1/links/" + linkId + "/readable")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.data.link.id").value(linkId))
                .andExpect(jsonPath("$.data.link.originalUrl")
                        .value("https://example.com/readable-owner"))
                .andExpect(jsonPath("$.data.textContent").value("第一段\n\n第二段"))
                .andExpect(jsonPath("$.data.link.userId").doesNotExist())
                .andExpect(jsonPath("$.data.link.urlHash").doesNotExist());
    }

    // [测试意图] 验证其他用户即使知道 linkId 也只能得到统一 404，原用户仍能读取。
    @Test
    void shouldIsolateReadableContentByOwner() throws Exception {
        String aliceToken = registerAndLogin("readable-alice");
        String bobToken = registerAndLogin("readable-bob");
        long bobLinkId = saveLink(
                bobToken,
                defaultInboxId(bobToken),
                "https://example.com/bob-secret"
        );
        jdbcTemplate.update(
                "UPDATE link SET text_content = ? WHERE id = ?",
                "Bob 的私人正文",
                bobLinkId
        );

        mockMvc.perform(get("/api/v1/links/" + bobLinkId + "/readable")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/links/" + bobLinkId + "/readable")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.textContent").value("Bob 的私人正文"));
    }

    // [测试意图] 验证本人链接正文为 null 或全空白时返回“正文尚未生成”。
    @Test
    void shouldRejectMissingOrBlankContent() throws Exception {
        String token = registerAndLogin("readable-blank");
        long linkId = saveLink(
                token,
                defaultInboxId(token),
                "https://example.com/readable-blank"
        );

        assertReadableContentNotFound(token, linkId);

        jdbcTemplate.update(
                "UPDATE link SET text_content = ? WHERE id = ?",
                " \n\t ",
                linkId
        );
        assertReadableContentNotFound(token, linkId);
    }

    // [测试意图] 验证匿名请求在业务读取前被统一认证边界拒绝。
    @Test
    void shouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/links/42/readable"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    private void assertReadableContentNotFound(
            String token,
            long linkId
    ) throws Exception {
        mockMvc.perform(get("/api/v1/links/" + linkId + "/readable")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("正文尚未生成"));
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
                                  "description": "",
                                  "tagIds": []
                                }
                                """.formatted(url, collectionId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Number id = JsonPath.read(body, "$.data.link.id");
        return id.longValue();
    }
}
