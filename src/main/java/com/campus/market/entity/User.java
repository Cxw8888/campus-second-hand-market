package com.campus.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.market.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户实体，对应表 tb_user。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "tb_user", autoResultMap = true)
public class User extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 学号/账号 */
    @TableField("username")
    private String username;

    /** BCrypt 加密密码（仅允许写入，序列化时永不返回） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @TableField("password")
    private String password;

    /** 昵称 */
    @TableField("nickname")
    private String nickname;

    /** 头像 URL */
    @TableField("avatar")
    private String avatar;

    /** 手机号 */
    @TableField("phone")
    private String phone;

    /** 校园邮箱 */
    @TableField("email")
    private String email;

    /** 角色：0-学生, 1-管理员 */
    @TableField("role")
    private Integer role;

    /** 状态：0-正常, 1-封禁 */
    @TableField("status")
    private Integer status;
}
