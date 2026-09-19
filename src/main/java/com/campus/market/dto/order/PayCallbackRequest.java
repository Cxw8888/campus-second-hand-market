package com.campus.market.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 模拟支付回调请求（批次 6.0.2 安全加固 · S3 起必须携带签名；S3 遗留批起签名覆盖金额）。
 *
 * <p>本项目为校园线下真实交易的辅助平台，系统内"支付"为<b>模拟支付回调</b>，真实资金流转在线下进行。</p>
 *
 * <h3>签名约定（与支付网关一致，详见 {@code PayCallbackSignService}）</h3>
 * <pre>
 * payload = orderNo + "|" + tradeNo + "|" + amount + "|" + timestamp   （UTF-8）
 * sign    = HMAC-SHA256(payload, callbackSecret)                       （十六进制小写）
 * </pre>
 * <p><b>amount 必须是 2 位小数字符串</b>（如 {@code "45.00"}，不是 {@code "45"} 或 {@code "45.0"}）——
 * 服务端在算签名前会先 {@code setScale(2, HALF_UP).toPlainString()} 格式化，
 * <b>调用方必须先格式化再算签名</b>，否则两端 payload 不一致、签名必然对不上。
 * 这样约定是为了绕开"JSON 数字反序列化后 scale 不确定"的问题
 * （{@code {"amount":45} → BigDecimal("45")}、{@code {"amount":45.00} → BigDecimal("45.00")}）。</p>
 *
 * <h3>历史格式（已废弃）</h3>
 * <p>V34 及之前的 payload 只有三段 {@code orderNo|tradeNo|timestamp}，<b>不含金额</b> ——
 * 那意味着拿到密钥就能签任意金额的回调。本批起不再兼容三段签名（本项目支付是模拟的，
 * 调用方只有自己的代码，属可控的 breaking change）。</p>
 *
 * <p>幂等约定：按 {@code order_no + 回调流水号（tradeNo）} 去重，重复回调直接返回成功
 * （去重在<b>验签通过之后</b>执行，未通过验签的请求既不改状态也不占去重键）。</p>
 *
 * <h3>为什么 timestamp / sign 不加 {@code @NotBlank} / {@code @NotNull}</h3>
 * <p>校验顺序由需求固定为「① 时间戳不为空且在窗口内 → ② 签名不为空 → ③ HMAC 匹配 → ④ 金额匹配」，
 * 且这几类失败的对外文案<b>统一</b>为「回调签名校验失败」。若把两个字段交给 Bean Validation，
 * 两者同时缺失时返回的是 Spring 拼装的字段错误文案，顺序也不受控 ——
 * 既拿不到规定的顺序，也拿不到统一文案。故这两个字段由
 * {@code PayCallbackSignService#verify} 手工校验（与 102/103/106/107 由业务层手工返回同理）。</p>
 *
 * <h3>为什么 amount 反而交给 Bean Validation</h3>
 * <p>金额的"缺失/越界"是<b>请求格式问题</b>（不是"签名或账目对不上"），
 * 在进业务逻辑之前就该被挡掉：否则 {@code formatAmount(null)} 会在算签名时抛
 * {@code IllegalStateException}，把"请求格式错误"报成"服务端内部错误"。
 * 它与"金额不匹配"是两件事：前者 code=100 + 字段文案，后者 code=100 + 统一文案「回调签名校验失败」。</p>
 */
@Data
@Schema(description = "模拟支付回调请求（携带 HMAC-SHA256 签名，签名覆盖金额）")
public class PayCallbackRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "订单号不能为空")
    @Schema(description = "订单号（雪花算法）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;

    @NotBlank(message = "支付流水号不能为空")
    @Schema(description = "支付回调流水号（用于幂等去重）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tradeNo;

    /**
     * 支付金额（元）。必须与订单的 {@code amount} 一致（服务端用 {@code compareTo} 比对）。
     *
     * <p>已纳入签名 payload：{@code orderNo|tradeNo|amount|timestamp}，
     * 且签名用的 amount 字符串固定为 2 位小数格式（{@code "45.00"}）。</p>
     *
     * <p>用 {@code BigDecimal} 而不是 {@code double}/{@code String}：金额比对必须是精确十进制
     * （{@code double} 会有 0.1+0.2 那类误差），而 {@code String} 无法在参数层做范围校验。</p>
     */
    @NotNull(message = "支付金额不能为空")
    @DecimalMin(value = "0.01", message = "支付金额必须大于 0")
    @DecimalMax(value = "99999999.99", message = "支付金额超出上限")
    @Schema(description = "支付金额（元，必须与订单金额一致；签名时固定格式化为 2 位小数）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    /**
     * 时间戳（<b>毫秒</b>，与 {@code System.currentTimeMillis()} 同一口径）。
     *
     * <p>用包装类型 {@code Long} 而非 {@code long}：需要区分"没传"（null）与"传了 0"，
     * 两者都必须被拒绝，但日志里要能看出是哪一种。</p>
     */
    @Schema(description = "回调时间戳（毫秒，与 System.currentTimeMillis() 同口径；偏差超过 app.pay.timestamp-window-seconds 即拒绝）")
    private Long timestamp;

    /** HMAC-SHA256 十六进制小写签名。 */
    @Schema(description = "签名：HMAC-SHA256(orderNo|tradeNo|amount|timestamp, PAY_CALLBACK_SECRET) 的十六进制小写，"
            + "其中 amount 为 2 位小数字符串")
    private String sign;
}
