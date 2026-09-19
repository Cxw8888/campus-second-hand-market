package com.campus.market.security;

import com.campus.market.common.constant.PathConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 6.0.2 安全加固 · M7-a 单测：完全公开路径的按环境解析。
 *
 * <p>对应决策 2 的"PUBLIC_PATHS 按环境摘除"：dev/答辩要能打开 knife4j，
 * prod 不得把接口文档路径当成公开路径。断言写成"清单对比"而不是"包含某个字符串"，
 * 是为了防止以后有人往 {@code PathConstants.PUBLIC_PATHS} 里加新的文档路径却忘了同步摘除。</p>
 */
class PublicPathResolverTest {

    @Test
    @DisplayName("① dev（及非 prod）→ 原样返回全集，knife4j 照旧可用")
    void devShouldKeepAllPublicPaths() {
        assertThat(PublicPathResolver.resolve(new String[]{"dev"}))
                .containsExactly(PathConstants.PUBLIC_PATHS);
        assertThat(PublicPathResolver.resolve(new String[]{"staging"}))
                .contains("/doc.html", "/v3/api-docs/**", "/swagger-ui/**");
        assertThat(PublicPathResolver.resolve(null))
                .contains("/doc.html");
    }

    @Test
    @DisplayName("② prod → 摘除全部接口文档路径，且只摘这些")
    void prodShouldRemoveExactlyTheDocPaths() {
        List<String> prodPaths = Arrays.asList(PublicPathResolver.resolve(new String[]{"prod"}));

        // 接口文档相关：一个都不能留
        assertThat(prodPaths).doesNotContain(
                "/doc.html", "/swagger-ui.html", "/swagger-ui/**",
                "/v3/api-docs", "/v3/api-docs/**", "/webjars/**");
        // 与文档无关的公开路径必须保留（否则会把 actuator / 静态图片一起弄坏）
        assertThat(prodPaths).contains(
                "/actuator/health", "/favicon.ico", "/error", "/static/**");
        // 摘除数量与 DOC_PATHS 一致（防止"加了文档路径却忘了摘"）
        assertThat(prodPaths).hasSize(PathConstants.PUBLIC_PATHS.length - PublicPathResolver.docPaths().length);
        // prod 判定大小写不敏感、多 profile 混搭同样生效
        assertThat(PublicPathResolver.resolve(new String[]{"PROD"})).doesNotContain("/doc.html");
        assertThat(PublicPathResolver.resolve(new String[]{"dev", "prod"})).doesNotContain("/doc.html");
    }

    @Test
    @DisplayName("③ docPaths() 覆盖 prod 要封禁的全部路径（含实测仍然可达的 /doc.html）")
    void docPathsShouldCoverEverythingToBlock() {
        assertThat(PublicPathResolver.docPaths()).contains(
                "/doc.html",          // 实测：knife4j jar 内的静态资源，关开关也还在 → 必须封禁
                "/swagger-ui.html", "/swagger-ui/**",
                "/v3/api-docs", "/v3/api-docs/**", "/webjars/**");
        // 返回值是副本：调用方改数组不能污染全局常量
        String[] first = PublicPathResolver.docPaths();
        first[0] = "/hacked";
        assertThat(PublicPathResolver.docPaths()).doesNotContain("/hacked");
    }
}
