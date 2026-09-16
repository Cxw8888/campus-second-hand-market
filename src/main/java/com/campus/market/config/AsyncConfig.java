package com.campus.market.config;

import com.campus.market.config.properties.TaskProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 自定义异步线程池（站内信发送等）。
 *
 * <p>参数依据：核心 8（4 核 CPU × 2 IO 密集型经验值）、最大 16（突发流量预留）、
 * 队列 200（缓冲上限）、拒绝策略 CallerRunsPolicy。严禁使用 Executors 快捷方法。</p>
 */
@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class AsyncConfig {

    public static final String NOTIFICATION_EXECUTOR = "notificationExecutor";

    private final TaskProperties taskProperties;

    @Bean(name = NOTIFICATION_EXECUTOR)
    public Executor notificationExecutor() {
        TaskProperties.NotificationPool pool = taskProperties.getNotification();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCorePoolSize());
        executor.setMaxPoolSize(pool.getMaxPoolSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setKeepAliveSeconds(pool.getKeepAliveSeconds());
        executor.setThreadNamePrefix("notify-");
        // CallerRunsPolicy：队列满时由调用线程执行，保证不丢任务且不无限堆积
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("站内信线程池初始化完成: core={}, max={}, queue={}, reject=CallerRunsPolicy",
                pool.getCorePoolSize(), pool.getMaxPoolSize(), pool.getQueueCapacity());
        return executor;
    }
}
