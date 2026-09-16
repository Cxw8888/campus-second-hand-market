package com.campus.market.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 商品发布 / 编辑请求（PUT 为全量更新语义）。
 *
 * <p>校验规则（PROJECT_CONTEXT 3.7）：title 长度 1-100、price &gt; 0、
 * condition_level 为 1-4 枚举、image_urls 非空且 ≤ 9 张。</p>
 *
 * <p>注解校验失败由全局异常处理器统一映射为 {@code code=100}；业务层会再做一次兜底校验。</p>
 */
@Data
@Schema(description = "商品发布 / 编辑请求")
public class ProductSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "商品分类不能为空")
    @Schema(description = "分类ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long categoryId;

    @NotBlank(message = "商品标题不能为空")
    @Size(min = 1, max = 100, message = "商品标题长度必须为1-100")
    @Schema(description = "商品标题（1-100 字）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Size(max = 5000, message = "商品描述长度不能超过5000")
    @Schema(description = "商品描述（≤5000 字）")
    private String description;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.01", message = "商品价格必须大于0")
    @Schema(description = "商品价格（> 0，单位元）", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal price;

    @NotNull(message = "商品库存不能为空")
    @Min(value = 0, message = "商品库存不能小于0")
    @Schema(description = "库存数量（≥ 0）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer stock;

    @NotNull(message = "商品成色不能为空")
    @Min(value = 1, message = "商品成色必须为1-4")
    @Max(value = 4, message = "商品成色必须为1-4")
    @Schema(description = "成色：1-全新, 2-几乎全新, 3-轻微使用痕迹, 4-明显使用痕迹",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer conditionLevel;

    @NotNull(message = "交易方式不能为空")
    @Min(value = 1, message = "交易方式必须为1-3")
    @Max(value = 3, message = "交易方式必须为1-3")
    @Schema(description = "交易方式：1-仅面交, 2-仅邮寄, 3-两者皆可", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer tradeType;

    @Size(max = 100, message = "面交地点长度不能超过100")
    @Schema(description = "面交地点（≤100 字）")
    private String tradeLocation;

    @NotEmpty(message = "商品图片不能为空")
    @Size(max = 9, message = "商品图片最多9张")
    @Schema(description = "商品图片 URL 列表（1-9 张，先调 /api/v1/upload/image 上传）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> imageUrls;
}
