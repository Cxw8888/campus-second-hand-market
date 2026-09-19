package com.campus.market.util;

import com.campus.market.common.filter.RequestIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 6.0.6 · 自审 Minor 5 单测：外部输入进日志前必须剥离 CR/LF。
 *
 * <p>修前两处直接把外部输入写进日志 / MDC：</p>
 * <ul>
 *   <li>{@code RequestIdFilter}：{@code X-Request-Id} 是客户端可控请求头，
 *       原样进 MDC 且原样回显到响应头 —— 传 {@code "a%0d%0a..."} 既能伪造日志行，
 *       也碰到了响应头注入的边；</li>
 *   <li>{@code ProductServiceImpl} 的搜索关键字日志（{@code ?keyword=...}）。</li>
 * </ul>
 *
 * <p>这里覆盖 {@link LogSanitizer} 的规则本身 + {@link RequestIdFilter} 的真实行为
 * （用 spring-test 的 Mock 请求/响应，不 mock 过滤器内部）。</p>
 */
class LogSanitizerTest {

    @Test
    @DisplayName("① CR/LF/TAB/NUL 一律替换成 '_'，日志里不可能出现换行")
    void controlCharactersAreReplaced() {
        assertThat(LogSanitizer.sanitize("a\r\nb\tc\u0000d"))
                .isEqualTo("a__b_c_d")
                .doesNotContain("\n", "\r", "\t");
    }

    @Test
    @DisplayName("② 伪造日志行被压成一行（攻击者无法凭空造出 ERROR 行）")
    void forgedLogLineIsFlattened() {
        String forged = "x\r\n2026-01-01 00:00:00.000 [main] ERROR o.a.Fake - 管理员已删除全部数据";

        String sanitized = LogSanitizer.sanitize(forged);

        assertThat(sanitized).doesNotContain("\r", "\n");
        assertThat(sanitized.lines()).as("清洗后必须只有一行").hasSize(1);
        assertThat(sanitized).startsWith("x__2026-01-01");
    }

    @Test
    @DisplayName("③ null → '-'（日志可读，不留空白）；普通字符串原样保留")
    void nullBecomesDashAndPlainTextIsUnchanged() {
        assertThat(LogSanitizer.sanitize(null)).isEqualTo("-");
        assertThat(LogSanitizer.sanitize("机械键盘")).isEqualTo("机械键盘");
    }

    @Test
    @DisplayName("④ 超长输入被截断（避免单个请求把日志撑爆）")
    void overlyLongInputIsTruncated() {
        String longKeyword = "a".repeat(500);

        String sanitized = LogSanitizer.sanitize(longKeyword);

        assertThat(sanitized).hasSize(LogSanitizer.DEFAULT_MAX_LENGTH + 3).endsWith("...");
        assertThat(LogSanitizer.sanitize(longKeyword, LogSanitizer.REQUEST_ID_MAX_LENGTH))
                .hasSize(LogSanitizer.REQUEST_ID_MAX_LENGTH + 3);
    }

    @Test
    @DisplayName("⑤ RequestIdFilter：带 CRLF 的 X-Request-Id → 清洗后写入 MDC 与响应头，且仍是同一行")
    void requestIdFilterSanitizesHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/product/list");
        request.addHeader(RequestIdFilter.HEADER, "trace\r\nX-Injected: 1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilter(request, response, new MockFilterChain());

        String echoed = response.getHeader(RequestIdFilter.HEADER);
        assertThat(echoed).isNotNull().doesNotContain("\r", "\n");
        assertThat(echoed).isEqualTo("trace__X-Injected: 1");
        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo(echoed);
    }

    @Test
    @DisplayName("⑥ RequestIdFilter：空 / 纯控制字符的头 → 退回服务端生成的 UUID（长度 32）")
    void blankRequestIdFallsBackToGeneratedUuid() throws Exception {
        MockHttpServletRequest blank = new MockHttpServletRequest("GET", "/api/v1/product/list");
        blank.addHeader(RequestIdFilter.HEADER, "   ");
        MockHttpServletResponse blankResponse = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(blank, blankResponse, new MockFilterChain());

        MockHttpServletRequest missing = new MockHttpServletRequest("GET", "/api/v1/product/list");
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(missing, missingResponse, new MockFilterChain());

        assertThat(blankResponse.getHeader(RequestIdFilter.HEADER))
                .as("纯空白头不可信，必须换成服务端生成的 ID")
                .isNotNull()
                .hasSize(32);
        assertThat(missingResponse.getHeader(RequestIdFilter.HEADER)).isNotNull().hasSize(32);
        assertThat(blankResponse.getHeader(RequestIdFilter.HEADER))
                .isNotEqualTo(missingResponse.getHeader(RequestIdFilter.HEADER));
    }
}
