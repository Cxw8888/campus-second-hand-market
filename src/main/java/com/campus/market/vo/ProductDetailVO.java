package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 商品详情 VO：在 {@link ProductListVO} 基础上补充描述与图集。
 *
 * <p><b>安全约定</b>：同样只暴露卖家 id / 昵称 / 头像，<b>严禁包含 phone、email</b>。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "商品详情（已剔除卖家手机号 / 邮箱等敏感字段）")
public class ProductDetailVO extends ProductListVO {

    private static final long serialVersionUID = 1L;

    @Schema(description = "商品描述")
    private String description;

    @Schema(description = "商品图集（1-9 张）")
    private List<String> imageUrls;
}
