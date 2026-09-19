package com.campus.market.common.filter;

import com.campus.market.util.LogSanitizer;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路追踪过滤器：为每个请求生成 requestId 并写入 MDC / request attribute。
 *
 * <p>全局异常处理器在 log.error 时会带上 requestId，实现"完整堆栈 + requestId"的日志规范。</p>
 *
 * <p><b>批次 6.0.6 · Minor 5</b>：{@code X-Request-Id} 是<b>客户端可控</b>的请求头，
 * 修前原样写进 MDC —— 攻击者传入带 {@code \r\n} 的值就能在日志里伪造日志行
 * （"{@code a\r\n2026-... ERROR ...}" 看起来就是系统自己打的一条错误日志），
 * 也会原样回显到响应头（响应头里的 CR/LF 属于 header 注入面）。
 * 现在统一经 {@link LogSanitizer} 清洗（控制字符 → {@code '_'}、长度上限 64），
 * 并据此判断"洗完还有内容才算有效 ID"，空值/纯控制字符一律退回服务端生成的 UUID。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements Filter {

    public static final String MDC_KEY = "requestId";
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String rawRequestId = null;
        if (request instanceof HttpServletRequest httpRequest) {
            rawRequestId = httpRequest.getHeader(HEADER);
        }
        String requestId;
        if (LogSanitizer.hasText(rawRequestId)) {
            requestId = LogSanitizer.sanitize(rawRequestId.trim(), LogSanitizer.REQUEST_ID_MAX_LENGTH);
        } else {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        try {
            MDC.put(MDC_KEY, requestId);
            request.setAttribute(ATTRIBUTE, requestId);
            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.setHeader(HEADER, requestId);
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
