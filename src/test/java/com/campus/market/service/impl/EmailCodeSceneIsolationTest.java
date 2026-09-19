package com.campus.market.service.impl;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.EmailProperties;
import com.campus.market.service.EmailCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.6 · 自审 Minor 6 单测：验证码必须按 <b>scene 隔离</b>。
 *
 * <p>修前 Key 是 {@code email:code:{email}}（无 scene）：
 * 用 {@code scene=REGISTER} 取到的码可以直接用于 {@code reset-password} / {@code change-email}，
 * 而注册是"谁都能调"的场景 —— 等于把找回密码/换绑邮箱的验证码门槛降到注册那一档。</p>
 *
 * <p>修后 Key 为 {@code email:code:{scene}:{email}}，且 scene 归一化（大写 + 去空白）：
 * 取码与用码的场景必须一致，否则读不到码 → code=103。</p>
 *
 * <p>同时守住两件事没有跟着改歪：① 失败计量/锁定<b>仍然按邮箱维度</b>
 * （按 scene 拆会让攻击者换场景拿多份额度）；② 校验通过后只删本场景的码。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailCodeSceneIsolationTest {

    private static final String EMAIL = "scene-isolation@stu.edu.cn";

    private static final String CODE = "246810";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private Environment environment;

    private EmailProperties emailProperties;

    private EmailCodeServiceImpl emailCodeService;

    @BeforeEach
    void setUp() {
        emailProperties = new EmailProperties();
        emailProperties.setSkip(true);
        emailCodeService = new EmailCodeServiceImpl(redisTemplate, emailProperties, mailSender, environment);

        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 默认没有锁定、没有限流、也没有任何验证码
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);
        when(valueOperations.increment(anyString())).thenReturn(1L);
    }

    @Test
    @DisplayName("① Key 里必须带 scene：email:code:{scene}:{email}")
    void redisKeyContainsScene() {
        assertThat(RedisKeys.emailCode(EmailCodeService.SCENE_REGISTER, EMAIL))
                .isEqualTo("email:code:REGISTER:" + EMAIL);
        assertThat(RedisKeys.emailCode(EmailCodeService.SCENE_RESET_PASSWORD, EMAIL))
                .isEqualTo("email:code:RESET_PASSWORD:" + EMAIL)
                .isNotEqualTo(RedisKeys.emailCode(EmailCodeService.SCENE_REGISTER, EMAIL));
    }

    @Test
    @DisplayName("② 发码写入的是带 scene 的 Key（且 scene 走归一化：小写也落 REGISTER）")
    void sendWritesSceneScopedKey() {
        emailCodeService.send(EMAIL, "register");

        verify(valueOperations).set(eq("email:code:REGISTER:" + EMAIL), anyString(),
                eq(Duration.ofSeconds(300L)));
    }

    @Test
    @DisplayName("③ ★注册场景取到的码拿去重置密码 → 103（修前会被直接放行）")
    void codeFromRegisterSceneCannotBeUsedForResetPassword() {
        // 只有 REGISTER 场景的码存在于 Redis
        when(valueOperations.get("email:code:REGISTER:" + EMAIL)).thenReturn(CODE);
        when(valueOperations.get("email:code:RESET_PASSWORD:" + EMAIL)).thenReturn(null);

        assertThatThrownBy(() -> emailCodeService.verify(EMAIL, EmailCodeService.SCENE_RESET_PASSWORD, CODE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .as("跨场景用码必须按「验证码错误或已过期」拒绝")
                        .isEqualTo(ErrorCode.EMAIL_CODE_ERROR.getCode()));
    }

    @Test
    @DisplayName("④ 同场景取码 → 用码通过，且只删除本场景的 Key")
    void codeFromSameSceneIsAccepted() {
        when(valueOperations.get("email:code:REGISTER:" + EMAIL)).thenReturn(CODE);

        assertThatCode(() -> emailCodeService.verify(EMAIL, EmailCodeService.SCENE_REGISTER, CODE))
                .doesNotThrowAnyException();

        verify(redisTemplate).delete("email:code:REGISTER:" + EMAIL);
        verify(redisTemplate, never()).delete("email:code:RESET_PASSWORD:" + EMAIL);
        verify(redisTemplate, never()).delete("email:code:BIND_EMAIL:" + EMAIL);
    }

    @Test
    @DisplayName("⑤ scene 大小写不敏感：取码 REGISTER / 用码 register 仍能配对")
    void sceneMatchingIsCaseInsensitive() {
        when(valueOperations.get("email:code:REGISTER:" + EMAIL)).thenReturn(CODE);

        assertThatCode(() -> emailCodeService.verify(EMAIL, "  register  ", CODE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⑥ 失败计量与锁定仍按邮箱维度（不按 scene 拆，避免换场景刷额度）")
    void failureCounterStaysPerEmail() {
        when(valueOperations.get("email:code:RESET_PASSWORD:" + EMAIL)).thenReturn(null);

        assertThatThrownBy(() -> emailCodeService.verify(EMAIL, EmailCodeService.SCENE_RESET_PASSWORD, "000000"))
                .isInstanceOf(BusinessException.class);

        verify(valueOperations).increment("email:fail:" + EMAIL);
    }
}
