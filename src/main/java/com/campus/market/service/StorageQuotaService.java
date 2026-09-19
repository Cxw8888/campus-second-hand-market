package com.campus.market.service;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 上传配额服务（批次 6.0.5.2 · M6-A2）。
 *
 * <h3>为什么需要它</h3>
 * <p>修前只有"单文件 ≤5MB"，<b>没有任何累计限制</b>：任一登录用户反复上传
 * （每次仍是 5MB 的合法图片）就能把磁盘打满（自审报告 M6 的 DoS 面）。</p>
 *
 * <h3>方案：按用户配额（决策 1 的子项，采用"每用户文件数 + 总容量"双阈值）</h3>
 * <ul>
 *   <li>{@code app.storage.max-files-per-user}（默认 100 张）；</li>
 *   <li>{@code app.storage.max-total-size-mb-per-user}（默认 50MB）；</li>
 *   <li>两者是"或"关系：<b>任一超限即拒绝</b>（code=100「上传配额已满」）。</li>
 * </ul>
 * <p>校园场景下单个学生 100 张 / 50MB 足够覆盖正常使用；配额耗尽时清理旧图（删除商品）会自动退还额度。</p>
 *
 * <h3>为什么用 Redis 计数（而不是查库/扫盘）</h3>
 * <p>上传是高频写路径：查库需要一张"上传记录表"（本批不改表结构，禁止加表）；
 * 扫盘需要遍历目录（O(n) 且与存储实现耦合）。Redis 计数只需 O(1) 的 INCR/INCRBY/DECR。</p>
 * <p><b>已知降级</b>：Redis 被清空/不可用时计数归零或跳过校验 → 退化为"暂时放宽"（不阻断上传）。
 * 这是有意的取舍：配额是<b>防滥用</b>而非安全边界，宁可暂时放宽也不要让上传整体不可用
 * （与项目其它 Redis 降级策略一致）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageQuotaService {

    /** 1MB 的字节数。 */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final StorageProperties storageProperties;
    private final StringRedisTemplate redisTemplate;

    /**
     * 校验本次上传是否会超出配额；超限抛 {@code code=100「上传配额已满」}。
     *
     * @param userId   当前登录用户
     * @param fileSize 本次上传文件字节数
     */
    public void assertWithinQuota(Long userId, long fileSize) {
        if (userId == null || userId <= 0) {
            return;
        }
        int maxFiles = storageProperties.getMaxFilesPerUser();
        long maxBytes = storageProperties.getMaxTotalSizeMbPerUser() * BYTES_PER_MB;

        long usedFiles;
        long usedBytes;
        try {
            usedFiles = currentCount(userId);
            usedBytes = currentBytes(userId);
        } catch (Exception e) {
            // Redis 故障降级：不阻断上传（配额是防滥用，不是安全边界）
            log.warn("上传配额校验降级（Redis 不可用，放行本次上传）: userId={}, err={}", userId, e.getMessage());
            return;
        }

        if (maxFiles > 0 && usedFiles + 1 > maxFiles) {
            log.warn("上传被拒：文件数超配额 userId={}, used={}, max={}", userId, usedFiles, maxFiles);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传配额已满");
        }
        if (maxBytes > 0 && usedBytes + fileSize > maxBytes) {
            log.warn("上传被拒：总容量超配额 userId={}, usedBytes={}, fileBytes={}, maxBytes={}",
                    userId, usedBytes, fileSize, maxBytes);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传配额已满");
        }
    }

    /** 记录一次成功上传（+1 张 / +fileSize 字节）。Redis 故障仅告警。 */
    public void recordUpload(Long userId, long fileSize) {
        if (userId == null || userId <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().increment(RedisKeys.storageUserCount(userId));
            if (fileSize > 0) {
                redisTemplate.opsForValue().increment(RedisKeys.storageUserBytes(userId), fileSize);
            }
        } catch (Exception e) {
            log.warn("上传配额计数增加失败（降级，配额暂时偏松）: userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 记录一次文件删除（−1 张 / −fileSize 字节），把额度退还用户。
     *
     * <p>计数不会被减到负数：先把当前值读出来判断（Redis 被清空后不会因为删除而变成 -1）。</p>
     */
    public void recordDelete(Long userId, long fileSize) {
        if (userId == null || userId <= 0) {
            return;
        }
        try {
            String countKey = RedisKeys.storageUserCount(userId);
            long count = parse(redisTemplate.opsForValue().get(countKey));
            if (count > 0) {
                redisTemplate.opsForValue().decrement(countKey);
            }
            if (fileSize > 0) {
                String bytesKey = RedisKeys.storageUserBytes(userId);
                long bytes = parse(redisTemplate.opsForValue().get(bytesKey));
                redisTemplate.opsForValue().set(bytesKey, String.valueOf(Math.max(0L, bytes - fileSize)));
            }
        } catch (Exception e) {
            log.warn("上传配额计数减少失败（降级）: userId={}, err={}", userId, e.getMessage());
        }
    }

    /** 当前已用文件数（Redis 不可用时抛异常由调用方决定是否降级）。 */
    public long currentCount(Long userId) {
        return parse(redisTemplate.opsForValue().get(RedisKeys.storageUserCount(userId)));
    }

    /** 当前已用字节数。 */
    public long currentBytes(Long userId) {
        return parse(redisTemplate.opsForValue().get(RedisKeys.storageUserBytes(userId)));
    }

    private static long parse(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
