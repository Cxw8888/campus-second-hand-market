package com.campus.market.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 更新个人资料请求（接口 1.7 PUT /api/v1/user/profile）。
 *
 * <p>非全量更新语义：字段为 {@code null} 时保持原值不变。</p>
 */
@Data
@Schema(description = "更新个人资料请求")
public class UpdateProfileRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Size(max = 50, message = "昵称长度不能超过50")
    @Schema(description = "昵称，最长 50 位")
    private String nickname;

    @Size(max = 20, message = "手机号长度不能超过20")
    @Schema(description = "手机号，最长 20 位")
    private String phone;

    @Size(max = 255, message = "头像URL长度不能超过255")
    @Schema(description = "头像 URL，最长 255 位")
    private String avatar;
}
