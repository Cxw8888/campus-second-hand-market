package com.campus.market.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 进程内兜底 JWT 黑名单。
 *
 * <p>用途：单 Token 注销（退出登录）时，除写 Redis {@code jwt:blacklist:{token}} 外同步写入本缓存。
 * 当 Redis 不可用时，{@link AuthInterceptor} 回退查询本缓存，
 * 保证"已登出 Token"在缓存有效期内仍被拒绝，避免 Redis 故障期间登出形同虚设。</p>
 *
 * <p>容量与过期：LRU 上限 {@value #MAX_ENTRIES} 条，单条 TTL 与 JWT 最长有效期同阶（2 小时），
 * 每次写入/读取都会清理过期条目，内存占用有界。单机缓存不跨实例，
 * 仅作 Redis 故障期间的降级兜底，不作为主方案。</p>
 */
@Slf4j
@Component
public class LocalJwtBlacklist {

    /** 最大条目数（LRU 淘汰）。 */
    private static final int MAX_ENTRIES = 10_000;

    /** 单条记录存活时间（毫秒），与 app.jwt.expire-hours=2 对应。 */
    private static final long TTL_MILLIS = 2 * 3600_000L;

    private final Map<String, Long> store = new LinkedHashMap<>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /**
     * 写入兜底黑名单。
     *
     * @param token         原始 JWT
     * @param remainingTtlMillis Token 剩余有效期（毫秒）；<= 0 时按 {@value #TTL_MILLIS} 兜底
     */
    public synchronized void add(String token, long remainingTtlMillis) {
        if (token == null || token.isBlank()) {
            return;
        }
        long ttl = remainingTtlMillis > 0 ? remainingTtlMillis : TTL_MILLIS;
        store.put(token, System.currentTimeMillis() + Math.min(ttl, TTL_MILLIS));
        if (log.isDebugEnabled()) {
            log.debug("已写入本地兜底黑名单: size={}", store.size());
        }
    }

    /**
     * 是否命中兜底黑名单（自动清理过期条目）。
     */
    public synchronized boolean contains(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long expireAt = store.get(token);
        if (expireAt == null) {
            return false;
        }
        if (expireAt <= System.currentTimeMillis()) {
            store.remove(token);
            return false;
        }
        return true;
    }

    /**
     * Redis 恢复可用后移除条目，避免内存中残留过期数据。
     */
    public synchronized void remove(String token) {
        if (token != null) {
            store.remove(token);
        }
    }

    /**
     * 清理已过期条目（可由定时任务调用，也可依赖 {@link #contains} 的惰性清理）。
     */
    public synchronized void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = store.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }

    /**
     * 当前条目数（仅供监控 / 测试使用）。
     */
    public synchronized int size() {
        return store.size();
    }
}
