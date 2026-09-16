package com.campus.market.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 买家申请退款请求（1/2 → 6）。
 */
@Data
@Schema(description = "申请退款请求")
public class RefundApplyRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "退款原因不能为空")
    @Size(max = 200, message = "退款原因长度不能超过200")
    @Schema(description = "退款原因")
    private String reason;
}
