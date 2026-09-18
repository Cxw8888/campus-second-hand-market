package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 热门商品榜的单项（批次 5.5.2）。
 *
 * <p>同时充当 {@link AdminHotProductVO} 的列表元素与 Mapper 的查询投影
 * （与 5.5.1 的 {@code AdminOrderStatusVO} / {@code AdminProductCategoryVO}
 * 同一做法：字段一一对应时不再多造一个中间类）。</p>
 *
 * <h3>productTitle 的口径（本批最容易写错的地方）</h3>
 * <p>标题取的是<b>订单快照</b> {@code tb_order.product_title}（下单当时看到的标题），
 * 而<b>不是</b> {@code tb_product.title}：商品可能已被卖家改名、下架甚至逻辑删除，
 * 而"当时卖出去的到底是什么"才是榜单要回答的问题。同一商品的多笔订单标题可能不同，
 * SQL 用 {@code GROUP_CONCAT(... ORDER BY create_time DESC)} 取<b>最新一单</b>的标题
 * （严禁用 {@code MAX(product_title)} —— 那是字典序最大，不是最新）。</p>
 *
 * <h3>categoryName 可能为 null（前端显示「—」）</h3>
 * <p>三种情况都会让分类名为空：① 商品被逻辑删除；② 商品的 {@code category_id} 为空；
 * ③ 分类本身被逻辑删除。此时<b>不影响该商品出现在榜单里</b>（订单是真实发生过的），
 * 文案由前端出（后端不硬编码展示文案）。</p>
 */
@Data
@Schema(description = "热门商品榜单项（按订单数排名）")
public class HotProductItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "商品ID（Long → JSON 字符串，前端全程按字符串用，禁止 Number()）")
    private Long productId;

    @Schema(description = "商品标题（取订单快照中最新一单的标题）")
    private String productTitle;

    @Schema(description = "分类名称；null = 商品已删除 / 未分类 / 分类已删除（前端显示「—」）")
    private String categoryName;

    @Schema(description = "窗口内订单数（Long → JSON 字符串，前端 api 层 Number() 归一化）")
    private Long orderCount;

    public HotProductItem() {
    }

    public HotProductItem(Long productId, String productTitle, String categoryName, Long orderCount) {
        this.productId = productId;
        this.productTitle = productTitle;
        this.categoryName = categoryName;
        this.orderCount = orderCount;
    }
}
