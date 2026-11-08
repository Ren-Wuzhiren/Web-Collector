package com.webcollector.common.ratelimit;

import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.support.SharedTestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RateLimitServiceIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private RateLimitProperties properties;
    private RateLimitService service;

    @DynamicPropertySource
    static void registerContainerProperties(
            DynamicPropertyRegistry registry
    ) {
        SharedTestContainers.registerProperties(registry);
    }

    @BeforeEach
    void setUp() {
        clearRateLimitKeys();
        properties = RateLimitProperties.defaults();
        properties.getLogin().setLimit(2);
        properties.getLogin().setWindowSeconds(60);
        service = new RateLimitService(
                redisTemplate,
                properties,
                Clock.systemUTC()
        );
    }

    @AfterEach
    void tearDown() {
        clearRateLimitKeys();
    }

    // [测试意图] 验证同一 scope/subject 达到上限后抛 429 业务异常并给出正数等待秒数。
    @Test
    void shouldRejectRequestAfterLimitAndExposeRetryAfter() {
        service.requireAllowed(RateLimitScope.LOGIN, "203.0.113.7");
        service.requireAllowed(RateLimitScope.LOGIN, "203.0.113.7");

        assertThatThrownBy(() -> service.requireAllowed(
                RateLimitScope.LOGIN,
                "203.0.113.7"
        ))
                .isInstanceOfSatisfying(RateLimitedException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(exception.getRetryAfterSeconds())
                            .isBetween(1L, 60L);
                });
    }

    // [测试意图] 验证不同 subject 和不同 scope 各自拥有独立计数额度。
    @Test
    void shouldIsolateSubjectsAndScopes() {
        service.requireAllowed(RateLimitScope.LOGIN, "203.0.113.7");
        service.requireAllowed(RateLimitScope.LOGIN, "203.0.113.7");

        service.requireAllowed(RateLimitScope.LOGIN, "203.0.113.8");
        service.requireAllowed(RateLimitScope.REGISTER, "203.0.113.7");
    }

    // [测试意图] 验证 Redis key 带 TTL，不会因非原子 EXPIRE 留下永久计数键。
    @Test
    void shouldSetTtlOnCounterKey() {
        service.requireAllowed(RateLimitScope.LOGIN, "ttl-subject");

        Set<String> keys = redisTemplate.keys("webcollector:rate-limit:LOGIN:ttl-subject:*");

        assertThat(keys).hasSize(1);
        assertThat(redisTemplate.getExpire(keys.iterator().next()))
                .isBetween(1L, 60L);
    }

    // [测试意图] 验证固定窗口过期后同一 subject 可以重新获得完整额度。
    @Test
    void shouldAllowAgainAfterFixedWindowExpires() throws InterruptedException {
        properties.getLogin().setLimit(1);
        properties.getLogin().setWindowSeconds(1);

        service.requireAllowed(RateLimitScope.LOGIN, "expiring-subject");
        assertThatThrownBy(() -> service.requireAllowed(
                RateLimitScope.LOGIN,
                "expiring-subject"
        )).isInstanceOf(RateLimitedException.class);

        Thread.sleep(1_100);

        service.requireAllowed(RateLimitScope.LOGIN, "expiring-subject");
    }

    // [测试意图] 验证并发请求只允许恰好 limit 次，证明 Redis 计数没有被竞态绕过。
    @Test
    void shouldEnforceLimitUnderConcurrency() throws Exception {
        properties.getLogin().setLimit(5);
        properties.getLogin().setWindowSeconds(60);
        int requests = 20;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests);

        try {
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int index = 0; index < requests; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    try {
                        service.requireAllowed(
                                RateLimitScope.LOGIN,
                                "concurrent-subject"
                        );
                        return true;
                    } catch (RateLimitedException exception) {
                        return false;
                    }
                }));
            }

            ready.await();
            start.countDown();

            long allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    allowed++;
                }
            }

            assertThat(allowed).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    // [测试意图] 验证 Redis 故障时失败关闭为 503，不能静默放行受保护请求。
    @Test
    void shouldFailClosedWhenRedisIsUnavailable() {
        StringRedisTemplate unavailable = new StringRedisTemplate() {
            @Override
            public <T> T execute(
                    RedisScript<T> script,
                    List<String> keys,
                    Object... args
            ) {
                throw new RedisConnectionFailureException("redis down");
            }
        };
        RateLimitService failingService = new RateLimitService(
                unavailable,
                RateLimitProperties.defaults(),
                Clock.systemUTC()
        );

        assertThatThrownBy(() -> failingService.requireAllowed(
                RateLimitScope.LOGIN,
                "203.0.113.7"
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE)
                );
    }

    // [测试意图] 验证策略关闭后完全绕过 Redis，不读取也不写入限流状态。
    @Test
    void shouldSkipRedisWhenPolicyIsDisabled() {
        properties.getLogin().setEnabled(false);
        StringRedisTemplate unused = org.mockito.Mockito.mock(
                StringRedisTemplate.class
        );
        RateLimitService disabledService = new RateLimitService(
                unused,
                properties,
                Clock.systemUTC()
        );

        disabledService.requireAllowed(RateLimitScope.LOGIN, "203.0.113.7");

        org.mockito.Mockito.verifyNoInteractions(unused);
    }

    private void clearRateLimitKeys() {
        Set<String> keys = redisTemplate.keys("webcollector:rate-limit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
