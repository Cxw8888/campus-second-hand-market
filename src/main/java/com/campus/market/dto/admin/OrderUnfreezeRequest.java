package com.campus.market.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serializable;

/**
 * 已冻结订单的解冻处理请求。
 *
 * <p>解冻后订单保持 status=5 待线下处理前的终态选择：CANCEL → 4-已取消（同步库存回补）；
 * COMPLETE → 3-已完成（管理员线下完成）。<b>严禁自动恢复原状态</b>。</p>
 */
@Data
@Schema(description = "解冻订单处理请求")
public class OrderUnfreezeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "处理方式不能为空")
    @Pattern(regexp = "CANCEL|COMPLETE", message = "处理方式只能为 CANCEL 或 COMPLETE")
    @Schema(description = "处理方式：CANCEL-转已取消(4，回补库存)，COMPLETE-线下完成(3)")
    private String target;
}
