package com.campus.market.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务边界工具（批次 6.0.4 · M3/M4）。
 *
 * <p>提供 {@link #runAfterCommit(Runnable)}：把"只有在事务提交成功后才有意义"的动作
 * （发站内信、写用户状态缓存）推迟到 {@code afterCommit} 执行。</p>
 *
 * <h3>为什么需要它（自审报告 M3 / M4）</h3>
 * <ul>
 *   <li><b>M3</b>：{@code NotificationServiceImpl.sendAsync} 原先是纯 {@code @Async}，
 *       而它全部调用点都位于 {@code @Transactional} 方法体内 —— 事务<b>后段</b>回滚时
 *       业务数据没了，用户却已经收到"已支付/已取消/已发货"的<b>假通知</b>。</li>
 *   <li><b>M4</b>：{@code AdminServiceImpl.banUser} 在事务内写 {@code user:status:{userId}}，
 *       封禁事务一旦回滚，DB 里用户正常、缓存却标记封禁（且当时无 TTL）→ 该用户被永久 401。</li>
 * </ul>
 * <p>两者根因同一个：<b>把"副作用"写在了事务里</b>，而 Redis / 线程池都不参与 MySQL 的回滚。</p>
 *
 * <h3>与 6.0.3 B1 的 afterCompletion 钩子的关系（任务 D 的结论）</h3>
 * <p>Spring 的同步回调顺序是固定的：{@code beforeCommit → beforeCompletion → (提交) →
 * afterCommit → afterCompletion}。因此</p>
 * <ul>
 *   <li>本批的 {@code afterCommit}（发通知 / 写用户状态缓存）<b>先</b>执行；</li>
 *   <li>6.0.3 {@code StockService.restoreOnce} 注册的 {@code afterCompletion}
 *       （仅在非 COMMITTED 时删除 {@code order:restored:{orderId}}）<b>后</b>执行。</li>
 * </ul>
 * <p>两者作用对象也不同（通知 / 用户状态缓存 vs 库存回补凭证），一个只在"提交成功"时动作、
 * 另一个只在"提交失败"时动作，<b>语义互斥、顺序无害、互不覆盖</b>。
 * 顺序与效果都有单测与真机验证（见 6.0.4 报告「四、验证结果」）。</p>
 *
 * <h3>为什么不抛异常出去</h3>
 * <p>{@code afterCommit} 里抛出的异常会沿着 {@code commit()} 冒泡到调用方 ——
 * 而此刻事务<b>已经提交</b>，用户会看到一个"业务报错"，但数据其实已落库，
 * 属于最糟糕的一类误导。所以本工具统一 catch + log.error，把失败留在日志里。</p>
 */
@Slf4j
public final class TransactionHelper {

    private TransactionHelper() {
    }

    /**
     * 在事务提交后执行给定动作；<b>当前没有事务时立即执行</b>。
     *
     * <p>"无事务则立即执行"是必须的分支：否则在没有事务的调用路径上，
     * 回调永远不会被触发，通知会被静默丢弃（比发假通知更难发现）。</p>
     *
     * @param action 待执行动作（null 视为无操作）
     */
    public static void runAfterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无事务上下文：立即执行（例如定时任务被直接调用、单测直调等）
            runSafely(action, false);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(action, true);
            }
        });
    }

    /**
     * 执行动作并吞掉异常（事务已提交，抛出只会污染调用方）。
     *
     * @param afterCommit true 表示来自 afterCommit 回调（日志里区分来源，便于排查）
     */
    private static void runSafely(Runnable action, boolean afterCommit) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("{}执行失败（事务已提交，已降级不抛出）: err={}",
                    afterCommit ? "事务提交后动作" : "无事务上下文动作", e.getMessage(), e);
        }
    }
}
