package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.dto.admin.OrderUnfreezeRequest;
import com.campus.market.entity.Order;
import com.campus.market.entity.Product;
import com.campus.market.entity.User;
import com.campus.market.entity.enums.OrderStatus;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.3 安全加固 · B1/B2 单测（第二组）：封禁 / 解冻链路的库存与商品状态。
 *
 * <p>守的是自审报告 B1 + B2：</p>
 * <ul>
 *   <li><b>B1</b>：封禁时回补 + 解冻 CANCEL 又回补 = 同一订单补两遍。本批改为
 *       <b>封禁不回补，只在 5→4 回补一次</b>（决策 1 · 方案 A）。</li>
 *   <li><b>B2</b>：封禁只下架 {@code status=1}，售罄(2) 商品逃过下架。本批改为
 *       {@code status IN (1,2)} 统一下架，且顺序调整为"先冻结、再下架"。</li>
 * </ul>
 *
 * <p>这里 mock 掉 {@link StockService}（其内部幂等凭证由
 * {@code StockRestoreIdempotencyTest} 单独覆盖），只断言<b>谁在什么时候发起回补</b>——
 * 这正是 B1 的病灶所在。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminBanUnfreezeStockTest {

    private static final Long USER_ID = 7001L;
    private static final Long ORDER_ID = 8001L;
    private static final Long PRODUCT_ID = 9001L;

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
     * 补齐 MyBatis-Plus 的实体元数据缓存。
     *
     * <p>原因：{@code banUser} 里用了 {@code lambdaUpdate().set(User::getStatus, ...)} 与
     * {@code lambdaQuery().select(Product::getId)} —— {@code set(...)} / {@code select(...)}
     * 都会<b>立即</b>把方法引用翻译成列名，翻译依赖 TableInfo 缓存，
     * 缓存缺失就报 {@code can not find lambda cache for this entity}。
     * （{@code eq(...)} 是懒翻译，不受影响，这也是既有单测能跑的原因。）</p>
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

        User seller = new User();
        seller.setId(USER_ID);
        seller.setStatus(0);
        when(userMapper.selectById(USER_ID)).thenReturn(seller);
        when(productMapper.offShelfByUser(USER_ID)).thenReturn(3);
        when(orderMapper.freezeByUser(USER_ID)).thenReturn(1);
    }

    private Order frozenOrder() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setProductId(PRODUCT_ID);
        order.setQuantity(2);
        order.setUserId(USER_ID);
        order.setStatus(OrderStatus.FROZEN);
        order.setProductTitle("高等数学 同济第七版");
        return order;
    }

    private static OrderUnfreezeRequest target(String target) {
        OrderUnfreezeRequest request = new OrderUnfreezeRequest();
        request.setTarget(target);
        return request;
    }

    @Test
    @DisplayName("① 封禁卖家【不回补库存】（B1 核心：修前会逐单 restore，库存被凭空加回）")
    void banUserMustNotRestoreStock() {
        adminService.banUser(USER_ID);

        // 冻结与下架照做
        verify(orderMapper).freezeByUser(USER_ID);
        verify(productMapper).offShelfByUser(USER_ID);
        // 但绝不回补：冻结只是"交易暂停"，回补留给 5→4
        verify(stockService, never()).restoreOnce(any(), any(), any());
        // 也不再需要"先查出订单列表再逐单回补"的那次多余查询
        verify(orderMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("② 封禁 + 解冻 CANCEL（5→4）→ 恰好回补一次（库存 +1，不是 +2）")
    void unfreezeToCancelShouldRestoreExactlyOnce() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(frozenOrder());
        when(orderMapper.unfreezeToCancel(ORDER_ID)).thenReturn(1);

        adminService.banUser(USER_ID);
        adminService.unfreezeOrder(ORDER_ID, target("CANCEL"));

        verify(orderMapper).unfreezeToCancel(ORDER_ID);
        verify(stockService, times(1)).restoreOnce(eq(ORDER_ID), eq(PRODUCT_ID), eq(2));
    }

    @Test
    @DisplayName("③ 封禁 + 解冻 COMPLETE（5→3）→ 不回补（货已线下交付）")
    void unfreezeToCompleteShouldNotRestore() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(frozenOrder());
        when(orderMapper.unfreezeToComplete(ORDER_ID)).thenReturn(1);

        adminService.banUser(USER_ID);
        adminService.unfreezeOrder(ORDER_ID, target("COMPLETE"));

        verify(orderMapper).unfreezeToComplete(ORDER_ID);
        verify(stockService, never()).restoreOnce(any(), any(), any());
    }

    @Test
    @DisplayName("④ 重复解冻 CANCEL → 状态机先拦下（209），回补不会发生第二次")
    void repeatedUnfreezeCancelShouldNotRestoreTwice() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(frozenOrder());
        // 第一次成功，第二次状态已不是 5 → 影响行数 0
        when(orderMapper.unfreezeToCancel(ORDER_ID)).thenReturn(1, 0);

        adminService.unfreezeOrder(ORDER_ID, target("CANCEL"));
        assertThatThrownBy(() -> adminService.unfreezeOrder(ORDER_ID, target("CANCEL")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前状态不允许此操作");

        verify(stockService, times(1)).restoreOnce(eq(ORDER_ID), eq(PRODUCT_ID), eq(2));
    }

    @Test
    @DisplayName("⑤ 解封 → 商品不自动上架（售罄/下架的都要卖家手动重新上架）")
    void unbanShouldNotRelistProducts() {
        adminService.unbanUser(USER_ID);

        // 解封只改用户状态 + 审计 + token version；不得碰商品状态
        verify(productMapper, never()).offShelfByUser(anyLong());
        verify(productMapper, never()).relistIfSoldOut(anyLong());
        verify(productMapper, never()).update(any(), any());
        verify(stockService, never()).restoreOnce(any(), any(), any());
    }
}
