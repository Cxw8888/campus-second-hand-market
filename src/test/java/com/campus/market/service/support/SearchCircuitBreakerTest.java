package com.campus.market.service.support;

import com.campus.market.config.properties.SearchProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索熔断器单测（批次 5.4.4）。
 *
 * <p>熔断的关键行为都与"时间"有关（30 秒后自动恢复），因此这里注入<b>假时钟</b>，
 * 用确定性的方式验证窗口到期，而不是 {@code Thread.sleep(30_000)} 把测试拖成半分钟。</p>
 */
class SearchCircuitBreakerTest {

    /** 可手动推进的假时钟 */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private SearchCircuitBreaker breaker(int threshold, long openMillis) {
        SearchProperties properties = new SearchProperties();
        properties.getCircuitBreaker().setFailureThreshold(threshold);
        properties.getCircuitBreaker().setOpenMillis(openMillis);
        SearchCircuitBreaker circuitBreaker = new SearchCircuitBreaker(properties);
        circuitBreaker.setClock(now::get);
        return circuitBreaker;
    }

    @Test
    @DisplayName("连续超时未达阈值 → 闸门保持关闭")
    void shouldStayClosedBelowThreshold() {
        SearchCircuitBreaker breaker = breaker(5, 30_000L);

        for (int i = 0; i < 4; i++) {
            assertThat(breaker.recordTimeout()).as("第 %d 次超时不应开闸", i + 1).isFalse();
        }
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.consecutiveTimeouts()).isEqualTo(4);
    }

    @Test
    @DisplayName("连续超时达阈值（5 次）→ 开闸")
    void shouldOpenAtThreshold() {
        SearchCircuitBreaker breaker = breaker(5, 30_000L);

        for (int i = 0; i < 4; i++) {
            breaker.recordTimeout();
        }
        assertThat(breaker.recordTimeout()).as("第 5 次超时应开闸").isTrue();
        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    @DisplayName("闸门只在窗口内打开：29.9 秒仍开、30 秒后自动恢复")
    void shouldRecoverAfterOpenWindow() {
        SearchCircuitBreaker breaker = breaker(5, 30_000L);
        for (int i = 0; i < 5; i++) {
            breaker.recordTimeout();
        }
        assertThat(breaker.isOpen()).isTrue();

        now.addAndGet(29_900L);
        assertThat(breaker.isOpen()).as("窗口内（29.9 秒）仍应熔断").isTrue();

        now.addAndGet(200L); // 累计 30.1 秒
        assertThat(breaker.isOpen()).as("窗口到期后应自动恢复").isFalse();
    }

    @Test
    @DisplayName("中途成功一次 → 连续超时计数清零（避免零星超时累积误开闸）")
    void successShouldResetConsecutiveCounter() {
        SearchCircuitBreaker breaker = breaker(5, 30_000L);

        for (int i = 0; i < 4; i++) {
            breaker.recordTimeout();
        }
        breaker.recordSuccess();
        assertThat(breaker.consecutiveTimeouts()).isZero();

        // 重置后再来 4 次（合计 8 次超时）也不该开闸
        for (int i = 0; i < 4; i++) {
            assertThat(breaker.recordTimeout()).isFalse();
        }
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    @DisplayName("开闸时计数清零 → 窗口结束后需要重新攒满阈值才能再次开闸（不被\"续杯\"）")
    void openingShouldClearCounterSoWindowIsNotExtended() {
        SearchCircuitBreaker breaker = breaker(2, 30_000L);

        breaker.recordTimeout();
        assertThat(breaker.recordTimeout()).isTrue();
        assertThat(breaker.consecutiveTimeouts()).as("开闸时应清零").isZero();

        now.addAndGet(30_100L);
        assertThat(breaker.isOpen()).isFalse();

        // 恢复后第 1 次超时不足以再次开闸（阈值 2）
        assertThat(breaker.recordTimeout()).isFalse();
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    @DisplayName("阈值与窗口时长都来自配置")
    void shouldHonourConfiguredThresholdAndWindow() {
        SearchCircuitBreaker breaker = breaker(1, 5_000L);

        assertThat(breaker.recordTimeout()).isTrue();
        assertThat(breaker.isOpen()).isTrue();

        now.addAndGet(5_100L);
        assertThat(breaker.isOpen()).isFalse();
    }
}
