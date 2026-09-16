package com.campus.market.common.filter;

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
        String requestId = null;
        if (request instanceof HttpServletRequest httpRequest) {
            requestId = httpRequest.getHeader(HEADER);
        }
        if (requestId == null || requestId.isBlank()) {
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
