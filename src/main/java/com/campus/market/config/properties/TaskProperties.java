package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定时任务与线程池配置。
 *
 * <p>自定义 ThreadPoolTaskExecutor：核心 8（4 核 CPU × 2 IO 密集型经验值）、最大 16（突发流量预留）、
 * 队列 200（缓冲上限）、拒绝策略 CallerRunsPolicy，严禁使用 Executors 快捷方法。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.task")
public class TaskProperties {

    private NotificationPool notification = new NotificationPool();

    /**
     * 超时取消：{@code status=0} 且创建时间超过阈值，每 1 分钟扫描一次，lockAtMostFor=PT5M。
     *
     * <p>阈值<b>按交易方式区分</b>（批次 6.0.6 · Minor 3）：邮寄 15 分钟、面交 120 分钟
     * （校园面交是约时间见面，15 分钟就取消与真实场景不符）。</p>
     */
    private TimeoutCancel timeoutCancel = new TimeoutCancel();

    /**
     * 自动确认收货（批次 6.0.5.2 起同时覆盖<b>已支付面交单</b>的兜底完成）：
     * 邮寄单 {@code status=2 且 ship_time 超 days 天}；面交单 {@code status=1 且 pay_time 超 days 天}。
     */
    private AutoConfirm autoConfirm = new AutoConfirm();

    /** 退款被拒自动恢复：status=7 且 refund_reject_time 超 3 天，每日 1 次，lockAtMostFor=PT10M。 */
    private RefundRejectRecover refundRejectRecover = new RefundRejectRecover();

    @Data
    public static class NotificationPool {
        private int corePoolSize = 8;
        private int maxPoolSize = 16;
        private int queueCapacity = 200;
        private int keepAliveSeconds = 60;
    }

    @Data
    public static class TimeoutCancel {
        /** 邮寄单（trade_type IN (2,3)）待支付超时阈值（分钟）。 */
        private int minutes = 15;
        /**
         * 面交单（trade_type = 1）待支付超时阈值（分钟，批次 6.0.6 · Minor 3）。
         *
         * <p>面交是"约时间地点见面"，买家在路上/在找卖家都很正常，
         * 用邮寄的 15 分钟会大量误取消真实面交订单。</p>
         */
        private int faceMinutes = 120;
        /** 单批处理上限（每单独立事务，失败不影响其它单）。 */
        private int batchLimit = 200;
        private String lockAtMostFor = "PT5M";
    }

    @Data
    public static class AutoConfirm {
        /** 自动确认阈值（天）：邮寄按 ship_time、面交按 pay_time 计算。 */
        private int days = 7;
        /** 提前多少天提醒（默认 1 天 → 即发货后第 6 天起提醒）。 */
        private int remindBeforeDays = 1;
        /**
         * 提醒窗口宽度（天，默认 2）。
         *
         * <p>批次 6.0.5.2 · 定时任务③：原来窗口是"单日区间"（第 6~7 天），
         * 应用停机一天就永久漏提醒；放宽到 2 天宽即可覆盖"停机一天"，
         * 又不会去提醒那些<b>早就该被自动确认</b>的陈旧订单（那些订单已不在 status=2）。</p>
         */
        private int remindWindowDays = 2;
        /** 提醒单批上限（避免一次拉全表）。 */
        private int remindBatchLimit = 500;
        /** 自动确认单批上限。 */
        private int batchLimit = 200;
        private String lockAtMostFor = "PT10M";
        private String remindLockAtMostFor = "PT5M";
    }

    @Data
    public static class RefundRejectRecover {
        private int days = 3;
        private String lockAtMostFor = "PT10M";
    }
}
