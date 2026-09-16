package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审计日志视图对象。
 */
@Data
@Schema(description = "审计日志")
public class AuditLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "日志ID")
    private Long id;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人名称")
    private String operatorName;

    @Schema(description = "操作类型")
    private String operationType;

    @Schema(description = "目标类型")
    private String targetType;

    @Schema(description = "目标ID")
    private Long targetId;

    @Schema(description = "结果：1-成功, 0-失败")
    private Integer result;

    @Schema(description = "详情")
    private String detail;

    @Schema(description = "操作IP")
    private String ip;

    @Schema(description = "操作时间")
    private LocalDateTime createTime;
}
