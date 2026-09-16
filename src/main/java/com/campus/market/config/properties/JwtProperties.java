package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 相关配置（secret 必须由环境变量注入，严禁硬编码）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** 签名密钥，长度 >= 32 字节。 */
    private String secret;

    /** Token 有效期（小时），默认 2 小时。 */
    private int expireHours = 2;

    /** 签发者。 */
    private String issuer = "campus-market";
}
