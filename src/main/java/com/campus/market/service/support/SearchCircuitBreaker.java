package com.campus.market.service.support;

import com.campus.market.config.properties.SearchProperties;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

/**
 * 搜索熔断器（批次 5.4.4，极简实现）。
 *
 * <h3>为什么不引 Resilience4j</h3>
 * 项目 pom 里没有 Resilience4j / Sentinel，而批次规则明确"禁止装依赖"。搜索只读、
 * 失败可安全降级为"空列表"，所以需要的能力只有三件事：<b>计连续超时次数、到阈值开闸、
 * 到时间自动恢复</b>。用两个字段就能表达清楚，引一个通用熔断框架反而增加理解成本。
 *
 * <h3>状态机</h3>
 * <pre>
 *   CLOSED（正常放行）：每次超时 consecutiveTimeouts+1；成功一次即清零
 *        │  连续超时达 failureThreshold
 *        ▼
 *   OPEN（直接返回空列表，不打 DB）：持续 openMillis 后自动回到 CLOSED
 * </pre>
 *
 * <p>注意两点刻意的设计：</p>
 * <ol>
 *   <li><b>没有 HALF_OPEN 半开态</b>：窗口一到就完全放行。对这个场景够用——
 *       半开态的价值是"只放一个探针请求"，而搜索是高频只读接口，窗口到期后第一批
 *       并发请求本身就起到探针作用，真还超时会在几次内再次开闸。</li>
 *   <li><b>开闸时把计数清零</b>：否则窗口结束后只需 1 次超时就会立刻重新开闸，
 *       等于熔断时长被"续杯"，故障恢复的窗口会变得不可预测。</li>
 * </ol>
 *
 * <p>线程安全：{@link AtomicInteger} + {@code volatile} 时间戳，无锁。</p>
 */
@Component
public class SearchCircuitBreaker {

    private final int failureThreshold;
    private final long openMillis;

    /** 连续超时次数（成功一次清零）。 */
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger();

    /** 熔断闸门的关闭时刻（毫秒时间戳）；0 表示从未打开。 */
    private volatile long openUntil = 0L;

    /** 时钟：生产用系统时钟，单测可注入假时钟以确定性地验证"30 秒后恢复"。 */
    private volatile LongSupplier clock = System::currentTimeMillis;

    public SearchCircuitBreaker(SearchProperties properties) {
        SearchProperties.CircuitBreaker config = properties.getCircuitBreaker();
        this.failureThreshold = Math.max(1, config.getFailureThreshold());
        this.openMillis = Math.max(1L, config.getOpenMillis());
    }

    /**
     * 闸门是否处于打开（熔断）状态。
     *
     * @return true 表示当前应直接返回兜底结果，不要访问数据库
     */
    public boolean isOpen() {
        long until = openUntil;
        return until > 0L && clock.getAsLong() < until;
    }

    /**
     * 记录一次超时。
     *
     * @return true 表示这次超时把闸门打开了（调用方据此打一条醒目的日志）
     */
    public boolean recordTimeout() {
        int count = consecutiveTimeouts.incrementAndGet();
        if (count < failureThreshold) {
            return false;
        }
        openUntil = clock.getAsLong() + openMillis;
        consecutiveTimeouts.set(0);
        return true;
    }

    /** 记录一次成功：清零连续超时计数并复位闸门。 */
    public void recordSuccess() {
        consecutiveTimeouts.set(0);
        openUntil = 0L;
    }

    /** 当前连续超时次数（仅用于日志与单测断言）。 */
    public int consecutiveTimeouts() {
        return consecutiveTimeouts.get();
    }

    /**
     * 替换时钟。
     *
     * <p><b>仅供单测</b>注入假时钟，用确定性的方式验证"30 秒窗口后自动恢复"，
     * 生产代码不需要（也绝不应该）调用它。设为 public 是为了让测试类能跨包使用。</p>
     */
    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }
}
