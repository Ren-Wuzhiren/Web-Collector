package com.webcollector.common.ratelimit;

import com.jayway.jsonpath.JsonPath;
import com.webcollector.auth.dto.RegisterRequest;
import com.webcollector.auth.service.AuthService;
import com.webcollector.common.web.RequestIdFilter;
import com.webcollector.support.SharedTestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RateLimitEndpointIntegrationTest.FixedClockConfig.class)
class RateLimitEndpointIntegrationTest {
    private static final Instant FIXED_INSTANT =
            Instant.parse("2026-11-08T08:00:30Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitProperties properties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AuthService authService;

    @DynamicPropertySource
    static void registerContainerProperties(
            DynamicPropertyRegistry registry
    ) {
        SharedTestContainers.registerProperties(registry);
    }

    @BeforeEach
    void setUp() {
        enableContractDefaults();
        clearRateLimitKeys();
    }

    @AfterEach
    void tearDown() {
        clearRateLimitKeys();
    }

    // [测试意图] 验证登录接口真实第 11 次请求返回 429、Retry-After、统一错误码和 requestId。
    @Test
    void shouldRejectEleventhLoginRequest() throws Exception {
        for (int index = 1; index <= 10; index++) {
            mockMvc.perform(invalidRequest(
                            "/api/v1/auth/login",
                            "198.51.100.21",
                            "req-login-" + index
                    ))
                    .andExpect(status().isBadRequest());
        }

        MvcResult result = mockMvc.perform(invalidRequest(
                        "/api/v1/auth/login",
                        "198.51.100.21",
                        "req-login-011"
                ))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.requestId").value("req-login-011"))
                .andReturn();

        assertThat(Long.parseLong(
                result.getResponse().getHeader("Retry-After")
        )).isBetween(1L, 60L);
    }

    // [测试意图] 验证注册接口真实第 6 次请求返回 429，且另一个客户端 IP 仍可继续访问。
    @Test
    void shouldRejectSixthRegistrationRequestPerIp() throws Exception {
        for (int index = 1; index <= 5; index++) {
            mockMvc.perform(invalidRequest(
                            "/api/v1/auth/register",
                            "198.51.100.22",
                            "req-register-" + index
                    ))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(invalidRequest(
                        "/api/v1/auth/register",
                        "198.51.100.22",
                        "req-register-006"
                ))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        mockMvc.perform(invalidRequest(
                        "/api/v1/auth/register",
                        "198.51.100.23",
                        "req-register-other-ip"
                ))
                .andExpect(status().isBadRequest());
    }

    // [测试意图] 验证创建链接真实第 31 次请求返回 429，并先通过真实登录确定用户 subject。
    @Test
    void shouldRejectThirtyFirstLinkCreationForAuthenticatedUser() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12);
        String username = "rl" + suffix;
        String password = "correct-horse";
        authService.register(new RegisterRequest(
                username,
                username + "@example.com",
                password
        ));
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(remoteAddress("198.51.100.25"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s"
                                }
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andReturn();
        String token = JsonPath.read(
                loginResult.getResponse().getContentAsString(),
                "$.data.token"
        );
        String authorization = "Bearer " + token;

        for (int index = 1; index <= 30; index++) {
            mockMvc.perform(invalidRequest(
                            "/api/v1/links",
                            "198.51.100.24",
                            "req-link-" + index
                    ).header("Authorization", authorization))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(invalidRequest(
                        "/api/v1/links",
                        "198.51.100.24",
                        "req-link-031"
                ).header("Authorization", authorization))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.requestId").value("req-link-031"));
    }

    private MockHttpServletRequestBuilder invalidRequest(
            String path,
            String remoteAddress,
            String requestId
    ) {
        return post(path)
                .with(remoteAddress(remoteAddress))
                .header(RequestIdFilter.REQUEST_ID_HEADER, requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    private RequestPostProcessor remoteAddress(String remoteAddress) {
        return request -> {
            request.setRemoteAddr(remoteAddress);
            return request;
        };
    }

    private void enableContractDefaults() {
        properties.getLogin().setEnabled(true);
        properties.getLogin().setLimit(10);
        properties.getLogin().setWindowSeconds(60);
        properties.getRegister().setEnabled(true);
        properties.getRegister().setLimit(5);
        properties.getRegister().setWindowSeconds(600);
        properties.getCreateLink().setEnabled(true);
        properties.getCreateLink().setLimit(30);
        properties.getCreateLink().setWindowSeconds(60);
    }

    private void clearRateLimitKeys() {
        Set<String> keys = redisTemplate.keys("webcollector:rate-limit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedRateLimitClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }
}
