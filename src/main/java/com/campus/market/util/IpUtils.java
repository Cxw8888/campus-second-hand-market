package com.campus.market.util;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 客户端 IP 提取工具（登录防爆破 IP 维度、审计日志均依赖）。
 *
 * <h3>批次 6.0.2 安全加固 · M1</h3>
 * <p><b>修前</b>：无条件采信 {@code X-Forwarded-For} / {@code X-Real-IP} 等转发头。
 * 由于这些头是<b>请求方自己可以随便写</b>的，攻击者每次登录失败换一个伪造 IP，
 * 就能让每个伪造 IP 各有一份失败计数 → IP 维度“5 分钟 20 次失败锁 30 分钟”形同虚设，
 * 同时审计日志里的 IP 也全部失真。</p>
 *
 * <p><b>修后</b>：只在“直连对端确实是可信代理”时才采信转发头，否则一律用
 * {@link HttpServletRequest#getRemoteAddr()}。判定依据是配置项
 * {@code app.security.trusted-proxies}（见 {@code TrustedProxyProperties}）：</p>
 * <ol>
 *   <li>列表为空（默认）→ 一律用 RemoteAddr（最安全，直连部署即此形态）；</li>
 *   <li>RemoteAddr 不在列表中 → 说明这是“绕过代理直连”或来自不可信网络，用 RemoteAddr；</li>
 *   <li>RemoteAddr 在列表中 → 采信转发头（链式解析见 {@link #resolveForwardedFor}）。</li>
 * </ol>
 *
 * <h3>与需求原文的一处偏离（已在批次报告说明）</h3>
 * <p>需求原文写的是“在可信代理后 → 采信 X-Forwarded-For 的<b>第一个</b> IP”。
 * 本实现改为业界标准的<b>从右往左跳过可信代理、取第一个不可信地址</b>。
 * 原因是“取第一个”仍然可被伪造：nginx 最常见的写法
 * {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;} 会把客户端自带的
 * XFF <b>原样保留并追加</b>，于是攻击者发送 {@code X-Forwarded-For: 1.2.3.4} 后，
 * 到达后端的是 {@code "1.2.3.4, <攻击者真实IP>"} —— 取第一个正好取到攻击者控制的值，
 * 绕过依旧成立。从右往左取则取到代理亲眼看到的那个地址。
 * 当 XFF 只有一个元素（另一种常见写法 {@code $remote_addr}）时，两种规则结果完全相同，
 * 因此这次调整只影响“原本会被绕过”的那一种情况。</p>
 *
 * <h3>返回值安全性</h3>
 * <p>转发头的值会被用作 Redis Key（{@code login:fail:ip:{ip}}）与日志字段，
 * 因此本类对取值做了字符白名单 + 长度限制（见 {@link #sanitize}）：
 * 含空格/控制字符/换行或超长的值一律丢弃并退回 RemoteAddr，
 * 顺带堵住“用 CR/LF 污染日志取证”的路子。</p>
 */
public final class IpUtils {

    /** 无法取得 IP 时的占位值。 */
    public static final String UNKNOWN = "unknown";

    /** IP 字面量最大长度（IPv6 全展开 + 作用域后缀 ≈ 45~50）。 */
    private static final int MAX_IP_LENGTH = 45;

    /** IPv4 点分十进制字面量。 */
    private static final Pattern IPV4_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /** IPv6 字面量允许的字符集（含 ':' 所以不会被当作主机名，从而不会触发 DNS）。 */
    private static final Pattern IPV6_CHARS_PATTERN = Pattern.compile("^[0-9a-fA-F:.]+$");

    /** 采信顺序：XFF 优先（链式解析），其余为常见反向代理的单值头。 */
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private static final String[] FALLBACK_HEADERS = {
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
    };

    private IpUtils() {
    }

    /**
     * 取客户端 IP（<b>不采信任何转发头</b>）。
     *
     * <p>等价于“可信代理列表为空”，是最安全的默认形态；配置了可信代理的调用方
     * 请使用 {@link #getClientIp(HttpServletRequest, Collection)}。</p>
     */
    public static String getClientIp(HttpServletRequest request) {
        return getClientIp(request, List.of());
    }

    /**
     * 取客户端 IP（仅当直连对端在 {@code trustedProxies} 中时才采信转发头）。
     *
     * @param trustedProxies 可信代理列表（单个 IP 或 CIDR）；null / 空 = 谁都不信
     */
    public static String getClientIp(HttpServletRequest request, Collection<String> trustedProxies) {
        if (request == null) {
            return UNKNOWN;
        }
        String remote = sanitize(request.getRemoteAddr());
        if (remote == null) {
            remote = UNKNOWN;
        }
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            // ① 未配置可信代理：一律用 RemoteAddr，转发头一概不看
            return remote;
        }
        if (!matchesAny(remote, trustedProxies)) {
            // ② 直连对端不是可信代理：请求可能绕过了代理，转发头不可信
            return remote;
        }
        // ③ 可信代理之后：解析转发头
        String forwarded = resolveForwardedFor(request.getHeader(X_FORWARDED_FOR), trustedProxies);
        if (forwarded != null) {
            return forwarded;
        }
        for (String header : FALLBACK_HEADERS) {
            String value = sanitize(firstSegment(request.getHeader(header)));
            if (value != null) {
                return value;
            }
        }
        return remote;
    }

    /**
     * 链式解析 {@code X-Forwarded-For}：<b>从右往左跳过可信代理，返回遇到的第一个不可信地址</b>。
     *
     * <p>XFF 的语义是“每经过一跳就追加一个地址”，因此最右边是最后一个代理看到的地址，
     * 最左边才是客户端自己声称的地址 —— 而“声称”正是可伪造的部分。从右往左走，
     * 遇到可信代理就继续往左，直到遇到第一个不可信地址，那才是我们能信的最左端。</p>
     *
     * <p>边界：全部条目都是可信代理时，退化为“取最左侧”（此时列表中没有任何不可信地址，
     * 取最左是信息量最大的选择）；无法解析出任何有效条目时返回 {@code null}，由调用方退回 RemoteAddr。</p>
     *
     * @param headerValue     {@code X-Forwarded-For} 原始值，可为 null
     * @param trustedProxies  非空的可信代理列表
     */
    static String resolveForwardedFor(String headerValue, Collection<String> trustedProxies) {
        List<String> chain = splitChain(headerValue);
        if (chain.isEmpty()) {
            return null;
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (!matchesAny(chain.get(i), trustedProxies)) {
                return chain.get(i);
            }
        }
        return chain.get(0);
    }

    private static List<String> splitChain(String headerValue) {
        List<String> chain = new ArrayList<>();
        if (headerValue == null || headerValue.isBlank()) {
            return chain;
        }
        for (String part : headerValue.split(",")) {
            String value = sanitize(part);
            if (value != null) {
                chain.add(value);
            }
        }
        return chain;
    }

    private static String firstSegment(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        int idx = headerValue.indexOf(',');
        return idx >= 0 ? headerValue.substring(0, idx) : headerValue;
    }

    /**
     * 取值清洗：去空白，剔除 {@code unknown}、控制字符/空格、超长值；不合格返回 null。
     */
    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || UNKNOWN.equalsIgnoreCase(trimmed)) {
            return null;
        }
        if (trimmed.length() > MAX_IP_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            // 只允许可见且非空格的字符：既挡住 CR/LF 日志注入，也挡住带空格的伪造值
            if (c <= 0x20 || c == 0x7F) {
                return null;
            }
        }
        return trimmed;
    }

    // ------------------------------------------------------------------ 可信代理匹配

    /**
     * IP 是否命中可信代理列表中的任意一条规则。
     *
     * <p>规则支持：单个 IP（{@code 127.0.0.1}、{@code ::1}）与 CIDR 段（{@code 10.0.0.0/8}、{@code ::1/128}）。</p>
     */
    public static boolean matchesAny(String ip, Collection<String> rules) {
        if (ip == null || rules == null || rules.isEmpty()) {
            return false;
        }
        byte[] address = parseLiteral(ip);
        if (address == null) {
            return false;
        }
        for (String rule : rules) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            if (matchesRule(address, rule.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRule(byte[] address, String rule) {
        int slash = rule.indexOf('/');
        String literal = slash >= 0 ? rule.substring(0, slash) : rule;
        byte[] network = parseLiteral(literal);
        if (network == null || network.length != address.length) {
            return false;
        }
        if (slash < 0) {
            // 单 IP：等值比较
            return Arrays.equals(address, network);
        }
        int prefixBits;
        try {
            prefixBits = Integer.parseInt(rule.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return false;
        }
        int maxBits = address.length * 8;
        if (prefixBits < 0 || prefixBits > maxBits) {
            return false;
        }
        return matchesPrefix(address, network, prefixBits);
    }

    private static boolean matchesPrefix(byte[] address, byte[] network, int prefixBits) {
        int fullBytes = prefixBits / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixBits % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /**
     * 解析 IP 字面量为字节数组；非法（或疑似主机名）返回 null。
     *
     * <p><b>为什么不用 {@code InetAddress.getByName} 直接解析一切</b>：它对非字面量会走 DNS。
     * 这里先用字符集与形态把范围锁死（IPv4 走自写解析；IPv6 必须含 ':' 且只含十六进制字符，
     * 这种串不可能是主机名），因此既不会触发网络解析，也不会因为配置里写错一个域名而卡住请求线程。</p>
     */
    static byte[] parseLiteral(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_IP_LENGTH) {
            return null;
        }
        String candidate = value.trim();
        if (IPV4_PATTERN.matcher(candidate).matches()) {
            String[] parts = candidate.split("\\.");
            byte[] result = new byte[4];
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(parts[i]);
                if (octet > 255) {
                    return null;
                }
                result[i] = (byte) octet;
            }
            return result;
        }
        if (candidate.indexOf(':') >= 0 && IPV6_CHARS_PATTERN.matcher(candidate).matches()) {
            try {
                byte[] address = InetAddress.getByName(candidate).getAddress();
                // getByName 对 "::ffff:1.2.3.4" 之类的输入可能返回 4 字节，统一要求 16 字节
                return address.length == 16 ? address : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
