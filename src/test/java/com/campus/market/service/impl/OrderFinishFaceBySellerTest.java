package com.campus.market.service.impl;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.OrderProperties;
import com.campus.market.entity.Order;
import com.campus.market.entity.Product;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.security.LoginUser;
import com.campus.market.security.UserContext;
import com.campus.market.service.NotificationSender;
import com.campus.market.service.OrderTokenService;
import com.campus.market.service.PayCallbackSignService;
import com.campus.market.service.StockService;
import com.campus.market.vo.OrderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.5.1 安全加固 · M2 单测：已支付面交单的终态路径（卖家确认面交完成 1→3）。
 *
 * <p>守的是自审报告 M2：修前 {@code status=1, trade_type=1} 的订单<b>没有任何终态路径</b> ——
 * {@code finishFaceToFace} 要求 status=0、{@code receiveByFace} 是买家操作、
 * 自动收货只覆盖 status=2。买家付款后失联，卖家无接口可推进，订单永久停在 1。</p>
 *
 * <p>四条用例分别守住：卖家可以推进（1→3）、非卖家被拒（203）、
 * 邮寄单被拒（209）、状态不对被拒（0/2 → 209，已完成 → 200 幂等）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderFinishFaceBySellerTest {

    private static final Long ORDER_ID = 6101L;
    private static final Long SELLER_ID = 6102L;
    private static final Long BUYER_ID = 6103L;
    private static final Long PRODUCT_ID = 6104L;

    /** 交易方式：1-仅面交。 */
    private static final int TRADE_TYPE_FACE = 1;

    /** 交易方式：2-仅邮寄。 */
    private static final int TRADE_TYPE_MAIL = 2;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private OrderTokenService orderTokenService;

    @Mock
    private OrderCreateService orderCreateService;

    @Mock
    private StockService stockService;

    @Mock
    private NotificationSender notificationSender;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private PayCallbackSignService payCallbackSignService;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(orderMapper, productMapper, orderTokenService, orderCreateService,
                stockService, notificationSender, new OrderProperties(), redisTemplate, payCallbackSignService);
        when(productMapper.selectByIdIgnoreLogicDelete(anyLong())).thenReturn(new Product());
    }

    @AfterEach
    void tearDown() {
        // UserContext 是 ThreadLocal，必须清理，避免污染其它用例
        UserContext.clear();
    }

    /** 已支付、面交、待确认收货的订单（M2 修复前"无路可走"的那一类）。 */
    private Order paidFaceOrder(int status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("1900000000000006101");
        order.setUserId(BUYER_ID);
        order.setSellerId(SELLER_ID);
        order.setProductId(PRODUCT_ID);
        order.setProductTitle("高等数学 同济第七版");
        order.setAmount(new BigDecimal("9.90"));
        order.setQuantity(1);
        order.setStatus(status);
        order.setTradeType(TRADE_TYPE_FACE);
        return order;
    }

    private void loginAs(Long userId) {
        UserContext.set(new LoginUser(userId, 0, 0L));
    }

    @Test
    @DisplayName("① 卖家确认已支付面交单 → 1→3 成功，并通知买家")
    void sellerShouldFinishPaidFaceOrder() {
        loginAs(SELLER_ID);
        Order before = paidFaceOrder(OrderStatus.PAID);
        Order after = paidFaceOrder(OrderStatus.FINISHED);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(before, after);
        when(orderMapper.finishFaceBySeller(ORDER_ID, SELLER_ID)).thenReturn(1);

        OrderVO vo = orderService.finishFaceBySeller(ORDER_ID);

        verify(orderMapper).finishFaceBySeller(ORDER_ID, SELLER_ID);
        assertThat(vo.getStatus()).isEqualTo(OrderStatus.FINISHED);
        // 通知接收者必须是买家（不是当前登录的卖家）
        verify(notificationSender).sendAsync(eq(BUYER_ID), any(), any(), eq(ORDER_ID), anyString());
    }

    @Test
    @DisplayName("② 买家调用该接口 → 203，且一条 SQL 都不发（面交完成由卖家/买家各自的接口负责）")
    void buyerShouldNotFinishBySellerEndpoint() {
        loginAs(BUYER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(paidFaceOrder(OrderStatus.PAID));

        assertThatThrownBy(() -> orderService.finishFaceBySeller(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权操作该订单")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NO_PERMISSION.getCode());

        verify(orderMapper, never()).finishFaceBySeller(anyLong(), anyLong());
    }

    @Test
    @DisplayName("③ 邮寄订单（trade_type=2）调用 → 209（面交终态接口严禁作用于邮寄单）")
    void mailOrderShouldBeRejected() {
        loginAs(SELLER_ID);
        Order mailOrder = paidFaceOrder(OrderStatus.PAID);
        mailOrder.setTradeType(TRADE_TYPE_MAIL);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(mailOrder);
        // SQL 层 trade_type = 1 守卫 ⇒ 邮寄单影响行数为 0
        when(orderMapper.finishFaceBySeller(ORDER_ID, SELLER_ID)).thenReturn(0);

        assertThatThrownBy(() -> orderService.finishFaceBySeller(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前状态不允许此操作")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.STATUS_NOT_ALLOWED.getCode());
    }

    @Test
    @DisplayName("④ 状态不对：status=0/2 → 209；已是 3-已完成 → 200「请勿重复操作」（幂等语义）")
    void wrongStatusShouldBeRejectedWithCorrectCode() {
        loginAs(SELLER_ID);

        // 待支付(0)：还没付钱，不能由卖家直接完成
        when(orderMapper.selectById(ORDER_ID)).thenReturn(paidFaceOrder(OrderStatus.PENDING_PAY));
        when(orderMapper.finishFaceBySeller(ORDER_ID, SELLER_ID)).thenReturn(0);
        assertThatThrownBy(() -> orderService.finishFaceBySeller(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.STATUS_NOT_ALLOWED.getCode());

        // 已发货(2)：面交单不可能处于该状态，同样 209
        when(orderMapper.selectById(ORDER_ID)).thenReturn(paidFaceOrder(OrderStatus.SHIPPED));
        assertThatThrownBy(() -> orderService.finishFaceBySeller(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.STATUS_NOT_ALLOWED.getCode());

        // 已完成(3)：重复操作 → code=200 + 明确文案（与其它状态变更接口一致）
        when(orderMapper.selectById(ORDER_ID)).thenReturn(paidFaceOrder(OrderStatus.FINISHED));
        assertThatThrownBy(() -> orderService.finishFaceBySeller(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请勿重复操作")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SUCCESS.getCode());
    }

    @Test
    @DisplayName("⑤ 订单不存在（含已逻辑删除）→ 203（与全部其它订单接口同一语义）")
    void missingOrderShouldReturnNoPermission() {
        loginAs(SELLER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.finishFaceBySeller(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权操作该订单")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NO_PERMISSION.getCode());

        verify(orderMapper, never()).finishFaceBySeller(anyLong(), anyLong());
    }

    @Test
    @DisplayName("⑥ 回归：原有两条面交/收货路径不受影响（0→3 卖家直接完成、1→3 买家确认）")
    void existingPathsShouldRemainIntact() {
        loginAs(SELLER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(paidFaceOrder(OrderStatus.PENDING_PAY),
                paidFaceOrder(OrderStatus.FINISHED));
        when(orderMapper.finishFaceToFace(ORDER_ID, SELLER_ID)).thenReturn(1);
        assertThat(orderService.finishFaceToFace(ORDER_ID).getStatus()).isEqualTo(OrderStatus.FINISHED);
        verify(orderMapper).finishFaceToFace(ORDER_ID, SELLER_ID);

        UserContext.clear();
        loginAs(BUYER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(paidFaceOrder(OrderStatus.PAID),
                paidFaceOrder(OrderStatus.FINISHED));
        when(orderMapper.receiveByFace(ORDER_ID, BUYER_ID)).thenReturn(1);
        assertThat(orderService.receive(ORDER_ID).getStatus()).isEqualTo(OrderStatus.FINISHED);
        verify(orderMapper).receiveByFace(ORDER_ID, BUYER_ID);
        // 买家走的是 receive，不该误调卖家那条 SQL
        verify(orderMapper, never()).finishFaceBySeller(anyLong(), anyLong());
    }
}
