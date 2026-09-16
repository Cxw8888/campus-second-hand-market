package com.campus.market.entity.enums;

/**
 * 商品状态常量。
 */
public final class ProductStatus {

    /** 0-下架/审核不通过 */
    public static final int OFF_SHELF = 0;

    /** 1-上架中 */
    public static final int ON_SALE = 1;

    /** 2-售罄 */
    public static final int SOLD_OUT = 2;

    /** 3-待审核 */
    public static final int PENDING_AUDIT = 3;

    private ProductStatus() {
    }
}
