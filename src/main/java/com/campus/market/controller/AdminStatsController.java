package com.campus.market.controller;

import com.campus.market.common.result.Result;
import com.campus.market.security.RequireRole;
import com.campus.market.service.AdminStatsService;
import com.campus.market.vo.AdminOrderStatusVO;
import com.campus.market.vo.AdminOverviewVO;
import com.campus.market.vo.AdminProductCategoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端数据统计接口（批次 5.5.1）。
 *
 * <p>权限写法<b>照抄</b>既有的两个管理端 Controller：类级 {@code @RequireRole(1)}
 * （{@code AdminController.java:38}、{@code AdminCategoryController.java:35}），
 * <b>严禁</b>使用 {@code @PreAuthorize}（{@code RequireRole} 的 javadoc 已明确禁止）。
 * 普通用户访问 → HTTP 200 + {@code code=403}；未登录 → HTTP 401。</p>
 *
 * <p>三个接口全部<b>只读</b>：因此没有事务、没有分布式锁、<b>不写审计日志</b>
 * （审计日志记录的是"管理动作"，看统计不是动作）。</p>
 */
@Tag(name = "管理端-数据统计", description = "概览卡片与分布图（只读，结果缓存 60 秒）")
@RestController
@RequestMapping("/api/v1/admin/stats")
@RequireRole(1)
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    /**
     * 概览卡片：8 个数字一次返回，前端不必发 8 个请求。
     */
    @Operation(summary = "概览卡片（8 个数字）",
            description = "用户/商品/订单的总数与今日新增 + GMV 累计与今日；今日口径为 GMT+8 当天 00:00:00 起")
    @GetMapping("/overview")
    public Result<AdminOverviewVO> overview() {
        return Result.success(adminStatsService.overview());
    }

    /**
     * 订单状态分布（恒定 8 条，label 由前端字典提供）。
     */
    @Operation(summary = "订单状态分布",
            description = "恒定返回 8 条（0~7 全量补齐，无数据 count=0）；不含 label，文案由前端 constants.js 提供")
    @GetMapping("/order-status")
    public Result<List<AdminOrderStatusVO>> orderStatus() {
        return Result.success(adminStatsService.orderStatusDistribution());
    }

    /**
     * 商品分类分布（含空分类与孤儿商品）。
     */
    @Operation(summary = "商品分类分布",
            description = "以分类为主表，空分类 count=0 也会返回；分类已逻辑删除的孤儿商品汇总为 categoryId 缺失的最后一条")
    @GetMapping("/product-category")
    public Result<List<AdminProductCategoryVO>> productCategory() {
        return Result.success(adminStatsService.productCategoryDistribution());
    }
}
