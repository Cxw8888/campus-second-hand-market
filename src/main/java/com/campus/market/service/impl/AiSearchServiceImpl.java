package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.market.common.result.PageResult;
import com.campus.market.dto.product.ProductQuery;
import com.campus.market.entity.Product;
import com.campus.market.entity.enums.ProductStatus;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.service.AiSearchService;
import com.campus.market.vo.ProductListVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 检索接口预留实现（MVP 退化版）。
 *
 * <p>语义检索能力（Spring AI + ES dense_vector kNN + BGE-M3）为论文创新点，
 * 在 P2 阶段替换本实现；MVP 阶段使用参数化 LIKE 保证接口契约与前端联调可用。</p>
 *
 * <p>熔断与缓存：超时熔断 500ms、结果缓存 60 秒属于接线层能力（Spring Retry / Redis），
 * 已在 {@code RedisKeys.AI_SEARCH_PREFIX} 预留缓存 Key 前缀；当前实现直接落 DB，
 * 保持骨架可运行，后续在该方法内加入 {@code @Cacheable} 或手动缓存即可。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSearchServiceImpl implements AiSearchService {

    private final ProductMapper productMapper;

    @Override
    public PageResult<ProductListVO> search(ProductQuery query) {
        Page<Product> page = new Page<>(query.current(), query.pageSize());
        String keyword = query.getKeyword();
        // TODO: P1阶段实现 500ms 熔断与 60s 结果缓存
        // 参数化 LIKE（wrapper.like 已参数化，等价于 CONCAT('%', #{keyword}, '%')），严禁字符串拼接
        IPage<Product> result = productMapper.selectPage(page, Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, ProductStatus.ON_SALE)
                .and(keyword != null && !keyword.isBlank(), wrapper -> wrapper
                        .like(Product::getTitle, keyword)
                        .or().like(Product::getDescription, keyword))
                .eq(query.getCategoryId() != null, Product::getCategoryId, query.getCategoryId())
                .ge(query.getMinPrice() != null, Product::getPrice, query.getMinPrice())
                .le(query.getMaxPrice() != null, Product::getPrice, query.getMaxPrice())
                .orderByDesc(Product::getCreateTime));
        return PageResult.of(result, product -> {
            ProductListVO vo = new ProductListVO();
            vo.setId(product.getId());
            vo.setTitle(product.getTitle());
            vo.setPrice(product.getPrice());
            vo.setStock(product.getStock());
            vo.setConditionLevel(product.getConditionLevel());
            vo.setTradeType(product.getTradeType());
            vo.setTradeLocation(product.getTradeLocation());
            vo.setCategoryId(product.getCategoryId());
            vo.setStatus(product.getStatus());
            vo.setCreateTime(product.getCreateTime());
            vo.setSellerId(product.getUserId());
            if (product.getImageUrls() != null && !product.getImageUrls().isEmpty()) {
                vo.setCoverImage(product.getImageUrls().get(0));
            }
            return vo;
        });
    }
}
