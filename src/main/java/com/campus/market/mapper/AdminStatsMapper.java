package com.campus.market.mapper;

import com.campus.market.dto.admin.DailyCount;
import com.campus.market.vo.AdminOrderStatusVO;
import com.campus.market.vo.AdminProductCategoryVO;
import com.campus.market.vo.HotProductItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端统计聚合 Mapper（批次 5.5.1 概览/分布 + 5.5.2 趋势/热门榜）。
 *
 * <p>共 14 条只读聚合 SQL：概览 8 条、分布 2 条、趋势 3 条（订单/商品/用户各一条）、
 * 热门榜 1 条。全部是同一种风格：手写 {@code @Select}、显式 {@code is_deleted = 0}、
 * 时间窗口由 Java 层按 GMT+8 算好后以 {@code LocalDateTime} 传入。</p>
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

    // ================================================================ 趋势（批次 5.5.2）

    /**
     * 每日新增订单数（近 N 天窗口内，按天分组）。
     *
     * <h3>时区口径（照抄 5.5.1，不发明 CONVERT_TZ）</h3>
     * <p>窗口边界仍是<b>Java 层按 Asia/Shanghai 算好</b>再以 {@code LocalDateTime} 传进来，
     * 与 5.5.1 的"今日"完全同一套做法（{@code create_time >= #{start}}）。
     * 这里唯一的 SQL 时间函数是 {@code DATE(create_time)} —— <b>只用于分组</b>，
     * 不做任何时区换算：JDBC URL 已声明 {@code serverTimezone=GMT+8}，
     * 库里的 DATETIME 本来就是 GMT+8 语义，取日期部分就是"北京时间的那一天"。
     * 用 MySQL 的 {@code DATE()} 而不是"循环查每一天"（N+1：30 天要 30 条 SQL）。</p>
     *
     * <p>{@code create_time < #{end}} 上界不可省：没有它，未来时间的数据（或跨天边界）
     * 会被分到一个不在补齐区间里的日期，然后在 Service 合并时<b>静默丢失</b>，
     * 表现为"各天之和 ≠ 总数"这种最难查的账不平。</p>
     */
    @Select("SELECT DATE(create_time) AS statDate, COUNT(*) AS `count` FROM tb_order"
            + " WHERE is_deleted = 0 AND create_time >= #{start} AND create_time < #{end}"
            + " GROUP BY DATE(create_time)")
    List<DailyCount> countOrdersByDay(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 每日新增商品数（口径与 {@link #countOrdersByDay} 完全一致，只换表）。 */
    @Select("SELECT DATE(create_time) AS statDate, COUNT(*) AS `count` FROM tb_product"
            + " WHERE is_deleted = 0 AND create_time >= #{start} AND create_time < #{end}"
            + " GROUP BY DATE(create_time)")
    List<DailyCount> countProductsByDay(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 每日新增用户数（口径与 {@link #countOrdersByDay} 完全一致，只换表）。 */
    @Select("SELECT DATE(create_time) AS statDate, COUNT(*) AS `count` FROM tb_user"
            + " WHERE is_deleted = 0 AND create_time >= #{start} AND create_time < #{end}"
            + " GROUP BY DATE(create_time)")
    List<DailyCount> countUsersByDay(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // ================================================================ 热门榜（批次 5.5.2）

    /**
     * 热门商品榜：近 N 天<b>按订单数</b>降序取前 limit 个商品（决策 1 + 决策 2）。
     *
     * <h3>为什么标题要这样取（本批最容易写错的一处）</h3>
     * <p>直接用 {@code GROUP BY product_id, product_title} 会把"同一商品改名前后"
     * 拆成两行，榜单上出现两个一模一样的商品；用 {@code MAX(product_title)} 取的又是
     * <b>字典序最大</b>的标题（"英语"排在"高等数学"后面纯属巧合），不是最新的。
     * 所以按需求采用 {@code GROUP_CONCAT(... ORDER BY create_time DESC)} +
     * {@code SUBSTRING_INDEX(..., 1)}：把该商品的全部历史标题按时间倒序拼起来，
     * 取第一段 = <b>最新一单的标题</b>。</p>
     *
     * <h3>分隔符为什么不是逗号</h3>
     * <p>需求示例用 {@code ','} 作分隔符，但商品标题里<b>真的可能有逗号</b>
     * （"大学英语四级真题, 近 5 年"），一旦出现在最新那一单的标题里，
     * {@code SUBSTRING_INDEX} 会在逗号处截断 → 榜单显示半截标题。
     * 所以改用 ASCII 单元分隔符 {@code 0x1F}（正文里不可能出现），并用
     * {@code CONVERT(... USING utf8mb4)} 把结果转回字符集 ——
     * <b>MySQL 规定"分隔符是二进制串时 GROUP_CONCAT 的结果也是二进制串"</b>，
     * 不转回 utf8mb4 的话 JDBC 侧会把中文标题当字节串处理（已在本机真库上实测：
     * {@code HEX()} 输出 39 字节 / 13 字符，正是 "二手高等数学教材（第三版）" 的 UTF-8）。</p>
     *
     * <h3>其它口径</h3>
     * <ul>
     *   <li>{@code LEFT JOIN tb_product} 且<b>不过滤 p.is_deleted</b>：商品被逻辑删除后
     *       依然要能出现在榜单里（订单是真实发生过的），只是分类名会是 null；</li>
     *   <li>{@code LEFT JOIN tb_category}（选项 A）：显示分类名，分类被逻辑删除时为 null，
     *       由前端显示「—」（后端不写死展示文案）；</li>
     *   <li><b>订单数不分状态</b>：含已取消/已冻结/退款中的订单（"被下单的次数"）。
     *       若将来要改成"成交口径"，加 {@code AND o.status = 3} 即可，需先在文档里对齐语义；</li>
     *   <li>{@code GROUP_CONCAT} 默认上限 {@code group_concat_max_len=1024}：超长会被截断，
     *       但因为我们按 {@code create_time DESC} 排序后取<b>第一段</b>，
     *       截断只会丢掉末尾的旧标题，最新标题一定保留（校园单品交易每件商品通常 1~5 单）；</li>
     *   <li>内层 {@code ORDER BY ... LIMIT} 负责"取前 N"，<b>外层必须再 ORDER BY 一次</b> ——
     *       MySQL 不保证派生表的行序会被外层继承（少了它榜单顺序会随机）。</li>
     * </ul>
     */
    @Select("SELECT t.product_id AS productId,"
            + " t.product_title AS productTitle,"
            + " c.name AS categoryName,"
            + " t.order_count AS orderCount"
            + " FROM ("
            + "   SELECT o.product_id,"
            + "     CONVERT(SUBSTRING_INDEX(GROUP_CONCAT(o.product_title ORDER BY o.create_time DESC, o.id DESC SEPARATOR 0x1F), 0x1F, 1) USING utf8mb4) AS product_title,"
            + "     COUNT(*) AS order_count"
            + "   FROM tb_order o"
            + "   WHERE o.is_deleted = 0 AND o.create_time >= #{start} AND o.create_time < #{end}"
            + "   GROUP BY o.product_id"
            + "   ORDER BY order_count DESC"
            + "   LIMIT #{limit}"
            + " ) t"
            + " LEFT JOIN tb_product p ON p.id = t.product_id"
            + " LEFT JOIN tb_category c ON c.id = p.category_id AND c.is_deleted = 0"
            + " ORDER BY t.order_count DESC, t.product_id ASC")
    List<HotProductItem> selectHotProducts(@Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end,
                                           @Param("limit") int limit);
}
