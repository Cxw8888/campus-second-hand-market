package com.campus.market.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 下单防重 Token 服务（接口幂等保护）。
 *
 * <p>前端先 {@code GET /api/v1/order/token} 获取 Token，提交订单时携带；
 * 后端通过 Lua 脚本在 Redis 中<b>原子校验并删除</b>，确保同一 Token 只能成功一次，
 * 有效拦截重复提交（返回 code=202）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTokenService {

    /**
     * Lua 原子校验并删除：存在则删除并返回 1，不存在返回 0。
     * 校验与删除必须原子完成，否则高并发下会出现"校验通过两次"的窗口。
     */
    private static final String LUA_CHECK_AND_DELETE =
            "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('DEL', KEYS[1]) return 1 else return 0 end";

    private static final DefaultRedisScript<Long> CHECK_AND_DELETE_SCRIPT =
            new DefaultRedisScript<>(LUA_CHECK_AND_DELETE, Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 生成防重 Token 并写入 Redis。
     *
     * @param userId 当前登录用户
     * @param ttlSeconds 有效期（默认 5 分钟）
     */
    public String generate(Long userId, long ttlSeconds) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String key = redisKey(userId, uuid);
        redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ttlSeconds));
        return uuid;
    }

    /**
     * 原子校验并消费 Token。
     *
     * @return true 表示 Token 有效且已消费；false 表示 Token 无效（重复提交或已过期）
     */
    public boolean consume(Long userId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long result = redisTemplate.execute(
                CHECK_AND_DELETE_SCRIPT,
                Collections.singletonList(redisKey(userId, token)));
        return result != null && result == 1L;
    }

    private String redisKey(Long userId, String uuid) {
        return com.campus.market.common.constant.RedisKeys.orderToken(userId, uuid);
    }
}
