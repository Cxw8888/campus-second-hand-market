package com.campus.market.util;

import java.util.regex.Pattern;

/**
 * 日志安全清洗（批次 6.0.6 · Minor 5）。
 *
 * <h3>为什么要单独一个工具</h3>
 * <p>本项目所有日志都是"单行一条"的格式（{@code pattern.console} 里没有 {@code %n}），
 * 但被记入日志的<b>外部输入</b>可能自带换行：搜索关键字（{@code ?keyword=...}）、
 * {@code X-Request-Id} 请求头。攻击者只要传入 {@code "a%0A%0A2026-01-01 00:00:00.000 [main] ERROR ..."}
 * 就能在日志里<b>伪造出一条不存在的日志行</b>——取证时无法区分哪行是系统写的、
 * 哪行是攻击者塞的（自审报告 Minor 5）。</p>
 *
 * <h3>清洗规则</h3>
 * <ol>
 *   <li>{@code null} → {@code "-"}（日志里显式可读，不留空白）；</li>
 *   <li>所有控制字符（含 {@code \r \n \t}、{@code 0x00-0x1F}、{@code 0x7F}）→ {@code '_'}
 *       —— 用一个可见字符替换而不是删除，保留"这里原本有控制字符"的证据；</li>
 *   <li>超长截断（默认 200 字符，{@code X-Request-Id} 用 64）：日志不能被单个请求撑爆，
 *       也避免让攻击者用超长输入拉高日志量与 IO。</li>
 * </ol>
 *
 * <p><b>只用于日志与响应头回显</b>，不得用于 SQL 参数或业务判断 ——
 * 清洗是"为了看得清"，不是"为了安全过滤输入"（输入校验各有各的守卫）。</p>
 */
public final class LogSanitizer {

    /** 默认最大长度：够放下一个搜索关键字或一段错误摘要。 */
    public static final int DEFAULT_MAX_LENGTH = 200;

    /** 请求头 ID 的最大长度。 */
    public static final int REQUEST_ID_MAX_LENGTH = 64;

    /** 控制字符：CR / LF / TAB / NUL 及其余 0x00-0x1F、0x7F。 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");

    private LogSanitizer() {
    }

    /**
     * 清洗为可安全写入单行日志的字符串（默认长度上限）。
     */
    public static String sanitize(String raw) {
        return sanitize(raw, DEFAULT_MAX_LENGTH);
    }

    /**
     * 清洗为可安全写入单行日志的字符串。
     *
     * @param raw       原始值（可为 null）
     * @param maxLength 最大保留长度（<=0 时使用默认值）
     * @return 非 null、单行、长度受限的字符串
     */
    public static String sanitize(String raw, int maxLength) {
        if (raw == null) {
            return "-";
        }
        int limit = maxLength > 0 ? maxLength : DEFAULT_MAX_LENGTH;
        // 先按"控制字符"替换，再截断：反过来会把截断点后的换行留着（先截断再替换也安全，
        // 但那时长度是按原始字符串算的，替换后可能变长——1 个控制字符 → 1 个 '_' 等长，
        // 所以顺序其实无影响；按"先清洗后截断"写，语义最好读）
        String cleaned = CONTROL_CHARS.matcher(raw).replaceAll("_");
        if (cleaned.length() > limit) {
            cleaned = cleaned.substring(0, limit) + "...";
        }
        return cleaned;
    }

    /**
     * 判断清洗后是否还有内容（用于"header 为空就生成新 ID"这类判定）。
     *
     * <p>注意：{@code sanitize()} 对 null 返回 {@code "-"}，因此判定必须基于原始值，
     * 不能用它替代 {@code StringUtils.hasText}。</p>
     */
    public static boolean hasText(String raw) {
        return raw != null && !raw.isBlank();
    }
}
