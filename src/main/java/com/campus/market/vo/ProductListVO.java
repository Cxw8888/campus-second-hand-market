package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品列表项 VO。
 *
 * <p><b>安全约定</b>：只包含卖家 id / 昵称 / 头像，<b>严禁包含卖家 phone、email 等敏感字段</b>
 * （列表查询时也只 select 这三列，从数据源头杜绝敏感字段外泄）。</p>
 */
@Data
@Schema(description = "商品列表项（已剔除卖家手机号 / 邮箱等敏感字段）")
public class ProductListVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "商品ID")
    private Long id;

    @Schema(description = "商品标题")
    private String title;

    @Schema(description = "商品价格")
    private BigDecimal price;

    @Schema(description = "库存数量")
    private Integer stock;

    @Schema(description = "成色：1-全新, 2-几乎全新, 3-轻微使用痕迹, 4-明显使用痕迹")
    private Integer conditionLevel;

    @Schema(description = "交易方式：1-仅面交, 2-仅邮寄, 3-两者皆可")
    private Integer tradeType;

    @Schema(description = "面交地点")
    private String tradeLocation;

    @Schema(description = "封面图（imageUrls 第一张）")
    private String coverImage;

    @Schema(description = "分类ID")
    private Long categoryId;

    @Schema(description = "分类名称")
    private String categoryName;

    @Schema(description = "状态：0-下架/审核不通过, 1-上架中, 2-售罄, 3-待审核")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "卖家ID")
    private Long sellerId;

    @Schema(description = "卖家昵称")
    private String sellerNickname;

    @Schema(description = "卖家头像")
    private String sellerAvatar;
}
