package com.campus.market.service;

import com.campus.market.config.properties.TaskProperties;
import com.campus.market.entity.Order;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 定时任务（Spring Task + ShedLock，确保多实例幂等）。
 *
 * <table>
 *   <tr><th>任务</th><th>阈值</th><th>间隔</th><th>lockAtMostFor</th></tr>
 *   <tr><td>超时取消订单</td><td>status=0 且创建超 15 分钟</td><td>每 1 分钟</td><td>PT5M</td></tr>
 *   <tr><td>自动确认收货</td><td>status=2 且 ship_time 超 7 天</td><td>每日 1 次</td><td>PT10M</td></tr>
 *   <tr><td>收货前提醒</td><td>status=2 且 ship_time 在 6-7 天之间</td><td>每日 1 次</td><td>PT5M</td></tr>
 *   <tr><td>退款被拒自动恢复</td><td>status=7 且 refund_reject_time 超 3 天</td><td>每日 1 次</td><td>PT10M</td></tr>
 * </table>
 *
 * <p><b>5-已冻结订单不参与超时扫描</b>（冻结即视为交易终止，冻结时已回补库存）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    /** 单批处理上限，避免长事务与缓存击穿。 */
    private static final int BATCH_LIMIT = 200;

    private static final int BIZ_TYPE_ORDER = 1;
    private static final int TYPE_ORDER = 1;

    private final OrderMapper orderMapper;
    private final StockService stockService;
    private final NotificationSender notificationSender;
    private final TaskProperties taskProperties;

    /**
     * 超时未支付自动取消（0→4）+ 库存回补，每 1 分钟扫描一次。
     */
    @Scheduled(cron = "0 * * * * ?")
    @SchedulerLock(name = "cancelTimeoutOrderTask", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Transactional(rollbackFor = Exception.class)
    public void cancelTimeoutOrders() {
        int minutes = taskProperties.getTimeoutCancel().getMinutes();
        List<Order> timeoutOrders = orderMapper.selectTimeoutPendingOrders(minutes, BATCH_LIMIT);
        if (timeoutOrders.isEmpty()) {
            return;
        }
        int cancelled = 0;
        for (Order order : timeoutOrders) {
            // 影响行数判断：并发下可能已被买家支付/取消，为 0 直接跳过
            int rows = orderMapper.cancelByTimeout(order.getId());
            if (rows > 0) {
                // 库存回补必须与订单状态更新同一事务（场景 ②）；
                // 批次 6.0.3 · B1：带幂等凭证（order:restored:{orderId}），避免与其它路径重复回补
                stockService.restoreOnce(order.getId(), order.getProductId(), order.getQuantity());
                cancelled++;
                notificationSender.sendAsync(order.getUserId(), TYPE_ORDER, BIZ_TYPE_ORDER, order.getId(),
                        "订单「" + order.getProductTitle() + "」超时未支付，已自动取消");
            }
        }
        if (cancelled > 0) {
            log.info("超时订单扫描完成: 扫描={}, 取消={}", timeoutOrders.size(), cancelled);
        }
    }

    /**
     * 发货满 7 天自动确认收货（2→3），每日 1 次。
     */
    @Scheduled(cron = "0 30 2 * * ?")
    @SchedulerLock(name = "autoConfirmReceiveTask", lockAtMostFor = "PT10M")
    public void autoConfirmReceive() {
        int rows = orderMapper.autoConfirmReceive();
        if (rows > 0) {
            log.info("自动确认收货完成: 处理订单数={}", rows);
        }
    }

    /**
     * 自动确认收货前 24 小时提醒买家（status=2 且发货后 6-7 天），每日 1 次。
     */
    @Scheduled(cron = "0 0 10 * * ?")
    @SchedulerLock(name = "autoConfirmRemindTask", lockAtMostFor = "PT5M")
    public void remindBeforeAutoConfirm() {
        int confirmDays = taskProperties.getAutoConfirm().getDays();
        List<Order> orders = orderMapper.selectAutoConfirmRemindOrders(confirmDays - 1, confirmDays);
        for (Order order : orders) {
            notificationSender.sendAsync(order.getUserId(), TYPE_ORDER, BIZ_TYPE_ORDER, order.getId(),
                    "订单「" + order.getProductTitle() + "」将在24小时后自动确认收货，请及时确认或申请售后");
        }
        if (!orders.isEmpty()) {
            log.info("自动确认收货提醒完成: 提醒数={}", orders.size());
        }
    }

    /**
     * 退款被拒（7）超过 3 天申诉期后自动恢复原状态：未发货→1，已发货→2。每日 1 次。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @SchedulerLock(name = "recoverRefundRejectedTask", lockAtMostFor = "PT10M")
    public void recoverFromRefundRejected() {
        int rows = orderMapper.recoverFromRefundRejected();
        if (rows > 0) {
            log.info("退款被拒自动恢复完成: 处理订单数={}", rows);
        }
    }

    /**
     * 待支付订单数量监控占位（可选 Prometheus 指标埋点预留）。
     */
    public long countPendingPayOrders() {
        return orderMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, OrderStatus.PENDING_PAY));
    }
}
