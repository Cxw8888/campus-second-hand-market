package com.campus.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.campus.market.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品实体，对应表 tb_product。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "tb_product", autoResultMap = true)
public class Product extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 发布者ID */
    @TableField("user_id")
    private Long userId;

    /** 分类ID */
    @TableField("category_id")
    private Long categoryId;

    /** 商品标题 */
    @TableField("title")
    private String title;

    /** 商品描述 */
    @TableField("description")
    private String description;

    /** 价格 */
    @TableField("price")
    private BigDecimal price;

    /** 库存数量 */
    @TableField("stock")
    private Integer stock;

    /** 成色：1-全新, 2-几乎全新, 3-轻微使用痕迹, 4-明显使用痕迹 */
    @TableField("condition_level")
    private Integer conditionLevel;

    /** 交易方式：1-仅面交, 2-仅邮寄, 3-两者皆可 */
    @TableField("trade_type")
    private Integer tradeType;

    /** 面交地点 */
    @TableField("trade_location")
    private String tradeLocation;

    /** 商品多图（JSON 数组，JSON 类型字段需 autoResultMap） */
    @TableField(value = "image_urls", typeHandler = JacksonTypeHandler.class)
    private List<String> imageUrls;

    /** 状态：0-下架/审核不通过, 1-上架中, 2-售罄, 3-待审核 */
    @TableField("status")
    private Integer status;
}
