package com.campus.market.common.constant;

/**
 * Spring Profile 名称与判定（批次 6.0.1 安全加固引入）。
 *
 * <p><b>为什么要收口这个小工具类</b>：生产环境安全断言（"JWT 密钥不得是仓库内置的默认值"、
 * "验证码降级开关必须关闭"）都要判断"当前激活的是不是 prod"。若每个类各写一份字符串字面量，
 * 迟早出现一处写 {@code "production"}、另一处写 {@code "prod"} 的分裂 —— 这种错误的表现是
 * <b>断言静默失效</b>（而不是报错），恰恰是最危险的失败方式。故 Profile 名与判定统一放这里，
 * 与 {@link PathConstants} / {@link RedisKeys} 的"常量统一收口"约定一致。</p>
 *
 * <p>判定本身写成纯静态方法，便于单测直接覆盖（不需要启动 Spring 上下文）。</p>
 */
public final class ProfileConstants {

    /** 本地开发 / 答辩演示 profile（application.yml 的默认值）。 */
    public static final String DEV = "dev";

    /** 生产 profile（application-prod.yml）。 */
    public static final String PROD = "prod";

    private ProfileConstants() {
    }

    /**
     * 指定 profile 是否处于激活状态（大小写不敏感）。
     *
     * @param activeProfiles {@code Environment#getActiveProfiles()} 的返回值，允许为 null
     * @param profile        待判定的 profile 名，允许为 null
     */
    public static boolean isActive(String[] activeProfiles, String profile) {
        if (activeProfiles == null || profile == null) {
            return false;
        }
        for (String active : activeProfiles) {
            if (profile.equalsIgnoreCase(active)) {
                return true;
            }
        }
        return false;
    }

    /** 是否激活了 prod。 */
    public static boolean isProd(String[] activeProfiles) {
        return isActive(activeProfiles, PROD);
    }

    /** 是否激活了 dev。 */
    public static boolean isDev(String[] activeProfiles) {
        return isActive(activeProfiles, DEV);
    }
}
