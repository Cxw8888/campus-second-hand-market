package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.market.common.query.PageQuery;
import com.campus.market.common.result.PageResult;
import com.campus.market.entity.Favorite;
import com.campus.market.entity.Product;
import com.campus.market.mapper.FavoriteMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.security.UserContext;
import com.campus.market.service.FavoriteService;
import com.campus.market.vo.FavoriteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收藏服务实现（物理删除 + 失效商品状态透出）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    /** 上架中。 */
    private static final int STATUS_ON_SALE = 1;

    private final FavoriteMapper favoriteMapper;
    private final ProductMapper productMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Long productId) {
        Long userId = UserContext.requireUserId();
        Long exists = favoriteMapper.selectCount(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getProductId, productId));
        if (exists != null && exists > 0) {
            // 幂等：重复收藏直接返回成功
            log.debug("重复收藏已忽略: userId={}, productId={}", userId, productId);
            return;
        }
        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setProductId(productId);
        try {
            favoriteMapper.insert(favorite);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发下唯一键 uk_user_product 兜底，视为幂等成功
            log.debug("并发收藏命中唯一键, 已忽略: userId={}, productId={}", userId, productId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long productId) {
        Long userId = UserContext.requireUserId();
        // 物理删除
        favoriteMapper.delete(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getProductId, productId));
    }

    @Override
    public PageResult<FavoriteVO> list(PageQuery query) {
        Long userId = UserContext.requireUserId();
        Page<Favorite> page = new Page<>(query.current(), query.pageSize());
        IPage<Favorite> result = favoriteMapper.selectPage(page, Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .orderByDesc(Favorite::getCreateTime));
        // 关键：使用绕过逻辑删除的自定义 SQL，保证失效 / 已删除商品仍可返回状态
        Map<Long, Product> productMap = loadProductsIgnoreLogicDelete(result.getRecords());
        return PageResult.of(result, favorite -> toVO(favorite, productMap));
    }

    @Override
    public boolean check(Long productId) {
        Long userId = UserContext.requireUserId();
        Long count = favoriteMapper.selectCount(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getProductId, productId));
        return count != null && count > 0;
    }

    private Map<Long, Product> loadProductsIgnoreLogicDelete(List<Favorite> favorites) {
        if (favorites == null || favorites.isEmpty()) {
            return Collections.emptyMap();
        }
        Collection<Long> ids = favorites.stream().map(Favorite::getProductId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return productMapper.selectByIdsIgnoreLogicDelete(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
    }

    private FavoriteVO toVO(Favorite favorite, Map<Long, Product> productMap) {
        FavoriteVO vo = new FavoriteVO();
        vo.setId(favorite.getId());
        vo.setProductId(favorite.getProductId());
        vo.setCreateTime(favorite.getCreateTime());
        Product product = productMap.get(favorite.getProductId());
        if (product == null) {
            // 商品已被物理删除（理论上不存在，防御性处理）
            vo.setIsDeleted(Boolean.TRUE);
            vo.setAvailable(Boolean.FALSE);
            vo.setTitle("商品已不存在");
            return vo;
        }
        vo.setTitle(product.getTitle());
        vo.setPrice(product.getPrice());
        vo.setProductStatus(product.getStatus());
        vo.setIsDeleted(Integer.valueOf(1).equals(product.getIsDeleted()));
        vo.setAvailable(!Boolean.TRUE.equals(vo.getIsDeleted()) && product.getStatus() != null
                && product.getStatus() == STATUS_ON_SALE);
        if (product.getImageUrls() != null && !product.getImageUrls().isEmpty()) {
            vo.setCoverImage(product.getImageUrls().get(0));
        }
        return vo;
    }
}
