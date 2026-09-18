package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 邮箱验证码响应 VO（接口 1.4 GET /api/v1/auth/email-code）。
 *
 * <p>验证码是否出现在 {@link #code} 字段，取决于 <b>降级开关 + profile</b> 两个条件
 * （批次 6.0.1 安全加固后的规则，比原来"skip=true 就回显"更严）：</p>
 * <ul>
 *   <li>{@code skip=false}（默认 / 生产）→ {@code code} 为 {@code null}，验证码只走邮件；</li>
 *   <li>{@code skip=true} 且 <b>dev profile</b> → {@code code} 回显（本地开发 / 答辩演示用）；</li>
 *   <li>{@code skip=true} 且非 dev → {@code code} 仍为 {@code null}，验证码只写后端日志
 *       （因为本接口是公开路径，回显等于"任何人可拿别人邮箱的验证码"）；</li>
 *   <li>{@code skip=true} 且 prod → 应用<b>启动即失败</b>（{@code EmailCodeServiceImpl} 的启动断言），
 *       所以这一组合在运行时不可达。</li>
 * </ul>
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
