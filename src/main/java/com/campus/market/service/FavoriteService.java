package com.campus.market.service;

import com.campus.market.common.result.PageResult;
import com.campus.market.common.query.PageQuery;
import com.campus.market.vo.FavoriteVO;

/**
 * 收藏服务。
 *
 * <p>tb_favorite 采用<b>物理删除</b>（无 is_deleted / update_time），
 * 唯一键 uk_user_product (user_id, product_id) 保证重复收藏幂等。</p>
 */
public interface FavoriteService {

    /**
     * 收藏商品（幂等：已收藏直接返回成功）。
     */
    void add(Long productId);

    /**
     * 取消收藏（物理删除）。
     */
    void remove(Long productId);

    /**
     * 我的收藏列表。
     *
     * <p><b>不过滤已删除 / 已下架商品</b>：返回 productStatus 与 isDeleted，
     * 前端标注失效并提供取消收藏入口。</p>
     */
    PageResult<FavoriteVO> list(PageQuery query);

    /**
     * 是否已收藏。
     */
    boolean check(Long productId);
}
