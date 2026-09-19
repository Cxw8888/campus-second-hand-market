package com.campus.market.service.impl;

import com.campus.market.service.NotificationSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 6.0.4 · M3 真机验证：站内信只在<b>事务提交后</b>才落库。
 *
 * <p><b>为什么必须是集成测试而不是单测</b>：M3 的缺陷表现完全依赖"真实事务边界 + 真实异步线程"——
 * 纯 Mockito 测不到 {@code afterCommit} 是否真的被 Spring 触发，也测不到通知是否真的写进了 MySQL。
 * 本类用真实的 {@link PlatformTransactionManager} 手工开启/回滚事务，用真实的
 * {@link NotificationSender}（走 {@code afterCommit} + 专用线程池）与真实的 MySQL 断言结果。</p>
 *
 * <p><b>刻意不加 {@code @Transactional}</b>：测试事务会把被测方法的事务"吸收"进来，
 * 于是 afterCommit 永远不会触发（测试结束才回滚），整个验证会变成空转。
 * 因此这里自己用 {@link TransactionTemplate} 控制提交/回滚，并在 {@link AfterEach} 手工清理。</p>
 *
 * <p>反向对照：若把 {@code NotificationServiceImpl.sendAsync} 改回"纯 @Async"（修复前），
 * 第一条用例会立刻失败（回滚路径也会落库一条通知）—— 这正是本类要守住的行为。</p>
 */
@SpringBootTest
class NotificationTransactionBoundaryIntegrationTest {

    /** 合成接收者 ID：tb_notification 无外键约束，不碰真实数据。 */
    private static final long RECEIVER_ID = 9_300_001L;

    private static final int TYPE_ORDER = 1;
    private static final int BIZ_TYPE_ORDER = 1;

    /** 用例标记（用于精确清理与计数）。 */
    private static final String MARKER_PREFIX = "6.0.4-M3-";

    @Autowired
    private NotificationSender notificationSender;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tb_notification WHERE content LIKE ?", MARKER_PREFIX + "%");
    }

    private long countByMarker(String marker) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_notification WHERE content LIKE ?", Long.class, marker + "%");
        return count == null ? 0L : count;
    }

    /** 轮询等待异步派发落库（最多 6 秒）。 */
    private void awaitCount(String marker, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 6000L;
        while (System.currentTimeMillis() < deadline) {
            if (countByMarker(marker) == expected) {
                return;
            }
            Thread.sleep(100L);
        }
        assertThat(countByMarker(marker))
                .as("等待 %d 毫秒后仍未达到期望的通知条数：marker=%s", 6000L, marker)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("① 事务回滚 → 站内信一条都不能落库（修复前这里会写出\"假通知\"）")
    void rollbackShouldNotPersistNotification() throws Exception {
        String marker = MARKER_PREFIX + "rollback-";

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 业务更新 + 通知注册（真实调用链：业务方法内的 sendAsync）
            notificationSender.sendAsync(RECEIVER_ID, TYPE_ORDER, BIZ_TYPE_ORDER, 0L, marker + "买家已支付");
            // 事务后段失败 → 整体回滚
            status.setRollbackOnly();
        });

        // 给异步线程充足的"犯错时间"：修复前它会在事务提交前就把消息发出去
        Thread.sleep(2000L);
        assertThat(countByMarker(marker))
                .as("事务回滚后不得留下任何通知（修复前 = 假通知）")
                .isZero();
    }

    @Test
    @DisplayName("② 事务提交 → 站内信异步落库（不能因为加了 afterCommit 把正常通知弄丢）")
    void commitShouldPersistNotification() throws Exception {
        String marker = MARKER_PREFIX + "commit-";

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                notificationSender.sendAsync(RECEIVER_ID, TYPE_ORDER, BIZ_TYPE_ORDER, 0L, marker + "买家已支付"));

        awaitCount(marker, 1L);
    }

    @Test
    @DisplayName("③ 无事务上下文 → 立即派发（定时任务/直调路径的通知不会被静默丢弃）")
    void withoutTransactionShouldDispatchImmediately() throws Exception {
        String marker = MARKER_PREFIX + "no-tx-";

        notificationSender.sendAsync(RECEIVER_ID, TYPE_ORDER, BIZ_TYPE_ORDER, 0L, marker + "订单超时已取消");

        awaitCount(marker, 1L);
    }
}
