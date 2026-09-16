package com.campus.market.util;

import com.campus.market.config.properties.SnowflakeProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 雪花算法订单号生成器。
 *
 * <p>order_no 必须配置 workerId（app.snowflake.worker-id，多实例必须唯一），
 * 结构：1 位符号位 + 41 位时间戳 + 5 位数据中心 + 5 位机器 + 12 位序列号。</p>
 */
@Component
@RequiredArgsConstructor
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01 00:00:00 GMT+8
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final SnowflakeProperties properties;

    private long workerId;
    private long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    @PostConstruct
    public void init() {
        long worker = properties.getWorkerId();
        long datacenter = properties.getDatacenterId();
        if (worker < 0 || worker > MAX_WORKER_ID) {
            throw new IllegalArgumentException("雪花算法 workerId 必须在 0-" + MAX_WORKER_ID + " 之间，当前: " + worker);
        }
        if (datacenter < 0 || datacenter > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("雪花算法 datacenterId 必须在 0-" + MAX_DATACENTER_ID + " 之间，当前: " + datacenter);
        }
        this.workerId = worker;
        this.datacenterId = datacenter;
    }

    /**
     * 生成下一个全局唯一 ID（线程安全）。
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            // 时钟回拨：等待至上次时间戳，避免生成重复 ID
            timestamp = waitUntil(lastTimestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /** 生成订单号（字符串形式，直接落库 tb_order.order_no）。 */
    public String nextOrderNo() {
        return String.valueOf(nextId());
    }

    private long waitUntil(long target) {
        long timestamp = System.currentTimeMillis();
        while (timestamp < target) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
