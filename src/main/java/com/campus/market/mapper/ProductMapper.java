package com.campus.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.market.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 商品 Mapper，含 CAS 库存扣减与回补。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

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
