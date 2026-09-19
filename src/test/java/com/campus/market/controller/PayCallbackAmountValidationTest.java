package com.campus.market.controller;

import com.campus.market.common.exception.GlobalExceptionHandler;
import com.campus.market.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付回调的<b>参数层校验</b>（S3 遗留批）：amount 缺失 / 越界必须在进业务逻辑之前被挡掉。
 *
 * <h3>为什么这条要在 Controller 层测</h3>
 * <p>amount 的 {@code @NotNull} / {@code @DecimalMin} / {@code @DecimalMax} 由 Bean Validation 在
 * {@code @Valid @RequestBody} 处执行，失败后由 {@link GlobalExceptionHandler} 统一转成
 * <b>HTTP 200 + code=100</b>（字段文案，如「支付金额不能为空」）。这两段配合只有走到 HTTP 层才看得见：
 * 直接调 Service 是测不到的（Service 假设参数已经合法）。</p>
 *
 * <p>用 {@code standaloneSetup}（不启动 Spring 上下文）而不是 {@code @SpringBootTest}：
 * 这里要验证的是"注解 + 异常处理器"这对契约，不需要真实 DB / Redis；standalone 更快也更稳
 * （项目里其它单测也都是无容器的）。</p>
 *
 * <p>⚠️ 区分两类失败：<b>金额缺失/越界 = 请求格式问题</b>（本类，字段文案）；
 * <b>金额与订单不一致 = 账目对不上</b>（{@code PayCallbackVerificationTest} ⑧，
 * 统一文案「回调签名校验失败」，不泄露期望值）。</p>
 */
class PayCallbackAmountValidationTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OrderService orderService = mock(OrderService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String body(String amountPart) {
        return "{"
                + "\"orderNo\":\"1900000000000000001\","
                + "\"tradeNo\":\"TRADE-20260919-0001\","
                + amountPart
                + "\"timestamp\":1726704000000,"
                + "\"sign\":\"deadbeef\""
                + "}";
    }

    @Test
    @DisplayName("① 完全不传 amount → HTTP 200 + code=100「支付金额不能为空」（不会走到业务逻辑）")
    void missingAmountIsRejectedByBeanValidation() throws Exception {
        mockMvc.perform(post("/api/v1/order/pay/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100))
                .andExpect(jsonPath("$.msg").value("支付金额不能为空"));
    }

    @Test
    @DisplayName("② amount=null（显式传 null）→ 同样 code=100")
    void explicitNullAmountIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/order/pay/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"amount\":null,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100))
                .andExpect(jsonPath("$.msg").value("支付金额不能为空"));
    }

    @Test
    @DisplayName("③ amount=0 → code=100「支付金额必须大于 0」")
    void zeroAmountIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/order/pay/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"amount\":0,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100))
                .andExpect(jsonPath("$.msg").value("支付金额必须大于 0"));
    }

    @Test
    @DisplayName("④ amount 为负数 → code=100「支付金额必须大于 0」")
    void negativeAmountIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/order/pay/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"amount\":-0.01,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100))
                .andExpect(jsonPath("$.msg").value("支付金额必须大于 0"));
    }

    @Test
    @DisplayName("⑤ amount 超过上限（99999999.99）→ code=100「支付金额超出上限」")
    void amountAboveUpperBoundIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/order/pay/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"amount\":9999999999999999.99,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100))
                .andExpect(jsonPath("$.msg").value("支付金额超出上限"));
    }

    @Test
    @DisplayName("⑥ 边界值 0.01 与 99999999.99 → 通过参数校验（进到业务层，由 Service 决定成败）")
    void boundaryAmountsPassValidation() throws Exception {
        // Service 是 mock：走到这里说明参数校验已放行（返回 mock 的默认 null → code=100 由业务层决定，
        // 因此这里只断言"不是参数校验失败的那两条文案"）
        mockMvc.perform(post("/api/v1/order/pay/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"amount\":0.01,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.not("支付金额必须大于 0")));

        mockMvc.perform(post("/api/v1/order/pay/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"amount\":99999999.99,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.not("支付金额超出上限")));
    }

    @Test
    @DisplayName("⑦ 订单号为空 → code=100「订单号不能为空」（原有校验未被破坏）")
    void blankOrderNoIsStillRejected() throws Exception {
        mockMvc.perform(post("/api/v1/order/pay/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"\",\"tradeNo\":\"T\",\"amount\":45.00,"
                                + "\"timestamp\":1726704000000,\"sign\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100))
                .andExpect(jsonPath("$.msg").value("订单号不能为空"));
    }
}
