package com.webcollector.common.ratelimit;

/**
 * 服务端固定选择的限流策略范围。
 *
 * <p>scope 不能由客户端参数决定，否则调用者可以切换计数桶绕过限制。</p>
 */
public enum RateLimitScope {
    LOGIN,
    REGISTER,
    CREATE_LINK
}
