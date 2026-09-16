package com.campus.market.service;

import com.campus.market.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 库存回补统一入口。
 *
 * <p>所有取消 / 退款路径必须调用本类完成回补，严禁各处自行拼 SQL：</p>
 * <ol>
 *   <li>买家主动取消（0→4）</li>
 *   <li>超时自动取消（0→4）</li>
 *   <li>管理端解冻转取消（5→4）</li>
 *   <li>卖家同意退款（6→4）</li>
 *   <li>管理员强制退款（6/7→4）</li>
 *   <li>封禁冻结订单（0/1/2/6→5）时同步回补</li>
 * </ol>
 *
 * <p>回补 SQL 为 CAS 风格原子操作（stock + quantity，售罄自动恢复上架），
 * MySQL 行锁已保证并发安全，<b>无需 Redisson 分布式锁</b>。
 * 调用方必须在与订单状态更新<b>同一事务</b>内调用本方法（{@code @Transactional}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockService {

    private final ProductMapper productMapper;
    private final ProductCacheService productCacheService;

    /**
     * 回补库存（统一入口）。
     *
     * <p>回补会联动 {@code status: 2-售罄 → 1-上架中}，因此必须删除商品详情缓存
     * （写路径统一"先更新 DB，再删除缓存"）。</p>
     *
     * @param productId 商品ID
     * @param quantity  回补数量
     * @return 影响行数，0 表示商品不存在或已删除
     */
    public int restore(Long productId, Integer quantity) {
        if (productId == null || quantity == null || quantity <= 0) {
            log.warn("库存回补参数非法, 已跳过: productId={}, quantity={}", productId, quantity);
            return 0;
        }
        int rows = productMapper.restoreStock(productId, quantity);
        if (rows == 0) {
            // 商品已被逻辑删除等情况：记录告警但不阻断订单状态流转
            log.warn("库存回补未生效: productId={}, quantity={}", productId, quantity);
        }
        // 无论是否命中，都按写路径规范失效详情缓存（售罄→上架的状态联动需要立刻可见）
        productCacheService.evictDetail(productId);
        return rows;
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
}
