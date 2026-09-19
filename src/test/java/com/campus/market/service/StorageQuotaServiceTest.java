package com.campus.market.service;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.StorageProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.5.2 安全加固 · M6-A2 单测：按用户的上传配额（文件数 + 总容量）。
 *
 * <p>守的是自审报告 M6 ②：修前只有"单文件 ≤5MB"，<b>没有累计限制</b>，
 * 任一登录用户反复上传合法图片就能把磁盘打满。</p>
 *
 * <p>配额计数在 Redis（不引入新表，符合"禁止改表结构"）；Redis 不可用时<b>降级放行</b> ——
 * 配额是防滥用而不是安全边界，不能因为缓存故障让整个上传功能不可用。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StorageQuotaServiceTest {

    private static final Long USER_ID = 8801L;
    private static final long ONE_MB = 1024L * 1024L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private StorageProperties storageProperties;

    private StorageQuotaService quotaService;

    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        storageProperties.setMaxFilesPerUser(3);
        storageProperties.setMaxTotalSizeMbPerUser(2L);
        quotaService = new StorageQuotaService(storageProperties, redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("① 未达上限 → 放行")
    void underQuotaShouldPass() {
        when(valueOperations.get(RedisKeys.storageUserCount(USER_ID))).thenReturn("1");
        when(valueOperations.get(RedisKeys.storageUserBytes(USER_ID))).thenReturn(String.valueOf(ONE_MB));

        assertThatCode(() -> quotaService.assertWithinQuota(USER_ID, ONE_MB / 2)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("② 文件数超上限（3 张已满，再传第 4 张）→ code=100「上传配额已满」")
    void fileCountExceededShouldBeRejected() {
        when(valueOperations.get(RedisKeys.storageUserCount(USER_ID))).thenReturn("3");
        when(valueOperations.get(RedisKeys.storageUserBytes(USER_ID))).thenReturn("0");

        assertThatThrownBy(() -> quotaService.assertWithinQuota(USER_ID, 1024L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传配额已满")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("③ 总容量超上限（2MB 已用满）→ 同样拒绝")
    void totalSizeExceededShouldBeRejected() {
        when(valueOperations.get(RedisKeys.storageUserCount(USER_ID))).thenReturn("1");
        when(valueOperations.get(RedisKeys.storageUserBytes(USER_ID))).thenReturn(String.valueOf(2 * ONE_MB));

        assertThatThrownBy(() -> quotaService.assertWithinQuota(USER_ID, 1024L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传配额已满");
    }

    @Test
    @DisplayName("④ 上传成功记账：文件数 +1、字节数 +fileSize")
    void recordUploadShouldIncrementBothCounters() {
        quotaService.recordUpload(USER_ID, 5 * ONE_MB);

        verify(valueOperations).increment(RedisKeys.storageUserCount(USER_ID));
        verify(valueOperations).increment(RedisKeys.storageUserBytes(USER_ID), 5 * ONE_MB);
    }

    @Test
    @DisplayName("⑤ 删除文件退还额度：−1 张、−fileSize 字节")
    void recordDeleteShouldRefundQuota() {
        when(valueOperations.get(RedisKeys.storageUserCount(USER_ID))).thenReturn("2");
        when(valueOperations.get(RedisKeys.storageUserBytes(USER_ID))).thenReturn(String.valueOf(3 * ONE_MB));

        quotaService.recordDelete(USER_ID, ONE_MB);

        verify(valueOperations, times(1)).decrement(RedisKeys.storageUserCount(USER_ID));
        verify(valueOperations).set(RedisKeys.storageUserBytes(USER_ID), String.valueOf(2 * ONE_MB));
    }

    @Test
    @DisplayName("⑤-补充 计数缺失（Redis 被清空）时删除不会把计数写成负数")
    void recordDeleteShouldNotGoNegative() {
        when(valueOperations.get(anyString())).thenReturn(null);

        quotaService.recordDelete(USER_ID, ONE_MB);

        verify(valueOperations, never()).decrement(anyString());
    }

    @Test
    @DisplayName("⑥ Redis 故障 → 降级放行（配额是防滥用，不是安全边界）")
    void redisFailureShouldDegradeToAllow() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> quotaService.assertWithinQuota(USER_ID, 10 * ONE_MB)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⑦ 计数缺失（Key 不存在）→ 视为 0，首次上传放行")
    void missingCountersShouldBeTreatedAsZero() {
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThat(quotaService.currentCount(USER_ID)).isZero();
        assertThat(quotaService.currentBytes(USER_ID)).isZero();
        assertThatCode(() -> quotaService.assertWithinQuota(USER_ID, ONE_MB)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⑧ 非法 userId（未登录）→ 不校验也不记账（上传接口本身已在更前面拦下）")
    void invalidUserIdShouldBeSkipped() {
        assertThatCode(() -> {
            quotaService.assertWithinQuota(null, ONE_MB);
            quotaService.assertWithinQuota(0L, ONE_MB);
            quotaService.recordUpload(null, ONE_MB);
            quotaService.recordDelete(null, ONE_MB);
        }).doesNotThrowAnyException();

        verify(valueOperations, never()).increment(anyString());
        verify(valueOperations, never()).increment(anyString(), anyLong());
    }
}
