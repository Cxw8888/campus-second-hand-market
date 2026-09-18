package com.campus.market.controller;

import com.campus.market.common.result.Result;
import com.campus.market.security.RequireRole;
import com.campus.market.service.AdminStatsService;
import com.campus.market.vo.AdminHotProductVO;
import com.campus.market.vo.AdminOrderStatusVO;
import com.campus.market.vo.AdminOverviewVO;
import com.campus.market.vo.AdminProductCategoryVO;
import com.campus.market.vo.AdminTrendVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端数据统计接口（批次 5.5.1 概览/分布 + 5.5.2 趋势/热门榜）。
 *
 * <p>权限写法<b>照抄</b>既有的两个管理端 Controller：类级 {@code @RequireRole(1)}
 * （{@code AdminController.java:38}、{@code AdminCategoryController.java:35}），
 * <b>严禁</b>使用 {@code @PreAuthorize}（{@code RequireRole} 的 javadoc 已明确禁止）。
 * 普通用户访问 → HTTP 200 + {@code code=403}；未登录 → HTTP 401。</p>
 *
 * <p>五个接口全部<b>只读</b>：因此没有事务、没有分布式锁、<b>不写审计日志</b>
 * （审计日志记录的是"管理动作"，看统计不是动作）。</p>
 *
 * <p>带参数的接口只声明 {@code @RequestParam(defaultValue=...)} 给默认值，
 * <b>白名单校验在 Service</b>（7/30 与 1~20），失败即 {@code code=100} ——
 * 理由见 {@code AdminStatsServiceImpl#requireAllowedDays} 的注释。</p>
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

    /**
     * 趋势数据（7 / 30 天，一次返回 3 组数据）。
     */
    @Operation(summary = "趋势数据（近 7 / 30 天）",
            description = "同一时间轴上的三条序列：每日订单量 / 每日商品发布 / 每日用户注册；"
                    + "日期缺口补 0，三个数组长度恒等于 days；days 白名单 7|30，其它值 code=100")
    @GetMapping("/trend")
    public Result<AdminTrendVO> trend(@RequestParam(defaultValue = "7") Integer days) {
        return Result.success(adminStatsService.trend(days));
    }

    /**
     * 热门商品榜（近 N 天按订单数）。
     */
    @Operation(summary = "热门商品榜（近 N 天按订单数）",
            description = "按窗口内订单数降序返回前 limit 个商品；标题取订单快照中最新一单的标题，"
                    + "商品已删除也能上榜（分类名为 null 时前端显示「—」）；"
                    + "days 白名单 7|30、limit 白名单 1~20，越界 code=100")
    @GetMapping("/hot-products")
    public Result<AdminHotProductVO> hotProducts(@RequestParam(defaultValue = "7") Integer days,
                                                 @RequestParam(defaultValue = "10") Integer limit) {
        return Result.success(adminStatsService.hotProducts(days, limit));
    }
}
