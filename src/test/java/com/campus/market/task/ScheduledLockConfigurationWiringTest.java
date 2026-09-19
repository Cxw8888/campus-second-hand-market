package com.campus.market.task;

import com.campus.market.service.ScheduledTasks;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.spring.ExtendedLockConfigurationExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 6.0.5.2 · 定时任务 B2 集成测试：{@code @SchedulerLock.lockAtMostFor} 的配置接线<b>真的生效</b>。
 *
 * <p>修前四个任务的 {@code lockAtMostFor} 在注解里写死 PT5M/PT10M，
 * {@code app.task.*.lock-at-most-for} 是<b>死配置</b>（改了没有任何效果）。</p>
 *
 * <p>这里不去读注解文本（那只能证明"写了占位符"），而是<b>让 ShedLock 自己解析</b>：
 * 注入 ShedLock 注册的 {@link ExtendedLockConfigurationExtractor}，对任务方法取锁配置，
 * 断言 {@code getLockAtMostFor()} 等于本次用 {@code properties} 覆盖的值 ——
 * 占位符没被解析、或解析成别的值，这条用例都会失败。</p>
 */
@SpringBootTest(properties = {
        "app.task.auto-confirm.lock-at-most-for=PT3M",
        "app.task.timeout-cancel.lock-at-most-for=PT7M",
        "app.task.auto-confirm.remind-lock-at-most-for=PT2M",
        "app.task.refund-reject-recover.lock-at-most-for=PT4M"
})
class ScheduledLockConfigurationWiringTest {

    @Autowired
    private ExtendedLockConfigurationExtractor lockConfigurationExtractor;

    /** 注意：注入的是 AOP 代理（ShedLock 用代理织入加锁逻辑）。 */
    @Autowired
    private ScheduledTasks scheduledTasks;

    private Duration lockAtMostForOf(String methodName) throws Exception {
        Method method = ScheduledTasks.class.getMethod(methodName);
        LockConfiguration configuration = lockConfigurationExtractor
                .getLockConfiguration(scheduledTasks, method, new Object[0])
                .orElseThrow(() -> new AssertionError("未取到锁配置: " + methodName));
        return configuration.getLockAtMostFor();
    }

    @Test
    @DisplayName("B2 四个任务的 lockAtMostFor 均来自配置（占位符被 ShedLock 正确解析）")
    void configuredLockAtMostForShouldBeResolved() throws Exception {
        assertThat(lockAtMostForOf("cancelTimeoutOrders")).isEqualTo(Duration.ofMinutes(7));
        assertThat(lockAtMostForOf("autoConfirmReceive")).isEqualTo(Duration.ofMinutes(3));
        assertThat(lockAtMostForOf("remindBeforeAutoConfirm")).isEqualTo(Duration.ofMinutes(2));
        assertThat(lockAtMostForOf("recoverFromRefundRejected")).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    @DisplayName("B2 回归对照：不配 properties 时走注解里的默认值（PT5M / PT10M）")
    void defaultValuesShouldStillApplyWhenNotOverridden() throws Exception {
        // 本类只覆盖了 4 个 lock-at-most-for，这里没有覆盖的是 lockAtLeastFor（注解里写死 PT30S）——
        // 确保"接线"没有把没覆盖的项一起弄坏
        Method method = ScheduledTasks.class.getMethod("cancelTimeoutOrders");
        LockConfiguration configuration = lockConfigurationExtractor
                .getLockConfiguration(scheduledTasks, method, new Object[0]).orElseThrow();
        assertThat(configuration.getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(30));
        assertThat(configuration.getName()).isEqualTo("cancelTimeoutOrderTask");
    }
}
