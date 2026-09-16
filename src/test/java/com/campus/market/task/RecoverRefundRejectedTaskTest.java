package com.campus.market.task;

import com.campus.market.entity.Order;
import com.campus.market.entity.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时任务：退款被拒（7）超过 3 天申诉期后自动恢复原状态（每日 03:00）。
 *
 * <p>对应 SQL：{@code OrderMapper.recoverFromRefundRejected()}
 * —— {@code SET status = CASE WHEN ship_time IS NULL THEN 1 ELSE 2 END
 * WHERE status=7 AND refund_reject_time < NOW() - INTERVAL 3 DAY AND is_deleted=0}。</p>
 *
 * <p>这里把 CASE 的两个分支都覆盖：未发货恢复 1、已发货恢复 2，
 * 而不是只断言「1 或 2」——只测一个分支的话，万一 CASE 写反了也测不出来。</p>
 */
class RecoverRefundRejectedTaskTest extends AbstractScheduledTaskTest {

    @Test
    @DisplayName("退款被拒超 3 天 → 未发货恢复 1、已发货恢复 2；未超期保持 7")
    void recoverShouldRestoreOriginalStatusOnlyAfterAppealWindow() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime rejectedFourDaysAgo = now.minusDays(4);

        // 命中且未发货（ship_time 为空）→ 恢复 1-已支付待发货
        Order neverShipped = insertOrder(OrderStatus.REFUND_REJECTED, TRADE_TYPE_MAIL, null, rejectedFourDaysAgo);

        // 命中且已发货（ship_time 有值）→ 恢复 2-已发货待收货
        Order alreadyShipped = insertOrder(
                OrderStatus.REFUND_REJECTED, TRADE_TYPE_MAIL, now.minusDays(10), rejectedFourDaysAgo);

        // 负向对照：被拒仅 1 天，仍在 3 天申诉期内，必须保持 7
        Order stillAppealing = insertOrder(OrderStatus.REFUND_REJECTED, TRADE_TYPE_MAIL, null, now.minusDays(1));

        scheduledTasks.recoverFromRefundRejected();

        assertThat(reload(neverShipped.getId()).getStatus())
                .as("超期且未发货 → 应恢复为 1-已支付待发货")
                .isEqualTo(OrderStatus.PAID);

        assertThat(reload(alreadyShipped.getId()).getStatus())
                .as("超期且已发货 → 应恢复为 2-已发货待收货")
                .isEqualTo(OrderStatus.SHIPPED);

        assertThat(reload(stillAppealing.getId()).getStatus())
                .as("仍在 3 天申诉期内的订单不应被恢复")
                .isEqualTo(OrderStatus.REFUND_REJECTED);
    }
}
