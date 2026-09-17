package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 商品搜索优化配置（批次 5.4.4）。
 *
 * <p>两块能力各带独立开关，便于出问题时<b>单独关掉</b>而不必回滚代码：</p>
 * <ul>
 *   <li>{@code search.cache.*} —— 结果缓存（Cache-Aside，TTL 60 秒 + 抖动）；</li>
 *   <li>{@code search.circuit-breaker.*} —— 超时熔断（500ms 超时，连续 5 次触发，熔断 30 秒）。</li>
 * </ul>
 *
 * <p><b>命名说明</b>：本项目其余业务配置都在 {@code app.*} 前缀下（app.email / app.order /
 * app.task …），本类按批次需求使用顶层 {@code search.*}。若要与既有约定统一，把这里与
 * application.yml 的键一起改成 {@code app.search.*} 即可（改前缀一处即可，无其他代码依赖）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "search")
public class SearchProperties {

    /** 结果缓存配置。 */
    private Cache cache = new Cache();

    /** 熔断配置。 */
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    @Data
    public static class Cache {

        /** 是否启用搜索结果缓存（关掉则每次都直达 DB，用于排查"结果不更新"类问题）。 */
        private boolean enabled = true;

        /** 缓存有效期（秒）：第 4 章要求的 60 秒。 */
        private long ttlSeconds = 60L;

        /**
         * TTL 随机抖动上限（秒）。
         *
         * <p>第 4 章只要求"结果缓存 60 秒"。加抖动的理由是避免<b>缓存雪崩</b>：
         * 同一批热门关键字往往在同一秒被写入，若 TTL 完全一致，它们会在同一秒集体失效，
         * 把并发的读请求同时打到 DB。0 表示不加抖动。</p>
         */
        private long ttlJitterSeconds = 10L;
    }

    @Data
    public static class CircuitBreaker {

        /** 是否启用超时熔断（关掉则同步直查 DB，不做 500ms 超时隔离）。 */
        private boolean enabled = true;

        /** 单次搜索的超时上限（毫秒）：第 4 章要求的 500ms。 */
        private long timeoutMs = 500L;

        /** 连续超时多少次触发熔断（成功一次即清零）。 */
        private int failureThreshold = 5;

        /** 熔断打开后的持续时长（毫秒）。 */
        private long openMillis = 30_000L;
    }
}
