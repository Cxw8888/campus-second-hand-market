package com.campus.market.dto.admin;

import com.campus.market.common.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端用户查询参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理端用户查询参数")
public class AdminUserQuery extends PageQuery {

    private static final long serialVersionUID = 1L;

    @Schema(description = "关键字（学号 / 昵称 / 邮箱 模糊匹配，参数化 LIKE）")
    private String keyword;

    @Schema(description = "状态：0-正常, 1-封禁")
    private Integer status;
}
