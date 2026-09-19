package com.campus.market.service.impl;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.OrderProperties;
import com.campus.market.config.properties.PayProperties;
import com.campus.market.dto.order.PayCallbackRequest;
import com.campus.market.entity.Order;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.service.NotificationSender;
import com.campus.market.service.OrderTokenService;
import com.campus.market.service.PayCallbackSignService;
import com.campus.market.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.2 安全加固 · S3 单测：支付回调必须验签。
 *
 * <p>守的是自审报告里的 S3：{@code /api/v1/order/pay/callback} 是<b>公开路径</b>，
 * 修前只做 Redis 去重 + 状态机 —— 任何人知道 orderNo 就能把订单 0→1，
 * 并让卖家收到「买家已支付，请尽快发货」的站内信。</p>
 *
 * <p>四个用例对应需求里规定的四条行为：无签名 / 错签名 / 过期时间戳 → code=100；
 * 正确签名 → 完成状态流转。用例里的签名都是<b>用 JDK 的 Mac 独立算出来的</b>
 * （不复用被测代码的 {@code hmacSha256Hex}），否则实现写错时测试会跟着一起错。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayCallbackVerificationTest {

    /** 单测用密钥（>= 32 字节）。 */
    private static final String SECRET = "unit-test-pay-callback-secret-0123456789-abcdefg";

    private static final long ORDER_ID = 987654321L;
    private static final String ORDER_NO = "1900000000000000001";
    private static final String TRADE_NO = "TRADE-20260919-0001";

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
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Environment environment;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        PayProperties payProperties = new PayProperties();
        payProperties.setCallbackSecret(SECRET);
        payProperties.setTimestampWindowSeconds(300L);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        PayCallbackSignService signService = new PayCallbackSignService(payProperties, environment);

        orderService = new OrderServiceImpl(orderMapper, productMapper, orderTokenService, orderCreateService,
                stockService, notificationSender, new OrderProperties(), redisTemplate, signService);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);
        // 批次 6.0.6 · Minor 9：去重改为"读侧判定（hasKey）+ 写侧在提交后落键"
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
    }

    private PayCallbackRequest request(Long timestamp, String sign) {
        PayCallbackRequest request = new PayCallbackRequest();
        request.setOrderNo(ORDER_NO);
        request.setTradeNo(TRADE_NO);
        request.setTimestamp(timestamp);
        request.setSign(sign);
        return request;
    }

    /** 测试侧独立实现：HMAC-SHA256(secret, orderNo|tradeNo|timestamp) 的十六进制小写。 */
    private static String sign(String orderNo, String tradeNo, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((orderNo + "|" + tradeNo + "|" + timestamp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Order pendingOrder() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setStatus(OrderStatus.PENDING_PAY);
        order.setSellerId(2L);
        order.setProductTitle("高等数学 同济第七版");
        return order;
    }

    @Test
    @DisplayName("① 无签名（只有 orderNo + tradeNo）→ code=100，且不碰 Redis、不改状态")
    void callbackWithoutSignIsRejected() {
        // 这正是自审报告里那条 curl：修前它会把订单改成"已支付"
        PayCallbackRequest request = request(System.currentTimeMillis(), null);

        assertThatThrownBy(() -> orderService.handlePayCallback(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("回调签名校验失败")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());

        verify(orderMapper, never()).pay(any());
        // 未通过验签的请求绝不能占用 Redis 去重键：否则它会把随后到达的合法回调当成"重复回调"吞掉
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("② 错误签名 → code=100，且不碰 Redis、不改状态")
    void callbackWithWrongSignIsRejected() {
        PayCallbackRequest request = request(System.currentTimeMillis(), "0123456789abcdef");

        assertThatThrownBy(() -> orderService.handlePayCallback(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("回调签名校验失败")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());

        verify(orderMapper, never()).pay(any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("③ 签名正确但时间戳超出窗口（5 分钟前的重放）→ code=100")
    void callbackWithExpiredTimestampIsRejected() {
        // 签名本身是对的 —— 说明拦下它的必须是时间窗，而不是签名比对
        long expired = System.currentTimeMillis() - 301_000L;
        PayCallbackRequest request = request(expired, sign(ORDER_NO, TRADE_NO, expired));

        assertThatThrownBy(() -> orderService.handlePayCallback(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("回调签名校验失败")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());

        verify(orderMapper, never()).pay(any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("④ 签名正确且在窗口内 → 通过，订单 0→1 完成状态流转")
    void callbackWithValidSignIsAccepted() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(orderMapper.pay(ORDER_ID)).thenReturn(1);

        boolean processed = orderService.handlePayCallback(request(now, sign(ORDER_NO, TRADE_NO, now)));

        assertThat(processed).isTrue();
        verify(orderMapper).pay(ORDER_ID);
        // 状态真的变了才发通知（"买家已支付，请尽快发货"）
        verify(notificationSender).sendAsync(any(), any(), any(), any(), anyString());
        // 去重键在验签通过、状态机生效之后才写（批次 6.0.6 · Minor 9：写侧改到 runAfterCommit）
        verify(valueOperations).set("pay:callback:" + ORDER_NO + ":" + TRADE_NO, "1", Duration.ofDays(7));
    }

    @Test
    @DisplayName("⑥ 去重键已存在（同一笔流水的重复回调）→ 返回 false 且不再改状态（Minor 9 读侧）")
    void duplicateCallbackIsIgnoredWithoutTouchingStateMachine() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(redisTemplate.hasKey("pay:callback:" + ORDER_NO + ":" + TRADE_NO)).thenReturn(true);

        boolean processed = orderService.handlePayCallback(request(now, sign(ORDER_NO, TRADE_NO, now)));

        assertThat(processed).as("重复回调返回 false（不是异常）").isFalse();
        verify(orderMapper, never()).pay(any());
        verify(notificationSender, never()).sendAsync(any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("⑦ ★Minor 9：状态机失败（事务要回滚）→ 去重键绝不落 Redis，同一笔流水可安全重试")
    void dedupKeyIsNotWrittenWhenStateMachineFails() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        // 模拟事务内后续步骤失败（DB 抖动 / 约束冲突）→ 事务将回滚
        when(orderMapper.pay(ORDER_ID)).thenThrow(new IllegalStateException("模拟事务内失败"));

        assertThatThrownBy(() -> orderService.handlePayCallback(request(now, sign(ORDER_NO, TRADE_NO, now))))
                .isInstanceOf(IllegalStateException.class);

        // 核心断言：修前这里是"键已占位、状态没变"——支付方重试同一笔流水会被永久吞掉
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("⑤ 补充：签名正确但订单不存在 → 同样 code=100（对外不暴露 orderNo 是否存在）")
    void callbackWithUnknownOrderIsRejectedWithSameMessage() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> orderService.handlePayCallback(request(now, sign(ORDER_NO, TRADE_NO, now))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("回调签名校验失败");

        verify(orderMapper, never()).pay(any());
    }
}
