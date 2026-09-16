package com.campus.market.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色要求注解（替代 @PreAuthorize，<b>严禁使用 @PreAuthorize</b>）。
 *
 * <p>由 {@link AuthInterceptor} 在强制认证路径上读取并校验；不满足返回 code=403。
 * 校验顺序：先 Token 有效性（401）→ 再角色（403）→ 最后业务层归属校验（203）。</p>
 *
 * <pre>
 * &#64;RequireRole(1)   // 仅管理员
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许的角色：0-学生，1-管理员。默认仅管理员。 */
    int[] value() default {1};
}
