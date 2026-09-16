package com.campus.market.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 找回密码请求（接口 1.5 POST /api/v1/auth/reset-password）。
 *
 * <p>成功后 {@code user:token:version:{userId}} +1，失效该用户全部已签发 Token。</p>
 */
@Data
@Schema(description = "找回密码请求")
public class ResetPasswordRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式错误")
    @Schema(description = "校园邮箱", example = "20210001@stu.edu.cn")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Schema(description = "邮箱验证码", example = "123456")
    private String emailCode;

    @NotBlank(message = "新密码不能为空")
    @Schema(description = "新密码：8-20 位强密码")
    private String newPassword;
}
