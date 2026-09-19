package com.campus.market.service.impl;

import com.campus.market.common.constant.ProfileConstants;
import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.EmailProperties;
import com.campus.market.service.EmailCodeService;
import com.campus.market.vo.EmailCodeVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 邮箱验证码服务实现。
 *
 * <h3>关键实现</h3>
 * <ol>
 *   <li><b>先格式后后缀</b>：邮箱格式非法 → code=102；不在校园邮箱后缀允许列表（配置读取，严禁硬编码）→ code=102；</li>
 *   <li><b>发送限流</b>：{@code email:limit:{email}} 已存在 → code=106（TTL = limitSeconds）；</li>
 *   <li><b>失败计量不可重置</b>：{@code email:fail:{email}} 在 failWindowSeconds 窗口内累计，
 *       达 failLockThreshold 次即视为锁定 → code=107；重新获取验证码<b>严禁删除</b>该 Key；</li>
 *   <li><b>降级开关（6.0.1 加固）</b>：skip=true 时不走 SMTP，验证码只写后端日志；
 *       <b>仅 dev profile</b> 才额外回显到响应体（方便本地开发/答辩），非 dev 一律不回显；
 *       并且 prod + skip=true 会在启动时直接失败（见 {@link #assertSkipNotUsedInProd()}）；</li>
 *   <li><b>发送异常</b>：捕获 {@link MailException} → 删除本次验证码 → code=105；</li>
 *   <li><b>Redis 故障降级</b>：读写异常仅 log.warn，不因缓存不可用阻断主流程。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeServiceImpl implements EmailCodeService {

    /** 校园邮箱长度上限（与 tb_user.email VARCHAR(100) 对齐）。 */
    private static final int EMAIL_MAX_LENGTH = 100;

    /** 基础邮箱格式校验（业务层 code=102；注解层 @Email 失败为 code=100）。 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final EmailProperties emailProperties;
    private final JavaMailSender mailSender;

    /** 用于判定"是否 dev / prod"（生产环境安全断言的唯一依据）。 */
    private final Environment environment;

    /**
     * 启动断言（安全加固 6.0.1 · S2）：prod 环境禁止 skip=true。
     *
     * <p>为什么必须 fail-fast 而不是打个 warn：skip=true 时验证码不再发送、而是（在 dev 下）
     * 直接进接口响应体，而获取验证码与找回密码都是<b>公开路径</b> ——
     * 一旦带着这个开关上线，任何人只要知道受害者校园邮箱就能重置其密码，
     * 属于"配置失误等于账号被接管"。宁可启动失败，也不能静默降级。</p>
     */
    @PostConstruct
    void assertSkipNotUsedInProd() {
        if (emailProperties.isSkip() && ProfileConstants.isProd(environment.getActiveProfiles())) {
            throw new IllegalStateException("生产环境(prod)禁止 app.email.skip=true —— "
                    + "该开关会让验证码不再发送(dev 下还会回显到响应体)，"
                    + "而 /auth/email-code 与 /auth/reset-password 是公开路径，等于任意账号可被接管。"
                    + "请关闭 EMAIL_SKIP 并配置真实 SMTP。");
        }
    }

    @Override
    public EmailCodeVO send(String email, String scene) {
        String normalized = normalizeEmail(email);
        String normalizedScene = normalizeScene(scene);
        String codeKey = RedisKeys.emailCode(normalizedScene, normalized);
        String limitKey = RedisKeys.emailLimit(normalized);

        // ① 已锁定（固定 30 分钟）：直接拒绝，且不消耗发送限流额度
        if (isFailLocked(normalized)) {
            log.warn("邮箱验证码服务已锁定，拒绝发送: email={}", maskEmail(normalized));
            throw new BusinessException(ErrorCode.EMAIL_CODE_LOCKED);
        }

        // ② 发送限流：60 秒内重复获取 → 106
        if (!tryAcquireSendLimit(limitKey)) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_TOO_FREQUENT);
        }

        // ③ 生成验证码（注意：不删除 email:fail，失败计数只能随时间窗口自然过期）
        String code = generateCode();
        long expireSeconds = emailProperties.getCodeExpireSeconds();
        try {
            redisTemplate.opsForValue().set(codeKey, code, Duration.ofSeconds(expireSeconds));
        } catch (Exception e) {
            log.warn("验证码写入 Redis 失败（降级）: email={}, err={}", maskEmail(normalized), e.getMessage());
        }

        if (emailProperties.isSkip()) {
            // 降级模式：不依赖 SMTP。验证码只写后端日志（可打印验证码，严禁打印密码）。
            // ⚠️ 6.0.1 加固：**只有 dev profile 才把验证码回显到响应体**。
            //    非 dev（例如自定义 staging / test profile）即使开了 skip，也只写日志、不回显 ——
            //    因为 /auth/email-code 是公开路径，回显等于"任何人可拿别人邮箱的验证码"。
            //    （prod + skip=true 更早一步：启动断言会直接拒绝启动。）
            boolean echoCode = ProfileConstants.isDev(environment.getActiveProfiles());
            if (echoCode) {
                log.info("【邮箱验证码·降级模式(dev 回显)】email={}, scene={}, code={}, expire={}s",
                        maskEmail(normalized), scene, code, expireSeconds);
            } else {
                log.warn("【邮箱验证码·降级模式(不回显)】email={}, scene={}, code={}, expire={}s —— "
                                + "当前 profile 非 dev，验证码仅记录在本日志中，请从日志获取",
                        maskEmail(normalized), scene, code, expireSeconds);
            }
            return new EmailCodeVO(true, echoCode ? code : null, expireSeconds);
        }

        // ④ 真实发送：MailException → 删除本次验证码 → 105
        sendByMail(normalized, code, scene, expireSeconds, codeKey);
        return new EmailCodeVO(false, null, expireSeconds);
    }

    @Override
    public void verify(String email, String scene, String code) {
        String normalized = normalizeEmail(email);
        String normalizedScene = normalizeScene(scene);
        String failKey = RedisKeys.emailFail(normalized);

        // ① 已锁定（固定 30 分钟）→ 107（锁定期间拒绝，不再累计）
        if (isFailLocked(normalized)) {
            log.warn("邮箱验证码服务已锁定，拒绝校验: email={}", maskEmail(normalized));
            throw new BusinessException(ErrorCode.EMAIL_CODE_LOCKED);
        }

        String codeKey = RedisKeys.emailCode(normalizedScene, normalized);
        String cached;
        try {
            cached = redisTemplate.opsForValue().get(codeKey);
        } catch (Exception e) {
            log.warn("验证码读取 Redis 失败（降级）: email={}, err={}", maskEmail(normalized), e.getMessage());
            cached = null;
        }

        boolean matched = cached != null && code != null && cached.trim().equals(code.trim());
        if (!matched) {
            // ② 失败计量：1 小时窗口累计，达阈值即写入固定 30 分钟锁定
            recordVerifyFailure(normalized, failKey);
            log.warn("验证码校验失败: email={}, scene={}, 原因={}", maskEmail(normalized), normalizedScene,
                    cached == null ? "验证码不存在或已过期（含 scene 不匹配）" : "验证码不匹配");
            throw new BusinessException(ErrorCode.EMAIL_CODE_ERROR);
        }

        // ③ 校验通过：删除验证码；email:fail 严禁删除（硬性要求，只能随窗口过期）
        try {
            redisTemplate.delete(codeKey);
        } catch (Exception e) {
            log.warn("验证码删除失败（降级）: email={}, err={}", maskEmail(normalized), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 场景归一化（批次 6.0.6 · Minor 6）：去空白 + 转大写；空值/无法识别统一落到
     * {@link EmailCodeService#SCENE_DEFAULT}。
     *
     * <p>为什么必须归一而不是"原样拼 Key"：{@code scene} 是查询参数，
     * 取码时传 {@code register}、用码时传 {@code REGISTER} 会拼出两个不同的 Key，
     * 表现为"刚拿到的码立刻校验失败"——那种 bug 极难排查。归一之后大小写不敏感。</p>
     *
     * <p>为什么不校验白名单（只认三个已知场景）：换绑/找回归属在<b>业务层</b>由
     * 各自的接口决定传什么 scene，而 email-code 接口本身对游客开放；
     * 这里保持"任意 scene 都能取码、但只能被同一个 scene 用掉"的语义最直观，
     * 也不需要给接口新增一个"未知场景"错误码。</p>
     */
    private static String normalizeScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return EmailCodeService.SCENE_DEFAULT;
        }
        return scene.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 邮箱校验：先格式（102），再校园后缀允许列表（102，后缀来自配置，严禁硬编码）。
     *
     * @return 归一化后的邮箱（trim + 小写）
     */
    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_FORMAT_ERROR, "邮箱不能为空");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > EMAIL_MAX_LENGTH || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.EMAIL_FORMAT_ERROR, "邮箱格式错误");
        }
        List<String> suffixes = emailProperties.getCampusSuffixes();
        boolean campusMail = suffixes != null && suffixes.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::endsWith);
        if (!campusMail) {
            throw new BusinessException(ErrorCode.EMAIL_FORMAT_ERROR, "仅支持校园邮箱");
        }
        return normalized;
    }

    /**
     * 判断邮箱验证码服务是否处于锁定态。
     *
     * <p>锁定语义严格按文档：{@code email:fail:{email}} 在 1 小时窗口内累计达 5 次，
     * 立即写入 {@code email:lock:{email}} 并设置<b>固定 30 分钟</b> TTL（app.email.fail-lock-seconds），
     * 期间一律返回 code=107 且不再计数；30 分钟后锁自动解除。</p>
     *
     * <p>注意：{@code email:fail} 与锁 Key 相互独立——重新获取验证码时<b>严禁删除 email:fail</b>，
     * 只能用独立的 {@code email:lock} 表达"固定 30 分钟"，不能拿 1 小时窗口的剩余时间当锁定时长。</p>
     */
    private boolean isFailLocked(String email) {
        String lockKey = RedisKeys.emailLock(email);
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
                return true;
            }
            // 兼容"计数已超阈值但锁 Key 缺失"的场景（如锁 Key 被误删）：立即补建固定 30 分钟锁
            String failValue = redisTemplate.opsForValue().get(RedisKeys.emailFail(email));
            if (failValue == null || Long.parseLong(failValue) < emailProperties.getFailLockThreshold()) {
                return false;
            }
            redisTemplate.opsForValue().set(lockKey, "1",
                    Duration.ofSeconds(emailProperties.getFailLockSeconds()));
            log.warn("验证码失败计数已达阈值，补建固定锁定: email={}, lockSeconds={}",
                    maskEmail(email), emailProperties.getFailLockSeconds());
            return true;
        } catch (Exception e) {
            // Redis 故障降级：无法判定锁定态时放行，不阻断主流程
            log.warn("验证码锁定态查询失败（降级放行）: err={}", e.getMessage());
            return false;
        }
    }

    /**
     * 发送限流：SETNX 抢占 {@code email:limit:{email}}，TTL = app.email.limit-seconds。
     *
     * @return true 表示允许发送
     */
    private boolean tryAcquireSendLimit(String limitKey) {
        try {
            Duration ttl = Duration.ofSeconds(emailProperties.getLimitSeconds());
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(limitKey, "1", ttl);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // Redis 故障降级：限流失效，放行
            log.warn("验证码发送限流判定失败（降级放行）: err={}", e.getMessage());
            return true;
        }
    }

    /**
     * 累计验证码校验失败次数（1 小时窗口），达阈值即写入固定 30 分钟的锁定 Key。
     *
     * <p>首次失败时设置窗口 TTL，后续失败不再续期，保证窗口语义为"1 小时内累计"；
     * <b>本方法绝不删除 {@code email:fail}</b>（硬性要求）。</p>
     *
     * @param email   归一化邮箱
     * @param failKey {@code email:fail:{email}}
     */
    private void recordVerifyFailure(String email, String failKey) {
        try {
            Boolean exists = redisTemplate.hasKey(failKey);
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (!Boolean.TRUE.equals(exists)) {
                redisTemplate.expire(failKey, Duration.ofSeconds(emailProperties.getFailWindowSeconds()));
            }
            if (count != null && count >= emailProperties.getFailLockThreshold()) {
                // 达阈值 → 写入固定 30 分钟的锁定 Key（与 1 小时失败窗口解耦）
                redisTemplate.opsForValue().set(RedisKeys.emailLock(email), "1",
                        Duration.ofSeconds(emailProperties.getFailLockSeconds()));
                log.error("【安全告警】邮箱验证码服务已锁定: failCount={}, lockSeconds={}",
                        count, emailProperties.getFailLockSeconds());
            }
        } catch (Exception e) {
            log.warn("验证码失败计数累加失败（降级）: err={}", e.getMessage());
        }
    }

    /**
     * 真实 SMTP 发送；失败删除本次验证码并抛 code=105。
     */
    private void sendByMail(String email, String code, String scene, long expireSeconds, String codeKey) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(email);
            message.setSubject("【校园二手交易平台】邮箱验证码");
            message.setText(buildMailText(code, scene, expireSeconds));
            mailSender.send(message);
            log.info("验证码邮件已发送: email={}, scene={}", maskEmail(email), scene);
        } catch (MailException e) {
            // 发送失败：删除本次验证码，避免留下无法送达的有效码
            try {
                redisTemplate.delete(codeKey);
            } catch (Exception ex) {
                log.warn("发送失败后清理验证码异常（降级）: err={}", ex.getMessage());
            }
            log.error("验证码邮件发送失败: email={}, scene={}", maskEmail(email), scene, e);
            throw new BusinessException(ErrorCode.MAIL_SEND_FAILED);
        }
    }

    private String buildMailText(String code, String scene, long expireSeconds) {
        long minutes = Math.max(1L, expireSeconds / 60L);
        String action = switch (scene == null ? "" : scene.toUpperCase(Locale.ROOT)) {
            case "REGISTER" -> "注册账号";
            case "RESET_PASSWORD" -> "重置密码";
            case "BIND_EMAIL" -> "换绑邮箱";
            default -> "身份验证";
        };
        return "您好：\n\n"
                + "您正在进行【" + action + "】操作，验证码为：" + code + "\n"
                + "验证码 " + minutes + " 分钟内有效，请勿泄露给他人。\n"
                + "如非本人操作，请忽略本邮件。\n\n"
                + "校园二手交易平台";
    }

    /** 生成 6 位数字验证码（SecureRandom）。 */
    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** 日志脱敏：保留首字符与前缀域名，避免完整邮箱落日志。 */
    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "-";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
