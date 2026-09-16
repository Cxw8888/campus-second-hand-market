package com.campus.market.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录请求（接口 1.2 POST /api/v1/auth/login）。
 */
@Data
@Schema(description = "登录请求")
public class LoginRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "学号不能为空")
    @Schema(description = "学号/账号", example = "20210001")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "密码")
    private String password;
}
