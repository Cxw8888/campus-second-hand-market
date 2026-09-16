package com.campus.market.common.constant;

/**
 * Redis Key 与缓存相关常量（统一收口，严禁散落字符串字面量）。
 *
 * <p>Key 命名规范见 PROJECT_CONTEXT 3.1 / 3.3 / 3.4。</p>
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    // ---------------- 认证与安全 ----------------

    /** 用户级 Token 版本：封禁 / 解封 / 改密 / 换绑邮箱 / 找回密码时 +1，使该用户全部 Token 立即失效。 */
    public static final String USER_TOKEN_VERSION_PREFIX = "user:token:version:";

    /** 单 Token 注销黑名单，TTL 与 Token 剩余时间一致。 */
    public static final String JWT_BLACKLIST_PREFIX = "jwt:blacklist:";

    /** 拦截器兜底：用户封禁状态缓存。 */
    public static final String USER_STATUS_PREFIX = "user:status:";

    /** 登录失败计数（账号维度），TTL 5 分钟。 */
    public static final String LOGIN_FAIL_PREFIX = "login:fail:";

    /** 登录锁定（账号维度），TTL 15 分钟。 */
    public static final String LOGIN_LOCK_PREFIX = "login:lock:";

    /** 登录失败计数（IP 维度）。 */
    public static final String LOGIN_FAIL_IP_PREFIX = "login:fail:ip:";

    /** 登录锁定（IP 维度），TTL 30 分钟。 */
    public static final String LOGIN_LOCK_IP_PREFIX = "login:lock:ip:";

    // ---------------- 邮箱验证码 ----------------

    /** 邮箱验证码，TTL 5 分钟。 */
    public static final String EMAIL_CODE_PREFIX = "email:code:";

    /** 邮箱验证码发送限流，TTL 60 秒，命中返回 code=106。 */
    public static final String EMAIL_LIMIT_PREFIX = "email:limit:";

    /** 邮箱验证码失败计数（1 小时窗口累计；重新获取验证码时<b>严禁</b>删除）。 */
    public static final String EMAIL_FAIL_PREFIX = "email:fail:";

    /** 邮箱验证码服务锁定（失败达阈值后固定锁定 30 分钟，返回 code=107）。 */
    public static final String EMAIL_LOCK_PREFIX = "email:lock:";

    // ---------------- 业务 ----------------

    /** 下单防重 Token。 */
    public static final String ORDER_TOKEN_PREFIX = "order:token:";

    /** 商品详情缓存。 */
    public static final String PRODUCT_DETAIL_PREFIX = "product:detail:";

    /** 商品分类列表缓存（读多写少，写操作后失效）。 */
    public static final String PRODUCT_CATEGORY_LIST = "product:category:list";

    /** AI 搜索结果缓存（60 秒）。 */
    public static final String AI_SEARCH_PREFIX = "ai:search:";

    /** 支付回调流水去重。 */
    public static final String PAY_CALLBACK_PREFIX = "pay:callback:";

    // ---------------- Key 构建方法 ----------------

    /** user:token:version:{userId} */
    public static String userTokenVersion(Long userId) {
        return USER_TOKEN_VERSION_PREFIX + userId;
    }

    /** jwt:blacklist:{token} */
    public static String jwtBlacklist(String token) {
        return JWT_BLACKLIST_PREFIX + token;
    }

    /** user:status:{userId} */
    public static String userStatus(Long userId) {
        return USER_STATUS_PREFIX + userId;
    }

    /** login:fail:{username} */
    public static String loginFail(String username) {
        return LOGIN_FAIL_PREFIX + username;
    }

    /** login:lock:{username} */
    public static String loginLock(String username) {
        return LOGIN_LOCK_PREFIX + username;
    }

    /** login:fail:ip:{ip} */
    public static String loginFailIp(String ip) {
        return LOGIN_FAIL_IP_PREFIX + ip;
    }

    /** login:lock:ip:{ip} */
    public static String loginLockIp(String ip) {
        return LOGIN_LOCK_IP_PREFIX + ip;
    }

    /** email:code:{email} */
    public static String emailCode(String email) {
        return EMAIL_CODE_PREFIX + email;
    }

    /** email:limit:{email} */
    public static String emailLimit(String email) {
        return EMAIL_LIMIT_PREFIX + email;
    }

    /** email:fail:{email} */
    public static String emailFail(String email) {
        return EMAIL_FAIL_PREFIX + email;
    }

    /** email:lock:{email}（失败达阈值后固定锁定 30 分钟） */
    public static String emailLock(String email) {
        return EMAIL_LOCK_PREFIX + email;
    }

    /** order:token:{userId}:{uuid} */
    public static String orderToken(Long userId, String uuid) {
        return ORDER_TOKEN_PREFIX + userId + ":" + uuid;
    }

    /** product:detail:{productId} */
    public static String productDetail(Long productId) {
        return PRODUCT_DETAIL_PREFIX + productId;
    }
}
