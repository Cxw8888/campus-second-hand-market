package com.campus.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.market.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 站内信通知实体，对应表 tb_notification。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "tb_notification", autoResultMap = true)
public class Notification extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 通知ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 接收者ID */
    @TableField("user_id")
    private Long userId;

    /** 通知类型：1-订单, 2-审核, 3-系统 */
    @TableField("type")
    private Integer type;

    /** 业务类型：1-订单, 2-商品, 3-系统 */
    @TableField("biz_type")
    private Integer bizType;

    /** 业务ID（系统通知=0） */
    @TableField("biz_id")
    private Long bizId;

    /** 内容 */
    @TableField("content")
    private String content;

    /** 是否已读：0-未读, 1-已读 */
    @TableField("is_read")
    private Integer isRead;
}
