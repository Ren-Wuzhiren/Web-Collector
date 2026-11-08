package com.webcollector.common.ratelimit;

import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 固定窗口限流服务契约。
 *
 * <p>生产实现需要使用 Redis Lua 原子完成计数与 TTL，并在 Redis
 * 不可用时失败关闭为 {@code SERVICE_UNAVAILABLE}。</p>
 */
public class RateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final String KEY_PREFIX = "webcollector:rate-limit:";
    private static final DefaultRedisScript<List> INCREMENT_SCRIPT =
            new DefaultRedisScript<>("""
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                      redis.call('EXPIRE', KEYS[1], ARGV[1])
                    end
                    local ttl = redis.call('TTL', KEYS[1])
                    return {count, ttl}
                    """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final Clock clock;

    public RateLimitService(
            StringRedisTemplate redisTemplate,
            RateLimitProperties properties,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    public void requireAllowed(RateLimitScope scope, String subject) {
        RateLimitProperties.Policy policy = policyFor(scope);

        if (!policy.isEnabled()) {
            return;
        }

        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("限流 subject 不能为空");
        }

        if (policy.getLimit() <= 0 || policy.getWindowSeconds() <= 0) {
            log.error("Invalid rate limit policy. scope={}", scope);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }

        Instant now = clock.instant();
        long nowSeconds = now.getEpochSecond();
        long windowSeconds = policy.getWindowSeconds();
        long windowStart = nowSeconds - Math.floorMod(nowSeconds, windowSeconds);
        String key = KEY_PREFIX
                + scope.name()
                + ":"
                + subject
                + ":"
                + windowStart;

        List<?> result;

        try {
            result = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(key),
                    Long.toString(windowSeconds)
            );
        } catch (DataAccessException exception) {
            log.warn(
                    "Rate limit Redis unavailable. scope={} requestId={}",
                    scope,
                    com.webcollector.common.web.RequestIdContext.get(),
                    exception
            );
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }

        if (result == null || result.size() < 2) {
            log.error("Rate limit Redis script returned invalid result. scope={}", scope);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }

        long count = toLong(result.get(0));

        if (count > policy.getLimit()) {
            long remaining = windowStart + windowSeconds - nowSeconds;
            throw new RateLimitedException(Math.max(1L, remaining));
        }
    }

    private RateLimitProperties.Policy policyFor(RateLimitScope scope) {
        return switch (scope) {
            case LOGIN -> properties.getLogin();
            case REGISTER -> properties.getRegister();
            case CREATE_LINK -> properties.getCreateLink();
        };
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            log.error("Rate limit Redis script returned non-numeric count");
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
