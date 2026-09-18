package com.campus.market.service.impl;

import com.campus.market.config.properties.EmailProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.1 安全加固 · S2 单测：邮箱验证码降级开关的启动断言。
 *
 * <p>守的是自审报告里的 S2：{@code app.email.skip=true} 时验证码不再走 SMTP，
 * 而 {@code /auth/email-code}、{@code /auth/reset-password} 都是<b>公开路径</b> ——
 * 带着这个开关上线，等于"知道受害者校园邮箱即可重置其密码"。
 * 所以 prod + skip=true 必须<b>拒绝启动</b>，而不是打个 warn 继续跑。</p>
 *
 * <p>断言方法本身是纯逻辑（只看 skip 开关 + 激活 profile），
 * 因此这里用纯 Mockito 构造实例直接调用，不启动 Spring 上下文
 * （起上下文会连带依赖 MySQL/Redis，反而让这条安全断言难以在 CI 里跑）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailCodeServiceSkipGuardTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private Environment environment;

    private EmailCodeServiceImpl service(boolean skip, String... activeProfiles) {
        EmailProperties properties = new EmailProperties();
        properties.setSkip(skip);
        when(environment.getActiveProfiles()).thenReturn(activeProfiles);
        return new EmailCodeServiceImpl(redisTemplate, properties, mailSender, environment);
    }

    @Test
    @DisplayName("① prod + skip=true → 拒绝启动（S2 的核心断言）")
    void prodWithSkipEnabledShouldFailFast() {
        assertThatThrownBy(() -> service(true, "prod").assertSkipNotUsedInProd())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产环境(prod)")
                .hasMessageContaining("app.email.skip=true");
    }

    @Test
    @DisplayName("② prod + skip=false → 放行（正常生产配置不能被误拦）")
    void prodWithSkipDisabledShouldPass() {
        assertThatCode(() -> service(false, "prod").assertSkipNotUsedInProd())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("③ dev + skip=true → 放行（本地开发/答辩演示要照旧可用）")
    void devWithSkipEnabledShouldPass() {
        assertThatCode(() -> service(true, "dev").assertSkipNotUsedInProd())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("④ 默认值就是安全的：new EmailProperties().isSkip() == false")
    void propertiesDefaultShouldBeSafe() {
        assertThatCode(() -> service(false, "dev").assertSkipNotUsedInProd())
                .doesNotThrowAnyException();
        // 字段默认值本身必须是 false —— 否则"yml 里漏写 skip"就等于开了降级开关
        assertThatCode(() -> {
            EmailProperties properties = new EmailProperties();
            if (properties.isSkip()) {
                throw new AssertionError("EmailProperties.skip 的字段默认值必须是 false");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⑤ prod 判定大小写不敏感 / 多 profile 混搭同样拦截")
    void prodDetectionShouldBeCaseInsensitiveAndMultiProfileAware() {
        assertThatThrownBy(() -> service(true, "PROD").assertSkipNotUsedInProd())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(true, "dev", "prod").assertSkipNotUsedInProd())
                .isInstanceOf(IllegalStateException.class);
        // 非 prod 的自定义 profile：放行（skip 仍可用，但不回显验证码，见 send()）
        assertThatCode(() -> service(true, "staging").assertSkipNotUsedInProd())
                .doesNotThrowAnyException();
        assertThatCode(() -> service(true).assertSkipNotUsedInProd())
                .doesNotThrowAnyException();
    }
}
