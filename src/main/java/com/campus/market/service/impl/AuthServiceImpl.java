package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.market.common.constant.PathConstants;
import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.JwtProperties;
import com.campus.market.config.properties.LoginSecurityProperties;
import com.campus.market.config.properties.TrustedProxyProperties;
import com.campus.market.dto.auth.LoginRequest;
import com.campus.market.dto.auth.RegisterRequest;
import com.campus.market.dto.auth.ResetPasswordRequest;
import com.campus.market.entity.User;
import com.campus.market.mapper.UserMapper;
import com.campus.market.security.LocalJwtBlacklist;
import com.campus.market.service.AuthService;
import com.campus.market.service.EmailCodeService;
import com.campus.market.service.TokenVersionService;
import com.campus.market.util.IpUtils;
import com.campus.market.util.JwtUtils;
import com.campus.market.util.PasswordValidator;
import com.campus.market.vo.EmailCodeVO;
import com.campus.market.vo.LoginVO;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;

/**
 * 认证服务实现（注册 / 登录 / 退出 / 验证码 / 找回密码）。
 *
 * <h3>登录防爆破双维度</h3>
 * <ul>
 *   <li><b>账号维度</b>：{@code login:fail:{username}} TTL 5 分钟；达 5 次写 {@code login:lock:{username}} TTL 15 分钟
 *       并返回 104「账号已锁定15分钟」；<b>锁定期间拒绝且不再计数</b>；登录成功清除账号维度两个 Key。</li>
 *   <li><b>IP 维度</b>：{@code login:fail:ip:{ip}}；单 IP 5 分钟内失败 20 次写 {@code login:lock:ip:{ip}}
 *       TTL 30 分钟并返回 104「IP已被临时限制，请稍后重试」。</li>
 * </ul>
 *
 * <h3>批次 6.0.2 安全加固 · M1</h3>
 * <p>修前有两个洞，合起来等于"在线口令爆破几乎无限制"：</p>
 * <ol>
 *   <li><b>IP 维度可伪造</b>：{@code IpUtils} 无条件采信 {@code X-Forwarded-For}，
 *       每次换一个伪造 IP 就换一份计数额度（且审计日志 IP 失真）。修法见 {@code IpUtils}
 *       （只在 {@code app.security.trusted-proxies} 命中的可信代理之后才采信转发头）；</li>
 *   <li><b>账号维度未归一化</b>：计数 Key 直接拼 {@code getUsername().trim()}，而 {@code tb_user}
 *       是 {@code utf8mb4_0900_ai_ci}（大小写不敏感）—— {@code admin} / {@code Admin} /
 *       {@code aDmIn} 登录到<b>同一个账号</b>，却各有一份失败计数，5 次/15 分钟的保护被放大 2ⁿ 倍。
 *       修法：所有用 username 拼 Key 的位置统一走 {@link #normalizeUsername(String)}
 *       （{@code trim + toLowerCase(Locale.ROOT)}），与数据库排序规则语义对齐。</li>
 * </ol>
 * <p><b>迁移影响</b>：Key 形态变化会让"含大写字母的账号"当前累计的失败计数重置一次
 * （旧 Key {@code login:fail:Admin} 不再被读取）。对安全是<b>单向收紧</b>：
 * 重置后所有大小写变体共用同一份计数，不存在"少算"的窗口。</p>
 *
 * <h3>防状态探测</h3>
 * 先校验密码（失败 101），密码正确后再校验 status（封禁 205），严禁先查 status。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 用户角色：0-学生。 */
    private static final int ROLE_STUDENT = 0;

    /** 用户状态：0-正常。 */
    private static final int STATUS_NORMAL = 0;

    /** 用户状态：1-封禁。 */
    private static final int STATUS_BANNED = 1;

    /** 黑名单兜底 TTL（秒）：Token 剩余时间异常为 0 时使用，避免写入无过期键。 */
    private static final long BLACKLIST_MIN_TTL_SECONDS = 1L;

    /** 默认 Token 有效期（秒），用于 JwtProperties 未提供过期配置时的兜底。 */
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 7200L;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;
    private final TokenVersionService tokenVersionService;
    private final EmailCodeService emailCodeService;
    private final LoginSecurityProperties loginSecurityProperties;
    private final JwtProperties jwtProperties;
    private final LocalJwtBlacklist localJwtBlacklist;
    /** 可信代理列表（批次 6.0.2 · M1）：为空表示不采信任何转发头。 */
    private final TrustedProxyProperties trustedProxyProperties;

    // ================================================================== 注册

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long register(RegisterRequest request) {
        // ① 校验校园邮箱验证码（格式 / 后缀 / 限流 / 锁定 / 一次性消费均在 EmailCodeService 内处理）
        //    scene 必须与取码时一致（批次 6.0.6 · Minor 6：验证码按 scene 隔离）
        emailCodeService.verify(request.getEmail(), EmailCodeService.SCENE_REGISTER, request.getEmailCode());

        // ② 强密码校验（8-20 位 + 字母 + 数字 + 特殊字符 + 非弱密码），不合规 → code=100
        PasswordValidator.validate(request.getPassword());

        // ③ 唯一性校验
        //    批次 6.0.2 · M1：预检用归一化后的账号（与 tb_user 的 ci 排序规则口径一致，
        //    否则 "Admin" 与已存在的 "admin" 在预检时看着不重名，直到 INSERT 撞唯一索引才报错）。
        //    ⚠️ 落库仍保留用户输入的大小写：存的是"账号"，展示与排查都更贴近原样，
        //       而登录查询与计数 Key 一律走归一化 —— 两者靠 ci 排序规则对齐。
        String storedUsername = request.getUsername().trim();
        String usernameKey = normalizeUsername(request.getUsername());
        if (existsByUsername(usernameKey)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该学号已被注册");
        }
        String email = normalizeEmail(request.getEmail());
        if (existsByEmail(email)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该邮箱已被注册");
        }

        // ④ 落库（密码仅存 BCrypt 哈希）
        User user = new User();
        user.setUsername(storedUsername);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(resolveNickname(request.getNickname(), storedUsername));
        user.setEmail(email);
        user.setRole(ROLE_STUDENT);
        user.setStatus(STATUS_NORMAL);
        userMapper.insert(user);

        log.info("用户注册成功: userId={}, username={}", user.getId(), storedUsername);
        return user.getId();
    }

    // ================================================================== 登录

    @Override
    public LoginVO login(LoginRequest request, HttpServletRequest httpRequest) {
        // 批次 6.0.2 · M1：账号先归一化再拼 Key / 查库，与 tb_user 的 utf8mb4_0900_ai_ci 语义对齐，
        // 避免 admin / Admin / aDmIn 各拿一份失败计数额度。
        String username = normalizeUsername(request.getUsername());
        String ip = resolveClientIp(httpRequest);
        String failKey = RedisKeys.loginFail(username);
        String lockKey = RedisKeys.loginLock(username);
        String failIpKey = RedisKeys.loginFailIp(ip);
        String lockIpKey = RedisKeys.loginLockIp(ip);

        // ① 锁定态检查（账号 → IP）；锁定期间直接拒绝且不再计数
        if (isLocked(lockKey)) {
            log.warn("账号锁定期间登录请求被拒绝: username={}, ip={}", username, ip);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (isLocked(lockIpKey)) {
            log.warn("IP 限制期间登录请求被拒绝: username={}, ip={}", username, ip);
            throw new BusinessException(ErrorCode.IP_LIMITED);
        }

        User user = findByUsername(username);

        // ② 先校验密码（防状态探测）：失败 → 101，并累加账号 + IP 双维度失败计数
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            recordLoginFailure(username, ip, failKey, lockKey, failIpKey, lockIpKey);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        // ③ 密码正确后再校验封禁状态 → 205（仅密码正确时才会泄露该信息）
        if (Integer.valueOf(STATUS_BANNED).equals(user.getStatus())) {
            log.warn("被封禁用户尝试登录: userId={}, username={}", user.getId(), username);
            throw new BusinessException(ErrorCode.USER_BANNED);
        }

        // ④ 登录成功：清除账号维度两个 Key（IP 维度计数保留，避免绕过 IP 限流）
        clearKeys(failKey, lockKey);

        long version = tokenVersionService.currentVersion(user.getId());
        String token = jwtUtils.generateToken(user, version);

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setTokenType("Bearer");
        vo.setExpiresIn(resolveExpiresInSeconds());
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setRole(user.getRole());
        log.info("用户登录成功: userId={}, username={}", user.getId(), username);
        return vo;
    }

    // ================================================================== 退出登录

    @Override
    public void logout(String authorizationHeader) {
        String token = stripBearer(authorizationHeader);
        if (token == null) {
            // 拦截器已保证强制认证路径携带有效 Token，此处仅兜底
            log.warn("退出登录未取得有效 Token（缺少 Authorization 头）");
            return;
        }
        Claims claims = jwtUtils.parseToken(token);
        if (claims == null) {
            return;
        }
        long remainingMillis = jwtUtils.getRemainingMillis(claims);
        long ttlSeconds = Math.max(BLACKLIST_MIN_TTL_SECONDS, remainingMillis / 1000L);
        // 同步写入进程内兜底黑名单：Redis 不可用期间退出登录仍然生效
        localJwtBlacklist.add(token, remainingMillis);
        try {
            // 单 Token 黑名单，TTL 与 Token 剩余有效期一致
            redisTemplate.opsForValue().set(RedisKeys.jwtBlacklist(token), "1", Duration.ofSeconds(ttlSeconds));
            log.info("用户退出登录，Token 已加入黑名单: userId={}, ttl={}s",
                    jwtUtils.getUserId(claims), ttlSeconds);
        } catch (Exception e) {
            // Redis 故障降级：不阻断退出流程（不可用时该 Token 只能等自然过期，属可接受降级）
            log.warn("退出登录写入黑名单失败（降级，该 Token 将等自然过期）: err={}", e.getMessage());
        }
    }

    // ================================================================== 邮箱验证码

    @Override
    public EmailCodeVO sendEmailCode(String email, String scene) {
        return emailCodeService.send(email, scene);
    }

    // ================================================================== 找回密码

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(ResetPasswordRequest request) {
        String email = normalizeEmail(request.getEmail());

        // ① 校验验证码（失败/过期 103；邮箱验证码服务锁定 107）
        //    scene=RESET_PASSWORD（批次 6.0.6 · Minor 6）：注册场景的码不能用于找回密码
        emailCodeService.verify(email, EmailCodeService.SCENE_RESET_PASSWORD, request.getEmailCode());

        User user = findByEmail(email);
        if (user == null) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_ERROR, "邮箱未注册");
        }

        // ② 新密码不得与旧密码相同 + 强密码校验
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "新密码不能与旧密码相同");
        }
        PasswordValidator.validate(request.getNewPassword());

        User update = new User();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(update);

        // ③ 找回密码成功后 version +1：该用户全部已签发 Token 立即失效
        tokenVersionService.increaseVersion(user.getId());
        log.info("找回密码成功，Token version 已提升: userId={}", user.getId());
    }

    // ================================================================== 内部实现

    /**
     * 记录一次登录失败：账号维度（达阈值即锁定）+ IP 维度（达阈值即限制）。
     */
    private void recordLoginFailure(String username, String ip, String failKey, String lockKey,
                                    String failIpKey, String lockIpKey) {
        // ---------- 账号维度：TTL 5 分钟，第 5 次失败即写锁定 Key ----------
        try {
            Boolean exists = redisTemplate.hasKey(failKey);
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (!Boolean.TRUE.equals(exists)) {
                redisTemplate.expire(failKey, Duration.ofSeconds(loginSecurityProperties.getAccountFailTtlSeconds()));
            }
            if (count != null && count >= loginSecurityProperties.getAccountFailThreshold()) {
                redisTemplate.opsForValue().set(lockKey, "1",
                        Duration.ofSeconds(loginSecurityProperties.getAccountLockSeconds()));
                log.error("【安全告警】账号连续登录失败达阈值已锁定: username={}, failCount={}, lockSeconds={}",
                        username, count, loginSecurityProperties.getAccountLockSeconds());
            }
        } catch (Exception e) {
            log.warn("账号维度登录失败计数写入失败（降级）: username={}, err={}", username, e.getMessage());
        }

        // ---------- IP 维度：5 分钟窗口内失败 20 次 → 限制 30 分钟 ----------
        try {
            Boolean exists = redisTemplate.hasKey(failIpKey);
            Long count = redisTemplate.opsForValue().increment(failIpKey);
            if (!Boolean.TRUE.equals(exists)) {
                redisTemplate.expire(failIpKey, Duration.ofSeconds(loginSecurityProperties.getIpFailWindowSeconds()));
            }
            if (count != null && count >= loginSecurityProperties.getIpFailThreshold()) {
                redisTemplate.opsForValue().set(lockIpKey, "1",
                        Duration.ofSeconds(loginSecurityProperties.getIpLockSeconds()));
                log.error("【安全告警】IP 登录失败达阈值已被临时限制: ip={}, failCount={}, lockSeconds={}",
                        ip, count, loginSecurityProperties.getIpLockSeconds());
            }
        } catch (Exception e) {
            log.warn("IP 维度登录失败计数写入失败（降级）: ip={}, err={}", ip, e.getMessage());
        }

        // 安全审计：仅记录账号与 IP，严禁记录明文密码
        log.warn("登录失败（用户名或密码错误）: username={}, ip={}", username, ip);
    }

    /**
     * 锁定态判断；Redis 故障时降级放行，不阻断登录主流程。
     */
    private boolean isLocked(String lockKey) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey));
        } catch (Exception e) {
            log.warn("登录锁定态查询失败（降级放行）: err={}", e.getMessage());
            return false;
        }
    }

    private void clearKeys(String... keys) {
        try {
            for (String key : keys) {
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.warn("登录成功后清理失败计数失败（降级）: err={}", e.getMessage());
        }
    }

    private boolean existsByUsername(String username) {
        return userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)) > 0;
    }

    private boolean existsByEmail(String email) {
        return userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getEmail, email)) > 0;
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));
    }

    private User findByEmail(String email) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getEmail, email)
                .last("LIMIT 1"));
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveNickname(String nickname, String username) {
        if (nickname == null || nickname.isBlank()) {
            // 昵称可选，缺省使用学号
            return username;
        }
        return nickname.trim();
    }

    /**
     * 账号归一化（批次 6.0.2 · M1）：{@code trim + toLowerCase(Locale.ROOT)}。
     *
     * <p><b>为什么必须归一化</b>：{@code tb_user.username} 用的是
     * {@code utf8mb4_0900_ai_ci}（大小写不敏感），因此 {@code admin} / {@code Admin} /
     * {@code aDmIn} 都能登录到同一个账号；但 Redis 计数 Key 是大小写敏感的字符串拼接，
     * 三种写法会各拿一份"5 次/15 分钟"的额度 —— 含字母的账号（含全部 demo 管理员
     * {@code adminXXXXXXXX}）等于把爆破预算放大了 2ⁿ 倍。</p>
     *
     * <p>用 {@link Locale#ROOT} 而不是默认 Locale：土耳其语等区域下
     * {@code "I".toLowerCase()} 会得到 {@code "ı"}，那会让同一个账号在两个 Locale 的机器上
     * 落到不同 Key —— 单机看不出来，多实例/容器化时就是"计数偶发不共享"的幽灵 Bug。</p>
     *
     * @param username 原始账号（可为 null）
     * @return 归一化后的账号；入参为 null 时返回 null（交由上层校验处理）
     */
    public static String normalizeUsername(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 解析客户端 IP；缺失时退化为远端地址。同一 IP 共用一个计数 Key，不会产生无界 Key 集合。
     *
     * <p>批次 6.0.2 · M1：改为<b>只在可信代理之后</b>采信 {@code X-Forwarded-For}
     * （可信列表见 {@code app.security.trusted-proxies}，默认为空 = 不采信任何转发头）。</p>
     */
    private String resolveClientIp(HttpServletRequest httpRequest) {
        if (httpRequest == null) {
            return "unknown";
        }
        String ip = IpUtils.getClientIp(httpRequest, trustedProxyProperties.getTrustedProxies());
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            String remote = httpRequest.getRemoteAddr();
            return (remote == null || remote.isBlank()) ? "unknown" : remote;
        }
        return ip;
    }

    /**
     * 去除 Bearer 前缀，返回裸 Token；无有效值时返回 null。
     */
    private String stripBearer(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String header = authorizationHeader.trim();
        if (header.regionMatches(true, 0, PathConstants.BEARER_PREFIX, 0, PathConstants.BEARER_PREFIX.length())) {
            header = header.substring(PathConstants.BEARER_PREFIX.length()).trim();
        }
        return header.isEmpty() ? null : header;
    }

    /**
     * Token 有效期（秒）：由 JwtProperties 推导，缺失时使用默认 2 小时。
     */
    private long resolveExpiresInSeconds() {
        try {
            int hours = jwtProperties.getExpireHours();
            if (hours > 0) {
                return hours * 3600L;
            }
        } catch (Exception e) {
            log.warn("读取 JWT 有效期配置失败，使用默认值: err={}", e.getMessage());
        }
        return DEFAULT_EXPIRES_IN_SECONDS;
    }
}
