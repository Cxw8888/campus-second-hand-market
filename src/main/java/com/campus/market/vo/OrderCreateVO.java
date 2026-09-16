package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 下单响应：返回订单关键信息，前端据此跳转支付 / 订单详情。
 */
@Data
@Schema(description = "下单结果")
public class OrderCreateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "订单号（雪花算法）")
    private String orderNo;

    @Schema(description = "订单总金额（后端计算：product_price × quantity）")
    private BigDecimal amount;

    @Schema(description = "订单状态：0-待支付")
    private Integer status;
}
