package com.campus.market.dto.product;

import com.campus.market.common.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 商品列表查询参数（多条件检索 + 排序下推 SQL）。
 *
 * <p>排序字段 {@code sortBy} 与排序方向 {@code order} 只允许白名单取值，
 * 由业务层拼装为 {@code ORDER BY}（严禁内存排序、严禁字符串拼接）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "商品列表查询参数")
public class ProductQuery extends PageQuery {

    private static final long serialVersionUID = 1L;

    @Schema(description = "关键字（模糊匹配标题或描述）")
    private String keyword;

    @Schema(description = "分类ID")
    private Long categoryId;

    @DecimalMin(value = "0", message = "最低价格不能小于0")
    @Schema(description = "最低价格")
    private BigDecimal minPrice;

    @DecimalMin(value = "0", message = "最高价格不能小于0")
    @Schema(description = "最高价格")
    private BigDecimal maxPrice;

    @Min(value = 1, message = "成色筛选必须为1-4")
    @Max(value = 4, message = "成色筛选必须为1-4")
    @Schema(description = "成色筛选：1-4")
    private Integer conditionLevel;

    @Min(value = 1, message = "交易方式筛选必须为1-3")
    @Max(value = 3, message = "交易方式筛选必须为1-3")
    @Schema(description = "交易方式筛选：1-仅面交, 2-仅邮寄, 3-两者皆可")
    private Integer tradeType;

    @Pattern(regexp = "price|create_time", message = "排序字段仅支持 price 或 create_time")
    @Schema(description = "排序字段：price | create_time（默认 create_time）")
    private String sortBy = "create_time";

    @Pattern(regexp = "asc|desc", message = "排序方向仅支持 asc 或 desc")
    @Schema(description = "排序方向：asc | desc（默认 desc）")
    private String order = "desc";

    /** 仅"我的商品"（卖家视角）使用：按状态筛选。 */
    @Min(value = 0, message = "商品状态必须为0-3")
    @Max(value = 3, message = "商品状态必须为0-3")
    @Schema(description = "商品状态筛选（仅 /product/my 使用）：0-下架, 1-上架中, 2-售罄, 3-待审核")
    private Integer status;
}
