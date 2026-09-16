package com.campus.market.common.enums;

/**
 * 管理端审计操作类型（tb_audit_log.operation_type）。
 *
 * <p>枚举名直接落库为字符串，严禁使用魔法值。</p>
 */
public enum AuditOperationType {

    /** 商品审核通过（3→1）。 */
    APPROVE_PRODUCT,

    /** 商品审核不通过（3→0）。 */
    REJECT_PRODUCT,

    /** 封禁用户。 */
    BAN_USER,

    /** 解封用户。 */
    UNBAN_USER,

    /** 强制下架商品（→0）。 */
    FORCE_OFFLINE,

    /** 解冻订单（5→4）。 */
    UNFREEZE_ORDER,

    /** 已完成订单（5→3）。 */
    COMPLETE_ORDER,

    /** 新建分类。 */
    CREATE_CATEGORY,

    /** 修改分类。 */
    UPDATE_CATEGORY,

    /** 删除分类。 */
    DELETE_CATEGORY,

    /** 强制退款（6/7→4）。 */
    REFUND_ORDER
}
