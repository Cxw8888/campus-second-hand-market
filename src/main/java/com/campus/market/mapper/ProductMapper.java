package com.campus.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.market.dto.product.ProductQuery;
import com.campus.market.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 商品 Mapper，含 CAS 库存扣减与回补，以及关键词检索（FULLTEXT + LIKE 双路径）。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    // ================================================================ 关键词检索（批次 5.4.4）

    /**
     * 检索共用的筛选条件：分类 / 价格区间 / 成色 / 交易方式。
     *
     * <p>抽成常量是为了让 FULLTEXT 与 LIKE 两条路径的过滤口径<b>字面一致</b> ——
     * 降级时唯一变化的只有"关键字怎么匹配"，避免两条路径慢慢长歪。</p>
     *
     * <p>注意：不含关键字、不含 {@code is_deleted} / {@code status} / 排序 / 分页。</p>
     */
    String KEYWORD_FILTER_SQL =
            "<if test='query.categoryId != null'>AND category_id = #{query.categoryId} </if>"
                    + "<if test='query.minPrice != null'>AND price &gt;= #{query.minPrice} </if>"
                    + "<if test='query.maxPrice != null'>AND price &lt;= #{query.maxPrice} </if>"
                    + "<if test='query.conditionLevel != null'>AND condition_level = #{query.conditionLevel} </if>"
                    + "<if test='query.tradeType != null'>AND trade_type = #{query.tradeType} </if>";

    /**
     * 排序下推 SQL（列名白名单）。
     *
     * <p>用 {@code <choose>} 在<b>固定字面量</b>之间选择，绝不做字符串拼接：
     * {@code sortBy} / {@code order} 本身已被 {@link ProductQuery} 的 {@code @Pattern} 限制为
     * price|create_time 与 asc|desc，这里再把它们收窄成写死的 SQL 片段，双保险。</p>
     *
     * <p>末尾的 {@code id DESC} 是稳定排序兜底，避免翻页出现重复 / 漏项。</p>
     */
    String KEYWORD_ORDER_SQL =
            "<choose><when test=\"query.sortBy == 'price'\">price </when>"
                    + "<otherwise>create_time </otherwise></choose>"
                    + "<choose><when test=\"query.order == 'asc'\">ASC, </when>"
                    + "<otherwise>DESC, </otherwise></choose>"
                    + "id DESC";

    /**
     * FULLTEXT 检索命中总数（与 {@link #searchFulltext} 的过滤条件完全一致，只是不带排序 / 分页）。
     *
     * <p>{@code status = 1} 是硬编码的：本路径只服务 C 端商品列表（{@code ProductServiceImpl.list}），
     * 语义与原先的 {@code wrapper.eq(status, ON_SALE)} 一致，故写死为常量而非参数，
     * 避免调用方误传其它状态导致"待审核商品被打到搜索结果里"。</p>
     */
    @Select("<script>SELECT COUNT(*) FROM tb_product"
            + " WHERE is_deleted = 0 AND status = 1"
            + " AND MATCH(title, description) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE)"
            + KEYWORD_FILTER_SQL
            + "</script>")
    long countFulltext(@Param("query") ProductQuery query, @Param("keyword") String keyword);

    /**
     * FULLTEXT 关键词检索（ngram 解析器，中文按 2 字一组切分），相关度降序 + 用户选定排序。
     *
     * <p>{@code @ResultMap("mybatis-plus_Product")} 不能省：{@code image_urls} 是 JSON 列，
     * 靠实体上 {@code @TableField(typeHandler = JacksonTypeHandler.class)} 才能映射成
     * {@code List<String>}；自定义 SQL 若不显式引用 MP 生成的 resultMap，就会走自动映射，
     * 拿到的是原始字符串 → 列表项的封面图（coverImage）会整片消失。</p>
     */
    @ResultMap("mybatis-plus_Product")
    @Select("<script>SELECT * FROM tb_product"
            + " WHERE is_deleted = 0 AND status = 1"
            + " AND MATCH(title, description) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE)"
            + KEYWORD_FILTER_SQL
            + " ORDER BY MATCH(title, description) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) DESC, "
            + KEYWORD_ORDER_SQL
            + " LIMIT #{offset}, #{size}"
            + "</script>")
    List<Product> searchFulltext(@Param("query") ProductQuery query,
                                 @Param("keyword") String keyword,
                                 @Param("offset") int offset,
                                 @Param("size") int size);

    /**
     * LIKE 检索命中总数（FULLTEXT 不可用时的降级路径，也是短关键字路径）。
     *
     * <p>与 5.4.4 之前的行为完全等价：{@code title LIKE %kw% OR description LIKE %kw%}，
     * 用 {@code CONCAT('%', #{keyword}, '%')} 参数化，<b>严禁字符串拼接</b>。</p>
     */
    @Select("<script>SELECT COUNT(*) FROM tb_product"
            + " WHERE is_deleted = 0 AND status = 1"
            + " AND (title LIKE CONCAT('%', #{keyword}, '%')"
            + " OR description LIKE CONCAT('%', #{keyword}, '%'))"
            + KEYWORD_FILTER_SQL
            + "</script>")
    long countLike(@Param("query") ProductQuery query, @Param("keyword") String keyword);

    /** LIKE 检索（降级路径）：过滤条件与 FULLTEXT 路径逐字一致，仅关键字匹配方式不同。 */
    @ResultMap("mybatis-plus_Product")
    @Select("<script>SELECT * FROM tb_product"
            + " WHERE is_deleted = 0 AND status = 1"
            + " AND (title LIKE CONCAT('%', #{keyword}, '%')"
            + " OR description LIKE CONCAT('%', #{keyword}, '%'))"
            + KEYWORD_FILTER_SQL
            + " ORDER BY " + KEYWORD_ORDER_SQL
            + " LIMIT #{offset}, #{size}"
            + "</script>")
    List<Product> searchLike(@Param("query") ProductQuery query,
                             @Param("keyword") String keyword,
                             @Param("offset") int offset,
                             @Param("size") int size);

    // ================================================================ 库存与回看

    /**
     * CAS 扣减库存（防超卖）：仅当 stock >= quantity 且商品处于上架状态时扣减；
     * 扣减后库存为 0 时联动置为 2-售罄。返回影响行数，0 表示库存不足或状态不允许。
     *
     * <p><b>警告：两个 SET 赋值的先后顺序不可调换。</b>MySQL 的 UPDATE 赋值<i>从左到右</i>求值，
     * 后面的表达式读到的是同一语句中<b>已被更新</b>的列值。因此 status 必须写在 stock 之前，
     * 此时 CASE 里的 {@code stock} 才是扣减前的旧值。若把 stock 写在前面，
     * {@code stock - #{quantity}} 会变成「扣减后的库存再减一次」，导致
     * 「即将售罄（newStock == quantity）」被误判为「已售罄」——
     * 例如 stock=3 连续两次 qty=1 下单后，库存还剩 1 却被置为 2-售罄，
     * 后续下单直接 204「商品不存在或已下架」；同时 qty&gt;1 真正扣到 0 时反而不会置售罄。</p>
     */
    @Update("UPDATE tb_product SET status = CASE WHEN stock - #{quantity} = 0 THEN 2 ELSE status END, " +
            "stock = stock - #{quantity} " +
            "WHERE id = #{id} AND stock >= #{quantity} AND status = 1 AND is_deleted = 0")
    int deductStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 库存回补（统一入口，CAS 风格）：售罄(2) 回补后自动恢复上架(1)。返回影响行数。
     */
    @Update("UPDATE tb_product SET stock = stock + #{quantity}, status = CASE WHEN status = 2 THEN 1 ELSE status END " +
            "WHERE id = #{id} AND is_deleted = 0")
    int restoreStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 绕过逻辑删除过滤的精确查询（订单回看已删除商品专用，自定义 SQL 为主方案）。
     */
    @Select("SELECT * FROM tb_product WHERE id = #{id}")
    Product selectByIdIgnoreLogicDelete(@Param("id") Long id);

    /**
     * 绕过逻辑删除过滤的批量查询（订单列表回看已删除商品，避免 N+1 查询）。
     */
    @Select("<script>SELECT * FROM tb_product WHERE id IN " +
            "<foreach collection='ids' item='item' open='(' separator=',' close=')'>#{item}</foreach></script>")
    List<Product> selectByIdsIgnoreLogicDelete(@Param("ids") Collection<Long> ids);
}
