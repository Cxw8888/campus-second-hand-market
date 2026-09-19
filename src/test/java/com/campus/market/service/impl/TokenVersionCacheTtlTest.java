package com.campus.market.service.impl;

import com.campus.market.common.constant.RedisKeys;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.4 安全加固 · M4 单测（第二组）：用户状态缓存必须带 TTL。
 *
 * <p>修前用 {@code set(key, value)}（无过期）：一旦缓存与 DB 不一致（历史遗留的无 TTL 键、
 * 写缓存成功但库侧回滚/回档等），这条键会<b>永久</b>把用户钉在"封禁"状态。
 * 本批改为 {@code set(key, value, TTL)}，TTL = 1 天（取值理由见
 * {@code TokenVersionServiceImpl.CACHE_STATUS_TTL} 注释）。</p>
 *
 * <p>说明：Redis 键的"到点自动消失"由 Redis 自身保证，单测无法快进时间；
 * 这里断言的是<b>写入时确实带了 1 天 TTL</b>这一机制（也就是缺陷的修复点）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenVersionCacheTtlTest {

    private static final Long USER_ID = 8801L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TokenVersionServiceImpl tokenVersionService;

    @BeforeEach
    void setUp() {
        tokenVersionService = new TokenVersionServiceImpl(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("① 写用户状态缓存必须带 1 天 TTL（修前是无过期的 set，会永久残留）")
    void cacheUserStatusShouldWriteWithOneDayTtl() {
        tokenVersionService.cacheUserStatus(USER_ID, 1);

        verify(valueOperations).set(RedisKeys.userStatus(USER_ID), "1", Duration.ofDays(1));
    }

    @Test
    @DisplayName("② 解封写 0 同样带 TTL（正常态也要能自然过期，避免长期占键）")
    void cacheUserStatusZeroShouldAlsoCarryTtl() {
        tokenVersionService.cacheUserStatus(USER_ID, 0);

        verify(valueOperations).set(RedisKeys.userStatus(USER_ID), "0", Duration.ofDays(1));
    }

    @Test
    @DisplayName("③ Redis 故障时只降级告警，不抛异常（拦截器会查库兜底）")
    void cacheUserStatusShouldDegradeOnRedisFailure() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> tokenVersionService.cacheUserStatus(USER_ID, 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("④ 参数为空时不写缓存（回归保护）")
    void cacheUserStatusShouldIgnoreNullArguments() {
        tokenVersionService.cacheUserStatus(null, 1);
        tokenVersionService.cacheUserStatus(USER_ID, null);

        verify(valueOperations, org.mockito.Mockito.never()).set(anyString(), anyString(), any(Duration.class));
    }
}
