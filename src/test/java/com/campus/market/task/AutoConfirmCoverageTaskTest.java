package com.campus.market.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.market.entity.Notification;
import com.campus.market.entity.Order;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.NotificationMapper;
import com.campus.market.service.ScheduledTasks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.AopTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 6.0.5.2 · 定时任务 B1 + C 集成测试（真实 MySQL）：
 * 自动确认收货<b>按配置阈值</b>执行，并<b>覆盖面交单</b>（6.0.5.1 遗留的"双方失联"兜底）。
 *
 * <p>用 {@code app.task.auto-confirm.days=3} 覆盖配置：如果阈值没接线（修前 SQL 写死 7 天），
 * "已支付 4 天的面交单"不会被确认，本用例会直接失败 —— 这是对 B1 的行为级证明，
 * 比断言"SQL 参数等于 3"更硬。</p>
 *
 * <p>面交单判龄用 {@code pay_time}（面交不发货、没有 ship_time）：</p>
 * <ul>
 *   <li>命中：{@code status=1 AND trade_type=1 AND pay_time} 超阈值 → 1→3；</li>
 *   <li>不命中：面交但未超期 / 邮寄单停在 status=1（待发货）/ 邮寄未超期。</li>
 * </ul>
 *
 * <p>顺带覆盖 6.0.5.2 新增的"买卖双方各收一条站内信"。</p>
 */
@SpringBootTest(properties = {
        "app.task.auto-confirm.days=3",
        // 本机 demo 库里可能残留 3~7 天龄的旧订单（此前批次验收脚本造的），
        // 把单批上限调大，保证"本次用例造的订单"一定落在候选集内
        "app.task.auto-confirm.batch-limit=1000"
})
class AutoConfirmCoverageTaskTest extends AbstractScheduledTaskTest {

    @Autowired
    private NotificationMapper notificationMapper;

    /**
     * 调任务时<b>绕过 ShedLock 代理</b>（本批新增集成测试的统一做法）。
     *
     * <p>任务方法带 {@code @SchedulerLock}，走代理调用会真的去 {@code shedlock} 表抢锁：
     * 锁的获取用独立事务提交、释放却跟着用例事务走 —— 用例一失败回滚，锁就被"撤销释放"，
     * {@code lock_until} 停在 +lockAtMostFor，<b>下一次运行会被静默跳过</b>
     * （表现为断言莫名其妙失败，本批实测踩到过；试图在事务里 UPDATE 那把锁反而会
     * 让 ShedLock 的独立事务等行锁 50 秒，更糟）。
     * 用例关心的是"任务体 + SQL + 状态流转"，加锁行为由 {@code ScheduledLockConfigurationWiringTest} 单独验证，
     * 因此这里取目标对象直接调用。</p>
     */
    private ScheduledTasks task() {
        return AopTestUtils.getTargetObject(scheduledTasks);
    }

    @Test
    @DisplayName("B1+C：面交单已支付超 3 天 → 自动完成（1→3）并通知双方；未超期/邮寄待发货的不动")
    void autoConfirmShouldCoverPaidFaceOrdersWithinConfiguredThreshold() {
        LocalDateTime now = LocalDateTime.now();

        // 命中①：面交单，已支付 4 天（配置阈值 3 天）
        Order faceDue = insertOrder(OrderStatus.PAID, TRADE_TYPE_FACE, null, null, now.minusDays(4));
        // 负向①：面交单只支付 2 天 → 不确认（守住"阈值真的按配置生效"）
        Order faceNotDue = insertOrder(OrderStatus.PAID, TRADE_TYPE_FACE, null, null, now.minusDays(2));
        // 负向②：邮寄单停在 status=1（已支付待发货）→ 不该被自动确认（只有发货满期才走 2→3）
        Order mailPaid = insertOrder(OrderStatus.PAID, TRADE_TYPE_MAIL, null, null, now.minusDays(4));
        // 命中②：邮寄单发货 4 天 → 仍按原规则确认（2→3）
        Order mailDue = insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_MAIL, now.minusDays(4), null);
        // 负向③：邮寄单发货 2 天 → 不确认
        Order mailNotDue = insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_MAIL, now.minusDays(2), null);

        task().autoConfirmReceive();

        Order confirmedFace = reload(faceDue.getId());
        assertThat(confirmedFace.getStatus())
                .as("已支付面交单超阈值必须被自动完成（6.0.5.1 遗留的双方失联场景）")
                .isEqualTo(OrderStatus.FINISHED);
        assertThat(confirmedFace.getFinishTime()).as("必须同时写入 finish_time").isNotNull();

        assertThat(reload(faceNotDue.getId()).getStatus())
                .as("未到阈值的面交单不得被确认（阈值必须来自配置）")
                .isEqualTo(OrderStatus.PAID);
        assertThat(reload(mailPaid.getId()).getStatus())
                .as("邮寄单停在待发货(1) 不参与自动确认")
                .isEqualTo(OrderStatus.PAID);
        assertThat(reload(mailDue.getId()).getStatus())
                .as("邮寄单发货超阈值仍按原规则确认")
                .isEqualTo(OrderStatus.FINISHED);
        assertThat(reload(mailNotDue.getId()).getStatus())
                .as("邮寄未超阈值不确认")
                .isEqualTo(OrderStatus.SHIPPED);

        // 通知：两张订单被自动完成，买卖双方各一条 = 4 条
        List<Notification> notifications = notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .in(Notification::getUserId, List.of(BUYER_ID, SELLER_ID)));
        assertThat(notifications)
                .as("每张被自动完成的订单都应通知买卖双方，实际=%d", notifications.size())
                .hasSize(4);
        assertThat(notifications)
                .extracting(Notification::getContent)
                .anySatisfy(content -> assertThat(content).contains("自动确认完成"))
                .anySatisfy(content -> assertThat(content).contains("自动确认收货"));
    }
}
