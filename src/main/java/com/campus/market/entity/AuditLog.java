package com.campus.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端审计日志实体，对应表 tb_audit_log（只追加，无 update_time / is_deleted）。
 */
@Data
@TableName(value = "tb_audit_log", autoResultMap = true)
public class AuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 日志ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作人ID */
    @TableField("operator_id")
    private Long operatorId;

    /** 操作人名称 */
    @TableField("operator_name")
    private String operatorName;

    /** 操作类型 */
    @TableField("operation_type")
    private String operationType;

    /** 目标类型 */
    @TableField("target_type")
    private String targetType;

    /** 目标ID */
    @TableField("target_id")
    private Long targetId;

    /** 结果：1-成功, 0-失败 */
    @TableField("result")
    private Integer result;

    /** 详情 */
    @TableField("detail")
    private String detail;

    /** 操作IP */
    @TableField("ip")
    private String ip;

    /** 操作时间 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
