package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付回调验签配置（批次 6.0.2 安全加固 · S3）。
 *
 * <p><b>背景</b>：{@code /api/v1/order/pay/callback} 是公开路径（{@code PathConstants.OPTIONAL_AUTH_PATHS}），
 * 修前只做 Redis 去重 + 状态机，<b>不验签、不验金额</b> —— 任何人知道 orderNo 就能把订单 0→1。
 * 本批引入服务端共享密钥的 HMAC-SHA256 签名校验。</p>
 *
 * <h3>为什么回调必须签名，而不是"改成强制认证"</h3>
 * <p>支付回调的调用方是<b>支付网关</b>（本项目为模拟网关），它没有用户 Token；
 * 若改成强制认证，网关就无法回调。所以对外回调的通行做法是"共享密钥 + 签名 + 时间戳"，
 * 用密钥证明"这次回调来自网关"，而不是用用户身份证明。</p>
 *
 * <h3>配置来源</h3>
 * <ul>
 *   <li>{@code application.yml}：{@code ${PAY_CALLBACK_SECRET:}} —— <b>空默认值</b>，
 *       本地开发不注入也能启动（此时回调一律被拒绝，见 {@code PayCallbackSignService}）；</li>
 *   <li>{@code application-prod.yml}：{@code ${PAY_CALLBACK_SECRET}} —— <b>无默认值</b>，
 *       并且由启动断言兜底（空 / 长度 &lt; 32 字节 / 未替换占位符 → 拒绝启动）。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.pay")
public class PayProperties {

    /**
     * 支付回调 HMAC 共享密钥（≥ 32 字节）。
     *
     * <p><b>空值语义</b>：空 = 未配置。此时回调接口<b>一律拒绝</b>（fail-closed），
     * 绝不退化成"没有签名也放行"。</p>
     */
    private String callbackSecret;

    /**
     * 回调时间戳允许的时间窗口（秒），默认 300（5 分钟）。
     *
     * <p>请求里的 {@code timestamp} 与服务器当前时间的偏差超过该窗口即拒绝，
     * 用于压缩重放窗口（窗口内的重放由 Redis 去重 + 状态机幂等兜底）。</p>
     */
    private long timestampWindowSeconds = 300L;
}
