package com.campus.market.controller;

import com.campus.market.common.query.PageQuery;
import com.campus.market.common.result.PageResult;
import com.campus.market.common.result.Result;
import com.campus.market.service.NotificationService;
import com.campus.market.vo.NotificationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站内信接口（接口清单 6.1 - 6.4，统一前缀 {@code /api/v1/notification}，强制认证）。
 *
 * <p>所有接口均只操作当前登录用户的通知；read 接口必须校验归属，越权返回 code=203。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/notification")
@RequiredArgsConstructor
@Tag(name = "站内信", description = "通知列表 / 已读 / 未读数")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/list")
    @Operation(summary = "站内信列表", description = "按创建时间倒序；isRead 可选（true 已读 / false 未读）")
    public Result<PageResult<NotificationVO>> list(
            @Parameter(description = "是否已读过滤，不传表示全部")
            @RequestParam(value = "isRead", required = false) Boolean isRead,
            @Valid PageQuery query) {
        return Result.success(notificationService.list(isRead, query));
    }

    @PutMapping("/read/{id}")
    @Operation(summary = "标记单条已读", description = "必须校验 user_id = 当前登录用户，否则 203")
    public Result<Void> markRead(
            @Parameter(description = "通知ID") @PathVariable("id") Long id) {
        notificationService.markRead(id);
        return Result.success();
    }

    @PutMapping("/read-all")
    @Operation(summary = "全部标记已读", description = "返回实际更新行数")
    public Result<Map<String, Integer>> markAllRead() {
        int updated = notificationService.markAllRead();
        return Result.success(Map.of("updated", updated));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "未读数量", description = "前端 30 秒轮询")
    public Result<Map<String, Long>> unreadCount() {
        return Result.success(Map.of("count", notificationService.unreadCount()));
    }
}
