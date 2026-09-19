package com.campus.market.security;

import com.campus.market.common.constant.PathConstants;
import com.campus.market.common.constant.ProfileConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 完全公开路径解析器（批次 6.0.2 安全加固 · M7-a）。
 *
 * <h3>为什么不直接把 {@code PathConstants.PUBLIC_PATHS} 改掉</h3>
 * <p>{@code PathConstants} 是<b>静态常量类</b>，被 {@code AuthInterceptor} 以
 * {@code PathConstants.PUBLIC_PATHS} 形式直接引用。要让它"按环境变化"，只有三条路：
 * ① 新增一个按 profile 计算的 Bean（本类，不动原常量）；② 把常量类改成 Spring Bean
 * （破坏所有静态引用，需要改多处）；③ 把列表挪进 yml 用 {@code @ConfigurationProperties} 绑定
 * （最灵活但改动最大）。选 ① —— 单文件新增、零破坏、可单测。</p>
 *
 * <h3>prod 摘除哪些路径</h3>
 * <p>接口文档相关路径（{@code /doc.html}、{@code /swagger-ui/**}、{@code /v3/api-docs/**}、
 * {@code /webjars/**}）在 prod 下从公开列表中摘掉，与
 * {@code application-prod.yml} 里 {@code knife4j.enable=false} +
 * {@code springdoc.*.enabled=false} 的配置开关形成<b>两道</b>防线：
 * 配置层让这些端点根本不存在（真正返回"不存在"的是它），路径层保证
 * "即使哪天有人把拦截器范围扩大到全站、或误开了配置开关，prod 也不会把它们当公开路径放行"。</p>
 *
 * <p><b>现实补充（务必知道）</b>：{@code WebMvcConfig} 目前把 {@code AuthInterceptor}
 * 只挂在 {@code /api/v1/**} 上，因此文档路径本来就<b>不进拦截器</b> ——
 * 也就是说本类的摘除动作当前属于"防御性收口"，真正生效的是配置开关。
 * 详见批次 6.0.2 报告「二、后端现实约束」。</p>
 */
@Slf4j
@Component
public class PublicPathResolver {

    /**
     * 仅用于接口文档的公开路径（prod 下摘除 + 封禁）。
     *
     * <p>注意不包含 {@code /favicon.ico}、{@code /error}、{@code /static/**}、{@code /actuator/**}：
     * 它们与文档无关，prod 下照旧放行（{@code /actuator/*} 的实际暴露面由
     * {@code management.endpoints.web.exposure.include} 控制）。</p>
     *
     * <p>本常量同时被 {@code WebMvcConfig} 用来在 prod 注册 {@link ApiDocGuardInterceptor} ——
     * 因为实测发现 {@code knife4j.enable=false} <b>并不能</b>让 {@code /doc.html} 消失：
     * 该页面是 knife4j-openapi3-ui 这个 jar 里 {@code META-INF/resources/doc.html} 的静态资源，
     * 由 Spring Boot 的默认静态资源映射直接吐出来，与开关无关。详见批次 6.0.2 报告。</p>
     */
    public static final String[] DOC_PATHS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/doc.html",
            "/webjars/**"
    };

    private final String[] publicPaths;

    public PublicPathResolver(Environment environment) {
        this.publicPaths = resolve(environment.getActiveProfiles());
    }

    /**
     * 当前环境实际生效的"完全公开路径"。
     *
     * <p>构造期算好并复用：拦截器每个请求都会调用本方法，逐请求过滤数组会白白产生垃圾对象。</p>
     */
    public String[] publicPaths() {
        return publicPaths.clone();
    }

    /** 接口文档路径清单（供 prod 封禁拦截器注册使用）。 */
    public static String[] docPaths() {
        return DOC_PATHS.clone();
    }

    /**
     * 按 profile 计算公开路径（纯静态函数，单测直接覆盖）。
     *
     * <p>非 prod 一律返回 {@link PathConstants#PUBLIC_PATHS} 原样（本地开发/答辩要能打开 knife4j）。</p>
     */
    static String[] resolve(String[] activeProfiles) {
        if (!ProfileConstants.isProd(activeProfiles)) {
            return PathConstants.PUBLIC_PATHS.clone();
        }
        String[] resolved = Arrays.stream(PathConstants.PUBLIC_PATHS)
                .filter(path -> !isDocPath(path))
                .toArray(String[]::new);
        log.info("生产环境(prod)：已从完全公开路径中摘除接口文档路径，共摘除 {} 条，剩余 {} 条",
                PathConstants.PUBLIC_PATHS.length - resolved.length, resolved.length);
        return resolved;
    }

    private static boolean isDocPath(String path) {
        return Arrays.stream(DOC_PATHS).anyMatch(doc -> doc.equals(path));
    }}
