package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安全响应头配置（批次 6.0.3 · M7 剩余项）。
 *
 * <p>自审报告 M7 的后半句：全站<b>没有</b> CSP / X-Frame-Options / X-Content-Type-Options / HSTS，
 * 使 localStorage 里的 Token 更脆弱（缺 XFO/CSP 时可被 iframe 嵌套做点击劫持、
 * 缺 nosniff 时内容嗅探可被利用）。本批补上。</p>
 *
 * <pre>
 * app:
 *   security:
 *     headers:
 *       enabled: true       # 总开关（应急用：全部响应头都不下发）
 *       csp-enabled: true   # CSP 单独开关（CSP 是唯一可能影响页面渲染的一个，故可单独关）
 * </pre>
 *
 * <p>HSTS 不设开关：它本身已由"prod + 本次请求确实是 HTTPS"两个条件守住，
 * 本机 HTTP 部署永远不会下发（见 {@code SecurityHeadersFilter}）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security.headers")
public class SecurityHeadersProperties {

    /** 是否下发安全响应头（默认 true）。 */
    private boolean enabled = true;

    /** 是否下发 Content-Security-Policy（默认 true；仅 prod 生效）。 */
    private boolean cspEnabled = true;
}
