package com.campus.market.service;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

/**
 * 库存回补统一入口（带订单维度幂等凭证）。
 *
 * <p>所有取消 / 退款 / 解冻路径必须调用本类完成回补，严禁各处自行拼 SQL：</p>
 * <ol>
 *   <li>买家主动取消（0→4）</li>
 *   <li>超时自动取消（0→4）</li>
 *   <li>管理端解冻转取消（5→4）</li>
 *   <li>卖家同意退款（6→4）</li>
 *   <li>管理员强制退款（6/7→4）</li>
 * </ol>
 *
 * <p><b>批次 6.0.3 · B1 起，封禁冻结订单（0/1/2/6→5）不再回补库存</b> ——
 * 冻结只是"交易暂停"，真正终止交易的是 5→4；封禁时就回补会让后续 5→4 再补一次
 * （库存 1 的商品被刷成 2，反复封禁/解冻可无限刷）。</p>
 *
 * <h3>为什么必须带幂等凭证</h3>
 * <p>订单状态机的 SQL 前置条件（{@code WHERE status = 0} 等）只能保证<b>同一条流转</b>
 * 不会发生两次，挡不住<b>两条不同路径先后对同一订单回补</b>。
 * 而库存回补是"加法"，加两次就是库存失真（自审报告 B1）。因此每次回补前先抢占
 * {@code order:restored:{orderId}}（SETNX，TTL 30 天）：抢不到 = 这个订单已经补过，直接跳过。</p>
 *
 * <h3>凭证与 MySQL 事务的边界（关键）</h3>
 * <ul>
 *   <li><b>回补未命中</b>（商品不存在 / 已逻辑删除，影响行数 0）→ 立即删除凭证，允许以后重试；</li>
 *   <li><b>抛异常</b> → 立即删除凭证；</li>
 *   <li><b>事务回滚</b>（凭证已写、SQL 也已执行，但事务没提交）→ 注册
 *       {@link TransactionSynchronization} 钩子，在 afterCompletion 里删除凭证。
 *       否则"凭证留下、库存没加"会让下一次合法的回补被静默跳过 = <b>永久少一件库存</b>；</li>
 *   <li><b>Redis 不可用</b> → 降级放行（继续回补）并打 warn。理由与支付回调去重一致：
 *       订单状态机本身已能挡住"同一流转重复执行"，凭证是第二道防线；
 *       反过来若"Redis 挂了就不回补"，会让已取消的订单永久吞掉库存，损失更实在。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockService {

    /** 回补幂等凭证 TTL：30 天（覆盖订单全部生命周期，见 {@link RedisKeys#ORDER_RESTORED_PREFIX}）。 */
    private static final Duration RESTORE_TOKEN_TTL = Duration.ofDays(30);

    private final ProductMapper productMapper;
    private final ProductCacheService productCacheService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 回补库存（统一入口，<b>订单维度幂等</b>）。
     *
     * <p>两步语义分离，都要做：</p>
     * <ol>
     *   <li>{@code stock = stock + quantity}（{@link ProductMapper#restoreStock}，<b>不改状态</b>）；</li>
     *   <li>商品若因本单售罄且仍有库存 → 重新上架（{@link ProductMapper#relistIfSoldOut}）。
     *       这是"交易终止后商品应重新可售"的显式一步，与加库存解耦（B2）。</li>
     * </ol>
     *
     * <p>调用方必须在与订单状态更新<b>同一事务</b>内调用（{@code @Transactional}）。</p>
     *
     * @param orderId   订单ID（幂等凭证的主体；为空则拒绝回补，避免无凭证的裸回补）
     * @param productId 商品ID
     * @param quantity  回补数量
     * @return 回补影响行数；0 表示未回补（参数非法 / 已回补过 / 商品不存在）
     */
    public int restoreOnce(Long orderId, Long productId, Integer quantity) {
        if (productId == null || quantity == null || quantity <= 0) {
            log.warn("库存回补参数非法, 已跳过: orderId={}, productId={}, quantity={}", orderId, productId, quantity);
            return 0;
        }
        if (orderId == null) {
            // 没有订单ID就没有幂等主体，宁可漏补也不能裸补（裸补正是 B1 的成因）
            log.error("库存回补缺少订单ID, 已跳过（幂等凭证必须绑定订单）: productId={}, quantity={}",
                    productId, quantity);
            return 0;
        }
        if (!tryAcquireRestoreToken(orderId)) {
            log.warn("订单 {} 的库存已回补过, 跳过重复回补: productId={}, quantity={}", orderId, productId, quantity);
            return 0;
        }

        try {
            int rows = productMapper.restoreStock(productId, quantity);
            // 无论是否命中，都按写路径规范失效详情缓存
            productCacheService.evictDetail(productId);
            if (rows == 0) {
                log.warn("库存回补未生效（商品不存在或已逻辑删除）, 已释放幂等凭证以允许重试: orderId={}, productId={}",
                        orderId, productId);
                releaseRestoreToken(orderId);
                return 0;
            }
            // 售罄(2) 且回补后仍有库存 → 重新上架(1)；已下架(0) 的商品不会被这条 SQL 放出来
            int relisted = productMapper.relistIfSoldOut(productId);
            // 事务回滚时释放凭证（详见类注释）
            releaseTokenOnRollback(orderId);
            if (relisted > 0) {
                log.info("库存回补后商品重新上架: orderId={}, productId={}, quantity={}", orderId, productId, quantity);
            }
            return rows;
        } catch (RuntimeException e) {
            releaseRestoreToken(orderId);
            throw e;
        }
    }

    /**
     * CAS 扣减库存（防超卖）。
     *
     * <p>扣减会联动 {@code stock 归零 → status: 1-上架中 → 2-售罄}，因此必须删除商品详情缓存。</p>
     *
     * @return 影响行数，0 表示库存不足或商品状态不允许
     */
    public int deduct(Long productId, Integer quantity) {
        int rows = productMapper.deductStock(productId, quantity);
        productCacheService.evictDetail(productId);
        return rows;
    }

    // ------------------------------------------------------------------ 幂等凭证

    /**
     * 抢占回补凭证：SETNX {@code order:restored:{orderId}}，TTL 30 天。
     *
     * @return true = 抢到（本次可以回补）；false = 已有凭证（跳过）；Redis 故障时返回 true（降级放行）
     */
    private boolean tryAcquireRestoreToken(Long orderId) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(RedisKeys.orderRestored(orderId), "1", RESTORE_TOKEN_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("库存回补幂等凭证写入失败（降级放行，由订单状态机兜底）: orderId={}, err={}",
                    orderId, e.getMessage());
            return true;
        }
    }

    /** 释放回补凭证（仅用于"本次没真正补成"的场景，让以后的调用还能补）。 */
    private void releaseRestoreToken(Long orderId) {
        try {
            redisTemplate.delete(RedisKeys.orderRestored(orderId));
        } catch (Exception e) {
            log.warn("库存回补幂等凭证删除失败（降级）: orderId={}, err={}", orderId, e.getMessage());
        }
    }

    /**
     * 注册"事务回滚则释放凭证"的钩子。
     *
     * <p>凭证写在 Redis、库存在 MySQL，两者不在同一个事务里。若本次回补之后事务回滚，
     * 库存没加、凭证却留下了 —— 之后的合法回补会被静默跳过，等价于永久少一件库存。
     * 所以在有事务同步时挂一个 afterCompletion：只要不是 COMMITTED 就释放凭证。</p>
     */
    private void releaseTokenOnRollback(Long orderId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    log.warn("事务未提交（status={}）, 释放库存回补幂等凭证以允许重试: orderId={}", status, orderId);
                    releaseRestoreToken(orderId);
                }
            }
        });
    }
}
