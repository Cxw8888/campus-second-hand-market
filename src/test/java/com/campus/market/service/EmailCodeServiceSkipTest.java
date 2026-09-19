package com.campus.market.service;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.config.properties.EmailProperties;
import com.campus.market.vo.EmailCodeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * §10-2 毕设降级开关：{@code app.email.skip=true} 时验证码直接返回，完全不碰 SMTP。
 *
 * <p>这条用例证明的是"答辩现场不依赖校园网 SMTP"这个设计目标：只要开关打开，
 * 无论邮件服务器是否可用，接口都能正常拿到验证码。</p>
 *
 * <p><b>注意：</b>dev profile 本身就配了 {@code app.email.skip=true}，但这里仍然显式用
 * properties 写一遍——否则将来有人在 dev 配置里把它改成 false，这条用例会静默地
 * 转去测真实 SMTP 分支（并且因为 mailSender 是 mock、没打桩而报错），失去原本的验证意图。</p>
 *
 * <p><b>6.0.1 起增加一个隐含前提</b>：验证码"回显"还需要当前 profile 是 dev
 * （非 dev 即使 skip=true 也只写日志、不回显，见 {@code EmailCodeServiceImpl#send}）。
 * 本用例没有 {@code @ActiveProfiles}，profile 取自 {@code application.yml} 的默认值 dev，
 * 所以回显成立。若将来有人给这个测试类加上别的 profile，这里的"验证码非空"断言会失败——
 * 那正是我们想要的行为（说明回显被正确地关掉了）。</p>
 *
 * <p>用 {@code @MockBean}：Spring Boot 3.2.5，{@code @MockitoBean} 要 3.4+。</p>
 */
@SpringBootTest(properties = "app.email.skip=true")
class EmailCodeServiceSkipTest {

    /** 固定测试邮箱：必须落在 app.email.campus-suffixes 白名单内，否则先被判 102。 */
    private static final String EMAIL = "unit-mail-skip@stu.edu.cn";

    private static final String SCENE = "REGISTER";

    @Autowired
    private EmailCodeService emailCodeService;

    @Autowired
    private EmailProperties emailProperties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 被 mock 掉的邮件发送器：降级模式下它应当"一次都不被调用"。 */
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
    @DisplayName("§10-2 email.skip=true → 验证码直接返回，且不调用 JavaMailSender")
    void sendShouldReturnCodeDirectlyAndNeverTouchMailSender() {
        EmailCodeVO vo = emailCodeService.send(EMAIL, SCENE);

        // ① 降级标记与验证码直接下发
        assertThat(vo.isSkip())
                .as("skip=true 时响应必须标记为降级模式")
                .isTrue();
        assertThat(vo.getCode())
                .as("降级模式下验证码必须直接返回（6.0.1 起仅 dev profile 回显；非 dev / skip=false 时为 null）")
                .isNotNull()
                .matches("\\d{6}");
        assertThat(vo.getExpireSeconds())
                .as("有效期应取自 app.email.code-expire-seconds")
                .isEqualTo(emailProperties.getCodeExpireSeconds());

        // ② 返回的验证码必须真的落在 Redis 里，且与响应一致——
        //    否则前端拿着这个码去注册会在 verify() 阶段失败
        String cached = redisTemplate.opsForValue().get(RedisKeys.emailCode(SCENE, EMAIL));
        assertThat(cached)
                .as("email:code:%s:%s 应已写入，且与响应返回的验证码一致", SCENE, EMAIL)
                .isEqualTo(vo.getCode());

        // ③ 核心断言：降级模式绝不能碰 SMTP
        verifyNoInteractions(mailSender);
    }

    /**
     * 清理本用例碰过的 4 个 Redis Key（含 60 秒发送限流锁，不清会导致重复运行被判 106）。
     */
    private void clearRedisKeys() {
        redisTemplate.delete(List.of(
                RedisKeys.emailCode(SCENE, EMAIL),
                RedisKeys.emailLimit(EMAIL),
                RedisKeys.emailFail(EMAIL),
                RedisKeys.emailLock(EMAIL)));
    }
}
