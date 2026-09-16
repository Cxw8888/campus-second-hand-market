package com.campus.market.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 分类新建 / 修改请求（管理端）。
 */
@Data
@Schema(description = "分类新建 / 修改请求")
public class CategorySaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称长度不能超过50")
    @Schema(description = "分类名称（≤50 字，全局唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "排序权重（升序展示），默认 0")
    private Integer sort = 0;
}
