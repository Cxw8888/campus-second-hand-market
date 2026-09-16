package com.campus.market.service;

import com.campus.market.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品缓存失效统一入口（写路径专用）。
 *
 * <p>数据库写入路径（发布 / 编辑 / 下架 / 删除 / 审核状态流转 / 强制下架 /
 * 下单 CAS 扣减 / 取消回补 / 售罄与恢复上架联动 / 分类迁移）一律调用本组件删除缓存，
 * 统一遵守 <b>"先更新 DB，再删除缓存"</b>——严禁先删缓存再更新 DB。</p>
 *
 * <p>删除失败或并发读导致短期脏数据，由延迟双删或短 TTL 兜底；
 * Redis 故障时降级：捕获异常并 log.warn，<b>不阻断主业务事务</b>。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCacheService {

    private final StringRedisTemplate redisTemplate;

    /**
     * 删除商品详情缓存 {@code product:detail:{productId}}。
     *
     * @param productId 商品ID，为空时直接跳过
     */
    public void evictDetail(Long productId) {
        if (productId == null) {
            return;
        }
        try {
            Boolean deleted = redisTemplate.delete(RedisKeys.productDetail(productId));
            log.debug("删除商品详情缓存: productId={}, deleted={}", productId, deleted);
        } catch (Exception e) {
            // 降级：缓存删除失败不影响 DB 事务提交，由 TTL 兜底
            log.warn("删除商品详情缓存失败（已降级，TTL 兜底）: productId={}, err={}", productId, e.getMessage());
        }
    }

    /**
     * 批量删除商品详情缓存（封禁用户批量下架商品等场景）。
     *
     * @param productIds 商品ID集合，为空时直接跳过
     */
    public void evictDetails(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        try {
            Set<String> keys = productIds.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(RedisKeys::productDetail)
                    .collect(Collectors.toSet());
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("批量删除商品详情缓存失败（已降级，TTL 兜底）: size={}, err={}", productIds.size(), e.getMessage());
        }
    }

    /**
     * 删除商品分类列表缓存 {@code product:category:list}。
     */
    public void evictCategoryList() {
        try {
            redisTemplate.delete(RedisKeys.PRODUCT_CATEGORY_LIST);
        } catch (Exception e) {
            log.warn("删除分类列表缓存失败（已降级，TTL 兜底）: err={}", e.getMessage());
        }
    }
}
