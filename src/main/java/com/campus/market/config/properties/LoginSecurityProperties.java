package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录防爆破配置（账号 + IP 双维度）。
 *
 * <p>账号维度：login:fail:{username}（TTL 5 分钟），达 5 次写 login:lock:{username}（TTL 15 分钟）。<br>
 * IP 维度：login:fail:ip:{ip}，单 IP 5 分钟内失败 20 次限制登录 30 分钟。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security.login")
public class LoginSecurityProperties {

    private int accountFailThreshold = 5;

    private long accountFailTtlSeconds = 300L;

    private long accountLockSeconds = 900L;

    private int ipFailThreshold = 20;

    private long ipFailWindowSeconds = 300L;

    private long ipLockSeconds = 1800L;
}
