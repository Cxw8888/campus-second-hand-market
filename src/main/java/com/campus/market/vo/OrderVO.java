package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单视图对象。
 *
 * <p>商品标题来自订单快照 {@code product_title}，防止卖家编辑商品后订单历史失真；
 * 商品已逻辑删除时仍可通过自定义 SQL 取回商品信息（不得使用 @InterceptorIgnore 作为主方案）。</p>
 */
@Data
@Schema(description = "订单信息")
public class OrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单ID")
    private Long id;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "买家ID")
    private Long userId;

    @Schema(description = "卖家ID")
    private Long sellerId;

    @Schema(description = "商品ID")
    private Long productId;

    @Schema(description = "商品标题快照")
    private String productTitle;

    @Schema(description = "商品封面图（商品已删除时为空）")
    private String productCover;

    @Schema(description = "商品是否已被删除（true 时前端展示快照信息）")
    private Boolean productDeleted;

    @Schema(description = "成交单价快照")
    private BigDecimal productPrice;

    @Schema(description = "订单总金额")
    private BigDecimal amount;

    @Schema(description = "购买数量")
    private Integer quantity;

    @Schema(description = "状态：0-待支付,1-已支付待发货,2-已发货待收货,3-已完成,4-已取消,5-已冻结,6-退款申请中,7-退款被拒")
    private Integer status;

    @Schema(description = "收货地址")
    private String address;

    @Schema(description = "交易方式快照：1-仅面交, 2-仅邮寄, 3-两者皆可")
    private Integer tradeType;

    @Schema(description = "支付时间")
    private LocalDateTime payTime;

    @Schema(description = "发货时间")
    private LocalDateTime shipTime;

    @Schema(description = "完成时间")
    private LocalDateTime finishTime;

    @Schema(description = "取消时间")
    private LocalDateTime cancelTime;

    @Schema(description = "取消原因")
    private String cancelReason;

    @Schema(description = "取消操作人（0=系统）")
    private Long cancelBy;

    @Schema(description = "退款申请时间")
    private LocalDateTime refundApplyTime;

    @Schema(description = "卖家拒绝退款时间")
    private LocalDateTime refundRejectTime;

    @Schema(description = "买家退款原因")
    private String refundReason;

    @Schema(description = "卖家拒绝退款原因")
    private String refundRejectReason;

    @Schema(description = "下单时间")
    private LocalDateTime createTime;

    @Schema(description = "商品图片（商品已删除时为空）")
    private List<String> productImages;
}
