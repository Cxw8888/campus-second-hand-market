package com.campus.market.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 批次 6.0.4 · M3/M4 单测：{@link TransactionHelper#runAfterCommit(Runnable)} 的语义。
 *
 * <p>这是本批两处修复（通知 / 用户状态缓存）共用的"提交后执行"底座，所以单独覆盖它的四个边界：
 * 有事务时推迟、回滚时不执行、无事务时立即执行、回调内异常不冒泡到调用方
 * （事务已提交，抛出只会让用户看到"业务报错"但数据其实已落库 —— 最糟的一类误导）。</p>
 */
class TransactionHelperTest {

    private static void triggerAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private static void triggerAfterCompletion(int status) {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(status));
    }

    @Test
    @DisplayName("① 有事务 → 推迟到 afterCommit；回滚（只走 afterCompletion）→ 不执行")
    void insideTransactionShouldRunOnlyOnCommit() {
        AtomicInteger counter = new AtomicInteger();

        TransactionSynchronizationManager.initSynchronization();
        try {
            TransactionHelper.runAfterCommit(counter::incrementAndGet);
            assertThat(counter.get()).as("注册阶段不得执行").isZero();

            triggerAfterCommit();
            assertThat(counter.get()).as("提交后应执行一次").isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // 回滚场景：另一个事务同步上下文，只触发 afterCompletion
        TransactionSynchronizationManager.initSynchronization();
        try {
            TransactionHelper.runAfterCommit(counter::incrementAndGet);
            triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(counter.get()).as("回滚后不得执行").isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("② 无事务 → 立即执行（不能静默丢弃，否则通知在非事务路径上全部消失）")
    void withoutTransactionShouldRunImmediately() {
        AtomicInteger counter = new AtomicInteger();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        TransactionHelper.runAfterCommit(counter::incrementAndGet);

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("③ 动作抛异常 → 不冒泡（事务已提交，抛出只会污染调用方），且不影响后续注册的回调")
    void exceptionFromActionShouldBeSwallowed() {
        List<String> executed = new ArrayList<>();

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatCode(() -> TransactionHelper.runAfterCommit(() -> {
                throw new IllegalStateException("模拟通知派发失败");
            })).doesNotThrowAnyException();

            TransactionHelper.runAfterCommit(() -> executed.add("第二个回调"));

            assertThatCode(TransactionHelperTest::triggerAfterCommit).doesNotThrowAnyException();
            assertThat(executed).as("前一个回调抛异常不得影响后一个").containsExactly("第二个回调");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("④ null 动作 → 无操作（不注册空回调，也不需要 NPE 保护）")
    void nullActionShouldBeNoOp() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            TransactionHelper.runAfterCommit(null);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThatCode(() -> TransactionHelper.runAfterCommit(null)).doesNotThrowAnyException();
    }
}
