package com.campus.market.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 注册请求（接口 1.1 POST /api/v1/auth/register）。
 *
 * <p>用户名即学号；密码需满足 8-20 位强密码规则（见 {@code PasswordValidator}）；
 * 邮箱必须为校园邮箱后缀允许列表内地址（后缀来自配置 {@code app.email.campus-suffixes}）。</p>
 */
@Data
@Schema(description = "注册请求")
public class RegisterRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "学号不能为空")
    @Size(max = 50, message = "学号长度不能超过50")
    @Schema(description = "学号/账号，最长 50 位", example = "20210001")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "密码：8-20 位，必须含字母、数字、至少 1 个特殊字符，且不在弱密码列表中")
    private String password;

    @Size(max = 50, message = "昵称长度不能超过50")
    @Schema(description = "昵称，可选，最长 50 位", example = "小明")
    private String nickname;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式错误")
    @Schema(description = "校园邮箱", example = "20210001@stu.edu.cn")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Schema(description = "邮箱验证码", example = "123456")
    private String emailCode;
}
