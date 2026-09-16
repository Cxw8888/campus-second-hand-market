package com.campus.market.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 提取工具（登录防爆破 IP 维度、审计日志均依赖）。
 */
public final class IpUtils {

    private static final String UNKNOWN = "unknown";

    private static final String[] HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
    };

    private IpUtils() {
    }

    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        for (String header : HEADERS) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isBlank() && !UNKNOWN.equalsIgnoreCase(ip)) {
                // X-Forwarded-For 可能是 "client, proxy1, proxy2"，取第一个
                int idx = ip.indexOf(',');
                return idx > 0 ? ip.substring(0, idx).trim() : ip.trim();
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? UNKNOWN : remote;
    }
}
