package com.campus.market.security;

import com.campus.market.common.constant.PathConstants;
import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.entity.User;
import com.campus.market.mapper.UserMapper;
import com.campus.market.service.TokenVersionService;
import com.campus.market.util.JwtUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 认证拦截器：JWT 校验 + 用户级 Token version 校验 + 三类路径语义 + @RequireRole 角色校验。
 *
 * <h3>三类路径语义（关键）</h3>
 * <ol>
 *   <li><b>完全公开</b>：/actuator/health、/actuator/prometheus、/swagger-ui/**、/v3/api-docs/** 等 ——
 *       直接放行，<b>不解析 Token</b>。</li>
 *   <li><b>可选认证</b>：/api/v1/product/detail/**、/api/v1/product/list、/api/v1/category/list ——
 *       尝试解析 Token：存在且有效则注入 {@link UserContext}；不存在 / 无效 / 过期一律<b>静默放行</b>，不报错。</li>
 *   <li><b>强制认证</b>：其余全部 /api/v1/** —— 必须携带有效 Token，否则抛 401 → HTTP 401。
 *       401 文案分两类：<b>未携带</b> Token 返回 {@code NOT_LOGIN}「请先登录」；
 *       <b>携带但已失效</b>（登出黑名单 / 已过期 / 签名被篡改 / 格式错误）返回
 *       {@code UNAUTHORIZED}「登录已失效，请重新登录」。</li>
 * </ol>
 *
 * <h3>用户级 Token 版本机制</h3>
 * JWT payload 携带 version，与 Redis {@code user:token:version:{userId}} 比对，不一致即拒绝（HTTP 401）。
 * 版本比对异常时兜底查询 {@code user:status:{userId}}，若 status=1（封禁）直接 401，双保险确保封禁立即生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;
    private final UserMapper userMapper;
    private final TokenVersionService tokenVersionService;
    private final LocalJwtBlacklist localJwtBlacklist;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();

        // ---------- ① 完全公开：不解析 Token，直接放行 ----------
        if (matchAny(PathConstants.PUBLIC_PATHS, uri)) {
            return true;
        }
        if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
            return true;
        }

        // ---------- ② 可选认证：解析成功则注入，失败静默放行 ----------
        if (matchAny(PathConstants.OPTIONAL_AUTH_PATHS, uri)) {
            Claims claims = resolveClaims(request);
            if (claims != null) {
                authenticate(claims, request, false, true);
            }
            return true;
        }

        // ---------- ③ 强制认证：必须携带有效 Token，否则 401 ----------
        // 注意顺序：先 Token 有效性（401）→ 再角色（403），认证失败时 authenticate 直接抛异常
        authenticate(resolveClaims(request), request, true, hasCredential(request));
        return checkRole(handler);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        // 必须清理 ThreadLocal，避免线程池复用导致用户串号
        UserContext.clear();
    }

    // ------------------------------------------------------------------ 内部实现

    private boolean matchAny(String[] patterns, String uri) {
        return Arrays.stream(patterns).anyMatch(pattern -> PATH_MATCHER.match(pattern, uri));
    }

    /**
     * 从请求头中提取 Bearer Token 原文。
     *
     * <p>统一出口，避免"是否携带凭证"与"解析凭证"两处逻辑各自实现而漂移。
     * 注意：Redis 故障时底层仍可能拒绝，见 {@link #resolveClaims}。</p>
     *
     * @return Token 原文；请求头缺失、前缀不是 Bearer、或 Token 为空时返回 {@code null}
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(PathConstants.AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(PathConstants.BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(PathConstants.BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 判断请求是否<b>携带</b>了 Bearer 凭证（<b>不</b>校验其有效性）。
     *
     * <p>用途：区分两类 401 文案（PROJECT_CONTEXT 第 5、6 章）。{@link #resolveClaims} 会把
     * "没带凭证"与"带了但不可用"都塌缩成 {@code null}，若只依据 {@code null} 判断，
     * 文案必然失真。故此处单独表达"是否携带"这一维度：</p>
     * <ul>
     *   <li>未携带凭证 → {@link ErrorCode#NOT_LOGIN}「请先登录」；</li>
     *   <li>携带但不可用（登出黑名单 / 已过期 / 签名被篡改 / 格式错误）
     *       → {@link ErrorCode#UNAUTHORIZED}「登录已失效，请重新登录」。</li>
     * </ul>
     *
     * @return true 表示请求头中确实存在一个非空的 Bearer Token
     */
    private boolean hasCredential(HttpServletRequest request) {
        return extractBearerToken(request) != null;
    }

    private Claims resolveClaims(HttpServletRequest request) {
        String token = extractBearerToken(request);
        if (token == null) {
            return null;
        }
        // 单 Token 注销：Redis 黑名单命中即失效（jwt:blacklist:{token}，TTL = Token 剩余有效期）
        String blacklistKey = RedisKeys.jwtBlacklist(token);
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(blacklistKey))) {
                return null;
            }
            // Redis 可用时清理本地兜底缓存，保持内存占用有界
            localJwtBlacklist.remove(token);
        } catch (Exception e) {
            // Redis 故障降级：回退到进程内兜底黑名单（退出登录时同步写入），
            // 保证 Redis 不可用期间"已登出 Token"仍被拒绝；JWT 有效期上限 2 小时。
            if (localJwtBlacklist.contains(token)) {
                log.debug("Redis 不可用, 命中本地兜底黑名单: {}", e.getMessage());
                return null;
            }
            log.warn("Redis 黑名单校验降级为进程内兜底: {}", e.getMessage());
        }
        return jwtUtils.parseToken(token);
    }

    /**
     * 认证主体逻辑。
     *
     * @param required            强制认证路径传 true：失败时抛业务异常（401）
     * @param credentialPresented 请求是否携带了 Bearer 凭证，仅用于在 401 时选择正确的文案
     * @return true 表示认证通过（或可选认证放行）
     */
    private boolean authenticate(Claims claims, HttpServletRequest request,
                                 boolean required, boolean credentialPresented) {
        if (claims == null) {
            if (required) {
                if (credentialPresented) {
                    // 携带了凭证但不可用：登出黑名单命中 / JWT 已过期 / 签名被篡改 / 格式错误
                    log.debug("凭证已失效, 拒绝访问: {} {}", request.getMethod(), request.getRequestURI());
                    throw new BusinessException(ErrorCode.UNAUTHORIZED);
                }
                // 完全未携带凭证
                log.debug("强制认证路径未携带 Token: {} {}", request.getMethod(), request.getRequestURI());
                throw new BusinessException(ErrorCode.NOT_LOGIN);
            }
            return true;
        }

        Long userId = jwtUtils.getUserId(claims);
        Integer role = jwtUtils.getRole(claims);
        Long version = jwtUtils.getVersion(claims);
        if (userId == null || role == null || version == null) {
            return failOptional(required, "Token payload 缺少必要字段");
        }

        // ---------- 用户级 Token version 比对 ----------
        // 统一通过 TokenVersionService 读取：Key 不存在时由该服务"初始化为 1 并写回"，
        // 绝不跳过比对直接放行（否则 Redis 被清空后旧 Token 会绕过封禁/改密失效机制）。
        Long currentVersion = tokenVersionService.currentVersion(userId);
        if (!currentVersion.equals(version)) {
            // 封禁 / 改密 / 换绑 / 找回密码后旧 Token 立即失效
            log.info("Token version 不匹配, 拒绝访问: userId={}, tokenVersion={}, currentVersion={}",
                    userId, version, currentVersion);
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 兜底双保险：版本比对通过仍需确认用户未被封禁（user:status:{userId} 缓存，未命中查库）
        if (isUserBanned(userId)) {
            log.info("用户已被封禁, 拒绝访问: userId={}", userId);
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        UserContext.set(new LoginUser(userId, role, version));
        return true;
    }

    /**
     * 版本比对失败时的兜底：查询 user:status:{userId} 缓存，未命中再查库（双保险）。
     */
    private boolean isUserBanned(Long userId) {
        try {
            String cached = redisTemplate.opsForValue().get(RedisKeys.userStatus(userId));
            if (cached != null) {
                return "1".equals(cached);
            }
        } catch (Exception ignored) {
            // 忽略，继续查库
        }
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getStatus)
                .eq(User::getId, userId));
        return user != null && Integer.valueOf(1).equals(user.getStatus());
    }

    private boolean failOptional(boolean required, String reason) {
        if (required) {
            log.debug("Token 校验失败: {}", reason);
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return true;
    }

    /**
     * @RequireRole 角色校验（严禁 @PreAuthorize）。
     *
     * <p>顺序：Token 有效性（401）→ 角色（403）。</p>
     */
    private boolean checkRole(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requireRole == null) {
            return true;
        }
        LoginUser loginUser = UserContext.get();
        if (loginUser == null || loginUser.getRole() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        for (int allowed : requireRole.value()) {
            if (loginUser.getRole() == allowed) {
                return true;
            }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }
}
