package com.campus.market.common.filter;

import com.campus.market.config.properties.SecurityHeadersProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.3 安全加固 · M7 剩余项单测：安全响应头。
 *
 * <p>守的是自审报告 M7 的后半句：全站没有 CSP / X-Frame-Options / X-Content-Type-Options / HSTS。
 * 断言的是"哪些头在哪个环境下出现"这条契约本身 —— 尤其是两条容易写错的分支：
 * ① dev 不下发 CSP（否则 knife4j 文档页会被自己的 CSP 打掉）；
 * ② HSTS 只在 prod <b>且请求确实是 HTTPS</b> 时下发（本机 HTTP 部署绝不能发，
 * 否则浏览器会在 max-age 内拒绝用 HTTP 打开站点）。</p>
 */
class SecurityHeadersFilterTest {

    private static SecurityHeadersFilter filter(SecurityHeadersProperties properties, String... profiles) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        return new SecurityHeadersFilter(properties, environment);
    }

    private static SecurityHeadersProperties properties() {
        SecurityHeadersProperties properties = new SecurityHeadersProperties();
        properties.setEnabled(true);
        properties.setCspEnabled(true);
        return properties;
    }

    private static MockHttpServletResponse run(SecurityHeadersFilter filter, boolean secure) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/product/list");
        request.setSecure(secure);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("① dev：下发四个基础头，但不下发 CSP 与 HSTS（knife4j 文档页要能正常打开）")
    void devShouldSendBaseHeadersOnly() throws Exception {
        MockHttpServletResponse response = run(filter(properties(), "dev"), false);

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(response.getHeader("Permissions-Policy"))
                .isEqualTo("camera=(), microphone=(), geolocation=()");
        assertThat(response.getHeader("Content-Security-Policy")).isNull();
        assertThat(response.getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    @DisplayName("② prod + HTTP：基础头 + CSP，但没有 HSTS（本机/内网 HTTP 部署不能发）")
    void prodOverHttpShouldSendCspButNotHsts() throws Exception {
        MockHttpServletResponse response = run(filter(properties(), "prod"), false);

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("default-src 'self'")
                .contains("script-src 'self' 'unsafe-inline'")
                .contains("frame-ancestors 'none'")
                .contains("object-src 'none'");
        assertThat(response.getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    @DisplayName("③ prod + HTTPS（isSecure）→ 额外下发 HSTS")
    void prodOverHttpsShouldSendHsts() throws Exception {
        MockHttpServletResponse response = run(filter(properties(), "prod"), true);

        assertThat(response.getHeader("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
        assertThat(response.getHeader("Content-Security-Policy")).isNotNull();
    }

    @Test
    @DisplayName("④ 总开关关掉 → 一个头都不下发；CSP 单独关 → 只少 CSP（应急降级用）")
    void switchesShouldWork() throws Exception {
        SecurityHeadersProperties disabled = properties();
        disabled.setEnabled(false);
        MockHttpServletResponse none = run(filter(disabled, "prod"), true);
        assertThat(none.getHeader("X-Content-Type-Options")).isNull();
        assertThat(none.getHeader("X-Frame-Options")).isNull();
        assertThat(none.getHeader("Content-Security-Policy")).isNull();
        assertThat(none.getHeader("Strict-Transport-Security")).isNull();

        SecurityHeadersProperties cspOff = properties();
        cspOff.setCspEnabled(false);
        MockHttpServletResponse withoutCsp = run(filter(cspOff, "prod"), false);
        assertThat(withoutCsp.getHeader("Content-Security-Policy")).isNull();
        assertThat(withoutCsp.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("⑤ 响应头写入不得影响请求继续向下传递（FilterChain 必须被调用）")
    void filterMustContinueChain() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/product/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(properties(), "prod").doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }
}
