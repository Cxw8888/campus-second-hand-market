package com.campus.market.controller;

import com.campus.market.common.result.Result;
import com.campus.market.dto.user.ChangeEmailRequest;
import com.campus.market.dto.user.ChangePasswordRequest;
import com.campus.market.dto.user.UpdateProfileRequest;
import com.campus.market.service.UserService;
import com.campus.market.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（接口清单 1.6 - 1.9，统一前缀 {@code /api/v1/user}，强制认证）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
@Tag(name = "用户", description = "个人资料 / 修改密码 / 换绑邮箱")
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    @Operation(summary = "查询个人资料", description = "返回 UserVO，password 永不返回")
    public Result<UserVO> getProfile() {
        return Result.success(userService.getProfile());
    }

    @PutMapping("/profile")
    @Operation(summary = "更新个人资料", description = "非全量更新：仅更新传入的字段")
    public Result<Void> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        userService.updateProfile(request);
        return Result.success();
    }

    @PutMapping("/password")
    @Operation(summary = "修改密码", description = "校验旧密码；新密码不得与旧密码相同；成功后 version+1")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(request);
        return Result.success();
    }

    @PostMapping("/change-email")
    @Operation(summary = "换绑邮箱", description = "校验登录密码 + 新邮箱验证码；成功后 version+1")
    public Result<Void> changeEmail(@Valid @RequestBody ChangeEmailRequest request) {
        userService.changeEmail(request);
        return Result.success();
    }
}
