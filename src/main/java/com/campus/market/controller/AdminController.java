package com.campus.market.controller;

import com.campus.market.common.result.PageResult;
import com.campus.market.common.result.Result;
import com.campus.market.dto.admin.AdminOrderQuery;
import com.campus.market.dto.admin.AdminProductQuery;
import com.campus.market.dto.admin.AdminUserQuery;
import com.campus.market.dto.admin.AuditLogQuery;
import com.campus.market.dto.admin.OrderUnfreezeRequest;
import com.campus.market.dto.admin.ProductAuditRequest;
import com.campus.market.security.RequireRole;
import com.campus.market.service.AdminService;
import com.campus.market.vo.AdminUserVO;
import com.campus.market.vo.AuditLogVO;
import com.campus.market.vo.OrderVO;
import com.campus.market.vo.ProductListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端接口。
 *
 * <p>全部接口要求 {@code @RequireRole(1)}（普通用户 → code=403），
 * 且所有写操作与业务同一事务写入 tb_audit_log。</p>
 */
@Tag(name = "管理端", description = "商品审核、封禁、强制下架、订单管理、审计日志")
@RestController
@RequestMapping("/api/v1/admin")
@RequireRole(1)
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    // ------------------------------------------------ 商品

    @Operation(summary = "商品审核列表", description = "默认查询 status=3 待审核商品")
    @GetMapping("/product/audit/list")
    public Result<PageResult<ProductListVO>> auditList(@Valid AdminProductQuery query) {
        return Result.success(adminService.listAuditProducts(query));
    }

    @Operation(summary = "商品审核", description = "通过 3→1；不通过 3→0 并通知卖家；写审计日志")
    @PutMapping("/product/audit/{id}")
    public Result<Void> audit(@PathVariable Long id, @Valid @RequestBody ProductAuditRequest request) {
        adminService.auditProduct(id, request);
        return Result.success();
    }

    @Operation(summary = "强制下架商品", description = "管理员对违规商品强制下架（status=0）")
    @PutMapping("/product/force-offline/{id}")
    public Result<Void> forceOffline(@PathVariable Long id,
                                     @RequestParam(required = false) String reason) {
        adminService.forceOffline(id, reason);
        return Result.success();
    }

    // ------------------------------------------------ 用户

    @Operation(summary = "用户列表")
    @GetMapping("/user/list")
    public Result<PageResult<AdminUserVO>> userList(@Valid AdminUserQuery query) {
        return Result.success(adminService.listUsers(query));
    }

    @Operation(summary = "封禁用户",
            description = "同事务：①status=1 ②在售商品下架 ③未完成订单冻结(回补库存) ④审计；提交后 version+1（重试3次）")
    @PutMapping("/user/ban/{id}")
    public Result<Void> banUser(@PathVariable Long id) {
        adminService.banUser(id);
        return Result.success();
    }

    @Operation(summary = "解封用户", description = "status=0 + version+1；已冻结订单保持 5 待线下处理")
    @PutMapping("/user/unban/{id}")
    public Result<Void> unbanUser(@PathVariable Long id) {
        adminService.unbanUser(id);
        return Result.success();
    }

    // ------------------------------------------------ 订单

    @Operation(summary = "全量订单查询")
    @GetMapping("/order/list")
    public Result<PageResult<OrderVO>> orderList(@Valid AdminOrderQuery query) {
        return Result.success(adminService.listOrders(query));
    }

    @Operation(summary = "冻结订单处理", description = "5→4（CANCEL，同步回补库存）或 5→3（COMPLETE，线下完成）")
    @PutMapping("/order/unfreeze/{id}")
    public Result<Void> unfreeze(@PathVariable Long id, @Valid @RequestBody OrderUnfreezeRequest request) {
        adminService.unfreezeOrder(id, request);
        return Result.success();
    }

    @Operation(summary = "管理员强制退款", description = "6/7→4 + 库存回补 + 审计日志")
    @PutMapping("/order/force-refund/{id}")
    public Result<Void> forceRefund(@PathVariable Long id,
                                    @RequestParam(required = false) String reason) {
        adminService.forceRefund(id, reason);
        return Result.success();
    }

    // ------------------------------------------------ 审计日志

    @Operation(summary = "审计日志查询", description = "仅管理员可访问")
    @GetMapping("/audit-log/list")
    public Result<PageResult<AuditLogVO>> auditLogList(@Valid AuditLogQuery query) {
        return Result.success(adminService.listAuditLogs(query));
    }
}
