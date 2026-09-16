package com.campus.market.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码安全配置。
 *
 * <p>仅引入 spring-security-crypto，<b>严禁引入 spring-boot-starter-security</b>；
 * 严禁 MD5/SHA-1；无 salt 字段，直接使用 encode() / matches()。</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
