package com.webcollector.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
class SearchFilterPaginationIntegrationTest {

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.registerProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM app_user");
    }

    // [测试意图] 验证全文、集合、任一标签和半开时间区间可组合过滤，且多标签链接只返回一次。
    @Test
    void shouldCombineFiltersAndDeduplicateTagMatches() throws Exception {
        Fixture owner = newUser("m62-filter-owner");
        long collection = owner.inboxId();
        long anotherCollection = createCollection(owner.token(), "Later");
        long tagA = createTag(owner.token(), "Java");
        long tagB = createTag(owner.token(), "Spring");

        long fromBoundary = saveLink(
                owner.token(),
                collection,
                "https://example.com/from-boundary",
                "shared from boundary",
                List.of(tagA, tagB)
        );
        long middle = saveLink(
                owner.token(),
                collection,
                "https://example.com/middle",
                "shared middle",
                List.of(tagB)
        );
        long toBoundary = saveLink(
                owner.token(),
                collection,
                "https://example.com/to-boundary",
                "shared to boundary",
                List.of(tagA)
        );
        saveLink(
                owner.token(),
                anotherCollection,
                "https://example.com/other-collection",
                "shared other collection",
                List.of(tagA)
        );
        setCreatedAt(fromBoundary, "2026-01-01T00:00:00Z");
        setCreatedAt(middle, "2026-01-02T00:00:00Z");
        setCreatedAt(toBoundary, "2026-01-03T00:00:00Z");

        Fixture foreignUser = newUser("m62-filter-foreign");
        long foreignTag = createTag(foreignUser.token(), "Foreign");
        long foreignLink = saveLink(
                foreignUser.token(),
                foreignUser.inboxId(),
                "https://foreign.example.com/shared",
                "shared foreign",
                List.of(foreignTag)
        );
        setCreatedAt(foreignLink, "2026-01-02T00:00:00Z");

        SearchResult result = search(
                owner.token(),
                "q", "shared",
                "collectionId", Long.toString(collection),
                "tagId", Long.toString(tagA),
                "tagId", Long.toString(tagB),
                "from", "2026-01-01T00:00:00Z",
                "to", "2026-01-03T00:00:00Z",
                "limit", "10"
        );

        assertThat(result.ids())
                .containsExactly(middle, fromBoundary)
                .doesNotHaveDuplicates()
                .doesNotContain(toBoundary, foreignLink);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    // [测试意图] 验证省略 limit 时默认最多返回 20 条，并正确给出下一页信号。
    @Test
    void shouldUseDefaultLimitOfTwenty() throws Exception {
        Fixture owner = newUser("m62-default-limit");
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            ids.add(saveLink(
                    owner.token(),
                    owner.inboxId(),
                    "https://example.com/default-" + index,
                    "default limit " + index,
                    List.of()
            ));
        }

        SearchResult result = search(owner.token());

        assertThat(result.ids()).hasSize(20);
        assertThat(result.ids().get(0)).isEqualTo(ids.get(20));
        assertThat(result.ids().get(19)).isEqualTo(ids.get(1));
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isNotBlank();
    }

    // [测试意图] 验证 keyset 连续翻页不重复，且游标对应记录删除后仍能继续查询。
    @Test
    void shouldContinueAfterCursorRowWasDeleted() throws Exception {
        Fixture owner = newUser("m62-cursor-delete");
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            ids.add(saveLink(
                    owner.token(),
                    owner.inboxId(),
                    "https://example.com/page-" + index,
                    "page " + index,
                    List.of()
            ));
        }

        SearchResult firstPage = search(
                owner.token(), "limit", "2"
        );
        assertThat(firstPage.ids())
                .containsExactly(ids.get(5), ids.get(4));
        assertThat(firstPage.hasMore()).isTrue();

        jdbcTemplate.update("DELETE FROM link WHERE id = ?", ids.get(4));

        SearchResult secondPage = search(
                owner.token(),
                "cursor", firstPage.nextCursor(),
                "limit", "2"
        );
        SearchResult thirdPage = search(
                owner.token(),
                "cursor", secondPage.nextCursor(),
                "limit", "2"
        );

        assertThat(secondPage.ids())
                .containsExactly(ids.get(3), ids.get(2));
        assertThat(thirdPage.ids())
                .containsExactly(ids.get(1), ids.get(0));
        assertThat(thirdPage.hasMore()).isFalse();
        assertThat(thirdPage.nextCursor()).isNull();
        assertThat(List.of(
                firstPage.ids().get(0),
                firstPage.ids().get(1),
                secondPage.ids().get(0),
                secondPage.ids().get(1),
                thirdPage.ids().get(0),
                thirdPage.ids().get(1)
        )).doesNotHaveDuplicates();
    }

    // [测试意图] 验证换查询条件或损坏游标会被拒绝，而不是返回无法解释的结果。
    @Test
    void shouldRejectInvalidOrMismatchedCursor() throws Exception {
        Fixture owner = newUser("m62-cursor-invalid");
        saveLink(
                owner.token(),
                owner.inboxId(),
                "https://example.com/shared-a",
                "shared alpha",
                List.of()
        );
        saveLink(
                owner.token(),
                owner.inboxId(),
                "https://example.com/shared-b",
                "shared beta",
                List.of()
        );
        SearchResult firstPage = search(
                owner.token(),
                "q", "shared",
                "limit", "1"
        );

        assertBadRequest(searchExpectFailure(
                owner.token(),
                "q", "different",
                "cursor", firstPage.nextCursor(),
                "limit", "1"
        ), "INVALID_REQUEST");
        assertBadRequest(searchExpectFailure(
                owner.token(),
                "cursor", "%%%",
                "limit", "1"
        ), "INVALID_REQUEST");
        assertBadRequest(searchExpectFailure(
                owner.token(),
                "cursor", encoded("""
                        {"version":99,"lastId":1,"fingerprint":"x"}
                        """),
                "limit", "1"
        ), "INVALID_REQUEST");
        assertBadRequest(searchExpectFailure(
                owner.token(),
                "cursor", encoded("""
                        {"version":1,"lastId":1}
                        """),
                "limit", "1"
        ), "INVALID_REQUEST");
    }

    // [测试意图] 验证参数边界和非法时间范围返回稳定 400。
    @Test
    void shouldValidateLimitTimeAndQueryLength() throws Exception {
        Fixture owner = newUser("m62-validation");

        assertBadRequest(searchExpectFailure(
                owner.token(), "limit", "0"
        ), "VALIDATION_ERROR");
        assertBadRequest(searchExpectFailure(
                owner.token(), "limit", "101"
        ), "VALIDATION_ERROR");
        assertBadRequest(searchExpectFailure(
                owner.token(),
                "from", "2026-01-02T00:00:00Z",
                "to", "2026-01-02T00:00:00Z"
        ), "INVALID_REQUEST");
        assertBadRequest(searchExpectFailure(
                owner.token(), "q", "a".repeat(201)
        ), "VALIDATION_ERROR");

        SearchResult blankQuery = search(owner.token(), "q", "   ");
        SearchResult absentQuery = search(owner.token());
        assertThat(blankQuery.ids()).isEmpty();
        assertThat(absentQuery.ids()).isEmpty();
    }

    // [测试意图] 验证其他用户的集合和标签不能让当前用户读到任何越权链接。
    @Test
    void shouldNotLeakDataThroughForeignCollectionOrTagIds() throws Exception {
        Fixture alice = newUser("m62-alice");
        Fixture bob = newUser("m62-bob");
        long aliceTag = createTag(alice.token(), "Alice");
        long bobTag = createTag(bob.token(), "Bob");
        long aliceLink = saveLink(
                alice.token(),
                alice.inboxId(),
                "https://alice.example.com/shared",
                "shared alice",
                List.of(aliceTag)
        );
        saveLink(
                bob.token(),
                bob.inboxId(),
                "https://bob.example.com/shared",
                "shared bob",
                List.of(bobTag)
        );

        SearchResult ownSearch = search(
                alice.token(),
                "q", "shared",
                "limit", "10"
        );
        SearchResult foreignCollection = search(
                alice.token(),
                "q", "shared",
                "collectionId", Long.toString(bob.inboxId()),
                "limit", "10"
        );
        SearchResult foreignTag = search(
                alice.token(),
                "q", "shared",
                "tagId", Long.toString(bobTag),
                "limit", "10"
        );

        assertThat(ownSearch.ids()).containsExactly(aliceLink);
        assertThat(foreignCollection.ids()).isEmpty();
        assertThat(foreignTag.ids()).isEmpty();
    }

    // [测试意图] 验证 V15 在真实 MySQL 8.4 上创建了用户与集合分页索引。
    @Test
    void shouldCreateAndUseV15SearchIndexes() throws Exception {
        Fixture owner = newUser("m62-index-plan");
        List<String> indexNames = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT index_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'link'
                  AND index_name IN (
                      'idx_link_user_id_desc',
                      'idx_link_user_collection_id_desc'
                  )
                ORDER BY index_name
                """,
                String.class
        );

        assertThat(indexNames).containsExactly(
                "idx_link_user_collection_id_desc",
                "idx_link_user_id_desc"
        );

        List<Map<String, Object>> plan = jdbcTemplate.queryForList(
                """
                EXPLAIN SELECT id
                FROM link
                WHERE user_id = ?
                ORDER BY id DESC
                LIMIT 20
                """,
                owner.userId()
        );
        assertThat(plan).anySatisfy(row -> {
            String possibleKeys = String.valueOf(row.get("possible_keys"));
            assertThat(possibleKeys)
                    .contains("idx_link_user_id_desc")
                    .contains("idx_link_user_collection_id_desc");
        });
    }

    private SearchResult search(String token, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/search")
                .header("Authorization", "Bearer " + token);
        for (int index = 0; index + 1 < params.length; index += 2) {
            request.param(params[index], params[index + 1]);
        }

        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(
                result.getResponse().getContentAsString()
        ).path("data");

        List<Long> ids = new ArrayList<>();
        data.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        JsonNode cursor = data.get("nextCursor");
        return new SearchResult(
                List.copyOf(ids),
                cursor == null || cursor.isNull() ? null : cursor.asText(),
                data.path("hasMore").asBoolean()
        );
    }

    private MvcResult searchExpectFailure(String token, String... params)
            throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/search")
                .header("Authorization", "Bearer " + token);
        for (int index = 0; index + 1 < params.length; index += 2) {
            request.param(params[index], params[index + 1]);
        }
        return mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andReturn();
    }

    private void assertBadRequest(MvcResult result, String errorCode)
            throws Exception {
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsString()
        );
        assertThat(body.path("code").asText()).isEqualTo(errorCode);
    }

    private Fixture newUser(String username) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", username,
                                "email", username + "@example.com",
                                "password", "correct-horse"
                        ))))
                .andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", username,
                                "password", "correct-horse"
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(body).path("data").path("token").asText();
        String collections = mockMvc.perform(get("/api/v1/collections")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long inboxId = objectMapper.readTree(collections)
                .path("data")
                .path(0)
                .path("id")
                .asLong();
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?",
                Long.class,
                username
        );
        return new Fixture(token, userId, inboxId);
    }

    private long createCollection(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/collections")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", name,
                                "description", ""
                        ))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private long createTag(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/tags")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private long saveLink(
            String token,
            long collectionId,
            String url,
            String title,
            List<Long> tagIds
    ) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("collectionId", collectionId);
        request.put("url", url);
        request.put("title", title);
        request.put("description", "");
        request.put("tagIds", tagIds);

        String body = mockMvc.perform(post("/api/v1/links")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body)
                .path("data")
                .path("link")
                .path("id")
                .asLong();
    }

    private void setCreatedAt(long linkId, String instant) {
        LocalDateTime createdAt = LocalDateTime.ofInstant(
                java.time.Instant.parse(instant),
                java.time.ZoneOffset.UTC
        );
        jdbcTemplate.update(
                "UPDATE link SET created_at = ?, updated_at = ? WHERE id = ?",
                createdAt,
                createdAt,
                linkId
        );
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String encoded(String json) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private record Fixture(String token, Long userId, long inboxId) {
    }

    private record SearchResult(
            List<Long> ids,
            String nextCursor,
            boolean hasMore
    ) {
    }
}
