package com.campus.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.market.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体，对应表 tb_order。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "tb_order", autoResultMap = true)
public class Order extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 订单ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单号（雪花算法） */
    @TableField("order_no")
    private String orderNo;

    /** 买家ID */
    @TableField("user_id")
    private Long userId;

    /** 商品ID */
    @TableField("product_id")
    private Long productId;

    /** 卖家ID */
    @TableField("seller_id")
    private Long sellerId;

    /** 价格快照 */
    @TableField("product_price")
    private BigDecimal productPrice;

    /** 总金额（= product_price × quantity） */
    @TableField("amount")
    private BigDecimal amount;

    /** 购买数量 */
    @TableField("quantity")
    private Integer quantity;

    /** 订单状态：0-待支付,1-已支付待发货,2-已发货待收货,3-已完成,4-已取消,5-已冻结,6-退款申请中,7-退款被拒 */
    @TableField("status")
    private Integer status;

    /** 收货地址（邮寄必填） */
    @TableField("address")
    private String address;

    /** 交易方式快照（后端从商品读取） */
    @TableField("trade_type")
    private Integer tradeType;

    /** 支付时间 */
    @TableField("pay_time")
    private LocalDateTime payTime;

    /** 发货时间 */
    @TableField("ship_time")
    private LocalDateTime shipTime;

    /** 完成时间 */
    @TableField("finish_time")
    private LocalDateTime finishTime;

    /** 取消时间 */
    @TableField("cancel_time")
    private LocalDateTime cancelTime;

    /** 取消原因 */
    @TableField("cancel_reason")
    private String cancelReason;

    /** 取消操作人（0=系统） */
    @TableField("cancel_by")
    private Long cancelBy;

    /** 退款申请时间 */
    @TableField("refund_apply_time")
    private LocalDateTime refundApplyTime;

    /** 卖家拒绝退款时间 */
    @TableField("refund_reject_time")
    private LocalDateTime refundRejectTime;

    /** 商品标题快照（下单时写入） */
    @TableField("product_title")
    private String productTitle;

    /** 买家申请退款原因 */
    @TableField("refund_reason")
    private String refundReason;

    /** 卖家拒绝退款原因 */
    @TableField("refund_reject_reason")
    private String refundRejectReason;
}
