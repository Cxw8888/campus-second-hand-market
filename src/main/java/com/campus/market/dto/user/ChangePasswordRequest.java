package com.campus.market.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 修改密码请求（接口 1.8 PUT /api/v1/user/password）。
 *
 * <p>校验旧密码，新密码不得与旧密码相同；成功后 {@code user:token:version:{userId}} +1。</p>
 */
@Data
@Schema(description = "修改密码请求")
public class ChangePasswordRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "旧密码不能为空")
    @Schema(description = "旧密码")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Schema(description = "新密码：8-20 位强密码，且不得与旧密码相同")
    private String newPassword;
}
