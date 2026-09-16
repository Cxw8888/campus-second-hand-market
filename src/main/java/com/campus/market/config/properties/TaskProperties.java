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

    /** 超时取消：status=0 且创建时间超 15 分钟，每 1 分钟扫描一次，lockAtMostFor=PT5M。 */
    private TimeoutCancel timeoutCancel = new TimeoutCancel();

    /** 自动确认收货：status=2 且 ship_time 超 7 天，每日 1 次，lockAtMostFor=PT10M。 */
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
        private int minutes = 15;
        private String lockAtMostFor = "PT5M";
    }

    @Data
    public static class AutoConfirm {
        private int days = 7;
        private String lockAtMostFor = "PT10M";
        private String remindLockAtMostFor = "PT5M";
    }

    @Data
    public static class RefundRejectRecover {
        private int days = 3;
        private String lockAtMostFor = "PT10M";
    }
}
