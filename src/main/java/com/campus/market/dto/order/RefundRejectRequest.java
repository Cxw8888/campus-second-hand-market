package com.campus.market.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 卖家拒绝退款请求（6 → 7，进入 3 天申诉期）。
 */
@Data
@Schema(description = "拒绝退款请求")
public class RefundRejectRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "拒绝原因不能为空")
    @Size(max = 200, message = "拒绝原因长度不能超过200")
    @Schema(description = "拒绝退款原因")
    private String rejectReason;
}
