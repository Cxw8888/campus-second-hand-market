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
import com.campus.market.service.support.PayCallbackProcessor;
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
import java.math.BigDecimal;
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
 * 支付回调校验单测：批次 6.0.2（S3 验签）+ 6.0.6（Minor 9 去重键时机）+ <b>S3 遗留批（金额校验）</b>。
 *
 * <p>守的是自审报告 S3：{@code /api/v1/order/pay/callback} 是<b>公开路径</b>，
 * 修前只做 Redis 去重 + 状态机 —— 任何人知道 orderNo 就能把订单 0→1，
 * 并让卖家收到「买家已支付，请尽快发货」的站内信。</p>
 *
 * <h3>S3 遗留批新增的两层保护</h3>
 * <ol>
 *   <li><b>金额纳入签名</b>：payload 从三段 {@code orderNo|tradeNo|timestamp} 改为四段
 *       {@code orderNo|tradeNo|amount|timestamp}（amount 固定 2 位小数）；</li>
 *   <li><b>金额与订单比对</b>：即便签名合法，也要 {@code compareTo} 等于订单快照金额 ——
 *       否则持密钥者可签一个 0.01 的回调把订单象征性付掉。</li>
 * </ol>
 *
 * <p>用例里的签名都是<b>用 JDK 的 Mac 独立算出来的</b>（不复用被测代码的 {@code hmacSha256Hex}），
 * 否则实现写错时测试会跟着一起错。全部失败路径都断言"对外只有一句文案"。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayCallbackVerificationTest {

    /** 单测用密钥（>= 32 字节）。 */
    private static final String SECRET = "unit-test-pay-callback-secret-0123456789-abcdefg";

    private static final long ORDER_ID = 987654321L;
    private static final String ORDER_NO = "1900000000000000001";
    private static final String TRADE_NO = "TRADE-20260919-0001";

    /** 订单快照金额（DECIMAL(10,2)，读出即 45.00）。 */
    private static final BigDecimal ORDER_AMOUNT = new BigDecimal("45.00");

    private static final String UNIFIED_MSG = "回调签名校验失败";

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

        // 状态机用真实的 processor（只是把 mapper / 通知 mock 掉）：
        // 这样 SQL 影响行数与"提交后才写去重键"的编排仍然受测，而不是被整体 mock 掉
        PayCallbackProcessor payCallbackProcessor = new PayCallbackProcessor(orderMapper, notificationSender);

        orderService = new OrderServiceImpl(orderMapper, productMapper, orderTokenService, orderCreateService,
                stockService, notificationSender, new OrderProperties(), redisTemplate, signService,
                payCallbackProcessor);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);
        // 批次 6.0.6 · Minor 9：去重改为"读侧判定（hasKey）+ 写侧在写库提交后落键"
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
    }

    private PayCallbackRequest request(Long timestamp, String sign) {
        return request(timestamp, sign, ORDER_AMOUNT);
    }

    private PayCallbackRequest request(Long timestamp, String sign, BigDecimal amount) {
        PayCallbackRequest request = new PayCallbackRequest();
        request.setOrderNo(ORDER_NO);
        request.setTradeNo(TRADE_NO);
        request.setAmount(amount);
        request.setTimestamp(timestamp);
        request.setSign(sign);
        return request;
    }

    /**
     * 测试侧独立实现：HMAC-SHA256(secret, orderNo|tradeNo|amount|timestamp) 的十六进制小写。
     *
     * <p>amount 直接传字符串（如 {@code "45.00"}）—— 与被测代码"先格式化再拼 payload"的约定一致，
     * 也让"用错格式算签名"这类用例能写得很直白。</p>
     */
    private static String sign(String orderNo, String tradeNo, String amountStr, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((orderNo + "|" + tradeNo + "|" + amountStr + "|" + timestamp)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按订单快照金额（"45.00"）签名。 */
    private static String sign(String orderNo, String tradeNo, long timestamp) {
        return sign(orderNo, tradeNo, "45.00", timestamp);
    }

    private Order order(int status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setStatus(status);
        order.setSellerId(2L);
        order.setAmount(ORDER_AMOUNT);
        order.setProductTitle("高等数学 同济第七版");
        return order;
    }

    private Order pendingOrder() {
        return order(OrderStatus.PENDING_PAY);
    }

    private static void assertUnifiedRejection(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .hasMessage(UNIFIED_MSG)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    // ================================================================ 原有 7 条（payload 已改为四段）

    @Test
    @DisplayName("① 无签名 → code=100「回调签名校验失败」，且不碰 Redis、不改状态")
    void callbackWithoutSignIsRejected() {
        // 这正是自审报告里那条 curl：修前它会把订单改成"已支付"
        assertUnifiedRejection(() -> orderService.handlePayCallback(request(System.currentTimeMillis(), null)));

        verify(orderMapper, never()).pay(any());
        // 未通过验签的请求绝不能占用 Redis 去重键：否则它会把随后到达的合法回调当成"重复回调"吞掉
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("② 错误签名 → code=100，且不碰 Redis、不改状态")
    void callbackWithWrongSignIsRejected() {
        assertUnifiedRejection(() -> orderService.handlePayCallback(
                request(System.currentTimeMillis(), "0123456789abcdef")));

        verify(orderMapper, never()).pay(any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("③ 签名正确但时间戳超出窗口（5 分钟前的重放）→ code=100")
    void callbackWithExpiredTimestampIsRejected() {
        // 签名本身是对的 —— 说明拦下它的必须是时间窗，而不是签名比对
        long expired = System.currentTimeMillis() - 301_000L;

        assertUnifiedRejection(() -> orderService.handlePayCallback(
                request(expired, sign(ORDER_NO, TRADE_NO, expired))));

        verify(orderMapper, never()).pay(any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("④ 签名正确 + 金额一致 + 待支付 → 通过，订单 0→1 并通知卖家")
    void callbackWithValidSignIsAccepted() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(orderMapper.pay(ORDER_ID)).thenReturn(1);

        boolean processed = orderService.handlePayCallback(request(now, sign(ORDER_NO, TRADE_NO, now)));

        assertThat(processed).isTrue();
        verify(orderMapper).pay(ORDER_ID);
        // 状态真的变了才发通知（"买家已支付，请尽快发货"）
        verify(notificationSender).sendAsync(any(), any(), any(), any(), anyString());
        // 去重键在验签通过、金额一致且状态机生效之后才写（6.0.6 · Minor 9：提交后才落键）
        verify(valueOperations).set("pay:callback:" + ORDER_NO + ":" + TRADE_NO, "1", Duration.ofDays(7));
    }

    @Test
    @DisplayName("⑤ 签名正确但订单不存在 → 同样 code=100（对外不暴露 orderNo 是否存在）")
    void callbackWithUnknownOrderIsRejectedWithSameMessage() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertUnifiedRejection(() -> orderService.handlePayCallback(
                request(now, sign(ORDER_NO, TRADE_NO, now))));

        verify(orderMapper, never()).pay(any());
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

    // ================================================================ S3 遗留批：金额校验

    @Test
    @DisplayName("⑧ ★金额与订单不一致（签名对新金额有效）→ code=100 统一文案，不改状态、不占去重键")
    void amountMismatchWithOrderIsRejectedWithUnifiedMessage() {
        long now = System.currentTimeMillis();
        BigDecimal oneYuan = new BigDecimal("1.00");
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());

        // 签名是"持密钥者"用 1.00 正确签出来的 —— 说明拦下它的必须是金额比对，而不是验签
        assertUnifiedRejection(() -> orderService.handlePayCallback(
                request(now, sign(ORDER_NO, TRADE_NO, "1.00", now), oneYuan)));

        verify(orderMapper, never()).pay(any());
        verify(notificationSender, never()).sendAsync(any(), any(), any(), any(), anyString());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("⑨ ★body 金额与签名金额不一致（签名用 1.00、body 传 45.00）→ 验签阶段即 code=100")
    void signatureComputedOverDifferentAmountIsRejected() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());

        assertUnifiedRejection(() -> orderService.handlePayCallback(
                request(now, sign(ORDER_NO, TRADE_NO, "1.00", now), ORDER_AMOUNT)));

        verify(orderMapper, never()).pay(any());
    }

    @Test
    @DisplayName("⑩ ★金额精度：body 传 45（无小数）+ 签名用 \"45.00\" → 通过（服务端统一格式化为 2 位小数）")
    void amountWithoutScaleStillMatchesBecauseBothSidesNormalize() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(orderMapper.pay(ORDER_ID)).thenReturn(1);

        boolean processed = orderService.handlePayCallback(
                request(now, sign(ORDER_NO, TRADE_NO, "45.00", now), new BigDecimal("45")));

        assertThat(processed)
                .as("JSON 里的 45 与 45.00 必须视为同一笔金额（否则两端 payload 不一致，永远验不过）")
                .isTrue();
        verify(orderMapper).pay(ORDER_ID);
    }

    @Test
    @DisplayName("⑪ 金额格式化的四舍五入：body 传 45.005、签名用 \"45.01\" → 验签通过但金额不匹配 → 100")
    void amountRoundingUsesHalfUpAndComparisonIsNumeric() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());

        // formatAmount(45.005) = "45.01"（HALF_UP）→ 签名能对上；再与订单 45.00 做 compareTo → 不等
        assertUnifiedRejection(() -> orderService.handlePayCallback(
                request(now, sign(ORDER_NO, TRADE_NO, "45.01", now), new BigDecimal("45.005"))));

        verify(orderMapper, never()).pay(any());
    }

    @Test
    @DisplayName("⑫ 订单已支付（status=1）+ 金额一致 → 幂等返回 false（不是 209）")
    void alreadyPaidOrderWithMatchingAmountIsIdempotent() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(order(OrderStatus.PAID));

        boolean processed = orderService.handlePayCallback(request(now, sign(ORDER_NO, TRADE_NO, now)));

        assertThat(processed).isFalse();
        verify(orderMapper, never()).pay(any());
    }

    @Test
    @DisplayName("⑬ ★订单已支付（status=1）+ 金额不匹配 → code=100（金额校验优先于状态幂等）")
    void alreadyPaidOrderWithWrongAmountIsRejected() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(order(OrderStatus.PAID));

        assertUnifiedRejection(() -> orderService.handlePayCallback(
                request(now, sign(ORDER_NO, TRADE_NO, "1.00", now), new BigDecimal("1.00"))));

        verify(orderMapper, never()).pay(any());
    }

    @Test
    @DisplayName("⑭ 订单已取消（status=4）+ 金额一致 → 209「当前状态不允许此操作」")
    void cancelledOrderWithMatchingAmountIsStatusNotAllowed() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(order(OrderStatus.CANCELLED));

        assertThatThrownBy(() -> orderService.handlePayCallback(request(now, sign(ORDER_NO, TRADE_NO, now))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.STATUS_NOT_ALLOWED.getCode());

        verify(orderMapper, never()).pay(any());
    }

    @Test
    @DisplayName("⑮ 旧的三段 payload 签名（V34 及之前）不再被接受 → code=100（breaking change 已生效）")
    void legacyThreeSegmentSignatureIsNoLongerAccepted() {
        long now = System.currentTimeMillis();
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        String legacySign = legacySign(ORDER_NO, TRADE_NO, now);

        assertUnifiedRejection(() -> orderService.handlePayCallback(request(now, legacySign)));

        verify(orderMapper, never()).pay(any());
    }

    /** 旧格式（无金额）签名，仅用于证明它已被废弃。 */
    private static String legacySign(String orderNo, String tradeNo, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((orderNo + "|" + tradeNo + "|" + timestamp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
