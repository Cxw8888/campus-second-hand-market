package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端用户视图对象。
 */
@Data
@Schema(description = "管理端用户信息")
public class AdminUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "学号/账号")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像URL")
    private String avatar;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "校园邮箱")
    private String email;

    @Schema(description = "角色：0-学生, 1-管理员")
    private Integer role;

    @Schema(description = "状态：0-正常, 1-封禁")
    private Integer status;

    @Schema(description = "注册时间")
    private LocalDateTime createTime;
}
