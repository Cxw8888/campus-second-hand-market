package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 商品发布结果 VO：返回新商品ID。
 */
@Data
@Schema(description = "商品发布结果")
public class ProductSaveVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "商品ID（新发布商品的ID，落库状态为 3-待审核）")
    private Long id;
}
