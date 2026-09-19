package com.campus.market.service;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.config.properties.TaskProperties;
import com.campus.market.entity.Order;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.service.support.OrderTaskProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 定时任务（Spring Task + ShedLock，确保多实例幂等）。
 *
 * <table>
 *   <tr><th>任务</th><th>阈值</th><th>间隔</th><th>lockAtMostFor</th></tr>
 *   <tr><td>超时取消订单</td><td>status=0 且创建超 {@code app.task.timeout-cancel.minutes}（默认 15 分钟）</td><td>每 1 分钟</td><td>配置项</td></tr>
 *   <tr><td>自动确认收货</td><td>邮寄：status=2 且 ship_time 超 {@code auto-confirm.days}；<b>面交：status=1 且 pay_time 超同值</b></td><td>每日 1 次</td><td>配置项</td></tr>
 *   <tr><td>收货前提醒</td><td>status=2 且 ship_time 落在 {@code [days-remindBeforeDays, +remindWindowDays]} 窗口</td><td>每日 1 次</td><td>配置项</td></tr>
 *   <tr><td>退款被拒自动恢复</td><td>status=7 且 refund_reject_time 超 {@code refund-reject-recover.days}</td><td>每日 1 次</td><td>配置项</td></tr>
 * </table>
 *
 * <p><b>5-已冻结订单不参与超时扫描</b>（冻结即视为交易终止，冻结时已回补库存）。</p>
 *
 * <h3>批次 6.0.5.2 的四处修正（定时任务 4 条）</h3>
 * <ol>
 *   <li><b>阈值接线</b>：自动确认收货的 SQL 由硬编码 {@code INTERVAL 7 DAY} 改为参数化
 *       {@code #{days}}，值取自 {@code app.task.auto-confirm.days}（改配置即改行为）；</li>
 *   <li><b>lock-at-most-for 接线</b>：四个任务的 {@code @SchedulerLock.lockAtMostFor} 全部改为
 *       {@code ${app.task.*.lock-at-most-for:默认值}} —— ShedLock 会用 Spring 的占位符解析器求值，
 *       配置项从此真正生效（修前配置项是死配置，注解里写死 PT5M/PT10M）；</li>
 *   <li><b>提醒去重 + LIMIT + 窗口放宽</b>：见 {@link #remindBeforeAutoConfirm()}；</li>
 *   <li><b>事务边界</b>：批处理方法不再带 {@code @Transactional}，逐单交给
 *       {@link OrderTaskProcessor}（每次调用一个独立事务），批内一单失败不再拖垮整批。</li>
 * </ol>
 *
 * <h3>面交兜底完成（6.0.5.1 遗留的 B 方案）</h3>
 * <p>{@link #autoConfirmReceive()} 现在同时覆盖 {@code status=1 AND trade_type=1} 的已支付面交单：
 * 6.0.5.1 给卖家补了"确认面交完成"的主动路径，但若买卖双方都失联，订单仍会永久停在 1 ——
 * 这里按 pay_time 超期兜底（与邮寄单的超时自动收货同一条规则）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private static final int BIZ_TYPE_ORDER = 1;
    private static final int TYPE_ORDER = 1;

    /** 提醒去重标记 TTL：7 天（覆盖订单剩余生命周期）。 */
    private static final Duration REMIND_DEDUP_TTL = Duration.ofDays(7);

    private final OrderMapper orderMapper;
    private final OrderTaskProcessor orderTaskProcessor;
    private final NotificationSender notificationSender;
    private final TaskProperties taskProperties;
    private final StringRedisTemplate redisTemplate;

    /**
     * 超时未支付自动取消（0→4）+ 库存回补，每 1 分钟扫描一次。
     *
     * <p><b>阈值按交易方式区分</b>（批次 6.0.6 · Minor 3）：邮寄 {@code minutes}（默认 15 分钟）、
     * 面交 {@code face-minutes}（默认 120 分钟）。修前两者共用 15 分钟，
     * 面交单（约见面）常在买家赶路途中被系统取消。</p>
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：整批一个事务时，批内一单失败会连带回滚另外 199 单
     * （而通知可能已经发出）。逐单事务见 {@link OrderTaskProcessor}。</p>
     */
    @Scheduled(cron = "0 * * * * ?")
    @SchedulerLock(name = "cancelTimeoutOrderTask",
            lockAtMostFor = "${app.task.timeout-cancel.lock-at-most-for:PT5M}",
            lockAtLeastFor = "PT30S")
    public void cancelTimeoutOrders() {
        TaskProperties.TimeoutCancel config = taskProperties.getTimeoutCancel();
        List<Order> timeoutOrders = orderMapper.selectTimeoutPendingOrders(
                config.getMinutes(), config.getFaceMinutes(), config.getBatchLimit());
        if (timeoutOrders.isEmpty()) {
            return;
        }
        int cancelled = 0;
        int failed = 0;
        for (Order order : timeoutOrders) {
            try {
                if (orderTaskProcessor.cancelTimeoutOne(order)) {
                    cancelled++;
                }
            } catch (Exception e) {
                // 单条失败只影响该单：下一个周期它仍是 status=0，会被重新扫到重试
                failed++;
                log.error("超时取消失败（仅该单受影响，下轮重试）: orderId={}", order.getId(), e);
            }
        }
        if (cancelled > 0 || failed > 0) {
            log.info("超时订单扫描完成: 扫描={}, 取消={}, 失败={}", timeoutOrders.size(), cancelled, failed);
        }
    }

    /**
     * 自动确认收货（邮寄 2→3）+ 面交兜底完成（1→3），每日 1 次。
     *
     * <p>阈值来自 {@code app.task.auto-confirm.days}；取数与更新都带状态守卫，
     * 重复执行/多实例并发都不会重复处理（{@code autoConfirmOne} 的影响行数判断）。</p>
     */
    @Scheduled(cron = "0 30 2 * * ?")
    @SchedulerLock(name = "autoConfirmReceiveTask",
            lockAtMostFor = "${app.task.auto-confirm.lock-at-most-for:PT10M}")
    public void autoConfirmReceive() {
        TaskProperties.AutoConfirm config = taskProperties.getAutoConfirm();
        List<Order> candidates = orderMapper.selectAutoConfirmCandidates(config.getDays(), config.getBatchLimit());
        if (candidates.isEmpty()) {
            return;
        }
        int confirmed = 0;
        int failed = 0;
        for (Order order : candidates) {
            try {
                if (orderTaskProcessor.autoConfirmOne(order)) {
                    confirmed++;
                }
            } catch (Exception e) {
                failed++;
                log.error("自动确认收货失败（仅该单受影响，下轮重试）: orderId={}", order.getId(), e);
            }
        }
        log.info("自动确认收货完成: 候选={}, 完成={}, 失败={}", candidates.size(), confirmed, failed);
    }

    /**
     * 自动确认收货前提醒买家（默认发货后第 6 天起），每日 1 次。
     *
     * <h3>批次 6.0.5.2 · 定时任务③ 的三处修正</h3>
     * <ul>
     *   <li><b>窗口放宽</b>：窗口宽度由配置 {@code remind-window-days}（默认 2 天）决定。
     *       修前是"单日区间"（第 6~7 天），应用停机一天就<b>永久漏提醒</b>；</li>
     *   <li><b>LIMIT</b>：{@code remind-batch-limit}（默认 500），避免一次把全表拉进内存；</li>
     *   <li><b>去重</b>：{@code task:remind:sent:{orderId}}（SETNX，TTL 7 天）。
     *       没有它时，窗口一放宽、任务一天多跑一次就会重复提醒同一单。</li>
     * </ul>
     */
    @Scheduled(cron = "0 0 10 * * ?")
    @SchedulerLock(name = "autoConfirmRemindTask",
            lockAtMostFor = "${app.task.auto-confirm.remind-lock-at-most-for:PT5M}")
    public void remindBeforeAutoConfirm() {
        TaskProperties.AutoConfirm config = taskProperties.getAutoConfirm();
        int fromDays = Math.max(1, config.getDays() - config.getRemindBeforeDays());
        int toDays = fromDays + Math.max(1, config.getRemindWindowDays());
        List<Order> orders = orderMapper.selectAutoConfirmRemindOrders(fromDays, toDays, config.getRemindBatchLimit());
        if (orders.isEmpty()) {
            return;
        }
        int sent = 0;
        int skipped = 0;
        for (Order order : orders) {
            if (!tryMarkRemindSent(order.getId())) {
                skipped++;
                continue;
            }
            notificationSender.sendAsync(order.getUserId(), TYPE_ORDER, BIZ_TYPE_ORDER, order.getId(),
                    "订单「" + order.getProductTitle() + "」将在24小时后自动确认收货，请及时确认或申请售后");
            sent++;
        }
        log.info("自动确认收货提醒完成: 窗口=({}天, {}天], 候选={}, 发送={}, 去重跳过={}",
                fromDays, toDays, orders.size(), sent, skipped);
    }

    /**
     * 退款被拒（7）超过 3 天申诉期后自动恢复原状态：未发货→1，已发货→2。每日 1 次。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @SchedulerLock(name = "recoverRefundRejectedTask",
            lockAtMostFor = "${app.task.refund-reject-recover.lock-at-most-for:PT10M}")
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

    /**
     * 抢占"该订单已提醒过"的标记（SETNX，TTL 7 天）。
     *
     * <p>Redis 故障时<b>降级为"未提醒过"</b>（返回 true）—— 宁可重复提醒，也不要不提醒：
     * 提醒是用户体验问题，漏提醒会让买家在不知情的情况下被自动确认收货。</p>
     */
    private boolean tryMarkRemindSent(Long orderId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(RedisKeys.taskRemindSent(orderId), "1", REMIND_DEDUP_TTL));
        } catch (Exception e) {
            log.warn("提醒去重标记写入失败（降级为放行，可能重复提醒）: orderId={}, err={}", orderId, e.getMessage());
            return true;
        }
    }
}
