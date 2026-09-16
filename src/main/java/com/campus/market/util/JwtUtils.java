package com.campus.market.util;

import com.campus.market.config.properties.JwtProperties;
import com.campus.market.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类。
 *
 * <p>payload 携带 userId、role、version（用户级 Token 版本机制）；
 * 有效期 2 小时（app.jwt.expire-hours）。解析失败一律返回 null，由拦截器决定是否放行或 401。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtils {

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_VERSION = "version";

    private final JwtProperties jwtProperties;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret 必须通过环境变量注入且长度不少于 32 字节");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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
