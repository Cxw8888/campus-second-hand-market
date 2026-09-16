package com.campus.market.dto.admin;

import com.campus.market.common.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端商品查询参数（审核列表默认查 3-待审核）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理端商品查询参数")
public class AdminProductQuery extends PageQuery {

    private static final long serialVersionUID = 1L;

    @Schema(description = "商品状态：0-下架/审核不通过, 1-上架中, 2-售罄, 3-待审核；默认 3")
    private Integer status = 3;

    @Schema(description = "关键字（标题模糊匹配，参数化 LIKE）")
    private String keyword;

    @Schema(description = "发布者ID")
    private Long userId;
}
