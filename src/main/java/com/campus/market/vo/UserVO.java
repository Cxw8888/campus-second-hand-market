package com.campus.market.vo;

import com.campus.market.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户信息 VO（接口 1.6 GET /api/v1/user/profile）。
 *
 * <p><b>绝不包含 password 字段</b>：本 VO 是用户信息对外输出的唯一载体。</p>
 */
@Data
@Schema(description = "用户信息")
public class UserVO implements Serializable {

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

    /**
     * Entity → VO 转换（显式逐字段赋值，确保 password 等敏感字段永不外泄）。
     */
    public static UserVO from(User user) {
        if (user == null) {
            return null;
        }
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
