package com.campus.market.service.impl;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.JwtProperties;
import com.campus.market.config.properties.LoginSecurityProperties;
import com.campus.market.config.properties.TrustedProxyProperties;
import com.campus.market.dto.auth.LoginRequest;
import com.campus.market.mapper.UserMapper;
import com.campus.market.security.LocalJwtBlacklist;
import com.campus.market.service.EmailCodeService;
import com.campus.market.service.TokenVersionService;
import com.campus.market.util.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.2 安全加固 · M1 单测：IP 维度不可伪造 + 账号计数 Key 归一化。
 *
 * <p>守的是自审报告里的 M1：两个洞合起来等于"在线口令爆破几乎无限制"——</p>
 * <ol>
 *   <li>{@code IpUtils} 无条件采信 {@code X-Forwarded-For} → 每次换一个伪造 header
 *       就换一份 IP 维度失败额度，还能污染审计日志里的 IP；</li>
 *   <li>账号计数 Key 直接拼 {@code getUsername().trim()}，而 {@code tb_user} 是
 *       {@code utf8mb4_0900_ai_ci}（大小写不敏感）→ {@code admin}/{@code Admin}/{@code aDmIn}
 *       登录到同一个账号却各有一份 5 次额度，含字母的账号（如全部 demo 管理员）等于把
 *       爆破预算放大 2ⁿ 倍。</li>
 * </ol>
 *
 * <p>两条用例断言的都是<b>真实的 Redis Key</b>（不是内部状态），因为漏洞的表现形式就是
 * "Key 拼错了"，只有断言 Key 才能证明修复到位。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthLoginSecurityTest {

    /** 攻击者伪造的转发头值（若被采信，就会出现 login:fail:ip:1.2.3.4）。 */
    private static final String SPOOFED_IP = "1.2.3.4";

    /** 真实直连地址。 */
    private static final String REAL_REMOTE_ADDR = "203.0.113.9";

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private TokenVersionService tokenVersionService;

    @Mock
    private EmailCodeService emailCodeService;

    @Mock
    private LocalJwtBlacklist localJwtBlacklist;

    private TrustedProxyProperties trustedProxyProperties;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        trustedProxyProperties = new TrustedProxyProperties();
        // 关键：默认空列表 = 不信任任何转发头（与 application.yml 的默认形态一致）
        trustedProxyProperties.setTrustedProxies(List.of());

        authService = new AuthServiceImpl(userMapper, passwordEncoder, jwtUtils, redisTemplate,
                tokenVersionService, emailCodeService, new LoginSecurityProperties(), new JwtProperties(),
                localJwtBlacklist, trustedProxyProperties);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        // 用户不存在 → 走"密码错误"分支（与"账号不存在"同样是 101，防状态探测）
        when(userMapper.selectOne(any())).thenReturn(null);
    }

    private static LoginRequest loginRequest(String username) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword("Whatever@123");
        return request;
    }

    /** 构造一个"直连 IP 是 REAL_REMOTE_ADDR、同时带伪造 XFF"的请求。 */
    private static MockHttpServletRequest spoofedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(REAL_REMOTE_ADDR);
        request.addHeader("X-Forwarded-For", SPOOFED_IP);
        request.addHeader("X-Real-IP", SPOOFED_IP);
        return request;
    }

    private void loginExpectFailure(String username, MockHttpServletRequest httpRequest) {
        assertThatThrownBy(() -> authService.login(loginRequest(username), httpRequest))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED.getCode());
    }

    @Test
    @DisplayName("① 无可信代理时，伪造 X-Forwarded-For 不改变 IP 维度计数 Key")
    void forgedForwardedForMustNotChangeIpCountKey() {
        loginExpectFailure("admin", spoofedRequest());

        // IP 维度计数必须落在真实远端地址上
        verify(valueOperations).increment("login:fail:ip:" + REAL_REMOTE_ADDR);
        // 绝不能落在攻击者伪造的地址上（修前就是这里被绕过：换 header 即换额度）
        verify(valueOperations, never()).increment("login:fail:ip:" + SPOOFED_IP);
    }

    @Test
    @DisplayName("①-补充 直连地址命中可信代理时，才采信 X-Forwarded-For")
    void forwardedForIsTrustedOnlyBehindTrustedProxy() {
        trustedProxyProperties.setTrustedProxies(List.of(REAL_REMOTE_ADDR));

        loginExpectFailure("admin", spoofedRequest());

        // 可信代理之后：XFF 生效（本用例的 XFF 只有一段，取到的就是它）
        verify(valueOperations).increment("login:fail:ip:" + SPOOFED_IP);
        verify(valueOperations, never()).increment("login:fail:ip:" + REAL_REMOTE_ADDR);
    }

    @Test
    @DisplayName("② Admin / admin / aDmIn 落到同一账号计数 Key（与 tb_user 的 ci 排序规则对齐）")
    void usernameVariantsMustShareTheSameCountKey() {
        for (String username : new String[]{"Admin", "admin", "aDmIn", "  ADMIN  "}) {
            loginExpectFailure(username, spoofedRequest());
        }

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, atLeastOnce()).increment(keyCaptor.capture());

        List<String> accountKeys = keyCaptor.getAllValues().stream()
                .filter(key -> key.startsWith("login:fail:") && !key.startsWith("login:fail:ip:"))
                .toList();
        // 4 次登录失败 → 4 次账号维度计数，全部落在同一个归一化 Key 上
        assertThat(accountKeys).hasSize(4).containsOnly("login:fail:admin");
        assertThat(accountKeys).doesNotContain("login:fail:Admin", "login:fail:aDmIn");
    }

    @Test
    @DisplayName("②-补充 归一化规则本身：trim + 全小写（Locale.ROOT），null 原样返回")
    void normalizeUsernameShouldTrimAndLowerCase() {
        assertThat(AuthServiceImpl.normalizeUsername("  Admin ")).isEqualTo("admin");
        assertThat(AuthServiceImpl.normalizeUsername("aDmIn")).isEqualTo("admin");
        assertThat(AuthServiceImpl.normalizeUsername("20210001")).isEqualTo("20210001");
        assertThat(AuthServiceImpl.normalizeUsername(null)).isNull();
    }

    @Test
    @DisplayName("③ 回归保护：失败计数 TTL 与锁定阈值仍按配置写入（IP 5 分钟窗口 / 账号 5 分钟窗口）")
    void failCountersStillGetTheirConfiguredTtl() {
        loginExpectFailure("admin", spoofedRequest());

        verify(redisTemplate).expire("login:fail:admin", Duration.ofSeconds(300));
        verify(redisTemplate).expire("login:fail:ip:" + REAL_REMOTE_ADDR, Duration.ofSeconds(300));
    }
}
