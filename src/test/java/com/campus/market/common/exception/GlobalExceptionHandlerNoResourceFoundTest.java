package com.campus.market.common.exception;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.filter.RequestIdFilter;
import com.campus.market.common.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler} 的 NoResourceFoundException 分支单测（批次 5.4.3）。
 *
 * <h3>为什么要单独映射</h3>
 * Spring Boot 3.2 默认开启静态资源映射，未匹配到任何 {@code @RequestMapping} 的请求会落到
 * ResourceHttpRequestHandler，由它抛 {@link NoResourceFoundException}（继承 {@code ServletException}）。
 * 在补这个分支之前，它会一路落到兜底分支，把"调用方把 URL 写错了"误报成
 * <b>code=500「服务器内部错误」</b> —— 5.4.2 实测时正是它让我误判"改动炸了"。
 *
 * <p>本用例钉住两点：① 语义降级为 code=100 且 <b>msg 带上真实路径</b>（可直接定位到写错的 URL）；
 * ② <b>不引入 404</b>：PROJECT_CONTEXT 第 5 章（V26 封版）错误码表里没有 404，
 * 且规定业务接口一律 HTTP 200 + body.code，故沿用 100。</p>
 */
class GlobalExceptionHandlerNoResourceFoundTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(uri);
        req.setAttribute(RequestIdFilter.ATTRIBUTE, "unit-test-request-id");
        return req;
    }

    @Test
    @DisplayName("URL 写错 → code=100『请求的接口不存在: /xxx』（修复前是 500）")
    void missingPathShouldReturnCode100WithPathInMessage() {
        MockHttpServletRequest req = request("/api/v1/nonexistent");
        NoResourceFoundException e = new NoResourceFoundException(HttpMethod.GET, "api/v1/nonexistent");

        Result<Void> result = handler.handleNoResourceFound(e, req);

        assertThat(result.getCode())
                .as("必须是 100（参数校验），不能是 500「服务器内部错误」")
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThat(result.getMsg())
                .as("msg 必须带上真实路径，方便直接定位写错的 URL")
                .isEqualTo("请求的接口不存在: /api/v1/nonexistent");
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("契约锁定：不使用 404 —— 封版错误码表里没有该码，业务接口一律 HTTP 200 + body.code")
    void shouldNotInventCode404() {
        NoResourceFoundException e = new NoResourceFoundException(HttpMethod.DELETE, "api/v1/admin/category/");

        Result<Void> result = handler.handleNoResourceFound(e, request("/api/v1/admin/category/"));

        assertThat(result.getCode()).isNotEqualTo(404);
        assertThat(result.getCode()).isEqualTo(100);
        // 复刻 5.4.2 真实踩到的那个 URL（漏了路径变量）
        assertThat(result.getMsg()).isEqualTo("请求的接口不存在: /api/v1/admin/category/");
    }

    @Test
    @DisplayName("静态资源前缀下报错也要给出完整 URL（异常自带的 resourcePath 只是相对路径）")
    void shouldReportFullRequestUriInsteadOfRelativeResourcePath() {
        MockHttpServletRequest req = request("/static/uploads/missing.png");
        // ResourceHttpRequestHandler 抛出的 resourcePath 是映射内部路径，实测只有 "/missing.png"
        NoResourceFoundException e = new NoResourceFoundException(HttpMethod.GET, "missing.png");

        Result<Void> result = handler.handleNoResourceFound(e, req);

        assertThat(result.getMsg())
                .as("必须回显调用方请求的完整 URL，而不是相对路径")
                .isEqualTo("请求的接口不存在: /static/uploads/missing.png");
    }

    @Test
    @DisplayName("request URI 为空时才退回异常自带的 resourcePath（保证提示不为空）")
    void blankRequestUriShouldFallBackToExceptionPath() {
        MockHttpServletRequest req = request("");
        NoResourceFoundException e = new NoResourceFoundException(HttpMethod.GET, "api/v1/whatever");

        Result<Void> result = handler.handleNoResourceFound(e, req);

        assertThat(result.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThat(result.getMsg()).isEqualTo("请求的接口不存在: /api/v1/whatever");
    }
}
