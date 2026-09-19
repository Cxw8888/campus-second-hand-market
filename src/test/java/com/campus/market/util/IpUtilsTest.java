package com.campus.market.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 6.0.2 安全加固 · M1 单测（第二组）：客户端 IP 提取。
 *
 * <p>{@code IpUtils} 是"IP 维度防爆破"与"审计日志 IP"两处共用的取数口，
 * 修前的行为是<b>无条件</b>采信 {@code X-Forwarded-For} —— 而转发头是请求方随手就能写的。</p>
 *
 * <p>这里断言三件事：① 无可信代理时一律用 RemoteAddr；② 可信代理之后才采信转发头，
 * 且链式取值不会取到可伪造的最左段；③ 取值做了字符/长度清洗（它会被直接拼进 Redis Key 与日志）。</p>
 */
class IpUtilsTest {

    private static final String REAL_REMOTE = "203.0.113.9";

    private static MockHttpServletRequest request(String remoteAddr, String... xffValues) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(remoteAddr);
        for (String value : xffValues) {
            request.addHeader("X-Forwarded-For", value);
        }
        return request;
    }

    @Test
    @DisplayName("① 可信代理列表为空（默认）→ 一律用 RemoteAddr，转发头完全不被采信")
    void emptyTrustedProxiesShouldIgnoreForwardedHeaders() {
        MockHttpServletRequest request = request(REAL_REMOTE, "1.2.3.4");
        request.addHeader("X-Real-IP", "5.6.7.8");

        assertThat(IpUtils.getClientIp(request, List.of())).isEqualTo(REAL_REMOTE);
        assertThat(IpUtils.getClientIp(request, null)).isEqualTo(REAL_REMOTE);
        // 无参重载 = 不信任任何转发头（最安全的默认形态）
        assertThat(IpUtils.getClientIp(request)).isEqualTo(REAL_REMOTE);
    }

    @Test
    @DisplayName("② 直连地址不在可信列表 → 仍用 RemoteAddr（挡住了\"绕过代理直连\"的伪造）")
    void untrustedPeerShouldIgnoreForwardedHeaders() {
        MockHttpServletRequest request = request(REAL_REMOTE, "1.2.3.4");

        assertThat(IpUtils.getClientIp(request, List.of("10.0.0.0/8"))).isEqualTo(REAL_REMOTE);
    }

    @Test
    @DisplayName("③ 可信代理之后：链式 XFF 取最右侧不可信地址（最左段是攻击者可伪造的）")
    void trustedProxyShouldResolveChainFromTheRight() {
        // 攻击者自己发了 XFF: 1.2.3.4，nginx 用 $proxy_add_x_forwarded_for 追加了真实来源
        MockHttpServletRequest chained = request("127.0.0.1", "1.2.3.4, 198.51.100.7");
        assertThat(IpUtils.getClientIp(chained, List.of("127.0.0.1"))).isEqualTo("198.51.100.7");

        // 单段 XFF（nginx 用 $remote_addr 的常见写法）→ 与"取第一个"结果一致
        MockHttpServletRequest single = request("127.0.0.1", "198.51.100.7");
        assertThat(IpUtils.getClientIp(single, List.of("127.0.0.1"))).isEqualTo("198.51.100.7");

        // 多级代理：右侧两跳都是可信代理，继续往左直到第一个不可信地址
        MockHttpServletRequest multiHop = request("127.0.0.1", "198.51.100.7, 10.0.0.5, 10.0.0.6");
        assertThat(IpUtils.getClientIp(multiHop, List.of("127.0.0.1", "10.0.0.0/8")))
                .isEqualTo("198.51.100.7");

        // 全部条目都是可信代理 → 退化为取最左侧（信息量最大的选择）
        MockHttpServletRequest allTrusted = request("127.0.0.1", "10.0.0.5, 10.0.0.6");
        assertThat(IpUtils.getClientIp(allTrusted, List.of("127.0.0.1", "10.0.0.0/8")))
                .isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("④ 转发头取值清洗：含空格/换行/超长/unknown 一律丢弃并退回 RemoteAddr")
    void forwardedHeaderValuesMustBeSanitized() {
        // CRLF 注入（试图污染日志与 Redis Key）
        assertThat(IpUtils.getClientIp(request("127.0.0.1", "1.2.3.4\r\nX-Injected: 1"), List.of("127.0.0.1")))
                .isEqualTo("127.0.0.1");
        // 带空格的值（"1.2.3.4 5.6.7.8" 这种脏数据）
        assertThat(IpUtils.getClientIp(request("127.0.0.1", "1.2.3.4 5.6.7.8"), List.of("127.0.0.1")))
                .isEqualTo("127.0.0.1");
        // unknown 占位
        assertThat(IpUtils.getClientIp(request("127.0.0.1", "unknown"), List.of("127.0.0.1")))
                .isEqualTo("127.0.0.1");
        // 超长值
        assertThat(IpUtils.getClientIp(request("127.0.0.1", "9".repeat(200)), List.of("127.0.0.1")))
                .isEqualTo("127.0.0.1");
        // XFF 全是脏值时，退到 X-Real-IP（若它干净且可信代理命中）
        MockHttpServletRequest fallback = request("127.0.0.1", "unknown");
        fallback.addHeader("X-Real-IP", "198.51.100.7");
        assertThat(IpUtils.getClientIp(fallback, List.of("127.0.0.1"))).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("⑤ 可信代理规则匹配：单 IP 等值 + IPv4/IPv6 CIDR（含 0 位与全位边界）")
    void trustedProxyRuleMatching() {
        assertThat(IpUtils.matchesAny("127.0.0.1", List.of("127.0.0.1"))).isTrue();
        assertThat(IpUtils.matchesAny("127.0.0.2", List.of("127.0.0.1"))).isFalse();

        // IPv4 CIDR
        assertThat(IpUtils.matchesAny("10.1.2.3", List.of("10.0.0.0/8"))).isTrue();
        assertThat(IpUtils.matchesAny("11.1.2.3", List.of("10.0.0.0/8"))).isFalse();
        assertThat(IpUtils.matchesAny("172.17.0.1", List.of("172.17.0.0/16"))).isTrue();
        // 非字节对齐的前缀长度（/12 落在第二个字节的高 4 位）
        assertThat(IpUtils.matchesAny("172.31.0.1", List.of("172.16.0.0/12"))).isTrue();
        assertThat(IpUtils.matchesAny("172.32.0.1", List.of("172.16.0.0/12"))).isFalse();
        // 边界：/0 命中一切，/32 只命中自身
        assertThat(IpUtils.matchesAny("8.8.8.8", List.of("0.0.0.0/0"))).isTrue();
        assertThat(IpUtils.matchesAny("8.8.8.8", List.of("8.8.8.8/32"))).isTrue();
        assertThat(IpUtils.matchesAny("8.8.8.9", List.of("8.8.8.8/32"))).isFalse();

        // IPv6 单地址与 CIDR
        assertThat(IpUtils.matchesAny("::1", List.of("::1"))).isTrue();
        assertThat(IpUtils.matchesAny("2001:db8::5", List.of("2001:db8::/32"))).isTrue();
        assertThat(IpUtils.matchesAny("2001:dc8::5", List.of("2001:db8::/32"))).isFalse();
        // 地址族不同不得误命中
        assertThat(IpUtils.matchesAny("10.0.0.1", List.of("::/0"))).isFalse();

        // 非法规则 / 非法地址不得抛异常（配置写错不能让每个请求都 500）
        assertThat(IpUtils.matchesAny("10.0.0.1", List.of("not-an-ip", "10.0.0.0/999", ""))).isFalse();
        assertThat(IpUtils.matchesAny("not-an-ip", List.of("10.0.0.0/8"))).isFalse();
        // 疑似主机名不能触发 DNS 解析（避免请求线程被网络卡住）：这里只要求"不命中且不抛"
        assertThat(IpUtils.matchesAny("localhost", List.of("localhost"))).isFalse();
    }

    @Test
    @DisplayName("⑥ 空请求 / 取不到 RemoteAddr → 返回 unknown 而不是 null")
    void missingRequestShouldReturnUnknown() {
        assertThat(IpUtils.getClientIp(null, List.of())).isEqualTo(IpUtils.UNKNOWN);
        MockHttpServletRequest noRemote = new MockHttpServletRequest();
        noRemote.setRemoteAddr("");
        assertThat(IpUtils.getClientIp(noRemote, List.of())).isEqualTo(IpUtils.UNKNOWN);
    }
}
