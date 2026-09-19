package com.campus.market.service;

import com.campus.market.common.constant.ProfileConstants;
import com.campus.market.common.constant.SecretGenerationHints;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.PayProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 支付回调签名校验（批次 6.0.2 安全加固 · S3）。
 *
 * <h3>修的是什么</h3>
 * <p>自审报告 S3：{@code /api/v1/order/pay/callback} 是公开路径，但代码里<b>没有</b>任何验签
 * （与 {@code @Operation} 注释「签名校验」相反）。任何人知道 orderNo 就能
 * {@code curl} 一下把订单从 0 推到 1，并让卖家收到「买家已支付，请尽快发货」的站内信。
 * 本类补上服务端共享密钥的 HMAC-SHA256 验签。</p>
 *
 * <h3>签名算法（与支付网关约定，必须逐字节一致）</h3>
 * <pre>
 * payload  = orderNo + "|" + tradeNo + "|" + timestamp     （UTF-8 编码）
 * sign     = HMAC-SHA256(payload, callbackSecret) 的十六进制小写字符串
 * </pre>
 * <p>比较使用 {@link MessageDigest#isEqual(byte[], byte[])}（常量时间），
 * 避免用 {@code equals} 逐字符短路比较而泄露"前几位猜对了"的时序信息。</p>
 *
 * <h3>校验顺序（任一失败 → code=100「回调签名校验失败」）</h3>
 * <ol>
 *   <li>timestamp 不为空，且与服务器时间偏差在 {@code app.pay.timestamp-window-seconds} 内；</li>
 *   <li>sign 不为空；</li>
 *   <li>HMAC 签名匹配；</li>
 *   <li>订单存在（按 orderNo 查）—— 由调用方 {@code OrderServiceImpl} 在校验通过后执行。</li>
 * </ol>
 * <p><b>为什么四类失败返回同一句文案</b>：对公网调用方只暴露"没通过校验"这一个事实，
 * 具体原因（时间戳过期 / 签名错 / 订单不存在）只进服务端日志。否则回调接口会变成一个
 * "orderNo 是否存在"的探测器。</p>
 *
 * <h3>未配置密钥时的行为（fail-closed）</h3>
 * <p>密钥为空（本地开发默认）时，<b>所有回调一律拒绝</b>，而不是"跳过验签放行"。
 * prod 环境更进一步：空密钥直接在启动断言里失败。
 * 本地要联调模拟回调，显式注入 {@code PAY_CALLBACK_SECRET} 即可。</p>
 *
 * <h3>本批遗留（已记入下一批 P0）</h3>
 * <p>签名只覆盖 {@code orderNo|tradeNo|timestamp}，<b>没有金额</b> ——
 * {@code PayCallbackRequest} 当前没有 amount 字段。模拟支付场景风险可控，
 * 接真实网关前必须把 amount 纳入签名并做金额比对。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayCallbackSignService {

    /** HMAC 算法名（与网关约定）。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 签名失败统一文案（对外只暴露这一句）。 */
    public static final String VERIFY_FAILED_MSG = "回调签名校验失败";

    /** 密钥最小字节数（HMAC-SHA256 安全要求，与 JWT 密钥同标准）。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final PayProperties payProperties;

    /** 用于判定当前是否 prod（与 6.0.1 的 JWT / 邮箱验证码断言同构）。 */
    private final Environment environment;

    /**
     * 启动断言（安全加固 6.0.2 · S3）：prod 环境必须注入合规的回调密钥。
     *
     * <p>三条规则（与 {@code JwtUtils#init()} 同构）：</p>
     * <ol>
     *   <li>prod + 密钥为空 → 拒绝启动（否则回调会被"拒绝一切"处理，支付流程静默失效）；</li>
     *   <li>密钥是未替换的占位符（{@code ${PAY_CALLBACK_SECRET}}）→ 拒绝启动，并点名环境变量；</li>
     *   <li>密钥长度 &lt; 32 字节 → 拒绝启动（HMAC 安全要求）。</li>
     * </ol>
     * <p>非 prod 且密钥为空时<b>不</b>拒绝启动（本地开发/答辩不需要配），
     * 但会打 warn，且运行期回调一律拒绝。</p>
     */
    @PostConstruct
    void assertSecretUsable() {
        validateSecret(payProperties.getCallbackSecret(), environment.getActiveProfiles());
        if (isBlank(payProperties.getCallbackSecret())) {
            log.warn("【安全提示】app.pay.callback-secret 未配置：支付回调验签不可用，"
                    + "/api/v1/order/pay/callback 将一律拒绝（code=100）。"
                    + "本地需要联调模拟回调时，请注入环境变量 PAY_CALLBACK_SECRET（>= 32 字节）。");
        }
    }

    /**
     * 密钥校验（启动期调用；纯静态函数，单测可直接覆盖，无需启动 Spring 上下文）。
     *
     * @param secret         来自 {@code app.pay.callback-secret}
     * @param activeProfiles {@code Environment#getActiveProfiles()}
     */
    static void validateSecret(String secret, String[] activeProfiles) {
        boolean prod = ProfileConstants.isProd(activeProfiles);
        if (isBlank(secret)) {
            if (prod) {
                throw new IllegalStateException(
                        "生产环境(prod)必须通过环境变量 PAY_CALLBACK_SECRET 注入支付回调密钥 "
                                + "(app.pay.callback-secret 当前为空) —— 否则 /api/v1/order/pay/callback "
                                + "这条公开路径无法验签，任何人都能用 orderNo 把订单改成已支付。\n"
                                + SecretGenerationHints.KEY_GENERATION_COMMANDS);
            }
            return;
        }
        // ⚠️ 与 JwtUtils 同源实测结论：application-prod.yml 里的 `${PAY_CALLBACK_SECRET}` 在环境变量缺失时
        //    不会让 Spring 抛"占位符无法解析"，而是把字面量绑定到 @ConfigurationProperties 字段上。
        //    因此必须显式认出"未替换的占位符"，否则只会报一句语焉不详的"长度不足"。
        if (secret.startsWith("${")) {
            throw new IllegalStateException(
                    "支付回调密钥未注入：app.pay.callback-secret 的值仍是未替换的占位符 " + secret
                            + "，请设置环境变量 PAY_CALLBACK_SECRET（>= 32 字节随机值）\n"
                            + SecretGenerationHints.KEY_GENERATION_COMMANDS);
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "支付回调密钥长度不足：app.pay.callback-secret 必须 >= " + MIN_SECRET_BYTES
                            + " 字节（当前 " + bytes + " 字节，HMAC-SHA256 安全要求）\n"
                            + SecretGenerationHints.KEY_GENERATION_COMMANDS);
        }
    }

    /**
     * 校验回调签名（步骤 ①~③；订单存在性由调用方随后校验）。
     *
     * <p>任一失败都抛 {@code code=100 + "回调签名校验失败"}，调用方无需再做判断 ——
     * 校验不通过时<b>绝不允许</b>继续走状态机。</p>
     *
     * @param orderNo   订单号（参与签名）
     * @param tradeNo   支付流水号（参与签名）
     * @param timestamp 时间戳（<b>毫秒</b>，与 {@code System.currentTimeMillis()} 同一口径）
     * @param sign      HMAC-SHA256 十六进制小写签名
     */
    public void verify(String orderNo, String tradeNo, Long timestamp, String sign) {
        String secret = payProperties.getCallbackSecret();

        // ① 时间戳：不为空 + 在时间窗口内（先算窗口，再验签名，避免为过期请求做无谓的 HMAC）
        long windowMillis = Math.max(0L, payProperties.getTimestampWindowSeconds()) * 1000L;
        long now = System.currentTimeMillis();
        if (timestamp == null) {
            reject("时间戳缺失", orderNo, null);
        }
        // 用"下界 + 上界"两段比较而不是 Math.abs(now - timestamp)：后者在 timestamp 取极端值
        // （如 Long.MAX_VALUE）时会整数溢出，把"明显过期/伪造"的时间戳算成合法。
        if (timestamp < now - windowMillis || timestamp > now + windowMillis) {
            reject("时间戳超出允许窗口(" + payProperties.getTimestampWindowSeconds() + "s)", orderNo, timestamp);
        }

        // ② 签名：不为空
        if (isBlank(sign)) {
            reject("签名为空", orderNo, timestamp);
        }

        // ③ 密钥未配置：fail-closed（绝不"跳过验签放行"）
        if (isBlank(secret)) {
            reject("回调密钥未配置，无法验签", orderNo, timestamp);
        }

        // ④ HMAC-SHA256 常量时间比对
        String expected = hmacSha256Hex(secret, buildPayload(orderNo, tradeNo, timestamp));
        boolean valid = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sign.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            reject("签名不匹配", orderNo, timestamp);
        }
    }

    /**
     * 待签名原文：{@code orderNo + "|" + tradeNo + "|" + timestamp}（UTF-8）。
     *
     * <p>用 {@code "|"} 做分隔符而非直接拼接：否则 {@code ("a","bc")} 与 {@code ("ab","c")}
     * 会得到同一段原文，签名可跨这两个不同的参数组合复用。</p>
     */
    static String buildPayload(String orderNo, String tradeNo, long timestamp) {
        return orderNo + "|" + tradeNo + "|" + timestamp;
    }

    /** 计算 HMAC-SHA256 并输出十六进制小写（与网关约定的签名形态）。 */
    static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (Exception e) {
            // 算法/spec 在当前 JVM 必存在，走到这里说明运行环境异常，按系统错误处理
            throw new IllegalStateException("HMAC-SHA256 计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 统一拒绝：对外只暴露一句文案，真实原因进 warn 日志（含 orderNo 与时间戳，便于对账）。
     */
    private void reject(String reason, String orderNo, Long timestamp) {
        log.warn("支付回调拒绝: reason={}, orderNo={}, timestamp={}", reason, orderNo, timestamp);
        throw new BusinessException(ErrorCode.PARAM_ERROR, VERIFY_FAILED_MSG);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
