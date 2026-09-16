package com.campus.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 校园二手交易平台启动类。
 *
 * <p>已启用能力：</p>
 * <ul>
 *   <li>{@code @EnableAsync} —— 见 {@code AsyncConfig}（站内信异步发送，自定义线程池）；</li>
 *   <li>{@code @EnableScheduling} + ShedLock —— 见 {@code SchedulerConfig}（超时取消、自动确认收货、退款被拒恢复）；</li>
 *   <li>MyBatis-Plus 分页插件 + 逻辑删除 + MetaObjectHandler 自动填充 —— 见 {@code MybatisPlusConfig}；</li>
 *   <li>拦截器三类路径语义 + 用户级 Token version —— 见 {@code AuthInterceptor} / {@code WebMvcConfig}。</li>
 * </ul>
 */
@Slf4j
@SpringBootApplication
public class CampusMarketApplication {

    public static void main(String[] args) throws UnknownHostException {
        Environment env = SpringApplication.run(CampusMarketApplication.class, args).getEnvironment();
        String port = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        log.info("""

                        ----------------------------------------------------------
                        校园二手交易平台启动成功
                        本地地址: http://127.0.0.1:{}{}
                        外部地址: http://{}:{}{}
                        接口文档: http://127.0.0.1:{}{}/doc.html
                        健康检查: http://127.0.0.1:{}{}/actuator/health
                        当前环境: {}
                        ----------------------------------------------------------""",
                port, contextPath,
                InetAddress.getLocalHost().getHostAddress(), port, contextPath,
                port, contextPath,
                port, contextPath,
                String.join(",", env.getActiveProfiles()));
    }
}
