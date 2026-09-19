package com.campus.market.common.constant;

/**
 * 拦截器路径语义常量（三类路径，见 PROJECT_CONTEXT 3.1）。
 */
public final class PathConstants {

    private PathConstants() {
    }

    /** 认证请求头。 */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /** Bearer 前缀。 */
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * ① 完全公开路径：完全跳过拦截器，不解析 Token。
     *
     * <p><b>批次 6.0.2 · M7-a</b>：本常量是"路径全集"，生产环境实际使用的集合由
     * {@code PublicPathResolver} 按 profile 计算 —— prod 下会摘除接口文档相关路径
     * （{@code /doc.html}、{@code /swagger-ui/**}、{@code /v3/api-docs/**}、{@code /webjars/**}）。
     * 之所以保留本常量不改，是为了不动 {@code AuthInterceptor} 等处的静态引用；
     * 之所以要"摘除"，是为了在配置开关（{@code knife4j.enable=false}）之外再加一道防线。</p>
     */
    public static final String[] PUBLIC_PATHS = {
            "/actuator/health",
            "/actuator/prometheus",
            "/actuator/info",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/doc.html",
            "/webjars/**",
            "/favicon.ico",
            "/error",
            "/static/**"
    };

    /**
     * ② 可选认证路径：尝试解析 Token，存在且有效则注入 SecurityContext，
     * 不存在或无效则静默放行（不报错）。
     *
     * <p>含两部分：一是文档指定的游客可浏览接口（商品详情可见性分级依赖它），
     * 二是登录 / 注册 / 验证码 / 支付回调等本身无需 Token 的接口。</p>
     */
    public static final String[] OPTIONAL_AUTH_PATHS = {
            // 文档指定：游客可浏览，登录后可见性提升
            "/api/v1/product/detail/**",
            "/api/v1/product/detail",
            "/api/v1/product/list",
            "/api/v1/category/list",
            "/api/v1/ai/search",
            // 无需 Token 的公共业务接口
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/email-code",
            "/api/v1/auth/reset-password",
            "/api/v1/order/pay/callback"
    };

    /**
     * ③ 强制认证路径：其余全部 /api/v1/** 必须携带有效 Token，否则 HTTP 401。
     * 例：/api/v1/auth/logout、/api/v1/order/**（除支付回调）、/api/v1/admin/** 等。
     */
    public static final String API_PREFIX = "/api/v1/**";
}
