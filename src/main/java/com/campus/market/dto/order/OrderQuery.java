package com.campus.market.dto.order;

import com.campus.market.common.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单列表查询：role=buyer 查"我买到的"，role=seller 查"我卖出的"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单列表查询参数")
public class OrderQuery extends PageQuery {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单状态：0-待支付,1-已支付待发货,2-已发货待收货,3-已完成,4-已取消,5-已冻结,6-退款申请中,7-退款被拒")
    private Integer status;

    @Schema(description = "视角：buyer-我买到的（默认），seller-我卖出的")
    private String role = "buyer";
}
