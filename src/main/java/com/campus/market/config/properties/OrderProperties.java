package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 订单相关配置：防重 Token 有效期、雪花算法 workerId（order_no 必须配置 workerId）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.order")
public class OrderProperties {

    /** 下单防重 Token 有效期（秒），对应 Redis order:token:{userId}:{uuid}。 */
    private long tokenTtlSeconds = 300L;
}
