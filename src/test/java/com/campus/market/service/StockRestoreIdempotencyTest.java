package com.campus.market.service;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.mapper.ProductMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.3 安全加固 · B1 单测（第一组）：库存回补的订单维度幂等凭证。
 *
 * <p>守的是自审报告 B1：库存回补是"加法"，加两次就是库存失真 ——
 * 修前"封禁时回补 + 解冻 CANCEL 又回补"，库存 1 的商品能被刷成 2，反复封禁解冻可无限刷。
 * 订单状态机的 SQL 前置条件只能保证"同一条流转不发生两次"，挡不住<b>两条不同路径</b>
 * 先后对同一订单回补，所以必须有 {@code order:restored:{orderId}} 这份订单维度的凭证。</p>
 *
 * <p>这里用真实的 {@link StockService}（只 mock 其下层的 Mapper / 缓存 / Redis），
 * 因为要断言的正是它内部的凭证时序：抢占 → 回补 → 重新上架 → 失败/回滚时释放。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockRestoreIdempotencyTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long PRODUCT_ID = 2001L;

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
        when(productMapper.relistIfSoldOut(anyLong())).thenReturn(1);
    }

    @Test
    @DisplayName("① 首次回补：抢占凭证(TTL 30 天) → 加库存 → 售罄则重新上架 → 失效缓存")
    void firstRestoreShouldAcquireTokenAndRelist() {
        int rows = stockService.restoreOnce(ORDER_ID, PRODUCT_ID, 2);

        assertThat(rows).isEqualTo(1);
        verify(valueOperations).setIfAbsent(RedisKeys.orderRestored(ORDER_ID), "1", Duration.ofDays(30));
        verify(productMapper).restoreStock(PRODUCT_ID, 2);
        // 回补不改状态；"是否重新上架"是显式第二步（B2）
        verify(productMapper).relistIfSoldOut(PRODUCT_ID);
        verify(productCacheService).evictDetail(PRODUCT_ID);
        // 成功回补不得释放凭证
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("② 已有凭证（订单已回补过）→ 直接跳过，一条 SQL 都不发")
    void secondRestoreShouldBeSkipped() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.FALSE);

        int rows = stockService.restoreOnce(ORDER_ID, PRODUCT_ID, 2);

        assertThat(rows).isZero();
        verify(productMapper, never()).restoreStock(anyLong(), anyInt());
        verify(productMapper, never()).relistIfSoldOut(anyLong());
        verify(productCacheService, never()).evictDetail(anyLong());
    }

    @Test
    @DisplayName("③ 回补未命中（商品不存在/已删除）→ 释放凭证，允许以后重试")
    void missedRestoreShouldReleaseToken() {
        when(productMapper.restoreStock(anyLong(), anyInt())).thenReturn(0);

        int rows = stockService.restoreOnce(ORDER_ID, PRODUCT_ID, 2);

        assertThat(rows).isZero();
        verify(redisTemplate).delete(RedisKeys.orderRestored(ORDER_ID));
        verify(productMapper, never()).relistIfSoldOut(anyLong());
    }

    @Test
    @DisplayName("④ Redis 故障 → 降级放行（继续回补）且不抛异常；订单状态机兜底")
    void redisFailureShouldDegradeToRestoreAnyway() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        int rows = stockService.restoreOnce(ORDER_ID, PRODUCT_ID, 2);

        assertThat(rows).isEqualTo(1);
        verify(productMapper).restoreStock(PRODUCT_ID, 2);
    }

    @Test
    @DisplayName("⑤ 事务回滚 → afterCompletion 释放凭证，避免\"凭证在、库存没加\"导致永久少一件")
    void rollbackShouldReleaseToken() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            stockService.restoreOnce(ORDER_ID, PRODUCT_ID, 2);

            var synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            // 事务提交：凭证保留
            synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            verify(redisTemplate, never()).delete(anyString());
            // 事务回滚：凭证释放
            synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(redisTemplate).delete(RedisKeys.orderRestored(ORDER_ID));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("⑥ 没有订单ID → 拒绝回补（裸回补正是 B1 的成因，宁可漏补不可裸补）")
    void missingOrderIdShouldRefuseToRestore() {
        int rows = stockService.restoreOnce(null, PRODUCT_ID, 2);

        assertThat(rows).isZero();
        verify(productMapper, never()).restoreStock(anyLong(), anyInt());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("⑦ 参数非法（数量<=0 / 商品为空）→ 不碰 Redis 也不发 SQL（回归保护）")
    void invalidArgumentsShouldBeIgnored() {
        assertThat(stockService.restoreOnce(ORDER_ID, PRODUCT_ID, 0)).isZero();
        assertThat(stockService.restoreOnce(ORDER_ID, null, 2)).isZero();

        verify(productMapper, never()).restoreStock(anyLong(), anyInt());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("⑧ 扣减路径不受影响：deduct 仍只走 CAS 扣减 + 失效缓存（本批未改）")
    void deductShouldRemainUnchanged() {
        stockService.deduct(PRODUCT_ID, 1);

        verify(productMapper).deductStock(eq(PRODUCT_ID), eq(1));
        verify(productCacheService).evictDetail(PRODUCT_ID);
    }
}
