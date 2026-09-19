package com.campus.market.service;

import com.campus.market.vo.EmailCodeVO;

/**
 * 邮箱验证码服务（接口 1.4 + 注册 / 找回密码 / 换绑邮箱共用）。
 *
 * <p>Redis Key 约定（PROJECT_CONTEXT 3.1）：</p>
 * <ul>
 *   <li>{@code email:code:{scene}:{email}} —— 验证码，TTL 5 分钟（app.email.code-expire-seconds）。
 *       <b>批次 6.0.6 · Minor 6</b>：Key 里加了 scene 维度（修前无 scene，
 *       注册场景取到的码可用于找回密码/换绑邮箱）；</li>
 *   <li>{@code email:limit:{email}} —— 发送限流，TTL 60 秒，命中返回 code=106；</li>
 *   <li>{@code email:fail:{email}} —— 失败计数，1 小时窗口累计，达 5 次锁定 30 分钟返回 code=107。
 *       <b>重新获取验证码时严禁删除该 Key</b>（防止通过刷新验证码重置失败计数）；
 *       计量与锁定<b>保持按邮箱维度</b>（不按 scene 拆分）：锁的是"这个邮箱被爆破"，
 *       按 scene 拆会让攻击者换场景拿到多份额度；</li>
 * </ul>
 *
 * <p>降级开关 {@code app.email.skip=true}（开发/答辩默认）时验证码不走 SMTP，
 * 直接在 {@link EmailCodeVO#getCode()} 返回并打印 INFO 日志。</p>
 */
public interface EmailCodeService {

    /** 注册场景。 */
    String SCENE_REGISTER = "REGISTER";

    /** 找回密码场景。 */
    String SCENE_RESET_PASSWORD = "RESET_PASSWORD";

    /** 换绑邮箱场景。 */
    String SCENE_BIND_EMAIL = "BIND_EMAIL";

    /** 未提供 / 无法识别的场景归一值（保证"取码 → 用码"仍能配对）。 */
    String SCENE_DEFAULT = "VERIFY";

    /**
     * 发送验证码。
     *
     * @param email 校园邮箱（后缀必须命中 app.email.campus-suffixes 允许列表）
     * @param scene 场景：REGISTER / RESET_PASSWORD / BIND_EMAIL
     * @return 验证码结果（skip=true 时含明文验证码）
     */
    EmailCodeVO send(String email, String scene);

    /**
     * 校验验证码。
     *
     * <p><b>scene 必须与取码时一致</b>（批次 6.0.6 · Minor 6）：不一致 → 缓存里读不到码 →
     * code=103。这样"注册场景的码"不能拿去重置密码。</p>
     *
     * <p>失败语义：邮箱验证码服务被锁定 → code=107；验证码错误或已过期 → code=103
     * （并累加 {@code email:fail}，累计达阈值即锁定邮箱验证码服务 30 分钟）。
     * 校验通过后删除 {@code email:code:{scene}:{email}}，但<b>不删除</b> {@code email:fail}。</p>
     *
     * @param email 校园邮箱
     * @param scene 场景（与取码时一致，大小写不敏感）
     * @param code  用户提交的验证码
     */
    void verify(String email, String scene, String code);
}
