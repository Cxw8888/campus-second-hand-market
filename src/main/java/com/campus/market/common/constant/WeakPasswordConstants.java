package com.campus.market.common.constant;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 内置弱密码字典（毕设简化版，恰好 100 条常见弱密码，硬编码，不引入外部字典文件）。
 *
 * <p>设计说明（PROJECT_CONTEXT 3.1）：密码强度校验要求"不在内置 100 条常见弱密码列表中"。
 * 早期方案计划引入 top 10000 外部字典文件，为简化打包体积与实现复杂度，
 * 毕设版本裁剪为 100 条高热度弱密码硬编码列表。</p>
 *
 * <p>匹配规则：忽略大小写，并同时命中去除首尾空白后的形式。</p>
 */
public final class WeakPasswordConstants {

    private WeakPasswordConstants() {
    }

    /**
     * 常见弱密码列表（恰好 100 条，均由小写字母、数字、特殊字符组成，比较时统一转小写）。
     */
    public static final List<String> WEAK_PASSWORDS = List.of(
            // ---------- 纯数字序列 / 重复数字（1-30） ----------
            "123456", "12345678", "123456789", "1234567890", "1234567",
            "12345", "1234", "123123", "111111", "000000",
            "666666", "888888", "999999", "222222", "333333",
            "555555", "777777", "112233", "121212", "131313",
            "11223344", "123321", "654321", "789456", "987654321",
            "147258369", "5201314", "520520", "1314520", "7758521",
            // ---------- 键盘序 / 键盘邻键（31-50） ----------
            "qwerty", "qwertyuiop", "qwerty123", "qwertyui", "asdfgh",
            "asdfghjkl", "zxcvbn", "zxcvbnm", "1qaz2wsx", "1q2w3e4r",
            "1qazxsw2", "qazwsx", "qazwsxedc", "zaq12wsx", "!qaz2wsx",
            "q1w2e3r4", "a1s2d3f4", "123qwe", "qwe123", "asd123",
            // ---------- 英文单词 / 拼音（51-70） ----------
            "password", "password1", "passw0rd", "p@ssw0rd", "iloveyou",
            "admin", "admin123", "admin888", "administrator", "root123",
            "letmein", "welcome", "monkey", "dragon", "sunshine",
            "princess", "football", "superman", "woaini", "woaini1314",
            // ---------- 字母 + 数字组合（71-90） ----------
            "abc123", "abc123456", "a123456", "a1234567", "a123456789",
            "abcd1234", "abcdef", "abcdefg", "abcd123456", "a1b2c3",
            "aa123456", "ab123456", "as123456", "wo123456", "qq123456",
            "qq123456789", "wang123456", "li123456", "zhang123", "chen123456",
            // ---------- 站点 / 场景 / 业务弱口令（91-100） ----------
            "123456a", "123456abc", "123456789a", "123456qq", "1234567890qwerty",
            "campus123", "student123", "school123", "test123", "admin@123"
    );

    /** 列表去重后的集合，用于 {@link #isWeak(String)} 的 O(1) 匹配。 */
    private static final Set<String> WEAK_PASSWORD_SET = new HashSet<>(WEAK_PASSWORDS);

    static {
        // 自我保护：字典必须恰好 100 条且无重复，避免后续维护误改破坏"100 条"约定
        if (WEAK_PASSWORDS.size() != 100) {
            throw new IllegalStateException("弱密码字典必须恰好 100 条，当前: " + WEAK_PASSWORDS.size());
        }
        if (WEAK_PASSWORD_SET.size() != WEAK_PASSWORDS.size()) {
            throw new IllegalStateException("弱密码字典存在重复项，去重后: " + WEAK_PASSWORD_SET.size());
        }
    }

    /**
     * 是否为常见弱密码。
     *
     * @param rawPassword 明文密码（仅用于比对，<b>严禁写入日志</b>）
     * @return true 表示命中弱密码字典
     */
    public static boolean isWeak(String rawPassword) {
        if (rawPassword == null) {
            return false;
        }
        String normalized = rawPassword.trim().toLowerCase(Locale.ROOT);
        return WEAK_PASSWORD_SET.contains(normalized);
    }

    /**
     * 字典条数（供测试与文档引用）。
     */
    public static int size() {
        return WEAK_PASSWORDS.size();
    }

    /**
     * 只读视图（防御性复制，避免调用方修改内部集合）。
     */
    public static List<String> list() {
        return List.copyOf(WEAK_PASSWORDS);
    }
}
