package com.campus.market.security;

import com.campus.market.common.exception.BusinessException;

/**
 * 当前登录用户上下文（ThreadLocal）。
 *
 * <p>写入时机：拦截器解析 Token 成功；清理时机：拦截器 afterCompletion（必须清理，避免线程池复用导致串号）。</p>
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser loginUser) {
        HOLDER.set(loginUser);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static boolean isLogin() {
        return HOLDER.get() != null && HOLDER.get().getUserId() != null;
    }

    /**
     * 强制获取当前用户ID；未登录时抛 401（强制认证路径由拦截器保证，此处为兜底）。
     */
    public static Long requireUserId() {
        LoginUser loginUser = HOLDER.get();
        if (loginUser == null || loginUser.getUserId() == null) {
            throw BusinessException.unauthorized();
        }
        return loginUser.getUserId();
    }

    /**
     * 可选认证路径使用：未登录返回 null，不抛异常。
     */
    public static Long getUserIdOrNull() {
        LoginUser loginUser = HOLDER.get();
        return loginUser == null ? null : loginUser.getUserId();
    }

    public static boolean isAdmin() {
        LoginUser loginUser = HOLDER.get();
        return loginUser != null && loginUser.isAdmin();
    }
}
