package com.campus.market.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 模拟支付回调请求（批次 6.0.2 安全加固 · S3 起必须携带签名）。
 *
 * <p>本项目为校园线下真实交易的辅助平台，系统内"支付"为<b>模拟支付回调</b>，真实资金流转在线下进行。</p>
 *
 * <h3>签名约定（与支付网关一致，详见 {@code PayCallbackSignService}）</h3>
 * <pre>
 * payload = orderNo + "|" + tradeNo + "|" + timestamp        （UTF-8）
 * sign    = HMAC-SHA256(payload, callbackSecret)             （十六进制小写）
 * </pre>
 *
 * <p>幂等约定：按 {@code order_no + 回调流水号（tradeNo）} 去重，重复回调直接返回成功
 * （去重在<b>验签通过之后</b>执行，未通过验签的请求既不改状态也不占去重键）。</p>
 *
 * <h3>为什么 timestamp / sign 不加 {@code @NotBlank} / {@code @NotNull}</h3>
 * <p>校验顺序由需求固定为「① 时间戳不为空且在窗口内 → ② 签名不为空 → ③ HMAC 匹配」，
 * 且任一失败的对外文案<b>统一</b>为「回调签名校验失败」。若把两个字段交给 Bean Validation，
 * 两者同时缺失时返回的是 Spring 拼装的字段错误文案，顺序也不受控 ——
 * 既拿不到规定的顺序，也拿不到统一文案。故这两个字段由
 * {@code PayCallbackSignService#verify} 手工校验（与 102/103/106/107 由业务层手工返回同理）。</p>
 */
@Data
@Schema(description = "模拟支付回调请求（携带 HMAC-SHA256 签名）")
public class PayCallbackRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "订单号不能为空")
    @Schema(description = "订单号（雪花算法）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;

    @NotBlank(message = "支付流水号不能为空")
    @Schema(description = "支付回调流水号（用于幂等去重）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tradeNo;

    /**
     * 时间戳（<b>毫秒</b>，与 {@code System.currentTimeMillis()} 同一口径）。
     *
     * <p>用包装类型 {@code Long} 而非 {@code long}：需要区分"没传"（null）与"传了 0"，
     * 两者都必须被拒绝，但日志里要能看出是哪一种。</p>
     */
    @Schema(description = "回调时间戳（毫秒，与 System.currentTimeMillis() 同口径；偏差超过 app.pay.timestamp-window-seconds 即拒绝）")
    private Long timestamp;

    /** HMAC-SHA256 十六进制小写签名。 */
    @Schema(description = "签名：HMAC-SHA256(orderNo|tradeNo|timestamp, PAY_CALLBACK_SECRET) 的十六进制小写")
    private String sign;
}
