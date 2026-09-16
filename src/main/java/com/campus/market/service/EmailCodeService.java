package com.campus.market.service;

import com.campus.market.vo.EmailCodeVO;

/**
 * 邮箱验证码服务（接口 1.4 + 注册 / 找回密码 / 换绑邮箱共用）。
 *
 * <p>Redis Key 约定（PROJECT_CONTEXT 3.1）：</p>
 * <ul>
 *   <li>{@code email:code:{email}} —— 验证码，TTL 5 分钟（app.email.code-expire-seconds）；</li>
 *   <li>{@code email:limit:{email}} —— 发送限流，TTL 60 秒，命中返回 code=106；</li>
 *   <li>{@code email:fail:{email}} —— 失败计数，1 小时窗口累计，达 5 次锁定 30 分钟返回 code=107。
 *       <b>重新获取验证码时严禁删除该 Key</b>（防止通过刷新验证码重置失败计数）；</li>
 * </ul>
 *
 * <p>降级开关 {@code app.email.skip=true}（开发/答辩默认）时验证码不走 SMTP，
 * 直接在 {@link EmailCodeVO#getCode()} 返回并打印 INFO 日志。</p>
 */
public interface EmailCodeService {

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
     * <p>失败语义：邮箱验证码服务被锁定 → code=107；验证码错误或已过期 → code=103
     * （并累加 {@code email:fail}，累计达阈值即锁定邮箱验证码服务 30 分钟）。
     * 校验通过后删除 {@code email:code}，但<b>不删除</b> {@code email:fail}。</p>
     *
     * @param email 校园邮箱
     * @param code  用户提交的验证码
     */
    void verify(String email, String code);
}
