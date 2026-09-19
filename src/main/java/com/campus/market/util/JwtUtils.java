package com.campus.market.util;

import com.campus.market.common.constant.ProfileConstants;
import com.campus.market.common.constant.SecretGenerationHints;
import com.campus.market.config.properties.JwtProperties;
import com.campus.market.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类。
 *
 * <p>payload 携带 userId、role、version（用户级 Token 版本机制）；
 * 有效期 2 小时（app.jwt.expire-hours）。解析失败一律返回 null，由拦截器决定是否放行或 401。</p>
 *
 * <h3>密钥来源与两道生产防线（批次 6.0.1 安全加固）</h3>
 * <p>背景：仓库是 <b>Public</b>，而 {@code application.yml} 为了本地开发保留了
 * 一个 dev 默认密钥（{@link #DEV_DEFAULT_SECRET}）。一旦生产环境漏配环境变量，
 * 这个已公开的密钥就会被用来签发 Token —— 攻击者可以自签 {@code role=1} 冒充管理员，
 * 属于"配置失误等于完全越权"。因此本类与配置文件一起构成<b>两道</b>防线：</p>
 * <ol>
 *   <li><b>配置层</b>：{@code application-prod.yml} 里写的是 {@code secret: ${JWT_SECRET}}
 *       （<b>没有默认值</b>），prod 环境未注入该变量时不会拿到 dev 默认密钥；</li>
 *   <li><b>启动断言层</b>：{@link #init()} 校验"非空 / 长度 ≥ 32 字节 / prod 不得等于 dev 默认值"，
 *       任一不满足即抛异常阻止启动。</li>
 * </ol>
 * <p><b>实测补充（6.0.1）</b>：环境变量缺失时，Spring 并不会抛"占位符无法解析"，
 * 而是把字面量 {@code "${JWT_SECRET}"} 绑定到 {@code @ConfigurationProperties} 字段上，
 * 于是拦下它的是<b>启动断言</b>（这也是为什么两道防线都要有）。
 * {@link #validateSecret(String, String[])} 会识别这种"未替换的占位符"并给出明确提示，
 * 避免只报一句语焉不详的"长度不足"。</p>
 * <p>校验逻辑抽成纯静态方法 {@link #validateSecret(String, String[])}，单测可直接覆盖
 * （无需启动 Spring 上下文）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtils {

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_VERSION = "version";

    /**
     * 仓库内置的 dev 默认密钥（仅限本地开发使用）。
     *
     * <p><b>严禁在生产环境使用</b>：它已经随公开仓库一同公开，任何拿到它的人都能伪造任意身份。
     * 这个常量同时被 {@link #validateSecret(String, String[])} 用来做"prod 不得使用默认值"的判定，
     * 与 {@code application.yml} 里那行默认值必须保持一致。</p>
     */
    public static final String DEV_DEFAULT_SECRET =
            "campus-market-dev-secret-key-please-override-in-env-32bytes";

    /** 密钥最小字节数（HMAC-SHA256 要求）。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties jwtProperties;

    /** 用于判定当前是否 prod（生产环境安全断言的唯一依据）。 */
    private final Environment environment;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        validateSecret(jwtProperties.getSecret(), environment.getActiveProfiles());
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 密钥校验（启动期调用；纯函数，便于单测）。
     *
     * <p>三条规则，任一不满足即抛 {@link IllegalStateException} 阻止应用启动：</p>
     * <ol>
     *   <li>密钥非空；</li>
     *   <li>密钥长度 ≥ 32 字节（HMAC-SHA256 要求）；</li>
     *   <li><b>prod 环境不得使用 dev 默认密钥</b>（仓库公开，等于把签发权公开）。</li>
     * </ol>
     *
     * @param secret         来自 {@code app.jwt.secret}
     * @param activeProfiles 当前激活的 profile（{@code Environment#getActiveProfiles()}）
     */
    static void validateSecret(String secret, String[] activeProfiles) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret 必须通过环境变量 JWT_SECRET 注入（当前为空）\n"
                            + SecretGenerationHints.KEY_GENERATION_COMMANDS);
        }
        // ⚠️ 实测结论（6.0.1）：application-prod.yml 里的 `secret: ${JWT_SECRET}` 在环境变量缺失时
        //    **不会**让 Spring 抛"占位符无法解析"，而是把字面量 "${JWT_SECRET}" 直接绑定到
        //    @ConfigurationProperties 字段上（relaxed binding 的行为）。
        //    因此这里必须显式认出"未替换的占位符"，否则只会报一句语焉不详的"长度不足"。
        if (secret.startsWith("${")) {
            throw new IllegalStateException(
                    "JWT secret 未注入：app.jwt.secret 的值仍是未替换的占位符 " + secret
                            + "，请设置环境变量 JWT_SECRET（>= 32 字节随机值）\n"
                            + SecretGenerationHints.KEY_GENERATION_COMMANDS);
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT secret 必须通过环境变量注入且长度不少于 " + MIN_SECRET_BYTES + " 字节"
                            + "（当前 " + secret.getBytes(StandardCharsets.UTF_8).length + " 字节）\n"
                            + SecretGenerationHints.KEY_GENERATION_COMMANDS);
        }
        if (ProfileConstants.isProd(activeProfiles) && DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "生产环境(prod)禁止使用仓库内置的 dev 默认 JWT 密钥 —— 该密钥已随公开仓库泄露，"
                            + "任何人可用它伪造管理员 Token。请通过环境变量 JWT_SECRET 注入一个 >= 32 字节的随机密钥\n"
                            + SecretGenerationHints.KEY_GENERATION_COMMANDS);
        }
    }

    /**
     * 签发 Token。
     *
     * @param user            用户实体
     * @param tokenVersion    用户级 Token 版本（Redis user:token:version:{userId} 当前值）
     */
    public String generateToken(User user, long tokenVersion) {
        long now = System.currentTimeMillis();
        long expireMillis = now + jwtProperties.getExpireHours() * 3600_000L;
        return Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_ROLE, user.getRole())
                .claim(CLAIM_VERSION, tokenVersion)
                .issuedAt(new Date(now))
                .expiration(new Date(expireMillis))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并验签 Token；任何异常（过期、篡改、格式错误）都返回 null。
     */
    public Claims parseToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public Long getUserId(Claims claims) {
        Object value = claims.get(CLAIM_USER_ID);
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    public Integer getRole(Claims claims) {
        Object value = claims.get(CLAIM_ROLE);
        return value == null ? null : Integer.valueOf(String.valueOf(value));
    }

    public Long getVersion(Claims claims) {
        Object value = claims.get(CLAIM_VERSION);
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    /**
     * Token 剩余有效毫秒数（单 Token 注销黑名单 TTL 与之保持一致）。
     */
    public long getRemainingMillis(Claims claims) {
        Date expiration = claims.getExpiration();
        if (expiration == null) {
            return 0L;
        }
        return Math.max(0L, expiration.getTime() - System.currentTimeMillis());
    }
}
