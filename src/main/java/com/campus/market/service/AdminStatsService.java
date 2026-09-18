package com.campus.market.service;

import com.campus.market.vo.AdminOrderStatusVO;
import com.campus.market.vo.AdminOverviewVO;
import com.campus.market.vo.AdminProductCategoryVO;

import java.util.List;

/**
 * 管理端数据统计服务（批次 5.5.1）。
 *
 * <p>三个方法对应三个只读接口，全部走 {@code admin:stats:*} 短 TTL 缓存；
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
}
