package com.campus.market.service;

import com.campus.market.common.result.PageResult;
import com.campus.market.dto.product.ProductQuery;
import com.campus.market.dto.product.ProductSaveRequest;
import com.campus.market.vo.ProductDetailVO;
import com.campus.market.vo.ProductListVO;

/**
 * 商品服务：检索、详情（可见性分级）、发布 / 编辑 / 删除 / 下架。
 *
 * <p>一致性约定：写路径必须"先更新 DB，再删除缓存"（{@code product:detail:{id}}）。</p>
 */
public interface ProductService {

    /**
     * 商品列表（游客 / 买家视角）：仅返回上架中（status=1）商品，排序下推 SQL。
     */
    PageResult<ProductListVO> list(ProductQuery query);

    /**
     * 我的商品（卖家视角）：返回当前登录用户的全部状态商品，可按 status 筛选。
     */
    PageResult<ProductListVO> listMy(ProductQuery query);

    /**
     * 商品详情，可见性分级：
     * <ul>
     *   <li>未登录：仅 status=1；</li>
     *   <li>已登录非卖家 / 非管理员：status IN (0,1,2)（禁止查看待审核 3）；</li>
     *   <li>卖家本人：全部状态；</li>
     *   <li>管理员：全部状态。</li>
     * </ul>
     * 不满足 → code=204。
     */
    ProductDetailVO detail(Long id);

    /**
     * 发布商品：落库 status=3（待审核）。
     *
     * @return 新商品ID
     */
    Long create(ProductSaveRequest request);

    /**
     * 编辑商品（全量更新语义）：关键字段变更或 status=0/3 → 重置为 3-待审核；
     * status=2 且新库存 &gt; 0 → 置为 1-上架中。
     */
    void update(Long id, ProductSaveRequest request);

    /**
     * 删除商品（逻辑删除）：存在未完成订单（0/1/2/6/7）→ code=207。
     */
    void delete(Long id);

    /**
     * 卖家下架自己的商品（status=1 → 0）。
     */
    void offShelf(Long id);
}
