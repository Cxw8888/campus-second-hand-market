package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 邮箱验证码配置（含毕设降级开关 email.skip）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.email")
public class EmailProperties {

    /**
     * 毕设降级开关：开发 / 答辩环境默认 true，验证码直接在接口响应中返回并打印控制台，
     * 不依赖 SMTP，避免答辩现场网络问题。生产环境必须 false。
     */
    private boolean skip = true;

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
