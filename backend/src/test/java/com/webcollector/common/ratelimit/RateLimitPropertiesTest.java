package com.webcollector.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPropertiesTest {

    // [测试意图] 验证三类接口的默认额度和窗口与阶段卡公开契约完全一致。
    @Test
    void shouldExposeContractDefaults() {
        RateLimitProperties properties = RateLimitProperties.defaults();

        assertThat(properties.getLogin().getLimit()).isEqualTo(10);
        assertThat(properties.getLogin().getWindowSeconds()).isEqualTo(60);
        assertThat(properties.getRegister().getLimit()).isEqualTo(5);
        assertThat(properties.getRegister().getWindowSeconds()).isEqualTo(600);
        assertThat(properties.getCreateLink().getLimit()).isEqualTo(30);
        assertThat(properties.getCreateLink().getWindowSeconds()).isEqualTo(60);
        assertThat(properties.getTrustedProxyCidrs())
                .containsExactly("172.16.0.0/12");
    }

    // [测试意图] 验证每个策略都默认启用，部署方可以显式关闭。
    @Test
    void shouldEnableEveryPolicyByDefaultAndAllowDisabling() {
        RateLimitProperties properties = RateLimitProperties.defaults();

        assertThat(List.of(
                properties.getLogin().isEnabled(),
                properties.getRegister().isEnabled(),
                properties.getCreateLink().isEnabled()
        )).containsOnly(true);

        properties.getLogin().setEnabled(false);

        assertThat(properties.getLogin().isEnabled()).isFalse();
    }
}
