package com.campus.market.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次 6.0.1 安全加固 · S1 单测：JWT 密钥的启动断言。
 *
 * <p>守的是自审报告里的 S1：<b>仓库是 Public，{@code application.yml} 里的 dev 默认密钥已公开，
 * 拿它就能自签 {@code role=1} 冒充管理员</b>。所以 prod 环境绝不能使用那个默认值 ——
 * 而且这件事必须是"启动就失败"，不能是"打个 warn 继续跑"。</p>
 *
 * <p>为什么直接测 {@code validateSecret} 而不是启动 Spring：断言逻辑本身是纯函数，
 * 直接测能覆盖到每一个分支（含 dev 必须放行、prod 必须拒绝的对偶关系），
 * 不必为了测一条分支去起上下文（起上下文还依赖 MySQL/Redis）。</p>
 *
 * <p>另有一条真实启动的端到端验证（prod 缺 JWT_SECRET → 启动失败）在批次报告里给出，
 * 它证明的是"配置文件里 {@code ${JWT_SECRET}} 没写默认值"这一层，与本单测互补。</p>
 */
class JwtUtilsSecretValidationTest {

    /** 强密钥（≥32 字节，非默认值）。 */
    private static final String STRONG_SECRET = "unit-test-secret-key-0123456789-abcdefghijklmnop";

    private static final String[] DEV = {"dev"};
    private static final String[] PROD = {"prod"};
    private static final String[] STAGING = {"staging"};

    @Test
    @DisplayName("① dev + 仓库默认密钥 → 放行（本地开发必须照旧可用，否则本批把开发环境弄坏了）")
    void devProfileWithDefaultSecretShouldPass() {
        assertThatCode(() -> JwtUtils.validateSecret(JwtUtils.DEV_DEFAULT_SECRET, DEV))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("② prod + 仓库默认密钥 → 拒绝启动（S1 的核心断言）")
    void prodProfileWithDefaultSecretShouldFailFast() {
        assertThatThrownBy(() -> JwtUtils.validateSecret(JwtUtils.DEV_DEFAULT_SECRET, PROD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产环境(prod)")
                .hasMessageContaining("dev 默认 JWT 密钥");
    }

    @Test
    @DisplayName("③ prod + 强密钥 → 放行（不能把正常配置也拦下）")
    void prodProfileWithStrongSecretShouldPass() {
        assertThatCode(() -> JwtUtils.validateSecret(STRONG_SECRET, PROD))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("④ 非 dev / 非 prod（如 staging）+ 默认密钥 → 放行（本批只对 prod 收紧）")
    void otherProfileWithDefaultSecretShouldPass() {
        assertThatCode(() -> JwtUtils.validateSecret(JwtUtils.DEV_DEFAULT_SECRET, STAGING))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⑤ 空密钥 / 长度不足 → 拒绝启动（原有规则，回归保护）")
    void blankOrShortSecretShouldFailFast() {
        assertThatThrownBy(() -> JwtUtils.validateSecret(null, DEV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须通过环境变量");
        assertThatThrownBy(() -> JwtUtils.validateSecret("   ", DEV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须通过环境变量");
        // 31 字节（ASCII 计）= 不足 32 字节
        assertThatThrownBy(() -> JwtUtils.validateSecret("a".repeat(31), DEV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不少于 32 字节");
        // 32 字节恰好达标
        assertThatCode(() -> JwtUtils.validateSecret("a".repeat(32), DEV))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⑤-2 未替换的占位符（prod 漏配环境变量时的真实形态）→ 报错必须点名 JWT_SECRET")
    void unresolvedPlaceholderShouldFailWithActionableMessage() {
        // 这条是 6.0.1 实测出来的：application-prod.yml 的 ${JWT_SECRET} 在变量缺失时
        // 会被原样绑定成字符串 "${JWT_SECRET}"，如果只按"长度不足"报错，运维根本猜不到原因。
        assertThatThrownBy(() -> JwtUtils.validateSecret("${JWT_SECRET}", PROD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未注入")
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("⑥ 长度按 UTF-8 字节数算：中文密钥不会因为【看着长】而蒙混过关")
    void lengthShouldBeCountedInUtf8Bytes() {
        // 11 个汉字 = 33 字节（≥32，放行）
        assertThatCode(() -> JwtUtils.validateSecret("这是一个用于测试的密钥", DEV))
                .doesNotThrowAnyException();
        // 10 个汉字 = 30 字节（<32，拒绝）
        assertThatThrownBy(() -> JwtUtils.validateSecret("这是一个用于测试密", DEV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不少于 32 字节");
    }

    @Test
    @DisplayName("⑦ prod 判定大小写不敏感、多 profile 混搭也能识别")
    void prodDetectionShouldBeCaseInsensitiveAndMultiProfileAware() {
        assertThatThrownBy(() -> JwtUtils.validateSecret(JwtUtils.DEV_DEFAULT_SECRET, new String[]{"PROD"}))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtUtils.validateSecret(JwtUtils.DEV_DEFAULT_SECRET, new String[]{"dev", "prod"}))
                .isInstanceOf(IllegalStateException.class);
        // 空数组 / null：视为非 prod（application.yml 已保证默认 profile=dev）
        assertThatCode(() -> JwtUtils.validateSecret(JwtUtils.DEV_DEFAULT_SECRET, new String[]{}))
                .doesNotThrowAnyException();
        assertThatCode(() -> JwtUtils.validateSecret(JwtUtils.DEV_DEFAULT_SECRET, null))
                .doesNotThrowAnyException();
    }
}
