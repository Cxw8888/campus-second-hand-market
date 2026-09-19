package com.campus.market.service.impl;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.dto.order.OrderCreateRequest;
import com.campus.market.entity.Product;
import com.campus.market.entity.User;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.mapper.UserMapper;
import com.campus.market.service.NotificationSender;
import com.campus.market.service.StockService;
import com.campus.market.util.SnowflakeIdGenerator;
import com.campus.market.vo.OrderCreateVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.3 安全加固 · B2 单测（第三组）：下单时必须校验卖家状态。
 *
 * <p>守的是自审报告 B2 的最后一环：封禁卖家时商品会被统一下架，但"下架"与"下单"之间存在竞态
 * （买家可能已拿到商品详情、封禁后才提交下单），仅靠商品的 {@code status=1} 判断不足以兜住。
 * 这里断言的是<b>卖家被封禁 → 下单必被拒（204）且不扣库存</b>。</p>
 *
 * <p>失败文案复用 204「商品不存在或已下架」而非新建"卖家已被封禁"：下单是买家发起的，
 * 不该把另一个用户的账号状态泄露给买家。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCreateSellerStatusTest {

    private static final Long BUYER_ID = 5001L;
    private static final Long SELLER_ID = 5002L;
    private static final Long PRODUCT_ID = 6001L;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private StockService stockService;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private NotificationSender notificationSender;

    private OrderCreateService orderCreateService;

    @BeforeEach
    void setUp() {
        orderCreateService = new OrderCreateService(orderMapper, productMapper, userMapper,
                stockService, snowflakeIdGenerator, notificationSender);
        when(snowflakeIdGenerator.nextOrderNo()).thenReturn("1900000000000000001");
        when(stockService.deduct(anyLong(), anyInt())).thenReturn(1);
        when(orderMapper.insert(any())).thenReturn(1);
    }

    private Product onSaleProduct() {
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setUserId(SELLER_ID);
        product.setStatus(1);
        product.setTitle("高等数学 同济第七版");
        product.setPrice(new BigDecimal("9.90"));
        product.setTradeType(1);
        product.setStock(1);
        return product;
    }

    private static User seller(int status) {
        User user = new User();
        user.setId(SELLER_ID);
        user.setStatus(status);
        return user;
    }

    private static OrderCreateRequest request() {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setProductId(PRODUCT_ID);
        request.setQuantity(1);
        return request;
    }

    @Test
    @DisplayName("① 卖家已被封禁 → 下单被拒（204），且不扣库存、不落订单")
    void bannedSellerShouldBlockOrder() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(onSaleProduct());
        when(userMapper.selectById(SELLER_ID)).thenReturn(seller(1));

        assertThatThrownBy(() -> orderCreateService.create(request(), BUYER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE.getCode());

        verify(stockService, never()).deduct(anyLong(), anyInt());
        verify(orderMapper, never()).insert(any());
    }

    @Test
    @DisplayName("② 卖家不存在（已注销/已删除）→ 同样 204（不泄露内部原因）")
    void missingSellerShouldBlockOrder() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(onSaleProduct());
        when(userMapper.selectById(SELLER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderCreateService.create(request(), BUYER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商品不存在或已下架");

        verify(stockService, never()).deduct(anyLong(), anyInt());
    }

    @Test
    @DisplayName("③ 正常卖家 → 下单成功（本批改动不能把正常下单弄坏）")
    void normalSellerShouldOrderSuccessfully() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(onSaleProduct());
        when(userMapper.selectById(SELLER_ID)).thenReturn(seller(0));

        OrderCreateVO vo = orderCreateService.create(request(), BUYER_ID);

        assertThat(vo.getOrderNo()).isEqualTo("1900000000000000001");
        assertThat(vo.getAmount()).isEqualByComparingTo("9.90");
        verify(stockService).deduct(PRODUCT_ID, 1);
        verify(orderMapper).insert(any());
    }

    @Test
    @DisplayName("④ 商品非在售（下架/售罄/待审核）→ 204，且连卖家都不查（回归保护）")
    void offSaleProductShouldBeRejectedBeforeSellerLookup() {
        Product offShelf = onSaleProduct();
        offShelf.setStatus(2);   // 售罄：封禁后会长期停留在这个状态，不能被下单
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(offShelf);

        assertThatThrownBy(() -> orderCreateService.create(request(), BUYER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商品不存在或已下架");

        verify(userMapper, never()).selectById(anyLong());
        verify(stockService, never()).deduct(anyLong(), anyInt());
    }
}
