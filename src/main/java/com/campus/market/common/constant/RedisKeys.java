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

    /**
     * 拦截器兜底：用户封禁状态缓存。
     *
     * <p><b>批次 6.0.4 · M4</b>：该键必须带 TTL（1 天，见 {@code TokenVersionServiceImpl}），
     * 且只在封禁 / 解封事务<b>提交后</b>写入；拦截器读到 {@code "1"} 时还会再查一次库并以库为准
     * （缓存说封禁、库说正常 → 清除残留并自愈）。</p>
     */
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

    /**
     * 库存回补幂等凭证（批次 6.0.3 · B1）。
     *
     * <p>为什么需要它：库存回补是"加法"，加两次就是库存失真（B1 的 5→4 与封禁双重回补，
     * 库存 1 的商品能被刷成 2、反复封禁解冻可以无限刷）。订单状态机的 SQL 前置条件只能保证
     * "同一条流转不能发生两次"，挡不住"两条不同路径先后对同一订单回补"，
     * 因此必须有一份<b>订单维度</b>的回补凭证。</p>
     *
     * <p>TTL 30 天：覆盖订单的全部生命周期（超时取消 15 分钟、自动确认 7 天、退款申诉 3 天），
     * 过期后订单早已是终态，不会再触发回补。</p>
     */
    public static final String ORDER_RESTORED_PREFIX = "order:restored:";

    /**
     * 管理端统计缓存前缀（批次 5.5.1）。
     *
     * <p><b>刻意与 {@code search:} 完全分开</b>：搜索缓存里存的是分页商品列表（含 user/order 维度），
     * 统计缓存里存的是聚合数字，两者的失效节奏、排查手段、甚至"要不要清"都不同
     * （5.4.5 实测：多实例共享 Redis 时清缓存必须按前缀精确清，混用前缀会误伤另一半功能）。</p>
     */
    public static final String ADMIN_STATS_PREFIX = "admin:stats:";

    // ---------------- 定时任务 ----------------

    /**
     * 自动确认收货「提前提醒」去重标记（批次 6.0.5.2 · 定时任务③）。
     *
     * <p>提醒任务每日跑一次，但"窗口内的订单"可能在多天里反复命中（窗口放宽后更是如此）；
     * 没有去重标记就会重复提醒买家。TTL 7 天覆盖订单的剩余生命周期。</p>
     */
    public static final String TASK_REMIND_SENT_PREFIX = "task:remind:sent:";

    // ---------------- 存储配额 ----------------

    /**
     * 用户上传文件数（批次 6.0.5.2 · M6-A2）。
     *
     * <p>用 Redis 计数而不是每次扫盘/查库：上传是高频写路径，计数只需 O(1) 读写。
     * <b>不设 TTL</b>：它代表"当前磁盘上有多少属于该用户的文件"这一事实，
     * 允许过期会让配额凭空恢复（删除时会 −1，因此长期值是有界的）。
     * Redis 被清空时计数归零 → 退化为"暂时放宽"，属可接受降级（见 6.0.5.2 报告）。</p>
     */
    public static final String STORAGE_USER_COUNT_PREFIX = "storage:user:count:";

    /** 用户上传总字节数（同上）。 */
    public static final String STORAGE_USER_BYTES_PREFIX = "storage:user:bytes:";

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

    /** order:restored:{orderId}（库存回补幂等凭证，TTL 30 天） */
    public static String orderRestored(Long orderId) {
        return ORDER_RESTORED_PREFIX + orderId;
    }

    /** task:remind:sent:{orderId}（自动确认收货提醒去重，TTL 7 天） */
    public static String taskRemindSent(Long orderId) {
        return TASK_REMIND_SENT_PREFIX + orderId;
    }

    /** storage:user:count:{userId}（上传文件数配额计数） */
    public static String storageUserCount(Long userId) {
        return STORAGE_USER_COUNT_PREFIX + userId;
    }

    /** storage:user:bytes:{userId}（上传总字节配额计数） */
    public static String storageUserBytes(Long userId) {
        return STORAGE_USER_BYTES_PREFIX + userId;
    }

    /** admin:stats:overview */
    public static String adminStatsOverview() {
        return ADMIN_STATS_PREFIX + "overview";
    }

    /** admin:stats:order-status */
    public static String adminStatsOrderStatus() {
        return ADMIN_STATS_PREFIX + "order-status";
    }

    /** admin:stats:product-category */
    public static String adminStatsProductCategory() {
        return ADMIN_STATS_PREFIX + "product-category";
    }

    /**
     * admin:stats:trend:{days}（批次 5.5.2）
     *
     * <p><b>必须带 days 维度</b>：7 天与 30 天返回的数组长度、日期区间都不同，
     * 共用一个 Key 会直接把 30 天的数据当成 7 天渲染（缓存 Key 少带维度是 5.4.4
     * 已经踩过一次的坑：换了排序却返回上一次的顺序）。</p>
     */
    public static String adminStatsTrend(int days) {
        return ADMIN_STATS_PREFIX + "trend:" + days;
    }

    /**
     * admin:stats:hot-products:{days}:{limit}（批次 5.5.2）
     *
     * <p>days 与 limit 都是会影响结果的维度（不同 limit 是"前 10"与"前 20"，
     * 不是同一份数据），所以两个都要进 Key —— 拼接方式与 {@link #orderToken(Long, String)}
     * 一致（前缀 + 冒号分隔的参数）。</p>
     */
    public static String adminStatsHotProducts(int days, int limit) {
        return ADMIN_STATS_PREFIX + "hot-products:" + days + ":" + limit;
    }
}
