package com.campus.market.entity.enums;

/**
 * 订单状态常量。
 */
public final class OrderStatus {

    /** 0-待支付 */
    public static final int PENDING_PAY = 0;

    /** 1-已支付待发货 */
    public static final int PAID = 1;

    /** 2-已发货待收货 */
    public static final int SHIPPED = 2;

    /** 3-已完成 */
    public static final int FINISHED = 3;

    /** 4-已取消 */
    public static final int CANCELLED = 4;

    /** 5-已冻结 */
    public static final int FROZEN = 5;

    /** 6-退款申请中 */
    public static final int REFUND_APPLYING = 6;

    /** 7-退款被拒 */
    public static final int REFUND_REJECTED = 7;

    private OrderStatus() {
    }
}
