package com.campus.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 收藏实体，对应表 tb_favorite（物理删除，无 update_time / is_deleted）。
 */
@Data
@TableName(value = "tb_favorite", autoResultMap = true)
public class Favorite implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 收藏ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @TableField("user_id")
    private Long userId;

    /** 商品ID */
    @TableField("product_id")
    private Long productId;

    /** 创建时间 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
