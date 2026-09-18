package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 商品分类分布（批次 5.5.1）：{@code categoryId} + {@code categoryName} + {@code count}。
 *
 * <p>取数口径：以 {@code tb_category} 为主表 LEFT JOIN {@code tb_product}，
 * 因此<b>没有商品的分类也会出现（count=0）</b> —— 管理员能从图上直接看出"哪个分类是空的"，
 * 这比只画有数据的分类更有信息量。</p>
 *
 * <p><b>孤儿商品</b>（5.4.4 引入「分类逻辑删除让位改名」后可能出现 category_id 指向已删除分类）
 * 会以 {@code categoryId=null} 的形式单独汇总为最后一条。注意：{@code application.yml} 里
 * {@code default-property-inclusion: non_null} 会把 null 字段整个省略，所以前端拿到的是
 * <b>字段缺失</b>而不是 {@code null}，判断时用 {@code item.categoryId == null}（能同时兜住两种）。</p>
 */
@Data
@Schema(description = "商品分类分布")
public class AdminProductCategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "分类ID；null（JSON 里整个字段缺失）= 分类已删除或未分类的孤儿商品")
    private Long categoryId;

    @Schema(description = "分类名称；null = 分类已删除，由前端出「未分类」文案")
    private String categoryName;

    @Schema(description = "该分类下商品数（未删除）")
    private Long count;

    public AdminProductCategoryVO() {
    }

    public AdminProductCategoryVO(Long categoryId, String categoryName, Long count) {
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.count = count;
    }
}
