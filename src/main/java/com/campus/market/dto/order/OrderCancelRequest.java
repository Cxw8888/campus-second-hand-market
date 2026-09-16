package com.campus.market.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 买家取消订单请求（仅 status=0 待支付订单可取消）。
 */
@Data
@Schema(description = "取消订单请求")
public class OrderCancelRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Size(max = 200, message = "取消原因长度不能超过200")
    @Schema(description = "取消原因")
    private String reason;
}
