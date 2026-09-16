package com.campus.market.util;

import com.campus.market.common.constant.WeakPasswordConstants;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;

import java.util.regex.Pattern;

/**
 * 密码强度校验器（毕设简化版，PROJECT_CONTEXT 3.1）。
 *
 * <p>规则：</p>
 * <ol>
 *   <li>长度 8-20 位；</li>
 *   <li>必须同时包含字母、数字、至少 1 个特殊字符；</li>
 *   <li>不在内置 100 条常见弱密码硬编码列表中（{@link WeakPasswordConstants}）。</li>
 * </ol>
 *
 * <p>不合规一律抛出 {@code BusinessException(ErrorCode.PARAM_ERROR, 具体原因)}，即 code=100 + 明确原因。
 * 本类为纯静态工具，<b>严禁</b>在任何日志中输出明文密码。</p>
 */
public final class PasswordValidator {

    /** 最小长度。 */
    public static final int MIN_LENGTH = 8;

    /** 最大长度。 */
    public static final int MAX_LENGTH = 20;

    /** 字母。 */
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");

    /** 数字。 */
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    /** 特殊字符（ASCII 可见符号中除字母数字外的全部字符）。 */
    private static final Pattern SPECIAL = Pattern.compile("[^A-Za-z0-9]");

    private PasswordValidator() {
    }

    /**
     * 校验密码强度，不合规直接抛出 code=100 业务异常。
     *
     * @param rawPassword 明文密码（仅用于校验，<b>严禁写入日志</b>）
     */
    public static void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "密码不能为空");
        }
        if (rawPassword.length() < MIN_LENGTH || rawPassword.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "密码长度必须为8-20位");
        }
        if (!LETTER.matcher(rawPassword).find()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "密码必须包含字母");
        }
        if (!DIGIT.matcher(rawPassword).find()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "密码必须包含数字");
        }
        if (!SPECIAL.matcher(rawPassword).find()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "密码必须包含特殊字符");
        }
        if (WeakPasswordConstants.isWeak(rawPassword)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "密码过于简单，请使用更复杂的密码");
        }
    }

    /**
     * 非异常式校验（供测试或条件判断使用）。
     *
     * @return true 表示密码符合强度要求
     */
    public static boolean isValid(String rawPassword) {
        try {
            validate(rawPassword);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }
}
