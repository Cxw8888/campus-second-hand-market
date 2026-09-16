package com.campus.market.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 模拟支付回调请求。
 *
 * <p>本项目为校园线下真实交易的辅助平台，系统内"支付"为<b>模拟支付回调</b>，真实资金流转在线下进行。</p>
 *
 * <p>幂等约定：按 {@code order_no + 回调流水号（tradeNo）} 去重，重复回调直接返回成功。</p>
 */
@Data
@Schema(description = "模拟支付回调请求")
public class PayCallbackRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "订单号不能为空")
    @Schema(description = "订单号（雪花算法）")
    private String orderNo;

    @NotBlank(message = "支付流水号不能为空")
    @Schema(description = "支付回调流水号（用于幂等去重）")
    private String tradeNo;
}
