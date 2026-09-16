package com.campus.market.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 全局字段父类：create_time / update_time / is_deleted。
 *
 * <p>例外表不继承本类：tb_favorite（无 is_deleted / update_time）、
 * shedlock（第三方结构）、tb_audit_log（只追加，无 update_time / is_deleted）。</p>
 */
@Data
public class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 创建时间，由 MetaObjectHandler 自动填充。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间，由 MetaObjectHandler 自动填充。 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0-未删除，1-已删除。 */
    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;
}
