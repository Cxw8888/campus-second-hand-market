package com.campus.market.service;

import com.campus.market.common.constant.SecretGenerationHints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次 6.0.2 安全加固 · S3 单测（第二组）：支付回调密钥的启动断言。
 *
 * <p>与 {@code JwtUtilsSecretValidationTest}（6.0.1 的 S1）同构：断言逻辑写成纯静态方法
 * {@link PayCallbackSignService#validateSecret(String, String[])}，直接测每个分支，
 * 不需要启动 Spring 上下文（起上下文会连带依赖 MySQL/Redis）。</p>
 *
 * <p>为什么这条断言必须存在：回调路径是<b>公开</b>的。若 prod 漏配 PAY_CALLBACK_SECRET
 * 而又允许"没有密钥就跳过验签"，那就等于把订单支付入口完全敞开 ——
 * 必须宁可启动失败，也不能静默降级。</p>
 *
 * <p>本类同时覆盖批次任务 E：报错文案里必须<b>同时</b>给出 Linux/macOS 与 Windows PowerShell
 * 两行密钥生成命令（本项目开发环境是 Windows，只给 openssl 等于让人先去装 openssl）。</p>
 */
class PayCallbackSignServiceGuardTest {

    private static final String[] PROD = {"prod"};
    private static final String[] DEV = {"dev"};
    private static final String STRONG_SECRET = "unit-test-pay-callback-secret-0123456789-abcdefg";

    @Test
    @DisplayName("① prod + 空密钥 → 拒绝启动（S3 的核心断言）")
    void prodWithEmptySecretShouldFailFast() {
        assertThatThrownBy(() -> PayCallbackSignService.validateSecret(null, PROD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产环境(prod)")
                .hasMessageContaining("PAY_CALLBACK_SECRET");

        assertThatThrownBy(() -> PayCallbackSignService.validateSecret("   ", PROD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产环境(prod)");
    }

    @Test
    @DisplayName("② 未替换的占位符（prod 漏配时的真实形态）→ 报错点名 PAY_CALLBACK_SECRET，而不是含糊的\"长度不足\"")
    void unresolvedPlaceholderShouldFailWithActionableMessage() {
        // 与 6.0.1 的 JWT 同源实测：application-prod.yml 的 ${PAY_CALLBACK_SECRET} 在变量缺失时
        // 会被原样绑定成字符串 "${PAY_CALLBACK_SECRET}"，而不是抛"占位符无法解析"。
        assertThatThrownBy(() -> PayCallbackSignService.validateSecret("${PAY_CALLBACK_SECRET}", PROD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未注入")
                .hasMessageContaining("PAY_CALLBACK_SECRET");
    }

    @Test
    @DisplayName("③ 长度 < 32 字节 → 拒绝启动；恰好 32 字节 / 强密钥 → 放行")
    void shortSecretShouldFailFast() {
        assertThatThrownBy(() -> PayCallbackSignService.validateSecret("a".repeat(31), PROD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足")
                .hasMessageContaining("32 字节");
        assertThatCode(() -> PayCallbackSignService.validateSecret("a".repeat(32), PROD))
                .doesNotThrowAnyException();
        assertThatCode(() -> PayCallbackSignService.validateSecret(STRONG_SECRET, PROD))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("④ dev（及非 prod）不注入密钥也能启动 —— 但运行期回调一律拒绝，绝不\"跳过验签放行\"")
    void devWithEmptySecretShouldStillStart() {
        assertThatCode(() -> PayCallbackSignService.validateSecret(null, DEV))
                .doesNotThrowAnyException();
        assertThatCode(() -> PayCallbackSignService.validateSecret("", DEV))
                .doesNotThrowAnyException();
        // 非 prod 的其它 profile 同样放行（staging / test 等）
        assertThatCode(() -> PayCallbackSignService.validateSecret(null, new String[]{"staging"}))
                .doesNotThrowAnyException();
        assertThatCode(() -> PayCallbackSignService.validateSecret(null, null))
                .doesNotThrowAnyException();
        // 但 prod 判定大小写不敏感、多 profile 混搭同样拦截
        assertThatThrownBy(() -> PayCallbackSignService.validateSecret(null, new String[]{"PROD"}))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PayCallbackSignService.validateSecret(null, new String[]{"dev", "prod"}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("⑤ 任务 E：报错文案必须同时给出 openssl 与 PowerShell 两行生成命令")
    void failureMessagesShouldContainBothPlatformCommands() {
        String hint = SecretGenerationHints.KEY_GENERATION_COMMANDS;
        assertThat(hint).contains("openssl rand -base64 48");
        assertThat(hint).contains("[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Max 256 }))");
        assertThat(hint).contains("Windows PowerShell");

        // 四条失败文案都要带上它（运维看到哪一条都能照着生成密钥）
        for (String secret : new String[]{null, "${PAY_CALLBACK_SECRET}", "a".repeat(31)}) {
            assertThatThrownBy(() -> PayCallbackSignService.validateSecret(secret, PROD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Windows PowerShell")
                    .hasMessageContaining("openssl rand -base64 48");
        }
    }
}
