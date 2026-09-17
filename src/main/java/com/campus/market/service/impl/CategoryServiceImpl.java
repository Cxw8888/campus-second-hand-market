package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.dto.product.CategoryMigrateRequest;
import com.campus.market.dto.product.CategorySaveRequest;
import com.campus.market.entity.Category;
import com.campus.market.entity.Product;
import com.campus.market.mapper.CategoryMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.service.CategoryService;
import com.campus.market.vo.CategoryVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商品分类服务实现。
 *
 * <p>缓存策略（Cache-Aside）：列表读路径先查 Redis，未命中查 DB 后回填；
 * 任何写操作后删除缓存（先更新 DB，再删除缓存）。Redis 故障时全部降级为直接查库并记录 warn 日志。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    /**
     * 分类列表缓存 Key。
     *
     * <p>说明：公共类 {@code RedisKeys} 未提供分类缓存 Key，且本次改动不允许修改 common 下的既有文件，
     * 故在本实现内以唯一常量收口，避免字符串散落各处。</p>
     */
    private static final String CATEGORY_LIST_CACHE_KEY = "product:category:list";

    /**
     * 逻辑删除时给 {@code name} 加的后缀：{@code #deleted{id}}。
     *
     * <p>为什么需要它（批次 5.4.2 缺陷修复）：{@code uk_category_name(name)} 是**单列唯一索引**，
     * 不含 {@code is_deleted}，所以逻辑删除的行会一直占着名字。而本项目的重名预检
     * {@link #existsByName} 走 MyBatis-Plus，会自动追加 {@code is_deleted = 0} ——
     * 对已删除的分类返回 count=0（"名字可用"），于是 INSERT 才撞上唯一索引抛
     * {@code DuplicateKeyException} → 表现为 code=500，管理员**永远无法再创建同名分类**。
     *
     * <p>删除时改名让位，等于把名字还给后来的分类；原名仍可从新名里还原
     * （去掉后缀即可），审计与排查都不丢信息。</p>
     */
    private static final String DELETED_NAME_SUFFIX = "#deleted";

    /**
     * 分类名长度上限，与 {@code CategorySaveRequest} 的 {@code @Size(max = 50)}
     * 和 {@code tb_category.name VARCHAR(50)} 严格对齐。
     *
     * <p>改名时若原名已达 50 字，直接拼后缀会超出列长度 → 又变成一个 500。
     * 因此按"后缀优先"截断原名的头部。</p>
     */
    private static final int CATEGORY_NAME_MAX_LENGTH = 50;

    /** 分类列表缓存 TTL：读多写少，写操作会主动失效，10 分钟兜底防止删除失败导致长期脏数据。 */
    private static final Duration CATEGORY_CACHE_TTL = Duration.ofMinutes(10);

    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public List<CategoryVO> list() {
        List<CategoryVO> cached = readCache();
        if (cached != null) {
            return cached;
        }
        // 排序下推 SQL；Stream API 转 VO
        List<CategoryVO> voList = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .orderByAsc(Category::getSort)
                        .orderByAsc(Category::getId))
                .stream()
                .map(CategoryServiceImpl::toVO)
                .collect(Collectors.toList());
        writeCache(voList);
        return voList;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CategorySaveRequest request) {
        String name = request.getName().trim();
        if (existsByName(name, null)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类名称已存在");
        }
        Category category = new Category();
        category.setName(name);
        category.setSort(request.getSort() == null ? 0 : request.getSort());
        categoryMapper.insert(category);
        // 先更新 DB，再删除缓存
        evictListCache();
        return category.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CategorySaveRequest request) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类不存在");
        }
        String name = request.getName().trim();
        if (existsByName(name, id)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类名称已存在");
        }
        Category entity = new Category();
        entity.setId(id);
        entity.setName(name);
        entity.setSort(request.getSort() == null ? 0 : request.getSort());
        categoryMapper.updateById(entity);
        // 先更新 DB，再删除缓存
        evictListCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类不存在");
        }
        // 分类下有商品（逻辑删除过滤由 MyBatis-Plus 自动追加 is_deleted=0）→ 禁止删除
        Long productCount = productMapper.selectCount(Wrappers.<Product>lambdaQuery()
                .eq(Product::getCategoryId, id));
        if (productCount != null && productCount > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_PRODUCT);
        }
        // ① 先改名让位：释放 uk_category_name(name) 对该名字的占用，否则同名分类再也建不出来
        //    （只 setName，其余字段为 null → MyBatis-Plus 不会覆盖 sort；update_time 由
        //     MetaObjectHandler 自动填充，符合全局字段规则）
        Category renamed = new Category();
        renamed.setId(id);
        renamed.setName(deletedName(category.getName(), id));
        categoryMapper.updateById(renamed);
        // ② 再逻辑删除（is_deleted=1）；①② 与审计写入同处一个事务
        categoryMapper.deleteById(id);
        evictListCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int migrate(CategoryMigrateRequest request) {
        Long fromCategoryId = request.getFromCategoryId();
        Long toCategoryId = request.getToCategoryId();
        if (fromCategoryId.equals(toCategoryId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "源分类与目标分类不能相同");
        }
        if (categoryMapper.selectById(fromCategoryId) == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "源分类不存在");
        }
        if (categoryMapper.selectById(toCategoryId) == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "目标分类不存在");
        }
        // 先取出待迁移商品ID（用于迁移后失效商品详情缓存中的 categoryName）
        List<Long> productIds = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                        .select(Product::getId)
                        .eq(Product::getCategoryId, fromCategoryId))
                .stream()
                .map(Product::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (productIds.isEmpty()) {
            // 没有任何商品需要迁移。这次调用本身不改业务数据，所以缓存不会脏；
            // 之所以仍要失效一次，是为了遵守本类的统一约定「任何写操作路径结束后都失效列表缓存」——
            // 否则以后有人在 return 之前加了写逻辑（例如顺手改分类名），就会漏掉失效。
            evictListCache();
            return 0;
        }
        // 级联迁移：UPDATE tb_product SET category_id = ? WHERE category_id = ? AND is_deleted = 0
        LambdaUpdateWrapper<Product> updateWrapper = Wrappers.<Product>lambdaUpdate()
                .set(Product::getCategoryId, toCategoryId)
                .in(Product::getCategoryId, fromCategoryId);
        int moved = productMapper.update(null, updateWrapper);

        // 先更新 DB，再删除缓存
        evictListCache();
        productIds.forEach(this::evictProductDetailCache);
        log.info("分类级联迁移完成: from={}, to={}, moved={}", fromCategoryId, toCategoryId, moved);
        return moved;
    }

    // ------------------------------------------------------------ 内部方法

    private boolean existsByName(String name, Long excludeId) {
        Long count = categoryMapper.selectCount(Wrappers.<Category>lambdaQuery()
                .eq(Category::getName, name)
                .ne(excludeId != null, Category::getId, excludeId));
        return count != null && count > 0;
    }

    private static CategoryVO toVO(Category category) {
        CategoryVO vo = new CategoryVO();
        vo.setId(category.getId());
        vo.setName(category.getName());
        vo.setSort(category.getSort());
        return vo;
    }

    /**
     * 生成逻辑删除后的"让位名字"：{@code 原名#deleted{id}}。
     *
     * <ul>
     *   <li>后缀带 {@code id}，保证同名分类被反复删除（不同 id）也不会互相冲突；</li>
     *   <li>原名已含 {@code #deleted} 时不重复追加（幂等，与清理脚本的
     *       {@code WHERE name NOT LIKE '%#deleted%'} 判据保持一致）；</li>
     *   <li>按"后缀优先"截断，保证总长不超过 {@code name VARCHAR(50)}，
     *       否则改名本身会因超长而报错（把 500 从一个地方搬到另一个地方）；</li>
     *   <li>不做 trim / 大小写处理：原名原样保留前缀，便于人工还原。</li>
     * </ul>
     */
    private static String deletedName(String name, Long id) {
        String original = name == null ? "" : name;
        if (original.contains(DELETED_NAME_SUFFIX)) {
            return original;
        }
        String suffix = DELETED_NAME_SUFFIX + id;
        int keep = Math.max(0, CATEGORY_NAME_MAX_LENGTH - suffix.length());
        String head = original.length() > keep ? original.substring(0, keep) : original;
        return head + suffix;
    }

    /**
     * 读缓存：Redis 连接失败 / 超时 / 反序列化异常一律降级为直接查 DB。
     */
    private List<CategoryVO> readCache() {
        try {
            String json = stringRedisTemplate.opsForValue().get(CATEGORY_LIST_CACHE_KEY);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, new TypeReference<List<CategoryVO>>() {
            });
        } catch (Exception e) {
            log.warn("分类列表缓存读取降级, 直接查库: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(List<CategoryVO> voList) {
        try {
            stringRedisTemplate.opsForValue()
                    .set(CATEGORY_LIST_CACHE_KEY, objectMapper.writeValueAsString(voList), CATEGORY_CACHE_TTL);
        } catch (Exception e) {
            log.warn("分类列表缓存写入降级: {}", e.getMessage());
        }
    }

    private void evictListCache() {
        try {
            stringRedisTemplate.delete(CATEGORY_LIST_CACHE_KEY);
        } catch (Exception e) {
            log.warn("分类列表缓存删除失败（依赖短 TTL 兜底）: {}", e.getMessage());
        }
    }

    private void evictProductDetailCache(Long productId) {
        try {
            stringRedisTemplate.delete(RedisKeys.productDetail(productId));
        } catch (Exception e) {
            log.warn("商品详情缓存删除失败（依赖短 TTL 兜底）: productId={}, err={}", productId, e.getMessage());
        }
    }
}
