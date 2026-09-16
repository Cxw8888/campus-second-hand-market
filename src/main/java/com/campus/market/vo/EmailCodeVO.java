package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 邮箱验证码响应 VO（接口 1.4 GET /api/v1/auth/email-code）。
 *
 * <p>毕设降级开关 {@code app.email.skip=true} 时，{@code skip=true} 且 {@code code} 直接返回；
 * 生产环境 {@code skip=false} 时 {@code code} 为 {@code null}，验证码仅通过邮件下发。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "邮箱验证码结果")
public class EmailCodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "是否为降级模式（true 表示验证码不走 SMTP，直接在 code 字段返回）")
    private boolean skip;

    @Schema(description = "验证码，仅 skip=true 时有值")
    private String code;

    @Schema(description = "验证码有效期（秒）")
    private long expireSeconds;
}
