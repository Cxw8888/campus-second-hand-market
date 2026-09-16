package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录响应 VO（接口 1.2 POST /api/v1/auth/login）。
 */
@Data
@Schema(description = "登录结果")
public class LoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "JWT Token")
    private String token;

    @Schema(description = "Token 类型，固定 Bearer")
    private String tokenType = "Bearer";

    @Schema(description = "Token 有效期（秒）")
    private Long expiresIn;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "学号/账号")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像URL")
    private String avatar;

    @Schema(description = "角色：0-学生, 1-管理员")
    private Integer role;
}
