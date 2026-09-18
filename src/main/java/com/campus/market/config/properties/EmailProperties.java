package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 邮箱验证码配置（含毕设降级开关 email.skip）。
 *
 * <p><b>批次 6.0.1 安全加固</b>：{@link #skip} 的默认值由 {@code true} 改为 {@code false}。</p>
 * <p>原设计是"开发/答辩默认跳过 SMTP"，但默认 true 的后果是：只要忘了配环境变量，
 * 验证码就回显到接口响应体，而 {@code /auth/email-code} 与 {@code /auth/reset-password}
 * 都是公开路径 —— <b>等于任意账号可被接管</b>。现在改成"安全默认 + 显式开启"：
 * 本地开发想要跳过 SMTP，显式设 {@code EMAIL_SKIP=true}（{@code application-dev.yml} 已为 dev 写好），
 * 生产环境则由 {@code application-prod.yml} 的占位符默认值与启动断言双重兜住。</p>
 * <p>同时：{@code skip=true} 也<b>不再无条件回显验证码</b> —— 只有 dev profile 才回显
 * （见 {@code EmailCodeServiceImpl#send}）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.email")
public class EmailProperties {

    /**
     * 邮箱降级开关：true = 不依赖 SMTP，验证码直接在后端日志输出（仅 dev 还会回显到响应体）。
     *
     * <p>默认 {@code false}（发真实邮件）。本地开发/答辩演示需要跳过 SMTP 时，
     * 显式设环境变量 {@code EMAIL_SKIP=true}；生产环境设为 true 会导致应用启动失败。</p>
     */
    private boolean skip = false;

    /** 验证码有效期（秒），对应 Redis email:code:{email}。 */
    private long codeExpireSeconds = 300L;

    /** 发送限流（秒），命中返回 code=106。 */
    private long limitSeconds = 60L;

    /** 失败锁定阈值，1 小时窗口内累计达 5 次锁定。 */
    private int failLockThreshold = 5;

    /** 锁定时长（秒），返回 code=107。 */
    private long failLockSeconds = 1800L;

    /** 失败计数窗口（秒）。 */
    private long failWindowSeconds = 3600L;

    /** 校园邮箱后缀允许列表（严禁硬编码在代码中）。 */
    private List<String> campusSuffixes = List.of("@stu.edu.cn");
}
