package com.campus.market.service.support;

import com.campus.market.config.AsyncConfig;
import com.campus.market.entity.Notification;
import com.campus.market.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 站内信异步派发器（批次 6.0.4 · M3 新增）。
 *
 * <h3>为什么要单独抽一个 Bean</h3>
 * <p>M3 的修法是：{@code NotificationServiceImpl.sendAsync} 先用
 * {@code TransactionHelper.runAfterCommit(...)} 把发送推迟到事务提交后，
 * 再真正异步派发。于是"异步"这件事必须由<b>另一个 Bean</b> 的方法承担 ——
 * Spring 的 {@code @Async} 依赖 AOP 代理，<b>同类内部调用不会走代理</b>，
 * 若把 {@code @Async} 方法留在 {@code NotificationServiceImpl} 内部自调用，
 * 它会退化成<b>同步</b>执行：请求线程在 afterCommit 里阻塞到全部重试结束
 * （最坏 2 次 × 2 秒），比修复前更糟。</p>
 *
 * <p>方案对比：</p>
 * <ul>
 *   <li><b>独立 Bean（本类，采用）</b>：显式、可单测、无自注入魔法；</li>
 *   <li>注入自身 / {@code AopContext.currentProxy()}：需要暴露代理或开
 *       {@code exposeProxy=true}，调用链上多一层隐式约定，容易在重构时失效。</li>
 * </ul>
 *
 * <p>重试策略与 6.0.4 之前完全一致（保留原实现）：最多 3 次尝试、固定间隔 2 秒、
 * 最终失败记录 error 降级，<b>严禁无限重试导致线程池堵塞</b>。
 * 区别只在于：此时事务<b>已经提交</b>，因此即使线程池饱和触发 CallerRunsPolicy、
 * 由调用线程同步执行这段 sleep，也<b>不再持有任何订单行锁</b>（行锁在 COMMIT 时已释放）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    /** 异步发送失败后的重试次数（总尝试次数 = 1 + 2）。 */
    private static final int MAX_RETRY = 2;

    /** 重试固定间隔（毫秒）。 */
    private static final long RETRY_INTERVAL_MILLIS = 2000L;

    /** 未读标记。 */
    private static final int UNREAD = 0;

    private final NotificationMapper notificationMapper;

    /**
     * 异步落库一条站内信（专用线程池 {@code notificationExecutor}）。
     *
     * <p>由 {@code NotificationServiceImpl.sendAsync} 在<b>事务提交后</b>调用；
     * 本方法自身不依赖任何事务上下文，异步线程内自行 insert。</p>
     */
    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    public void dispatch(Long userId, Integer type, Integer bizType, Long bizId, String content) {
        for (int attempt = 1; attempt <= MAX_RETRY + 1; attempt++) {
            try {
                insertNotification(userId, type, bizType, bizId, content);
                if (attempt > 1) {
                    log.info("站内信异步发送第 {} 次尝试成功: userId={}, bizType={}, bizId={}",
                            attempt, userId, bizType, bizId);
                }
                return;
            } catch (Exception e) {
                log.warn("站内信异步发送失败（第 {}/{} 次）: userId={}, bizType={}, bizId={}, err={}",
                        attempt, MAX_RETRY + 1, userId, bizType, bizId, e.getMessage());
                if (attempt > MAX_RETRY) {
                    break;
                }
                try {
                    // 固定间隔 2 秒，最多重试 2 次，严禁无限重试导致线程池堵塞。
                    // 事务已提交（行锁已释放），此处的等待不会再拖住任何业务行锁。
                    Thread.sleep(RETRY_INTERVAL_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("站内信重试等待被中断，提前降级: userId={}, bizId={}", userId, bizId);
                    break;
                }
            }
        }
        // 降级：记录 error 日志（业务流程已提交，前端可通过轮询接口感知缺失）
        log.error("站内信异步发送最终失败，已降级: userId={}, type={}, bizType={}, bizId={}, content={}",
                userId, type, bizType, bizId, content);
    }

    private void insertNotification(Long userId, Integer type, Integer bizType, Long bizId, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setBizType(bizType);
        notification.setBizId(bizId == null ? 0L : bizId);
        notification.setContent(content);
        notification.setIsRead(UNREAD);
        notificationMapper.insert(notification);
    }
}
