package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 雪花算法配置：order_no 必须配置 workerId，避免多实例生成重复订单号。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.snowflake")
public class SnowflakeProperties {

    private long workerId = 1L;

    private long datacenterId = 1L;
}
