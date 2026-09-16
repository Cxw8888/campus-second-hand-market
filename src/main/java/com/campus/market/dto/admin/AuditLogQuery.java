package com.campus.market.dto.admin;

import com.campus.market.common.query.PageQuery;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 审计日志查询参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "审计日志查询参数")
public class AuditLogQuery extends PageQuery {

    private static final long serialVersionUID = 1L;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作类型：APPROVE_PRODUCT / REJECT_PRODUCT / BAN_USER / UNBAN_USER / FORCE_OFFLINE / UNFREEZE_ORDER / COMPLETE_ORDER / CREATE_CATEGORY / UPDATE_CATEGORY / DELETE_CATEGORY / REFUND_ORDER")
    private String operationType;

    @Schema(description = "开始时间 yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @Schema(description = "结束时间 yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
