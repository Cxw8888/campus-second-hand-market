package com.campus.market.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 商品审核请求：通过 3→1，不通过 3→0 并通知卖家。
 */
@Data
@Schema(description = "商品审核请求")
public class ProductAuditRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "审核结果不能为空")
    @Schema(description = "是否通过：true 通过（3→1），false 不通过（3→0）")
    private Boolean pass;

    @Size(max = 200, message = "审核意见长度不能超过200")
    @Schema(description = "审核意见（不通过时建议填写）")
    private String reason;
}
