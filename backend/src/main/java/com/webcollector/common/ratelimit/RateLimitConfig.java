package com.webcollector.common.ratelimit;

import com.webcollector.auth.session.SessionTokenService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

/**
 * 限流的 Bean 装配和 MVC 拦截器注册。
 *
 * <p>只拦截三个明确的写接口，scope 仍由拦截器按路径固定选择。</p>
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {
    @Bean
    public RateLimitService rateLimitService(
            StringRedisTemplate redisTemplate,
            RateLimitProperties properties,
            Clock clock
    ) {
        return new RateLimitService(redisTemplate, properties, clock);
    }

    @Bean
    public ClientIpResolver clientIpResolver(RateLimitProperties properties) {
        return new ClientIpResolver(properties.getTrustedProxyCidrs());
    }

    @Bean
    public ClientRateLimitInterceptor clientRateLimitInterceptor(
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver,
            SessionTokenService sessionTokenService
    ) {
        return new ClientRateLimitInterceptor(
                rateLimitService,
                clientIpResolver,
                sessionTokenService
        );
    }

    @Bean
    public WebMvcConfigurer rateLimitWebMvcConfigurer(
            ClientRateLimitInterceptor interceptor
    ) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor)
                        .addPathPatterns(
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                "/api/v1/links"
                        );
            }
        };
    }
}
