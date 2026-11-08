package com.webcollector.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 三类受保护接口及可信代理的配置契约。
 *
 * <p>生产实现需要提供阶段卡约定的默认值，并允许环境变量覆盖。</p>
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {
    private Policy login = new Policy(true, 10, 60);
    private Policy register = new Policy(true, 5, 600);
    private Policy createLink = new Policy(true, 30, 60);
    private List<String> trustedProxyCidrs =
            new ArrayList<>(List.of("172.16.0.0/12"));

    public static RateLimitProperties defaults() {
        return new RateLimitProperties();
    }

    public Policy getLogin() {
        return login;
    }

    public void setLogin(Policy login) {
        this.login = login;
    }

    public Policy getRegister() {
        return register;
    }

    public void setRegister(Policy register) {
        this.register = register;
    }

    public Policy getCreateLink() {
        return createLink;
    }

    public void setCreateLink(Policy createLink) {
        this.createLink = createLink;
    }

    public List<String> getTrustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs == null
                ? new ArrayList<>()
                : new ArrayList<>(trustedProxyCidrs);
    }

    public static class Policy {
        private boolean enabled;
        private int limit;
        private int windowSeconds;

        public Policy() {
        }

        public Policy(boolean enabled, int limit, int windowSeconds) {
            this.enabled = enabled;
            this.limit = limit;
            this.windowSeconds = windowSeconds;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }
}
