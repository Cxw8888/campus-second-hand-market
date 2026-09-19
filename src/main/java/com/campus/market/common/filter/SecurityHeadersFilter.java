package com.campus.market.common.filter;

import com.campus.market.common.constant.ProfileConstants;
import com.campus.market.config.properties.SecurityHeadersProperties;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 安全响应头过滤器（批次 6.0.3 · M7 剩余项）。
 *
 * <p><b>修什么</b>：自审报告 M7 指出全站没有任何安全响应头。前端把 JWT 明文存在
 * localStorage，最怕的是 XSS 与点击劫持 —— XFO/CSP 正是针对这两类的浏览器侧防线；
 * 而 nosniff / Referrer-Policy / Permissions-Policy 都是零成本、零副作用的加固。</p>
 *
 * <h3>下发范围（重要）</h3>
 * <table>
 *   <tr><th>响应头</th><th>范围</th><th>值</th></tr>
 *   <tr><td>X-Content-Type-Options</td><td>全环境</td><td>nosniff</td></tr>
 *   <tr><td>X-Frame-Options</td><td>全环境</td><td>DENY（禁止被任何页面 iframe 嵌套）</td></tr>
 *   <tr><td>Referrer-Policy</td><td>全环境</td><td>strict-origin-when-cross-origin</td></tr>
 *   <tr><td>Permissions-Policy</td><td>全环境</td><td>camera=(), microphone=(), geolocation=()</td></tr>
 *   <tr><td>Content-Security-Policy</td><td><b>仅 prod</b></td><td>见 {@link #CSP_POLICY}</td></tr>
 *   <tr><td>Strict-Transport-Security</td><td><b>仅 prod 且本次请求是 HTTPS</b></td><td>max-age=31536000; includeSubDomains</td></tr>
 * </table>
 *
 * <h3>CSP 的宽严选择（批次报告「三、技术要点」有完整说明）</h3>
 * <p>采用"基础但可用"的策略：{@code 'self'} + 允许 {@code 'unsafe-inline'} 的脚本与样式。
 * 原因：Vue 3 的运行时会注入内联样式，Element Plus / ECharts 也依赖动态样式与内联脚本；
 * 若直接上 nonce/hash 的严格 CSP，前端在没有配套改造的情况下会白屏 —— 那属于"为了安全把功能弄坏"。
 * 因此本批只做<b>不妨碍现有渲染</b>的收紧（禁外链脚本、禁外链样式、禁 object/embed、
 * 禁被嵌套、限制 base-uri），把 nonce 化留给下批评估。
 * 另外 CSP 只在 prod 下发：dev 要能正常打开 knife4j（它是 SPA，内联脚本较多）。</p>
 *
 * <h3>HSTS 为什么不"always on"</h3>
 * <p>HSTS 是"让浏览器以后强制走 HTTPS"的承诺，只有在<b>确实提供 HTTPS</b> 时才成立；
 * 在纯 HTTP 部署上发这个头，等于让访问过的浏览器在 max-age 内无法用 HTTP 打开站点。
 * 本机/毕设部署没有 HTTPS，因此以 {@link HttpServletRequest#isSecure()} 为准 —— 它只有在
 * 容器确实终结了 TLS（或已按 {@code server.forward-headers-strategy=framework} 处理转发头）
 * 时才为 true，本机 HTTP 部署自然不会下发。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter implements Filter {

    /** 全环境下发的基础安全头。 */
    public static final String HEADER_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    public static final String HEADER_FRAME_OPTIONS = "X-Frame-Options";
    public static final String HEADER_REFERRER_POLICY = "Referrer-Policy";
    public static final String HEADER_PERMISSIONS_POLICY = "Permissions-Policy";
    public static final String HEADER_CSP = "Content-Security-Policy";
    public static final String HEADER_HSTS = "Strict-Transport-Security";

    /**
     * prod 的 CSP：默认只允许同源；脚本/样式允许内联（Vue 运行时与 Element Plus 需要）；
     * 图片允许 data:（Element Plus 的一些图标/占位图是 data URI）；禁止 object/embed（老式插件入口）。
     */
    public static final String CSP_POLICY = "default-src 'self'; "
            + "script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; "
            + "connect-src 'self'; "
            + "object-src 'none'; "
            + "frame-ancestors 'none'; "
            + "base-uri 'self'";

    /** HSTS：一年 + 子域。 */
    public static final String HSTS_POLICY = "max-age=31536000; includeSubDomains";

    private final SecurityHeadersProperties properties;
    private final Environment environment;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (properties.isEnabled()
                && request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {
            apply(httpRequest, httpResponse);
        }
        chain.doFilter(request, response);
    }

    private void apply(HttpServletRequest request, HttpServletResponse response) {
        // ---------- 全环境：四个零副作用的基本头 ----------
        response.setHeader(HEADER_CONTENT_TYPE_OPTIONS, "nosniff");
        // DENY：本项目的后端不提供任何需要被 iframe 嵌套的页面（前端是独立部署的 SPA）
        response.setHeader(HEADER_FRAME_OPTIONS, "DENY");
        response.setHeader(HEADER_REFERRER_POLICY, "strict-origin-when-cross-origin");
        response.setHeader(HEADER_PERMISSIONS_POLICY, "camera=(), microphone=(), geolocation=()");

        boolean prod = ProfileConstants.isProd(environment.getActiveProfiles());
        if (!prod) {
            // dev / 答辩：不限制 CSP，保证 knife4j 接口文档页可用
            return;
        }
        if (properties.isCspEnabled()) {
            response.setHeader(HEADER_CSP, CSP_POLICY);
        }
        if (request.isSecure()) {
            response.setHeader(HEADER_HSTS, HSTS_POLICY);
        }
    }
}
