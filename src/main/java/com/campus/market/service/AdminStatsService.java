package com.campus.market.service;

import com.campus.market.vo.AdminHotProductVO;
import com.campus.market.vo.AdminOrderStatusVO;
import com.campus.market.vo.AdminOverviewVO;
import com.campus.market.vo.AdminProductCategoryVO;
import com.campus.market.vo.AdminTrendVO;

import java.util.List;

/**
 * 管理端数据统计服务（批次 5.5.1 概览/分布 + 5.5.2 趋势/热门榜）。
 *
 * <p>五个方法对应五个只读接口，全部走 {@code admin:stats:*} 短 TTL 缓存；
 * 聚合查询不涉及写路径，因此<b>不需要分布式锁</b>（与搜索缓存同理）。</p>
 */
public interface AdminStatsService {

    /**
     * 概览卡片：8 个数字（用户 / 商品 / 订单 × 总数&今日新增 + GMV 累计&今日）。
     *
     * <p>无数据时各字段返回 <b>0</b>（Long 0 / BigDecimal 0），绝不返回 null。</p>
     */
    AdminOverviewVO overview();

    /**
     * 订单状态分布：恒定 8 条（0~7 全量补齐，无数据的状态 count=0）。
     *
     * <p>不含 label —— 状态文案的唯一事实来源是前端 constants.js 的 ORDER_STATUS_MAP。</p>
     */
    List<AdminOrderStatusVO> orderStatusDistribution();

    /**
     * 商品分类分布：以分类为主表，空分类 count=0 也会出现；孤儿商品（分类已逻辑删除）
     * 汇总为最后一条 {@code categoryId=null}。
     */
    List<AdminProductCategoryVO> productCategoryDistribution();

    /**
     * 趋势数据：同一时间轴上返回「每日订单量 / 每日商品发布 / 每日用户注册」三组计数。
     *
     * <p>窗口 = 今天（GMT+8）往前推 {@code days-1} 天到today 共 {@code days} 天，
     * 缺失的日期<b>补 0</b>（三个数组长度恒等于 days，且与 dates 一一对应）。</p>
     *
     * @param days 统计天数，<b>白名单只允许 7 或 30</b>；null 视为 7；
     *             其它值抛 {@code code=100}（PARAM_ERROR）
     */
    AdminTrendVO trend(Integer days);

    /**
     * 热门商品榜：近 {@code days} 天按<b>订单数</b>降序的前 {@code limit} 个商品。
     *
     * @param days  统计窗口天数，白名单只允许 7 或 30；null 视为 7；其它值抛 {@code code=100}
     * @param limit 返回条数，白名单 1~20；null 视为 10；其它值抛 {@code code=100}
     */
    AdminHotProductVO hotProducts(Integer days, Integer limit);
}
