package com.campus.market.service.impl;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.service.AdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 6.0.4 · M4 真机验证：封禁的用户状态缓存只在<b>事务提交后</b>写入，且带 1 天 TTL。
 *
 * <p>M3/M4 的缺陷都只在真实事务边界下才暴露，所以这里用真实的 {@link PlatformTransactionManager}
 * 手工控制提交/回滚，配真实 MySQL 与真实 Redis 断言：</p>
 * <ol>
 *   <li><b>回滚</b>：DB 用户仍是 status=0（回滚成功），Redis 里 <b>不能</b>留下 user:status 键
 *       —— 修复前会在事务内写入（且无 TTL），于是"库里正常、缓存封禁"→ 用户永久 401；</li>
 *   <li><b>提交</b>：DB status=1，Redis 键=1，且 <b>TTL 实测在 1 天量级</b>（修复前无 TTL = -1）。</li>
 * </ol>
 *
 * <p>不加 {@code @Transactional}（理由同 {@code NotificationTransactionBoundaryIntegrationTest}），
 * 用 {@link TransactionTemplate} 自己控边界，{@link AfterEach} 手工清理用户与缓存。</p>
 */
@SpringBootTest
class BanUserCacheTransactionBoundaryIntegrationTest {

    @Autowired
    private AdminService adminService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM tb_user WHERE username LIKE 'v604_ban_%'");
        jdbcTemplate.update("INSERT INTO tb_user (username, password, nickname, role, status) "
                + "VALUES (?, '{noop}test-only', ?, 0, 0)", "v604_ban_" + System.nanoTime(), "6.0.4 缓存边界用例");
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM tb_user WHERE username LIKE 'v604_ban_%' ORDER BY id DESC LIMIT 1", Long.class);
        redisTemplate.delete(RedisKeys.userStatus(userId));
    }

    @AfterEach
    void tearDown() {
        if (userId != null) {
            redisTemplate.delete(RedisKeys.userStatus(userId));
            redisTemplate.delete(RedisKeys.userTokenVersion(userId));
            jdbcTemplate.update("DELETE FROM tb_audit_log WHERE target_type = 'USER' AND target_id = ?", userId);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", userId);
        }
        jdbcTemplate.update("DELETE FROM tb_user WHERE username LIKE 'v604_ban_%'");
    }

    private Integer dbStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM tb_user WHERE id = ?", Integer.class, userId);
    }

    @Test
    @DisplayName("① 封禁事务回滚 → DB 用户正常，且 Redis 里不残留封禁缓存（修复点）")
    void rolledBackBanShouldNotLeaveBannedCache() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            adminService.banUser(userId);
            // 事务内可见：数据库确实被改成了封禁（说明用例真的走在封禁逻辑上）
            assertThat(dbStatus()).as("事务内的 DB 状态应为封禁").isEqualTo(1);
            // 但缓存此时【一条都不该有】——写缓存被推迟到了提交之后
            assertThat(redisTemplate.hasKey(RedisKeys.userStatus(userId)))
                    .as("事务提交前不得写用户状态缓存")
                    .isFalse();
            status.setRollbackOnly();
        });

        assertThat(dbStatus()).as("回滚后 DB 用户应恢复为正常").isEqualTo(0);
        assertThat(redisTemplate.hasKey(RedisKeys.userStatus(userId)))
                .as("回滚后不得残留封禁缓存（修复前会写入并永久钉死该用户）")
                .isFalse();
    }

    @Test
    @DisplayName("② 封禁事务提交 → 缓存写入 1 且 TTL 实测约 1 天（修复前无 TTL）")
    void committedBanShouldWriteCacheWithTtl() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> adminService.banUser(userId));

        assertThat(dbStatus()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(RedisKeys.userStatus(userId)))
                .as("提交后应写入封禁缓存")
                .isEqualTo("1");

        Long ttlSeconds = redisTemplate.getExpire(RedisKeys.userStatus(userId));
        assertThat(ttlSeconds)
                .as("缓存必须带 1 天 TTL（-1 表示永不过期，正是 M4 的缺陷）")
                .isNotNull()
                .isGreaterThan(Duration.ofHours(23).toSeconds())
                .isLessThanOrEqualTo(Duration.ofDays(1).toSeconds());
    }

    @Test
    @DisplayName("③ 解封事务提交 → 缓存写回 0（解封立即生效仍走缓存）")
    void committedUnbanShouldWriteNormalCache() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> adminService.banUser(userId));
        assertThat(redisTemplate.opsForValue().get(RedisKeys.userStatus(userId))).isEqualTo("1");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> adminService.unbanUser(userId));

        assertThat(dbStatus()).isEqualTo(0);
        assertThat(redisTemplate.opsForValue().get(RedisKeys.userStatus(userId))).isEqualTo("0");
    }
}
