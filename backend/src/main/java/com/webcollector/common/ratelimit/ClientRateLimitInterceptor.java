package com.webcollector.common.ratelimit;

import com.webcollector.auth.session.SessionTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 将公开 HTTP 接口固定映射到限流 scope 和 subject。
 *
 * <p>登录/注册使用客户端 IP，创建链接先校验登录再使用用户 ID。</p>
 */
public class ClientRateLimitInterceptor implements HandlerInterceptor {
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final SessionTokenService sessionTokenService;

    public ClientRateLimitInterceptor(
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver,
            SessionTokenService sessionTokenService
    ) {
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.sessionTokenService = sessionTokenService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();

        if ("/api/v1/auth/login".equals(path)) {
            rateLimitService.requireAllowed(
                    RateLimitScope.LOGIN,
                    clientIpResolver.resolve(request)
            );
        } else if ("/api/v1/auth/register".equals(path)) {
            rateLimitService.requireAllowed(
                    RateLimitScope.REGISTER,
                    clientIpResolver.resolve(request)
            );
        } else if ("/api/v1/links".equals(path)) {
            Long userId = sessionTokenService.requireLoginId();
            rateLimitService.requireAllowed(
                    RateLimitScope.CREATE_LINK,
                    userId.toString()
            );
        }

        return true;
    }
}
