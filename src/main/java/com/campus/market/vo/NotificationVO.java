package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内信 VO（接口 6.1 GET /api/v1/notification/list）。
 */
@Data
@Schema(description = "站内信通知")
public class NotificationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "通知ID")
    private Long id;

    @Schema(description = "通知类型：1-订单, 2-审核, 3-系统")
    private Integer type;

    @Schema(description = "业务类型：1-订单, 2-商品, 3-系统")
    private Integer bizType;

    @Schema(description = "业务ID（系统通知=0）")
    private Long bizId;

    @Schema(description = "通知内容")
    private String content;

    @Schema(description = "是否已读：0-未读, 1-已读")
    private Integer isRead;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
