package com.campus.market.dto.order;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 6.0.6 · 自审 Minor 4 单测：下单数量必须有上限（{@code @Max(100)}）。
 *
 * <p>修前 {@code OrderCreateRequest.quantity} 只有 {@code @Min(1)}：
 * {@code quantity=999999} 会一路走到"读商品 → 校验库存 → CAS 扣减"。
 * CAS 的 {@code stock >= quantity} 能挡住超卖（无资损），但白耗一次库存行锁，
 * 而且报错语义是 201「库存不足」而不是参数错误 —— 参数层能拒绝的输入不该下沉到业务层。</p>
 *
 * <p>用真实的 Hibernate Validator 校验（不 mock），断言的是<b>注解契约</b>本身：
 * 99/100 放行、101 拒绝、0 与负数仍由 {@code @Min} 拦住、null 由 {@code @NotNull} 拦住。</p>
 */
class OrderCreateQuantityValidationTest {

    private static ValidatorFactory factory;

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static OrderCreateRequest requestWithQuantity(Integer quantity) {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setProductId(1L);
        request.setQuantity(quantity);
        return request;
    }

    private static Set<String> messages(Integer quantity) {
        return validator.validate(requestWithQuantity(quantity)).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("① quantity=100（上限）→ 无校验错误")
    void quantityAtUpperBoundIsValid() {
        assertThat(messages(100)).isEmpty();
    }

    @Test
    @DisplayName("② quantity=1（下限）→ 无校验错误")
    void quantityAtLowerBoundIsValid() {
        assertThat(messages(1)).isEmpty();
    }

    @Test
    @DisplayName("③ ★quantity=101 → 被 @Max 拦下（修前会一路走到库存校验）")
    void quantityAboveUpperBoundIsRejected() {
        assertThat(messages(101))
                .as("超过上限必须由参数校验拦下，而不是等库存不足（201）")
                .containsExactly("购买数量不能超过100");
    }

    @Test
    @DisplayName("④ quantity=999999（恶意大数）→ 同样被 @Max 拦下")
    void maliciousLargeQuantityIsRejected() {
        assertThat(messages(999999)).containsExactly("购买数量不能超过100");
    }

    @Test
    @DisplayName("⑤ quantity=0 / -1 → 仍由 @Min 拦下（上限没有把下限挤掉）")
    void nonPositiveQuantityIsStillRejected() {
        assertThat(messages(0)).containsExactly("购买数量必须大于0");
        assertThat(messages(-1)).containsExactly("购买数量必须大于0");
    }

    @Test
    @DisplayName("⑥ quantity=null → 由 @NotNull 拦下")
    void nullQuantityIsRejected() {
        assertThat(messages(null)).containsExactly("购买数量不能为空");
    }
}
