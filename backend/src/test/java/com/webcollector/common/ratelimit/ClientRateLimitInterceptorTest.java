package com.webcollector.common.ratelimit;

import com.webcollector.auth.session.SessionTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientRateLimitInterceptorTest {

    private RateLimitService rateLimitService;
    private ClientIpResolver clientIpResolver;
    private SessionTokenService sessionTokenService;
    private ClientRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        clientIpResolver = mock(ClientIpResolver.class);
        sessionTokenService = mock(SessionTokenService.class);
        interceptor = new ClientRateLimitInterceptor(
                rateLimitService,
                clientIpResolver,
                sessionTokenService
        );
    }

    // [测试意图] 验证登录请求固定使用 LOGIN scope 和解析后的客户端 IP。
    @Test
    void shouldRateLimitLoginByClientIp() throws Exception {
        MockHttpServletRequest request = request(
                "POST",
                "/api/v1/auth/login"
        );
        when(clientIpResolver.resolve(request)).thenReturn("203.0.113.7");

        assertThat(interceptor.preHandle(
                request,
                new MockHttpServletResponse(),
                new Object()
        )).isTrue();

        verify(rateLimitService).requireAllowed(
                RateLimitScope.LOGIN,
                "203.0.113.7"
        );
    }

    // [测试意图] 验证注册请求固定使用 REGISTER scope 和解析后的客户端 IP。
    @Test
    void shouldRateLimitRegistrationByClientIp() throws Exception {
        MockHttpServletRequest request = request(
                "POST",
                "/api/v1/auth/register"
        );
        when(clientIpResolver.resolve(request)).thenReturn("203.0.113.8");

        interceptor.preHandle(
                request,
                new MockHttpServletResponse(),
                new Object()
        );

        verify(rateLimitService).requireAllowed(
                RateLimitScope.REGISTER,
                "203.0.113.8"
        );
    }

    // [测试意图] 验证创建链接先要求登录，再使用用户 ID 而不是 IP 作为 subject。
    @Test
    void shouldRateLimitLinkCreationByAuthenticatedUser() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/v1/links");
        when(sessionTokenService.requireLoginId()).thenReturn(42L);

        interceptor.preHandle(
                request,
                new MockHttpServletResponse(),
                new Object()
        );

        verify(rateLimitService).requireAllowed(
                RateLimitScope.CREATE_LINK,
                "42"
        );
        verifyNoInteractions(clientIpResolver);
    }

    // [测试意图] 验证非目标方法和接口不访问限流服务。
    @Test
    void shouldIgnoreUnprotectedRequests() throws Exception {
        interceptor.preHandle(
                request("GET", "/api/v1/links"),
                new MockHttpServletResponse(),
                new Object()
        );
        interceptor.preHandle(
                request("POST", "/api/v1/collections"),
                new MockHttpServletResponse(),
                new Object()
        );

        verifyNoInteractions(
                rateLimitService,
                clientIpResolver,
                sessionTokenService
        );
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
