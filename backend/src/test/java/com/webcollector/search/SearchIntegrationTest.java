package com.webcollector.search;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "app.auth.registration-enabled=true"
)
@AutoConfigureMockMvc
class SearchIntegrationTest {

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

    // [测试意图] 验证中文标题、正文和英文标题可通过 ngram 全文检索命中。
    @Test
    void shouldSearchChineseAndEnglishContent() throws Exception {
        String token = registerAndLogin("search-owner");
        long collectionId = defaultInboxId(token);
        long chineseId = saveLink(
                token,
                collectionId,
                "https://example.com/chinese",
                "中文笔记",
                "数据库资料"
        );
        long englishId = saveLink(
                token,
                collectionId,
                "https://example.com/english",
                "Spring Search Guide",
                "English full text"
        );
        jdbcTemplate.update(
                "UPDATE link SET text_content = ? WHERE id = ?",
                "正文包含全文检索与倒排索引",
                chineseId
        );
        jdbcTemplate.update(
                "UPDATE link SET text_content = ? WHERE id = ?",
                "searchable stored body",
                englishId
        );

        assertThat(searchIds(token, "全文检索")).containsExactly(chineseId);
        assertThat(searchIds(token, "Search")).containsExactly(englishId);
        assertThat(searchIds(token, "完全不存在")).isEmpty();

        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?",
                Long.class,
                "search-owner"
        );
        List<Map<String, Object>> plan = jdbcTemplate.queryForList(
                """
                EXPLAIN SELECT id
                FROM link
                WHERE user_id = ?
                  AND MATCH(title, description, text_content)
                      AGAINST (? IN NATURAL LANGUAGE MODE)
                """,
                ownerId,
                "全文检索"
        );
        assertThat(plan)
                .anySatisfy(row -> assertThat(String.valueOf(row.get("key")))
                        .isEqualTo("ft_link_content"));
    }

    // [测试意图] 验证相同关键词属于两个用户时，搜索结果严格按当前会话隔离。
    @Test
    void shouldSearchOnlyCurrentUsersLinks() throws Exception {
        String aliceToken = registerAndLogin("search-alice");
        String bobToken = registerAndLogin("search-bob");
        long aliceId = saveLink(
                aliceToken,
                defaultInboxId(aliceToken),
                "https://alice.example.com/shared",
                "Alice Search",
                "shared keyword"
        );
        long bobId = saveLink(
                bobToken,
                defaultInboxId(bobToken),
                "https://bob.example.com/shared",
                "Bob Search",
                "shared keyword"
        );

        assertThat(searchIds(aliceToken, "shared")).containsExactly(aliceId);
        assertThat(searchIds(bobToken, "shared")).containsExactly(bobId);

        mockMvc.perform(get("/api/v1/search?q=shared")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("Alice Search"));
    }

    // [测试意图] 验证空白、超长搜索词和未登录请求返回稳定错误响应。
    @Test
    void shouldValidateSearchQueryAndAuthentication() throws Exception {
        String token = registerAndLogin("search-validation");
        String tooLong = "a".repeat(201);

        mockMvc.perform(get("/api/v1/search")
                        .param("q", "   ")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/search")
                        .param("q", tooLong)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/search").param("q", "spring"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    private List<Long> searchIds(String token, String query) throws Exception {
        String body = mockMvc.perform(get("/api/v1/search")
                        .param("q", query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Number> ids = JsonPath.read(body, "$.data[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private long saveLink(
            String token,
            long collectionId,
            String url,
            String title,
            String description
    ) throws Exception {
        String body = mockMvc.perform(post("/api/v1/links")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "collectionId": %d,
                                  "url": "%s",
                                  "title": "%s",
                                  "description": "%s",
                                  "tagIds": []
                                }
                                """.formatted(collectionId, url, title, description)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Number id = JsonPath.read(body, "$.data.link.id");
        return id.longValue();
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
}
