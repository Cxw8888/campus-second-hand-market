package com.campus.market.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.market.common.constant.RedisKeys;
import com.campus.market.entity.Notification;
import com.campus.market.entity.Order;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.NotificationMapper;
import com.campus.market.service.ScheduledTasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.AopTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 6.0.5.2 · 定时任务 B3 集成测试（真实 MySQL + 真实 Redis）：
 * 提醒窗口<b>放宽到可覆盖"停机一天"</b>，并写入 7 天有效的去重标记。
 *
 * <p>修前的窗口是单日区间（第 6~7 天）：应用停机一天，正好落在窗口里的订单就<b>永久漏提醒</b>。
 * 现在窗口按配置放宽（默认 2 天宽，即第 6~8 天），本用例用"发货 7.5 天"的订单证明它已被纳入
 * （修复前该订单落在窗口外，不会被提醒）。</p>
 *
 * <p>去重：{@code task:remind:sent:{orderId}}（SETNX，TTL 7 天）。这里断言真实 Redis 里的键与 TTL；
 * "第二次不再发"由 {@code ScheduledTasksPlumbingTest} 用 mock 覆盖
 * （同一个测试类里第二次调用任务会被 ShedLock 静默跳过，见 {@code AbstractScheduledTaskTest} 的约束说明）。</p>
 */
class RemindWindowIntegrationTest extends AbstractScheduledTaskTest {

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long firstOrderId;

    /** 绕过 ShedLock 代理直接调任务体（原因见 {@code AutoConfirmCoverageTaskTest#task()}）。 */
    private ScheduledTasks task() {
        return AopTestUtils.getTargetObject(scheduledTasks);
    }

    @AfterEach
    void cleanupDedupKeys() {
        if (firstOrderId != null) {
            redisTemplate.delete(RedisKeys.taskRemindSent(firstOrderId));
        }
    }

    @Test
    @DisplayName("B3：窗口放宽到 (6天, 8天] —— 含「停机一天」的补偿单；去重标记 TTL 7 天；第二次调用不再重复提醒")
    void remindShouldCoverWidenedWindowAndMarkDedup() {
        LocalDateTime now = LocalDateTime.now();

        // 命中①：发货 6.5 天（原本就在窗口内）
        Order inWindow = insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_MAIL, now.minusDays(6).minusHours(12), null);
        firstOrderId = inWindow.getId();
        // 命中②：发货 7.5 天 —— 修复前落在单日窗口之外（停机一天就漏提醒），现在必须命中
        Order catchUp = insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_MAIL, now.minusDays(7).minusHours(12), null);
        // 负向①：发货 9 天，早已越过窗口（该由自动确认收货处理，不该再提醒）
        insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_MAIL, now.minusDays(9), null);
        // 负向②：面交单不参与提醒
        insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_FACE, now.minusDays(6).minusHours(12), null);

        task().remindBeforeAutoConfirm();

        List<Notification> firstRun = notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, BUYER_ID));
        assertThat(firstRun)
                .as("只有 (6天, 8天] 窗口内的两张邮寄单应被提醒，实际=%d", firstRun.size())
                .hasSize(2);
        assertThat(firstRun)
                .extracting(Notification::getBizId)
                .containsExactlyInAnyOrder(inWindow.getId(), catchUp.getId());

        // 去重标记：真实 Redis 键 + TTL 允许 7 天（略小于 7 天是正常的，因为已经过去了一点时间）
        String key = RedisKeys.taskRemindSent(inWindow.getId());
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("1");
        assertThat(redisTemplate.getExpire(key))
                .as("去重标记 TTL 应接近 7 天，实际=%s 秒", redisTemplate.getExpire(key))
                .isGreaterThan(6 * 24 * 3600L)
                .isLessThanOrEqualTo(7 * 24 * 3600L);

        // 关键：再跑一次同一个任务 → 去重标记命中，一条都不再发（修前会重复提醒）
        task().remindBeforeAutoConfirm();
        List<Notification> secondRun = notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, BUYER_ID));
        assertThat(secondRun)
                .as("第二次运行必须被去重标记拦下，通知条数不应增加")
                .hasSize(firstRun.size());
    }
}
