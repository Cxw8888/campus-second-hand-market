package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CORS 配置：allowedOrigins 走环境变量，严禁生产环境使用 *。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** 允许的前端域名列表（环境变量 CORS_ALLOWED_ORIGINS，逗号分隔）。 */
    private List<String> allowedOrigins = List.of("http://localhost:5173");

    private boolean allowCredentials = true;

    private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");

    private List<String> allowedHeaders = List.of("Authorization", "Content-Type");

    private long maxAge = 3600L;
}
