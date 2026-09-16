package com.campus.market.service;

import com.campus.market.common.result.PageResult;
import com.campus.market.dto.admin.AdminOrderQuery;
import com.campus.market.dto.admin.AdminProductQuery;
import com.campus.market.dto.admin.AdminUserQuery;
import com.campus.market.dto.admin.AuditLogQuery;
import com.campus.market.dto.admin.OrderUnfreezeRequest;
import com.campus.market.dto.admin.ProductAuditRequest;
import com.campus.market.vo.AdminUserVO;
import com.campus.market.vo.AuditLogVO;
import com.campus.market.vo.OrderVO;
import com.campus.market.vo.ProductListVO;

/**
 * 管理端服务：商品审核 / 强制下架 / 用户封禁解封 / 订单管理 / 审计日志查询。
 *
 * <p>所有方法调用方均需 `@RequireRole(1)`（普通用户 → 403），
 * 且所有写操作必须与审计日志<b>同一事务</b>写入 tb_audit_log。</p>
 */
public interface AdminService {

    /** 商品审核列表（默认查 status=3 待审核）。 */
    PageResult<ProductListVO> listAuditProducts(AdminProductQuery query);

    /** 商品审核：通过 3→1；不通过 3→0 并通知卖家。 */
    void auditProduct(Long id, ProductAuditRequest request);

    /** 强制下架违规商品（→0）。 */
    void forceOffline(Long id, String reason);

    /** 用户列表（关键字参数化 LIKE：学号/昵称/邮箱）。 */
    PageResult<AdminUserVO> listUsers(AdminUserQuery query);

    /**
     * 封禁用户。同一事务内完成：
     * ①status=1 ②在售商品下架 ③未完成订单冻结（0/1/2/6→5，同步库存回补）④写审计日志；
     * 事务提交后执行 user:token:version+1（失败重试 3 次，指数退避）。
     */
    void banUser(Long userId);

    /** 解封用户：status=0，并在事务提交后 version+1。 */
    void unbanUser(Long userId);

    /** 全量订单查询。 */
    PageResult<OrderVO> listOrders(AdminOrderQuery query);

    /** 已冻结订单处理：CANCEL→4（+库存回补）或 COMPLETE→3，严禁自动恢复原状态。 */
    void unfreezeOrder(Long id, OrderUnfreezeRequest request);

    /** 管理员强制退款（6/7→4）+ 库存回补 + 审计日志。 */
    void forceRefund(Long id, String reason);

    /** 审计日志查询。 */
    PageResult<AuditLogVO> listAuditLogs(AuditLogQuery query);
}
