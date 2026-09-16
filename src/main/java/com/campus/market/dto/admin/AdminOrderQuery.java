package com.campus.market.dto.admin;

import com.campus.market.common.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端订单查询参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理端订单查询参数")
public class AdminOrderQuery extends PageQuery {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单状态筛选")
    private Integer status;

    @Schema(description = "订单号（精确匹配）")
    private String orderNo;

    @Schema(description = "买家ID")
    private Long userId;

    @Schema(description = "卖家ID")
    private Long sellerId;
}
