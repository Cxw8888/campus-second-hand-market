package com.campus.market.service.impl;

import com.campus.market.mapper.NotificationMapper;
import com.campus.market.service.support.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 批次 6.0.4 安全加固 · M3 单测：站内信必须"事务提交后才发"。
 *
 * <p>守的是自审报告 M3：{@code sendAsync} 原先是纯 {@code @Async}，
 * 而它全部调用点都在 {@code @Transactional} 方法体内 —— 事务<b>后段</b>回滚时，
 * 业务数据没了、用户却已经收到"已支付 / 已取消 / 已发货"的<b>假通知</b>。</p>
 *
 * <p>这里用真实的 {@link NotificationServiceImpl} + mock 的 {@link NotificationDispatcher}，
 * 通过手工 {@code initSynchronization()} 模拟 Spring 的事务同步上下文，
 * 直接断言"什么时机才允许派发"——这正是缺陷的表现形式。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationAfterCommitTest {

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private NotificationDispatcher notificationDispatcher;

    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationServiceImpl(notificationMapper, notificationDispatcher);
    }

    /** 模拟 Spring 在事务提交后触发的 afterCommit 回调。 */
    private static void triggerAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    /** 模拟事务以指定状态结束（提交=STATUS_COMMITTED / 回滚=STATUS_ROLLED_BACK）。 */
    private static void triggerAfterCompletion(int status) {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(status));
    }

    @Test
    @DisplayName("① 事务内调用 → 注册回调（此刻不发）；事务提交 → 派发一次且参数透传")
    void sendAsyncInsideTransactionShouldDispatchOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            notificationService.sendAsync(7L, 1, 1, 99L, "买家已支付订单「教材」，请尽快发货");

            // 事务还没提交：一条都不能发出去
            verify(notificationDispatcher, never()).dispatch(any(), anyInt(), anyInt(), anyLong(), anyString());

            triggerAfterCommit();

            verify(notificationDispatcher).dispatch(7L, 1, 1, 99L, "买家已支付订单「教材」，请尽快发货");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("② 事务回滚 → 永不派发（关键：修复前这里会发出假通知）")
    void sendAsyncShouldNotDispatchWhenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            notificationService.sendAsync(7L, 1, 1, 99L, "买家已支付订单「教材」，请尽快发货");

            // 回滚路径：Spring 只调 afterCompletion(ROLLED_BACK)，不调 afterCommit
            triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(notificationDispatcher, never()).dispatch(any(), any(), any(), any(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("③ 无事务上下文 → 立即派发（否则定时任务/直调路径的通知会被静默丢弃）")
    void sendAsyncWithoutTransactionShouldDispatchImmediately() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        notificationService.sendAsync(8L, 1, 1, 100L, "订单「教材」超时未支付，已自动取消");

        verify(notificationDispatcher).dispatch(8L, 1, 1, 100L, "订单「教材」超时未支付，已自动取消");
    }

    @Test
    @DisplayName("④ 顺序验证：业务写完 → 事务提交 → 才发通知（通知永远排在业务之后）")
    void dispatchMustHappenAfterBusinessWork() {
        List<String> timeline = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();
        try {
            // 业务动作（模拟 OrderServiceImpl.pay 里的状态更新 + 通知注册）
            timeline.add("业务:订单 0→1");
            notificationService.sendAsync(7L, 1, 1, 99L, "买家已支付");
            timeline.add("事务:commit");

            // 提交前：时间线里没有通知
            assertThat(timeline).containsExactly("业务:订单 0→1", "事务:commit");

            triggerAfterCommit();
            timeline.add("通知:已派发");

            assertThat(timeline).containsExactly("业务:订单 0→1", "事务:commit", "通知:已派发");
            verify(notificationDispatcher).dispatch(any(), anyInt(), anyInt(), anyLong(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
