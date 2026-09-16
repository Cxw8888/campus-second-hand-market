package com.campus.market.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 分类级联迁移请求（管理端）：把源分类下所有商品迁移到目标分类。
 */
@Data
@Schema(description = "分类级联迁移请求")
public class CategoryMigrateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "源分类不能为空")
    @Schema(description = "源分类ID（迁移前）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long fromCategoryId;

    @NotNull(message = "目标分类不能为空")
    @Schema(description = "目标分类ID（迁移后）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long toCategoryId;
}
