package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 可信反向代理配置（批次 6.0.2 安全加固 · M1）。
 *
 * <p><b>背景</b>：自审报告 M1 —— {@code IpUtils} 无条件采信 {@code X-Forwarded-For}，
 * 于是"换一个 header 就绕过 IP 维度 30 分钟锁定"：攻击者每次请求都换一个伪造的 XFF 值，
 * 每个伪造 IP 各有一份失败计数，IP 维度限流形同虚设，还顺带污染审计日志里的 IP。</p>
 *
 * <h3>修法</h3>
 * <p>只有在"直连对端确实是可信代理"时，才采信转发头；否则一律用
 * {@code request.getRemoteAddr()}。<b>列表默认为空 = 谁都不信 = 一律用 RemoteAddr</b>
 * （最安全的默认值，本地开发与直连部署不需要任何配置）。</p>
 *
 * <pre>
 * app:
 *   security:
 *     trusted-proxies:      # 默认为空列表；只有部署在 nginx / SLB 之后才需要配
 *       - 10.0.0.0/8
 *       - 192.168.0.0/16
 * </pre>
 *
 * <p>支持两种写法（详见 {@code IpUtils#matchesAny}）：单个 IP（{@code 127.0.0.1}）与
 * IPv4/IPv6 的 CIDR 段（{@code 10.0.0.0/8}、{@code ::1/128}）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security")
public class TrustedProxyProperties {

    /**
     * 可信代理地址列表（单个 IP 或 CIDR 段）。
     *
     * <p>字段默认值与 yml 缺失时都是<b>空列表</b>，语义为"不信任任何转发头"。</p>
     */
    private List<String> trustedProxies = new ArrayList<>();
}
