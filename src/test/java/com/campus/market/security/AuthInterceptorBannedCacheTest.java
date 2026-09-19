package com.campus.market.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.entity.User;
import com.campus.market.mapper.UserMapper;
import com.campus.market.service.TokenVersionService;
import com.campus.market.util.JwtUtils;
import io.jsonwebtoken.Claims;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.4 安全加固 · M4 单测（第三组）：拦截器读侧加固
 * —— "缓存说封禁时以数据库为准"。
 *
 * <p>自审报告 M4 的另一半：{@code AuthInterceptor#isUserBanned} 原先是"缓存命中即定论"，
 * 于是任何一次缓存残留（无 TTL 的历史键、极端情况下写缓存成功但事务未提交等）
 * 都会让用户<b>永久 401</b> 且无法自愈。现在：</p>
 * <ul>
 *   <li>缓存说"正常" → 直接放行（热路径不查库）；</li>
 *   <li>缓存说"封禁"或缓存缺失 → <b>查库并以库为准</b>；库说正常则清除残留缓存（自愈）。</li>
 * </ul>
 *
 * <p>测试直接调 {@code preHandle}（未挂 {@code @RequireRole} 的普通 handler），
 * 走完"Token 解析 → 黑名单 → version 比对 → 封禁兜底"完整链路。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthInterceptorBannedCacheTest {

    private static final Long USER_ID = 6601L;
    private static final String TOKEN = "unit-test-token";

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserMapper userMapper;

    @Mock
    private TokenVersionService tokenVersionService;

    @Mock
    private LocalJwtBlacklist localJwtBlacklist;

    @Mock
    private PublicPathResolver publicPathResolver;

    @Mock
    private Claims claims;

    private AuthInterceptor interceptor;

    /** 补齐 TableInfo：拦截器查库用了 {@code lambdaQuery().select(User::getId, ...)}（立即翻译列名）。 */
    @BeforeAll
    static void initMybatisPlusEntityMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(jwtUtils, redisTemplate, userMapper, tokenVersionService,
                localJwtBlacklist, publicPathResolver);
        when(publicPathResolver.publicPaths()).thenReturn(new String[0]);

        when(jwtUtils.parseToken(TOKEN)).thenReturn(claims);
        when(jwtUtils.getUserId(claims)).thenReturn(USER_ID);
        when(jwtUtils.getRole(claims)).thenReturn(0);
        when(jwtUtils.getVersion(claims)).thenReturn(1L);
        when(tokenVersionService.currentVersion(USER_ID)).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/user/profile");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private static User user(int status) {
        User user = new User();
        user.setId(USER_ID);
        user.setStatus(status);
        return user;
    }

    private boolean preHandle(MockHttpServletRequest request) {
        return interceptor.preHandle(request, new org.springframework.mock.web.MockHttpServletResponse(), new Object());
    }

    @Test
    @DisplayName("① 缓存=正常(0) → 放行且不查库（热路径保持零额外开销）")
    void normalCachedStatusShouldPassWithoutDbQuery() {
        when(valueOperations.get(RedisKeys.userStatus(USER_ID))).thenReturn("0");

        assertThat(preHandle(request())).isTrue();
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("② 缓存=封禁(1) 但库里正常 → 放行并清除残留缓存（关键：修复前这里会永久 401）")
    void staleBannedCacheShouldBeOverriddenByDatabase() {
        when(valueOperations.get(RedisKeys.userStatus(USER_ID))).thenReturn("1");
        when(userMapper.selectOne(any())).thenReturn(user(0));

        assertThat(preHandle(request())).isTrue();
        // 清掉残留，后续请求走缓存=缺失 → 查库 → 正常，实现自愈
        verify(redisTemplate).delete(RedisKeys.userStatus(USER_ID));
    }

    @Test
    @DisplayName("③ 缓存=封禁(1) 且库里也是封禁 → 401（真封禁不能被绕过）")
    void bannedInBothCacheAndDbShouldFailWith401() {
        when(valueOperations.get(RedisKeys.userStatus(USER_ID))).thenReturn("1");
        when(userMapper.selectOne(any())).thenReturn(user(1));

        assertThatThrownBy(() -> preHandle(request()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(401);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("④ 缓存缺失：库里正常 → 放行；库里封禁 → 401（原有兜底行为不变）")
    void cacheMissShouldFallBackToDatabase() {
        when(valueOperations.get(RedisKeys.userStatus(USER_ID))).thenReturn(null);
        when(userMapper.selectOne(any())).thenReturn(user(0));
        assertThat(preHandle(request())).isTrue();

        when(userMapper.selectOne(any())).thenReturn(user(1));
        assertThatThrownBy(() -> preHandle(request()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("⑤ Redis 故障：缓存读失败不阻断——直接查库并以库为准")
    void redisFailureShouldFallBackToDatabase() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        when(userMapper.selectOne(any())).thenReturn(user(0));

        assertThat(preHandle(request())).isTrue();
    }
}
