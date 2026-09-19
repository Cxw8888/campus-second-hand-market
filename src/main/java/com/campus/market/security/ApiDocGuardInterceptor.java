package com.campus.market.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 接口文档路径封禁拦截器（批次 6.0.2 安全加固 · M7-a）。
 *
 * <h3>为什么关掉配置开关还不够（实测结论）</h3>
 * <p>{@code application-prod.yml} 里写了 {@code knife4j.enable=false} +
 * {@code springdoc.api-docs.enabled=false} + {@code springdoc.swagger-ui.enabled=false}，
 * 实测结果是：{@code /v3/api-docs}、{@code /swagger-ui/**}、{@code /webjars/**} 都不再存在，
 * <b>但 {@code /doc.html} 依然返回 200</b> —— 因为它是 knife4j-openapi3-ui 这个 jar 里
 * {@code META-INF/resources/doc.html} 的<b>静态资源</b>，由 Spring Boot 默认的静态资源映射
 * （{@code classpath:/META-INF/resources/}）直接吐出来，跟 {@code knife4j.enable} 无关。</p>
 *
 * <p>它本身不含接口契约（页面启动后去拉 {@code /v3/api-docs}，而那个端点已被关掉），
 * 但"生产环境还能打开一个 Swagger 文档页"这件事本身就不该发生 —— 对扫描器而言，
 * 它意味着"这里有文档"。所以补上本拦截器，prod 下把 {@link PublicPathResolver#DOC_PATHS}
 * 全部变成"接口不存在"。</p>
 *
 * <h3>为什么不直接 {@code response.sendError(404)}</h3>
 * <p>本项目封版契约（PROJECT_CONTEXT 5.1）明确规定：<b>错误码表没有 404</b>，
 * 未匹配到任何接口的路径统一返回 code=100「请求的接口不存在: {url}」（HTTP 200）。
 * 这里抛 {@link NoResourceFoundException}，正是让文档路径与"随便打一个不存在的路径"
 * 走<b>同一条</b>处理链（{@code GlobalExceptionHandler#handleNoResourceFound}），
 * 行为完全一致、不引入第二套语义。安全目标（攻击者拿不到接口契约）已经达成。</p>
 *
 * <p>只在 prod 注册（见 {@code WebMvcConfig}）：本地开发/答辩要正常打开 knife4j。</p>
 */
@Slf4j
@Component
public class ApiDocGuardInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String uri = request.getRequestURI();
        log.warn("生产环境已封禁接口文档路径: {} {}", request.getMethod(), uri);
        // 与"路径未匹配到任何接口"完全同构：交给 GlobalExceptionHandler 统一转成 code=100
        // （NoResourceFoundException 是受检异常，故本方法必须声明 throws Exception）
        throw new NoResourceFoundException(HttpMethod.valueOf(request.getMethod()), uri);
    }
}
