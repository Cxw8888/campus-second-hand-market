package com.campus.market.service;

import com.campus.market.dto.product.CategoryMigrateRequest;
import com.campus.market.dto.product.CategorySaveRequest;
import com.campus.market.vo.CategoryVO;

import java.util.List;

/**
 * 商品分类服务。
 *
 * <p>分类为读多写少数据：列表查询走 Redis 缓存（Cache-Aside + 短 TTL），
 * 任何写操作（新建 / 修改 / 删除 / 迁移）后失效缓存；Redis 故障时降级为直接查库。</p>
 */
public interface CategoryService {

    /**
     * 分类列表：按 sort 升序（Stream API 转 VO）。
     *
     * @return 分类列表，Redis 不可用时降级直接查 DB
     */
    List<CategoryVO> list();

    /**
     * 新建分类（名称唯一，重复抛 code=100）。
     *
     * @return 新分类ID
     */
    Long create(CategorySaveRequest request);

    /**
     * 修改分类（名称需保持唯一）。
     */
    void update(Long id, CategorySaveRequest request);

    /**
     * 删除分类：分类下存在商品（is_deleted=0）时禁止删除 → code=208。
     */
    void delete(Long id);

    /**
     * 级联迁移：把源分类下的商品全部迁移到目标分类。
     *
     * @return 实际迁移的商品条数
     */
    int migrate(CategoryMigrateRequest request);
}
