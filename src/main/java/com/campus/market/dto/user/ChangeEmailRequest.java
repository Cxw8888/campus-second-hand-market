package com.campus.market.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 换绑邮箱请求（接口 1.9 POST /api/v1/user/change-email）。
 *
 * <p>需校验登录密码 + 新邮箱验证码；成功后 {@code user:token:version:{userId}} +1。</p>
 */
@Data
@Schema(description = "换绑邮箱请求")
public class ChangeEmailRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "新邮箱不能为空")
    @Email(message = "邮箱格式错误")
    @Schema(description = "新校园邮箱", example = "20210001@stu.edu.cn")
    private String newEmail;

    @NotBlank(message = "验证码不能为空")
    @Schema(description = "新邮箱验证码", example = "123456")
    private String emailCode;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "当前登录密码（二次确认）")
    private String password;
}
