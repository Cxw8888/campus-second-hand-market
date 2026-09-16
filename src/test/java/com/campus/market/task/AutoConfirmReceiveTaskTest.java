package com.campus.market.task;

import com.campus.market.entity.Order;
import com.campus.market.entity.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时任务：发货满 7 天自动确认收货（2→3）。
 *
 * <p>对应 SQL：{@code OrderMapper.autoConfirmReceive()}
 * —— {@code SET status=3, finish_time=NOW() WHERE status=2 AND ship_time < NOW() - INTERVAL 7 DAY
 * AND trade_type IN (2,3) AND is_deleted=0}。</p>
 */
class AutoConfirmReceiveTaskTest extends AbstractScheduledTaskTest {

    @Test
    @DisplayName("发货满 7 天 → 自动确认收货：status 变 3 且 finish_time 落库")
    void autoConfirmReceiveShouldFinishOrderShippedOverSevenDays() {
        LocalDateTime now = LocalDateTime.now();

        // 命中：发货 8 天前
        Order overdue = insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_MAIL, now.minusDays(8), null);

        // 负向对照①：发货 6 天 23 小时，差 1 小时不到 7 天，必须保持 2
        // （守住 ship_time < NOW() - INTERVAL 7 DAY 这条边界，防止条件被放宽）
        Order notYetDue = insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_MAIL, now.minusDays(7).plusHours(1), null);

        // 负向对照②：面交单（trade_type=1）永不参与自动确认收货
        // （守住 SQL 里的 trade_type IN (2,3)，防止该条件被漏掉后面交单被误确认）
        Order faceOrder = insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_FACE, now.minusDays(8), null);

        scheduledTasks.autoConfirmReceive();

        Order confirmed = reload(overdue.getId());
        assertThat(confirmed.getStatus())
                .as("发货满 7 天的订单应自动转为 3-已完成")
                .isEqualTo(OrderStatus.FINISHED);
        assertThat(confirmed.getFinishTime())
                .as("自动确认收货必须同时写入 finish_time")
                .isNotNull();

        Order untouched = reload(notYetDue.getId());
        assertThat(untouched.getStatus())
                .as("发货未满 7 天的订单不应被自动确认")
                .isEqualTo(OrderStatus.SHIPPED);
        assertThat(untouched.getFinishTime()).isNull();

        assertThat(reload(faceOrder.getId()).getStatus())
                .as("面交单（trade_type=1）不应被自动确认收货")
                .isEqualTo(OrderStatus.SHIPPED);
    }
}
