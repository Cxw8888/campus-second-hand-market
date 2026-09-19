package com.campus.market.service;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.util.TransactionHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.4 · 任务 D 单测：本批的 <b>afterCommit</b>（发通知 / 写用户状态缓存）
 * 与 6.0.3 B1 的 <b>afterCompletion</b>（释放 {@code order:restored:{orderId}} 凭证）共存。
 *
 * <p>Spring 的同步回调顺序固定为
 * {@code beforeCommit → beforeCompletion → (提交) → afterCommit → afterCompletion}，
 * 两者作用对象不同、触发条件互斥：</p>
 * <ul>
 *   <li>afterCommit：<b>只在提交成功</b>时动作（发通知、写"封禁"缓存）；</li>
 *   <li>afterCompletion：<b>只在未提交</b>时动作（删掉回补凭证，允许重试）。</li>
 * </ul>
 * <p>因此同一个事务里两者可以共存：成功时"通知发出 + 凭证保留"，失败时"通知不发 + 凭证释放"。
 * 这里用真实的 {@link StockService}（afterCompletion 的注册方）与
 * {@link TransactionHelper}（afterCommit 的注册方）在同一同步上下文里验证这两种结局。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AfterCommitAfterCompletionCoexistenceTest {

    private static final Long ORDER_ID = 3001L;
    private static final Long PRODUCT_ID = 4001L;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductCacheService productCacheService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private StockService stockService;

    @BeforeEach
    void setUp() {
        stockService = new StockService(productMapper, productCacheService, redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);
        when(productMapper.restoreStock(anyLong(), anyInt())).thenReturn(1);
        when(productMapper.relistIfSoldOut(anyLong())).thenReturn(0);
    }

    /**
     * 注册一个"探针"同步器，用于记录 Spring 的两个阶段回调的实际触发顺序。
     * （测试里手工注册，等价于 Spring 调用 {@code TransactionSynchronizationUtils} 的方式。）
     */
    private static void registerProbe(List<String> timeline) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                timeline.add("afterCommit");
            }

            @Override
            public void afterCompletion(int status) {
                timeline.add("afterCompletion:" + status);
            }
        });
    }

    private static void triggerAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private static void triggerAfterCompletion(int status) {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(status));
    }

    @Test
    @DisplayName("① 提交：通知发出（afterCommit）且回补凭证保留（afterCompletion 是 COMMITTED 不动手）")
    void commitShouldNotifyAndKeepRestoreToken() {
        List<String> timeline = new ArrayList<>();

        TransactionSynchronizationManager.initSynchronization();
        try {
            registerProbe(timeline);
            // M3：通知注册 afterCommit
            TransactionHelper.runAfterCommit(() -> timeline.add("通知:已派发"));
            // 6.0.3 B1：回补内部注册 afterCompletion（凭证释放钩子）
            stockService.restoreOnce(ORDER_ID, PRODUCT_ID, 1);

            triggerAfterCommit();
            triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            assertThat(timeline).containsExactly("afterCommit", "通知:已派发", "afterCompletion:0");
            // 凭证保留：否则同一订单的后续（本不该发生的）回补会再次生效
            verify(redisTemplate, never()).delete(anyString());
            verify(productMapper).restoreStock(PRODUCT_ID, 1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("② 回滚：通知不发（afterCommit 不触发）且回补凭证释放（afterCompletion 删 key）")
    void rollbackShouldNotNotifyAndShouldReleaseRestoreToken() {
        List<String> timeline = new ArrayList<>();

        TransactionSynchronizationManager.initSynchronization();
        try {
            registerProbe(timeline);
            TransactionHelper.runAfterCommit(() -> timeline.add("通知:已派发"));
            stockService.restoreOnce(ORDER_ID, PRODUCT_ID, 1);

            // 回滚：Spring 只触发 afterCompletion(ROLLED_BACK)
            triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            assertThat(timeline).containsExactly("afterCompletion:1");
            assertThat(timeline).doesNotContain("通知:已派发");
            verify(redisTemplate).delete(RedisKeys.orderRestored(ORDER_ID));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("③ 两种钩子可同时注册且互不覆盖：同一事务里通知与回补各按自己的条件动作")
    void bothHooksShouldCoexist() {
        List<String> timeline = new ArrayList<>();

        TransactionSynchronizationManager.initSynchronization();
        try {
            TransactionHelper.runAfterCommit(() -> timeline.add("通知"));
            stockService.restoreOnce(ORDER_ID, PRODUCT_ID, 2);
            TransactionHelper.runAfterCommit(() -> timeline.add("用户状态缓存"));

            assertThat(timeline).as("注册阶段都不执行").isEmpty();
            assertThat(TransactionSynchronizationManager.getSynchronizations())
                    .as("afterCommit 两条 + afterCompletion 一条 = 3 个同步器")
                    .hasSize(3);

            triggerAfterCommit();
            assertThat(timeline).containsExactly("通知", "用户状态缓存");

            triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(timeline).as("COMMITTED 下 afterCompletion 不产生额外动作").hasSize(2);
            verify(redisTemplate, never()).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
