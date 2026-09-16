package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收藏项视图对象。
 *
 * <p><b>收藏列表不过滤已删除 / 已下架商品</b>：必须返回商品当前状态（productStatus、isDeleted），
 * 前端据此标注"已下架 / 已售罄 / 已删除"并提供"取消收藏"入口。</p>
 */
@Data
@Schema(description = "收藏项（含商品当前状态，含失效商品）")
public class FavoriteVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "收藏ID")
    private Long id;

    @Schema(description = "商品ID")
    private Long productId;

    @Schema(description = "商品标题（商品已删除时为快照占位说明）")
    private String title;

    @Schema(description = "封面图")
    private String coverImage;

    @Schema(description = "当前价格")
    private BigDecimal price;

    @Schema(description = "商品当前状态：0-下架/审核不通过, 1-上架中, 2-售罄, 3-待审核")
    private Integer productStatus;

    @Schema(description = "商品是否已被逻辑删除")
    private Boolean isDeleted;

    @Schema(description = "商品是否仍可下单（status=1 且 is_deleted=0）")
    private Boolean available;

    @Schema(description = "收藏时间")
    private LocalDateTime createTime;
}
