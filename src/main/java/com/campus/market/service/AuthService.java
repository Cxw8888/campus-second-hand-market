package com.campus.market.service;

import com.campus.market.dto.auth.LoginRequest;
import com.campus.market.dto.auth.RegisterRequest;
import com.campus.market.dto.auth.ResetPasswordRequest;
import com.campus.market.vo.EmailCodeVO;
import com.campus.market.vo.LoginVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 认证服务（接口 1.1 - 1.5）。
 *
 * <p>安全约束（PROJECT_CONTEXT 3.1）：</p>
 * <ul>
 *   <li>登录流程<b>防状态探测</b>：先校验密码（失败 code=101），密码正确后再校验 status（封禁 code=205），
 *       严禁先查 status；</li>
 *   <li>登录防爆破：账号维度（5 次 / 5 分钟 → 锁 15 分钟）+ IP 维度（20 次 / 5 分钟 → 限 30 分钟），命中 code=104；</li>
 *   <li>Token 失效：找回密码成功后 {@code user:token:version:{userId}} +1，使该用户全部 Token 立即失效；</li>
 *   <li>退出登录：单 Token 黑名单 {@code jwt:blacklist:{token}}，TTL = Token 剩余有效期；</li>
 *   <li>日志中<b>绝对禁止</b>打印明文密码。</li>
 * </ul>
 */
public interface AuthService {

    /**
     * 注册（接口 1.1）。
     *
     * @param request 注册请求
     * @return 新用户ID
     */
    Long register(RegisterRequest request);

    /**
     * 登录（接口 1.2）。
     *
     * @param request     登录请求
     * @param httpRequest 原始请求（用于提取客户端 IP，做 IP 维度防爆破）
     * @return 登录结果（Token + 用户基础信息）
     */
    LoginVO login(LoginRequest request, HttpServletRequest httpRequest);

    /**
     * 退出登录（接口 1.3）：将当前 Token 写入黑名单。
     *
     * @param authorizationHeader 原始 Authorization 请求头（可带 Bearer 前缀）
     */
    void logout(String authorizationHeader);

    /**
     * 发送邮箱验证码（接口 1.4）。
     *
     * @param email 校园邮箱
     * @param scene REGISTER / RESET_PASSWORD / BIND_EMAIL
     * @return 验证码结果（降级模式下直接返回明文验证码）
     */
    EmailCodeVO sendEmailCode(String email, String scene);

    /**
     * 找回密码（接口 1.5）：校验验证码后重置密码并提升 Token 版本。
     *
     * @param request 找回密码请求
     */
    void resetPassword(ResetPasswordRequest request);
}
