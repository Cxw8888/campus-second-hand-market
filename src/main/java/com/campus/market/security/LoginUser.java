package com.campus.market.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 登录态主体：由拦截器解析 Token 后注入 {@link UserContext}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;

    /** 0-学生, 1-管理员。 */
    private Integer role;

    /** Token 版本（用于与 Redis user:token:version:{userId} 比对）。 */
    private Long version;

    public boolean isAdmin() {
        return role != null && role == 1;
    }
}
