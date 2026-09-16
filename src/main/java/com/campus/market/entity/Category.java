package com.campus.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.market.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品分类实体，对应表 tb_category。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "tb_category", autoResultMap = true)
public class Category extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 分类ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 分类名称 */
    @TableField("name")
    private String name;

    /** 排序权重 */
    @TableField("sort")
    private Integer sort;
}
