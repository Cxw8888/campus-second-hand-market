package com.campus.market.service;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * §10-1 邮件发送失败路径：SMTP 抛 {@link MailSendException} → code=105，并删除本次验证码。
 *
 * <p><b>两处必须先说清楚的语义（容易踩坑）：</b></p>
 * <ol>
 *   <li>{@code send()} <b>不是"返回 code=105"</b>。它在失败时<b>抛</b> {@link BusinessException}，
 *       业务码挂在 {@code getCode()} 上（105 = MAIL_SEND_FAILED），与 HTTP 响应体的装配由
 *       {@code GlobalExceptionHandler} 负责。所以这里断言的是"抛出 105"。</li>
 *   <li>{@link com.campus.market.vo.EmailCodeVO#getCode()} 里的 {@code code} 是<b>6 位验证码本身</b>，
 *       不是业务响应码。命名撞车，别混淆。</li>
 * </ol>
 *
 * <p><b>为什么要把 skip 关掉：</b>{@code app.email.skip} 默认 true（毕设降级，验证码直接返回、
 * 不碰 SMTP）。只有 {@code skip=false} 才会走到 {@code sendByMail}，也才可能触发 105。
 * dev profile 里 {@code app.email.skip=true}，所以这里必须用 properties 显式覆盖。</p>
 *
 * <p>用 {@code @MockBean}：本项目 Spring Boot 3.2.5，{@code @MockitoBean} 是 3.4 才有的
 * （{@code @MockBean} 从 3.4 起才弃用）。</p>
 */
@SpringBootTest(properties = "app.email.skip=false")
class EmailCodeServiceFailureTest {

    /** 固定测试邮箱：校园后缀必须落在 app.email.campus-suffixes 白名单内。 */
    private static final String EMAIL = "unit-mail-failure@stu.edu.cn";

    private static final String SCENE = "REGISTER";

    @Autowired
    private EmailCodeService emailCodeService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 被 mock 掉的邮件发送器：本用例让它固定抛 MailSendException。 */
    @MockBean
    private JavaMailSender mailSender;

    @BeforeEach
    void clearRedisBefore() {
        clearRedisKeys();
    }

    @AfterEach
    void clearRedisAfter() {
        clearRedisKeys();
    }

    @Test
    @DisplayName("§10-1 SMTP 发送失败 → 抛 code=105，且本次验证码被删除")
    void sendShouldThrow105AndDeleteCodeWhenSmtpFails() {
        // 模拟真实 SMTP 故障（连接超时/认证失败等最终都会包成 MailSendException extends MailException）
        doThrow(new MailSendException("SMTP 连接超时（测试注入）"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        String codeKey = RedisKeys.emailCode(SCENE, EMAIL);

        assertThatThrownBy(() -> emailCodeService.send(EMAIL, SCENE))
                .as("SMTP 失败时应抛业务异常，而不是把异常吞掉")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .as("业务码应为 105（MAIL_SEND_FAILED），实际 %d",
                                ((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.MAIL_SEND_FAILED.getCode()))
                .hasMessage(ErrorCode.MAIL_SEND_FAILED.getMsg());

        // 证明"验证码确实生成过、也真的交给邮件了"——否则下面那条删除断言会失去意义
        // （Key 不存在也可能是因为压根没写过）。
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).as("收件人应为请求的邮箱").containsExactly(EMAIL);
        assertThat(sent.getSubject()).as("邮件主题应含「验证码」").contains("验证码");
        assertThat(sent.getText())
                .as("邮件正文应包含 6 位验证码")
                .containsPattern("\\d{6}");

        // 核心断言：发送失败必须把本次验证码删掉，绝不能留下一个"送不到却能用"的有效码
        assertThat(redisTemplate.hasKey(codeKey))
                .as("SMTP 失败后 email:code:%s:%s 必须被删除，否则会出现无法送达但仍可用的验证码", SCENE, EMAIL)
                .isFalse();
        assertThat(redisTemplate.opsForValue().get(codeKey)).isNull();
    }

    /**
     * 清理本用例碰过的 4 个 Redis Key。
     *
     * <p>{@code email:limit:} 尤其重要：{@code send()} 会 SETNX 一把 60 秒的发送限流锁，
     * 不清掉的话同一个邮箱在 60 秒内二次获取会直接抛 <b>106 发送过于频繁</b>，
     * 用例就会以一个和本意无关的原因失败（连续跑两次必踩）。</p>
     */
    private void clearRedisKeys() {
        redisTemplate.delete(List.of(
                RedisKeys.emailCode(SCENE, EMAIL),
                RedisKeys.emailLimit(EMAIL),
                RedisKeys.emailFail(EMAIL),
                RedisKeys.emailLock(EMAIL)));
    }
}
