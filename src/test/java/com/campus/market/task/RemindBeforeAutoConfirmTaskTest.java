package com.campus.market.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.market.entity.Notification;
import com.campus.market.entity.Order;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.NotificationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时任务：自动确认收货前 24 小时提醒买家（每日 10:00）。
 *
 * <p>对应取数 SQL：{@code OrderMapper.selectAutoConfirmRemindOrders(confirmDays-1, confirmDays)}
 * —— {@code status=2 AND trade_type IN (2,3) AND ship_time <= NOW() - INTERVAL 6 DAY
 * AND ship_time > NOW() - INTERVAL 7 DAY}，即发货后第 6~7 天这个窗口。</p>
 *
 * <p>站内信见 {@link SyncNotificationTestConfig}：异步边界被替换成同步落库，
 * 因此这里断言的是 tb_notification 里真实的一行，且随测试事务回滚。</p>
 */
class RemindBeforeAutoConfirmTaskTest extends AbstractScheduledTaskTest {

    @Autowired
    private NotificationMapper notificationMapper;

    @Test
    @DisplayName("发货后 6~7 天 → 买家收到一条「24 小时后自动确认收货」站内信")
    void remindShouldNotifyBuyerOnlyForOrdersInsideWindow() {
        LocalDateTime now = LocalDateTime.now();

        // 命中：发货 6.5 天前，落在 (NOW()-7d, NOW()-6d] 窗口内
        Order inWindow = insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_MAIL, now.minusDays(6).minusHours(12), null);

        // 负向对照①：发货才 2 天，远没到提醒窗口
        insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_MAIL, now.minusDays(2), null);

        // 负向对照②：发货 8 天，已经越过提醒窗口（该由自动确认收货处理，不该再提醒）
        insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_MAIL, now.minusDays(8), null);

        // 负向对照③：面交单不参与提醒（守住 trade_type IN (2,3)）
        insertOrder(OrderStatus.SHIPPED, TRADE_TYPE_FACE, now.minusDays(6).minusHours(12), null);

        scheduledTasks.remindBeforeAutoConfirm();

        // 四条订单同属一个买家：只有「窗口内那一条」应该产生通知，所以条数必须恰好为 1
        List<Notification> notifications = notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, BUYER_ID)
                .orderByAsc(Notification::getId));

        assertThat(notifications)
                .as("只有发货后 6~7 天的邮寄单才应触发提醒，实际通知条数=%d", notifications.size())
                .hasSize(1);

        Notification notification = notifications.get(0);
        assertThat(notification.getBizId())
                .as("通知应挂在窗口内那条订单上")
                .isEqualTo(inWindow.getId());
        assertThat(notification.getType()).as("type=1 订单").isEqualTo(1);
        assertThat(notification.getBizType()).as("bizType=1 订单").isEqualTo(1);
        assertThat(notification.getIsRead()).as("新通知默认未读").isZero();
        assertThat(notification.getContent())
                .as("提醒文案必须说明 24 小时后自动确认收货")
                .contains("24小时")
                .contains(inWindow.getProductTitle());
    }
}
