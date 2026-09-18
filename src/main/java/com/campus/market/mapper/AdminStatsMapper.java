package com.campus.market.mapper;

import com.campus.market.vo.AdminOrderStatusVO;
import com.campus.market.vo.AdminProductCategoryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端统计聚合 Mapper（批次 5.5.1）。
 *
 * <h3>为什么单开一个 Mapper，而不是往 ProductMapper / OrderMapper / UserMapper 里塞方法</h3>
 * <p>统计查询是<b>跨三张表的只读聚合</b>，语义上不属于任何一个实体域。单开一个 Mapper 有两个好处：
 * ① 不触碰已被大量业务依赖的既有 Mapper（回归面为零）；
 * ② 统计 SQL 的优化（加索引、改口径）可以整体一起动，不必在三个文件里翻找。</p>
 *
 * <h3>⚠️ 三条必须显式写死的约定</h3>
 * <ol>
 *   <li><b>{@code is_deleted = 0} 必须手写</b>：MyBatis-Plus 的逻辑删除只会注入到它自己生成的
 *       SQL 里，<b>自定义 {@code @Select} 不会自动加</b>。漏掉这一条，统计出来的数字会把
 *       逻辑删除的数据一起算进去（表现为"后台数字比列表多"）。</li>
 *   <li><b>不引用 {@code AdminProductQuery}</b>：它的 {@code status} 字段默认值是 3（待审核），
 *       ServiceImpl 里又是 {@code eq(query.getStatus() != null, ...)} —— 也就是说"省略参数"
 *       等于"只查待审核"而不是"不过滤"（5.1 已记录该坑）。统计要的是<b>全部状态</b>，
 *       所以这里一律手写 {@code WHERE}，绝不复用该 DTO。</li>
 *   <li><b>时间参数是 {@code LocalDateTime}</b>：由 Service 用
 *       {@code LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay()} 计算出来传进来。
 *       驱动配置为 {@code serverTimezone=GMT+8}，LocalDateTime 以字面量下发，
 *       与库里按 GMT+8 写入的 DATETIME 直接可比，不做任何时区换算。</li>
 * </ol>
 *
 * <p>全部方法都是<b>只读聚合</b>，不涉及写路径，因此无需分布式锁（与搜索缓存同理）。</p>
 */
@Mapper
public interface AdminStatsMapper {

    // ================================================================ 概览 · 用户

    /** 用户总数：tb_user 里 is_deleted=0 的全部用户（含封禁用户）。 */
    @Select("SELECT COUNT(*) FROM tb_user WHERE is_deleted = 0")
    Long countUsers();

    /** 今日新增用户：create_time >= 今天 00:00:00（GMT+8）。 */
    @Select("SELECT COUNT(*) FROM tb_user WHERE is_deleted = 0 AND create_time >= #{start}")
    Long countUsersCreatedSince(@Param("start") LocalDateTime start);

    // ================================================================ 概览 · 商品

    /**
     * 商品总数：tb_product 里 is_deleted=0 的全部商品，<b>不分状态</b>
     * （0-下架 / 1-在售 / 2-售罄 / 3-待审核 全算）。
     */
    @Select("SELECT COUNT(*) FROM tb_product WHERE is_deleted = 0")
    Long countProducts();

    /** 今日新增商品：create_time >= 今天 00:00:00（GMT+8）。 */
    @Select("SELECT COUNT(*) FROM tb_product WHERE is_deleted = 0 AND create_time >= #{start}")
    Long countProductsCreatedSince(@Param("start") LocalDateTime start);

    // ================================================================ 概览 · 订单与 GMV

    /** 订单总数：tb_order 里 is_deleted=0 的全部订单，<b>不分状态</b>（已取消/已冻结也算）。 */
    @Select("SELECT COUNT(*) FROM tb_order WHERE is_deleted = 0")
    Long countOrders();

    /** 今日新增订单：create_time >= 今天 00:00:00（GMT+8）。 */
    @Select("SELECT COUNT(*) FROM tb_order WHERE is_deleted = 0 AND create_time >= #{start}")
    Long countOrdersCreatedSince(@Param("start") LocalDateTime start);

    /**
     * 累计 GMV：仅统计 status=3（已完成）订单的 SUM(amount)。
     *
     * <p>{@code COALESCE(..., 0)} 是必需的：一条已完成订单都没有时 SUM 返回 NULL，
     * 落到 Java 就是 null，前端会显示成空而不是 0.00。</p>
     *
     * <p>返回类型必须是 {@link BigDecimal}：金额走 Double 会有二进制浮点误差。</p>
     */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM tb_order WHERE is_deleted = 0 AND status = 3")
    BigDecimal sumFinishedAmount();

    /**
     * 今日 GMV：今天<b>完成</b>的已完成订单 SUM(amount)。
     *
     * <p>口径说明（与需求给的"今天完成的订单"一致）：按 {@code finish_time} 过滤，
     * <b>不是</b> {@code create_time}。理由是这两个数回答的是不同的问题 ——
     * "今日新增订单数"看下单时间，"今日 GMV"看<b>成交落袋</b>时间。
     * 一笔昨天下的单今天确认收货，它今天的成交额理应算进今日 GMV，否则两个数字会互相矛盾
     * （会出现"今天一张单都没有，GMV 却有 300"的诡异画面）。</p>
     *
     * <p>状态机侧已核实 {@code finish_time} 一定会被写入：走 3 状态的所有 SQL
     * （receiveByMail / receiveByFace / finishFaceToFace / unfreezeToComplete /
     * autoConfirmReceive）都带 {@code finish_time = NOW()}。</p>
     */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM tb_order"
            + " WHERE is_deleted = 0 AND status = 3 AND finish_time >= #{start}")
    BigDecimal sumFinishedAmountSince(@Param("start") LocalDateTime start);

    // ================================================================ 分布

    /**
     * 订单状态分布：按 status 分组计数（未删除订单）。
     *
     * <p>只返回<b>库里真实出现过的状态</b>；"凑满 8 条"由 Service 补齐
     * （不在这里硬编码 8 个状态码，SQL 层保持"忠实反映数据"）。</p>
     */
    @Select("SELECT status, COUNT(*) AS `count` FROM tb_order WHERE is_deleted = 0 GROUP BY status")
    List<AdminOrderStatusVO> countOrdersByStatus();

    /**
     * 商品分类分布：以分类为主表 LEFT JOIN 商品，<b>没有商品的分类也会返回 count=0</b>。
     *
     * <p>用 {@code COUNT(p.id)} 而不是 {@code COUNT(*)}：LEFT JOIN 下没匹配到商品时
     * 右表是一行全 NULL，{@code COUNT(*)} 会把这一行算成 1，于是空分类显示成"有 1 件"。</p>
     */
    @Select("SELECT c.id AS categoryId, c.name AS categoryName, COUNT(p.id) AS `count`"
            + " FROM tb_category c"
            + " LEFT JOIN tb_product p ON p.category_id = c.id AND p.is_deleted = 0"
            + " WHERE c.is_deleted = 0"
            + " GROUP BY c.id, c.name"
            + " ORDER BY `count` DESC, c.id ASC")
    List<AdminProductCategoryVO> countProductsByCategory();

    /**
     * 孤儿商品数：分类已被逻辑删除（或 category_id 为空）的商品。
     *
     * <p>5.4.4 起分类删除是逻辑删除且"让位改名"，正常路径不会留下孤儿，
     * 但数据是手工造的、历史迁移过的，所以统计页必须能自证"有一批商品没归到任何分类"，
     * 而不是让这批商品在图上凭空消失（分布图各值之和 ≠ 商品总数时最难排查）。</p>
     */
    @Select("SELECT COUNT(*) FROM tb_product p WHERE p.is_deleted = 0"
            + " AND NOT EXISTS (SELECT 1 FROM tb_category c WHERE c.id = p.category_id AND c.is_deleted = 0)")
    Long countProductsWithoutCategory();
}
