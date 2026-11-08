package com.webcollector.common.ratelimit;

import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;

/**
 * 请求超过限流额度。
 *
 * <p>异常只暴露公开等待秒数，不携带 Redis key、当前计数或 subject。</p>
 */
public class RateLimitedException extends BusinessException {
    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
