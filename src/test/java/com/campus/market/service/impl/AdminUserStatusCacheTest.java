package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.market.entity.Product;
import com.campus.market.entity.User;
import com.campus.market.mapper.AuditLogMapper;
import com.campus.market.mapper.CategoryMapper;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.mapper.UserMapper;
import com.campus.market.service.AdminAuditService;
import com.campus.market.service.NotificationSender;
import com.campus.market.service.ProductCacheService;
import com.campus.market.service.StockService;
import com.campus.market.service.TokenVersionService;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.4 安全加固 · M4 单测（第一组）：封禁/解封的用户状态缓存必须"事务提交后才写"。
 *
 * <p>守的是自审报告 M4：{@code AdminServiceImpl.banUser} 在 {@code @Transactional} 内写
 * {@code user:status:{userId}}，而 Redis 不参与 MySQL 回滚 —— 封禁事务一旦失败，
 * DB 里用户正常、缓存却标记封禁（且当时无 TTL）→ 该用户<b>永久 401</b>，没有任何自愈路径。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminUserStatusCacheTest {

    private static final Long USER_ID = 7501L;

    /** 用户状态：0-正常。 */
    private static final int USER_NORMAL = 0;

    /** 用户状态：1-封禁。 */
    private static final int USER_BANNED = 1;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private AuditLogMapper auditLogMapper;

    @Mock
    private StockService stockService;

    @Mock
    private ProductCacheService productCacheService;

    @Mock
    private AdminAuditService adminAuditService;

    @Mock
    private TokenVersionService tokenVersionService;

    @Mock
    private NotificationSender notificationSender;

    private AdminServiceImpl adminService;

    /**
     * 补齐 MyBatis-Plus 实体元数据缓存：{@code banUser} 里的
     * {@code lambdaUpdate().set(User::getStatus, ...)} 会立即翻译列名，
     * 缺 TableInfo 会报 {@code can not find lambda cache}（与 6.0.3 同一踩坑）。
     */
    @BeforeAll
    static void initMybatisPlusEntityMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, User.class);
        TableInfoHelper.initTableInfo(assistant, Product.class);
    }

    @BeforeEach
    void setUp() {
        adminService = new AdminServiceImpl(orderMapper, productMapper, userMapper, categoryMapper,
                auditLogMapper, stockService, productCacheService, adminAuditService,
                tokenVersionService, notificationSender);

        User user = new User();
        user.setId(USER_ID);
        user.setStatus(USER_NORMAL);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(orderMapper.freezeByUser(USER_ID)).thenReturn(0);
        when(productMapper.offShelfByUser(USER_ID)).thenReturn(0);
    }

    private static void triggerAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private static void triggerAfterCompletion(int status) {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(status));
    }

    @Test
    @DisplayName("① 封禁：事务内不写缓存，提交后才写 status=1")
    void banShouldCacheStatusOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            adminService.banUser(USER_ID);

            // 事务尚未提交：缓存一条都不能写（这正是修复前"回滚后残留"的入口）
            verify(tokenVersionService, never()).cacheUserStatus(any(), anyInt());

            triggerAfterCommit();

            verify(tokenVersionService).cacheUserStatus(USER_ID, USER_BANNED);
            // 版本提升同样在提交后（既有行为，本批未改）
            verify(tokenVersionService).increaseVersionAfterCommit(USER_ID);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("② 封禁事务回滚 → 缓存永不写入（关键：修复前会写入并永久钉死该用户）")
    void banRollbackShouldNeverCacheStatus() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            adminService.banUser(USER_ID);
            triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(tokenVersionService, never()).cacheUserStatus(any(), anyInt());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("③ 解封：同样提交后才写 status=0（避免\"库里没解封成功却把缓存写成正常\"的反向不一致）")
    void unbanShouldCacheNormalStatusOnlyAfterCommit() {
        User banned = new User();
        banned.setId(USER_ID);
        banned.setStatus(USER_BANNED);
        when(userMapper.selectById(USER_ID)).thenReturn(banned);

        TransactionSynchronizationManager.initSynchronization();
        try {
            adminService.unbanUser(USER_ID);
            verify(tokenVersionService, never()).cacheUserStatus(any(), anyInt());

            triggerAfterCommit();
            verify(tokenVersionService).cacheUserStatus(USER_ID, USER_NORMAL);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("④ 无事务上下文（定时任务/直调）→ 立即写缓存，不会因为\"没有事务\"而丢写")
    void withoutTransactionShouldCacheImmediately() {
        adminService.banUser(USER_ID);

        verify(tokenVersionService).cacheUserStatus(eq(USER_ID), eq(USER_BANNED));
    }
}
