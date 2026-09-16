package com.campus.market.service.impl;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.service.TokenVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 用户级 Token 版本服务实现。
 *
 * <p>所有 Redis 操作均做故障降级：捕获异常后 {@code log.warn}，不因缓存不可用阻断主流程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenVersionServiceImpl implements TokenVersionService {

    /** 初始版本号。 */
    private static final long INITIAL_VERSION = 1L;

    /** 提交后 +1 的最大重试次数。 */
    private static final int MAX_RETRY = 3;

    /** 指数退避基础间隔（毫秒）：200 / 400 / 800。 */
    private static final long RETRY_BASE_MILLIS = 200L;

    private final StringRedisTemplate redisTemplate;

    @Override
    public long currentVersion(Long userId) {
        if (userId == null) {
            return INITIAL_VERSION;
        }
        String key = RedisKeys.userTokenVersion(userId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                // 不存在时初始化为 1 并写回（不设过期，版本号需长期有效）
                redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(INITIAL_VERSION));
                return INITIAL_VERSION;
            }
            return Long.parseLong(cached);
        } catch (Exception e) {
            log.warn("读取 Token version 失败，降级返回初始版本: userId={}, err={}", userId, e.getMessage());
            return INITIAL_VERSION;
        }
    }

    @Override
    public void increaseVersion(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            doIncreaseVersion(userId);
        } catch (Exception e) {
            // 同步调用场景（改密 / 换绑 / 找回密码）：失败降级不阻断主流程
            log.warn("Token version 提升失败（降级，拦截器将走 user:status 兜底）: userId={}, err={}",
                    userId, e.getMessage());
        }
    }

    /**
     * 实际执行版本 +1（异常向上抛出，供带重试的事务提交后回调判断成败）。
     */
    private void doIncreaseVersion(Long userId) {
        String key = RedisKeys.userTokenVersion(userId);
        // 首次操作时先落初始值，保证版本从 1 严格递增到 2
        redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(INITIAL_VERSION));
        Long version = redisTemplate.opsForValue().increment(key);
        log.info("Token version 已提升: userId={}, currentVersion={}", userId, version);
    }

    @Override
    public void increaseVersionAfterCommit(Long userId) {
        if (userId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无事务上下文：立即执行
            log.debug("当前无事务上下文，直接提升 Token version: userId={}", userId);
            increaseVersion(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                increaseVersionWithRetry(userId);
            }
        });
    }

    @Override
    public void cacheUserStatus(Long userId, Integer status) {
        if (userId == null || status == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(RedisKeys.userStatus(userId), String.valueOf(status));
            log.info("用户状态缓存已更新: userId={}, status={}", userId, status);
        } catch (Exception e) {
            log.warn("用户状态缓存写入失败（降级，拦截器将查库兜底）: userId={}, err={}", userId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 事务提交后带重试的版本提升：最多 3 次，指数退避 200ms / 400ms / 800ms。
     *
     * <p>最终仍失败：记录 error + 告警日志并降级，<b>不抛出</b>
     * （主事务已提交，抛出无意义且会污染调用方；封禁立即生效由拦截器 user:status 兜底保证）。</p>
     */
    private void increaseVersionWithRetry(Long userId) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                doIncreaseVersion(userId);
                if (attempt > 1) {
                    log.info("事务提交后提升 Token version 第 {} 次重试成功: userId={}", attempt, userId);
                }
                return;
            } catch (Exception e) {
                log.warn("事务提交后提升 Token version 失败（第 {}/{} 次）: userId={}, err={}",
                        attempt, MAX_RETRY, userId, e.getMessage());
                if (attempt == MAX_RETRY) {
                    break;
                }
                try {
                    // 指数退避：200ms / 400ms / 800ms
                    Thread.sleep(RETRY_BASE_MILLIS * (1L << (attempt - 1)));
                } catch (InterruptedException ie) {
                    // 保留中断标记，避免吞掉线程中断状态
                    Thread.currentThread().interrupt();
                    log.warn("Token version 重试退避被中断: userId={}", userId);
                    break;
                }
            }
        }
        // 降级告警：不阻断主流程，由拦截器 user:status 兜底保证封禁/解封 Token 立即失效
        log.error("【告警】事务提交后提升 Token version 最终失败，已降级依赖拦截器 user:status 兜底: userId={}", userId);
    }
}
