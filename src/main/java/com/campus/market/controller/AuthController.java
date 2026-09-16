package com.campus.market.controller;

import com.campus.market.common.result.Result;
import com.campus.market.dto.auth.LoginRequest;
import com.campus.market.dto.auth.RegisterRequest;
import com.campus.market.dto.auth.ResetPasswordRequest;
import com.campus.market.service.AuthService;
import com.campus.market.vo.EmailCodeVO;
import com.campus.market.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（接口清单 1.1 - 1.5，统一前缀 {@code /api/v1/auth}）。
 *
 * <p>业务接口一律 HTTP 200，业务结果由 {@code Result.code} 区分。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "注册 / 登录 / 退出 / 邮箱验证码 / 找回密码")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "注册", description = "用户名(学号) + 强密码 + 校园邮箱验证码；返回新用户ID")
    public Result<Map<String, Long>> register(@Valid @RequestBody RegisterRequest request) {
        Long userId = authService.register(request);
        return Result.success(Map.of("userId", userId));
    }

    @PostMapping("/login")
    @Operation(summary = "登录", description = "账号 + IP 双维度防爆破；封禁用户返回 205")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return Result.success(authService.login(request, httpRequest));
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录", description = "单 Token 黑名单，TTL = Token 剩余有效期")
    public Result<Void> logout(
            @Parameter(description = "Bearer Token", example = "Bearer eyJhbGciOi...")
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(authorization);
        return Result.success();
    }

    @GetMapping("/email-code")
    @Operation(summary = "获取邮箱验证码",
            description = "scene: REGISTER / RESET_PASSWORD / BIND_EMAIL；email.skip=true 时验证码直接返回")
    public Result<EmailCodeVO> sendEmailCode(
            @Parameter(description = "校园邮箱", example = "20210001@stu.edu.cn")
            @RequestParam("email") String email,
            @Parameter(description = "场景：REGISTER / RESET_PASSWORD / BIND_EMAIL", example = "REGISTER")
            @RequestParam("scene") String scene) {
        return Result.success(authService.sendEmailCode(email, scene));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "找回密码", description = "校验邮箱验证码后重置密码；成功后该用户全部 Token 失效")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return Result.success();
    }
}
