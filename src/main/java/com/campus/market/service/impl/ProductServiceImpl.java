package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.common.result.PageResult;
import com.campus.market.dto.product.ProductQuery;
import com.campus.market.dto.product.ProductSaveRequest;
import com.campus.market.entity.Category;
import com.campus.market.entity.Order;
import com.campus.market.entity.Product;
import com.campus.market.entity.User;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.entity.enums.ProductStatus;
import com.campus.market.mapper.CategoryMapper;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.mapper.UserMapper;
import com.campus.market.security.UserContext;
import com.campus.market.service.ProductService;
import com.campus.market.vo.ProductDetailVO;
import com.campus.market.vo.ProductListVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品服务实现。
 *
 * <h3>列表检索</h3>
 * 多条件过滤 + 排序全部下推 SQL（{@code ORDER BY}），关键字使用 MyBatis-Plus 参数化 {@code like}
 * （生成 {@code CONCAT('%', #{...}, '%')}），<b>严禁字符串拼接</b>；逻辑删除由全局配置自动追加 {@code is_deleted=0}。
 *
 * <h3>详情可见性分级</h3>
 * 未登录 → 仅 status=1；登录非卖家 / 非管理员 → status IN (0,1,2)（禁止查看待审核 3）；
 * 卖家本人 / 管理员 → 全部状态；不满足 → code=204。
 *
 * <h3>缓存一致性</h3>
 * 读路径 Cache-Aside（{@code product:detail:{id}}），Redis 故障降级直接查库；
 * 写路径严格执行"<b>先更新 DB，再删除缓存</b>"，删除失败仅记录 warn（依赖短 TTL 兜底）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    /**
     * 商品详情缓存 TTL：作为"删除缓存失败 / 其他写路径未失效缓存"的兜底。
     *
     * <p>注意：下单扣减 / 取消回补 / 售罄联动 / 审核流转属于订单与管理端模块，
     * 这些路径同样需要删除本 Key；在它们接入失效逻辑前，本 TTL 是脏数据的最长存活时间。</p>
     */
    private static final Duration DETAIL_CACHE_TTL = Duration.ofMinutes(5);

    /** 未完成订单状态：存在这些订单的商品禁止删除（code=207）。 */
    private static final List<Integer> UNFINISHED_ORDER_STATUS = List.of(
            OrderStatus.PENDING_PAY,
            OrderStatus.PAID,
            OrderStatus.SHIPPED,
            OrderStatus.REFUND_APPLYING,
            OrderStatus.REFUND_REJECTED
    );

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final OrderMapper orderMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // ================================================================ 查询

    @Override
    public PageResult<ProductListVO> list(ProductQuery query) {
        LambdaQueryWrapper<Product> wrapper = buildListWrapper(query);
        // 游客 / 买家视角：只返回上架中商品
        wrapper.eq(Product::getStatus, ProductStatus.ON_SALE);
        return pageQuery(query, wrapper);
    }

    @Override
    public PageResult<ProductListVO> listMy(ProductQuery query) {
        Long userId = UserContext.requireUserId();
        LambdaQueryWrapper<Product> wrapper = buildListWrapper(query);
        // 卖家视角：自己的全部状态商品（含 3-待审核）
        wrapper.eq(Product::getUserId, userId);
        if (query.getStatus() != null) {
            wrapper.eq(Product::getStatus, query.getStatus());
        }
        return pageQuery(query, wrapper);
    }

    @Override
    public ProductDetailVO detail(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品ID不能为空");
        }
        // ---------- 读路径 Cache-Aside：命中缓存后仍需做可见性分级（缓存可能由卖家本人写入） ----------
        ProductDetailVO cached = readDetailCache(id);
        if (cached != null) {
            checkVisibility(cached.getSellerId(), cached.getStatus());
            return cached;
        }
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw BusinessException.productNotAvailable();
        }
        checkVisibility(product.getUserId(), product.getStatus());
        ProductDetailVO vo = toDetailVO(product);
        writeDetailCache(id, vo);
        return vo;
    }

    // ================================================================ 写入

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ProductSaveRequest request) {
        Long userId = UserContext.requireUserId();
        validateSaveRequest(request);
        List<String> imageUrls = normalizeImageUrls(request);
        requireCategory(request.getCategoryId());

        Product product = new Product();
        product.setUserId(userId);
        product.setCategoryId(request.getCategoryId());
        product.setTitle(request.getTitle().trim());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());
        product.setConditionLevel(request.getConditionLevel());
        product.setTradeType(request.getTradeType());
        product.setTradeLocation(request.getTradeLocation());
        product.setImageUrls(imageUrls);
        // 发布 → 3-待审核
        product.setStatus(ProductStatus.PENDING_AUDIT);
        productMapper.insert(product);

        // 先更新 DB，再删除缓存
        evictDetailCache(product.getId());
        log.info("商品发布成功: id={}, userId={}, status={}", product.getId(), userId, product.getStatus());
        return product.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ProductSaveRequest request) {
        Product product = requireOwnProduct(id);
        validateSaveRequest(request);
        List<String> imageUrls = normalizeImageUrls(request);
        requireCategory(request.getCategoryId());

        boolean criticalChanged = isCriticalChanged(product, request, imageUrls);
        int newStatus = resolveStatus(product.getStatus(), request.getStock(), criticalChanged);

        Product entity = new Product();
        entity.setId(id);
        entity.setCategoryId(request.getCategoryId());
        entity.setTitle(request.getTitle().trim());
        entity.setDescription(request.getDescription());
        entity.setPrice(request.getPrice());
        entity.setStock(request.getStock());
        entity.setConditionLevel(request.getConditionLevel());
        entity.setTradeType(request.getTradeType());
        entity.setTradeLocation(request.getTradeLocation());
        // imageUrls 依赖实体上的 JacksonTypeHandler 完成 JSON 序列化
        entity.setImageUrls(imageUrls);
        entity.setStatus(newStatus);
        // 先更新 DB，再删除缓存
        productMapper.updateById(entity);

        // PUT 全量更新语义：description / tradeLocation 允许显式置空（updateById 会跳过 null 字段），
        // 故再用 UpdateWrapper 显式 set 一次（含 null），保证"全量替换"语义成立
        productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                .set(Product::getDescription, request.getDescription())
                .set(Product::getTradeLocation, request.getTradeLocation())
                .eq(Product::getId, id));

        evictDetailCache(id);
        log.info("商品编辑成功: id={}, status={}→{}, 关键字段变更={}",
                id, product.getStatus(), newStatus, criticalChanged);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Product product = requireOwnProduct(id);
        // 存在未完成订单（0/1/2/6/7）→ 禁止删除
        Long unfinished = orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .eq(Order::getProductId, id)
                .in(Order::getStatus, UNFINISHED_ORDER_STATUS));
        if (unfinished != null && unfinished > 0) {
            throw new BusinessException(ErrorCode.PRODUCT_HAS_ORDER);
        }
        // 逻辑删除（is_deleted=1，全局配置自动过滤）
        productMapper.deleteById(id);
        // 先更新 DB，再删除缓存
        evictDetailCache(id);
        log.info("商品已逻辑删除: id={}, userId={}", id, product.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void offShelf(Long id) {
        Product product = requireOwnProduct(id);
        int current = product.getStatus() == null ? ProductStatus.PENDING_AUDIT : product.getStatus();
        if (current == ProductStatus.OFF_SHELF) {
            // 幂等：已处于下架状态直接返回
            return;
        }
        if (current != ProductStatus.ON_SALE) {
            // 仅允许 1-上架中 → 0-下架
            throw BusinessException.statusNotAllowed();
        }
        Product entity = new Product();
        entity.setId(id);
        entity.setStatus(ProductStatus.OFF_SHELF);
        productMapper.updateById(entity);
        // 先更新 DB，再删除缓存
        evictDetailCache(id);
        log.info("商品下架成功: id={}, userId={}", id, product.getUserId());
    }

    // ================================================================ 列表组装

    /**
     * 组装列表查询条件：关键字（参数化 like，匹配 title 或 description）、分类、价格区间、成色、交易方式；
     * 排序下推 SQL（ORDER BY）。
     */
    private LambdaQueryWrapper<Product> buildListWrapper(ProductQuery query) {
        if (query.getMinPrice() != null && query.getMaxPrice() != null
                && query.getMinPrice().compareTo(query.getMaxPrice()) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "最低价格不能大于最高价格");
        }
        LambdaQueryWrapper<Product> wrapper = Wrappers.lambdaQuery();
        String keyword = query.getKeyword() == null ? null : query.getKeyword().trim();
        if (StringUtils.hasText(keyword)) {
            // 参数化检索（MyBatis-Plus 生成 CONCAT('%', #{...}, '%')），严禁字符串拼接
            wrapper.and(w -> w.like(Product::getTitle, keyword)
                    .or()
                    .like(Product::getDescription, keyword));
        }
        wrapper.eq(query.getCategoryId() != null, Product::getCategoryId, query.getCategoryId())
                .ge(query.getMinPrice() != null, Product::getPrice, query.getMinPrice())
                .le(query.getMaxPrice() != null, Product::getPrice, query.getMaxPrice())
                .eq(query.getConditionLevel() != null, Product::getConditionLevel, query.getConditionLevel())
                .eq(query.getTradeType() != null, Product::getTradeType, query.getTradeType());

        // 排序下推 SQL，严禁内存排序
        boolean asc = "asc".equalsIgnoreCase(query.getOrder());
        if ("price".equalsIgnoreCase(query.getSortBy())) {
            wrapper.orderBy(true, asc, Product::getPrice);
        } else {
            wrapper.orderBy(true, asc, Product::getCreateTime);
        }
        // 稳定排序兜底，避免翻页出现重复 / 漏项
        wrapper.orderByDesc(Product::getId);
        return wrapper;
    }

    /**
     * 分页查询 + Stream API 转 VO（批量补分类名与卖家展示字段，避免 N+1）。
     */
    private PageResult<ProductListVO> pageQuery(ProductQuery query, LambdaQueryWrapper<Product> wrapper) {
        Page<Product> page = new Page<>(query.current(), query.pageSize());
        Page<Product> result = productMapper.selectPage(page, wrapper);
        List<Product> records = result.getRecords();
        List<ProductListVO> voList;
        if (records == null || records.isEmpty()) {
            // 注意：不能直接返回 PageResult.empty，否则翻到超出末页时会丢失 total
            voList = Collections.emptyList();
        } else {
            Map<Long, String> categoryNames = loadCategoryNames(records);
            Map<Long, User> sellers = loadSellers(records);
            voList = records.stream()
                    .map(product -> fill(new ProductListVO(), product,
                            categoryNames.get(product.getCategoryId()),
                            sellers.get(product.getUserId())))
                    .collect(Collectors.toList());
        }
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), voList);
    }

    private Map<Long, String> loadCategoryNames(List<Product> records) {
        Set<Long> categoryIds = records.stream()
                .map(Product::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (categoryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return categoryMapper.selectBatchIds(categoryIds).stream()
                .filter(category -> category.getId() != null)
                .collect(Collectors.toMap(Category::getId,
                        category -> category.getName() == null ? "" : category.getName(),
                        (existing, replacement) -> existing));
    }

    /**
     * 批量加载卖家展示字段：<b>只 select id / nickname / avatar</b>，
     * 从数据源头杜绝 phone、email 等敏感字段进入 VO。
     */
    private Map<Long, User> loadSellers(List<Product> records) {
        Set<Long> sellerIds = records.stream()
                .map(Product::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (sellerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .select(User::getId, User::getNickname, User::getAvatar)
                        .in(User::getId, sellerIds))
                .stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(User::getId, Function.identity(), (existing, replacement) -> existing));
    }

    /**
     * 公共字段填充（列表 VO 与详情 VO 共用）；<b>严禁写入 phone / email</b>。
     */
    private <T extends ProductListVO> T fill(T vo, Product product, String categoryName, User seller) {
        vo.setId(product.getId());
        vo.setTitle(product.getTitle());
        vo.setPrice(product.getPrice());
        vo.setStock(product.getStock());
        vo.setConditionLevel(product.getConditionLevel());
        vo.setTradeType(product.getTradeType());
        vo.setTradeLocation(product.getTradeLocation());
        vo.setCoverImage(coverImage(product.getImageUrls()));
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setStatus(product.getStatus());
        vo.setCreateTime(product.getCreateTime());
        if (seller != null) {
            vo.setSellerId(seller.getId());
            vo.setSellerNickname(seller.getNickname());
            vo.setSellerAvatar(seller.getAvatar());
        } else {
            vo.setSellerId(product.getUserId());
        }
        return vo;
    }

    private ProductDetailVO toDetailVO(Product product) {
        Category category = product.getCategoryId() == null ? null : categoryMapper.selectById(product.getCategoryId());
        User seller = product.getUserId() == null ? null : userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getNickname, User::getAvatar)
                .eq(User::getId, product.getUserId()));
        ProductDetailVO vo = fill(new ProductDetailVO(), product,
                category == null ? null : category.getName(), seller);
        vo.setDescription(product.getDescription());
        vo.setImageUrls(product.getImageUrls());
        return vo;
    }

    private String coverImage(List<String> imageUrls) {
        return imageUrls == null || imageUrls.isEmpty() ? null : imageUrls.get(0);
    }

    // ================================================================ 可见性 / 状态机

    /**
     * 商品详情可见性分级。
     *
     * @param sellerId 商品发布者
     * @param status   商品当前状态
     */
    private void checkVisibility(Long sellerId, Integer status) {
        int current = status == null ? ProductStatus.OFF_SHELF : status;
        Long currentUserId = UserContext.getUserIdOrNull();

        // ① 未登录（游客）：仅 status=1 且 is_deleted=0
        if (currentUserId == null) {
            if (current != ProductStatus.ON_SALE) {
                throw BusinessException.productNotAvailable();
            }
            return;
        }
        // ② 管理员：全部状态
        if (UserContext.isAdmin()) {
            return;
        }
        // ③ 卖家本人：自己的商品全部状态（含待审核 3）
        if (currentUserId.equals(sellerId)) {
            return;
        }
        // ④ 已登录非卖家 / 非管理员：status IN (0,1,2)，禁止查看待审核
        if (current == ProductStatus.OFF_SHELF
                || current == ProductStatus.ON_SALE
                || current == ProductStatus.SOLD_OUT) {
            return;
        }
        throw BusinessException.productNotAvailable();
    }

    /**
     * 编辑后的状态流转：
     * <ul>
     *   <li>status=2-售罄：新库存 &gt; 0 → 1-上架中，否则保持 2；</li>
     *   <li>status=0-下架 / 3-待审核：重置为 3-待审核；</li>
     *   <li>status=1-上架中：关键字段（title/description/imageUrls/price/conditionLevel）变更 → 重置为 3，
     *       非关键字段（tradeLocation/tradeType）直接生效。</li>
     * </ul>
     */
    private int resolveStatus(Integer currentStatus, Integer newStock, boolean criticalChanged) {
        int current = currentStatus == null ? ProductStatus.PENDING_AUDIT : currentStatus;
        if (current == ProductStatus.SOLD_OUT) {
            return newStock != null && newStock > 0 ? ProductStatus.ON_SALE : ProductStatus.SOLD_OUT;
        }
        if (current == ProductStatus.OFF_SHELF || current == ProductStatus.PENDING_AUDIT) {
            return ProductStatus.PENDING_AUDIT;
        }
        return criticalChanged ? ProductStatus.PENDING_AUDIT : ProductStatus.ON_SALE;
    }

    private boolean isCriticalChanged(Product product, ProductSaveRequest request, List<String> imageUrls) {
        String newTitle = request.getTitle() == null ? null : request.getTitle().trim();
        return !Objects.equals(product.getTitle(), newTitle)
                || !Objects.equals(product.getDescription(), request.getDescription())
                || !Objects.equals(product.getConditionLevel(), request.getConditionLevel())
                || !samePrice(product.getPrice(), request.getPrice())
                || !Objects.equals(product.getImageUrls(), imageUrls);
    }

    private boolean samePrice(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    // ================================================================ 校验 / 缓存

    /**
     * 业务层兜底校验（与 DTO 注解校验形成双保险）：title 1-100、price &gt; 0、
     * condition_level 1-4、trade_type 1-3、stock ≥ 0。
     */
    private void validateSaveRequest(ProductSaveRequest request) {
        String title = request.getTitle() == null ? "" : request.getTitle().trim();
        if (title.isEmpty() || title.length() > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品标题长度必须为1-100");
        }
        if (request.getPrice() == null || request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品价格必须大于0");
        }
        if (request.getStock() == null || request.getStock() < 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品库存不能小于0");
        }
        if (request.getConditionLevel() == null || request.getConditionLevel() < 1 || request.getConditionLevel() > 4) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品成色必须为1-4");
        }
        if (request.getTradeType() == null || request.getTradeType() < 1 || request.getTradeType() > 3) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "交易方式必须为1-3");
        }
    }

    /**
     * 图片列表规范化（Stream API）：去空、去首尾空格、去重，并校验非空且 ≤ 9 张。
     */
    private List<String> normalizeImageUrls(ProductSaveRequest request) {
        List<String> raw = request.getImageUrls();
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品图片不能为空");
        }
        List<String> imageUrls = raw.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(url -> !url.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (imageUrls.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品图片不能为空");
        }
        if (imageUrls.size() > 9) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品图片最多9张");
        }
        return imageUrls;
    }

    private void requireCategory(Long categoryId) {
        if (categoryId == null || categoryMapper.selectById(categoryId) == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品分类不存在");
        }
    }

    /**
     * 校验商品存在且当前用户（作者本人或管理员）有操作权限。
     */
    private Product requireOwnProduct(Long id) {
        Product product = id == null ? null : productMapper.selectById(id);
        if (product == null) {
            throw BusinessException.productNotAvailable();
        }
        Long currentUserId = UserContext.requireUserId();
        if (!UserContext.isAdmin() && !currentUserId.equals(product.getUserId())) {
            throw BusinessException.noPermission("无权操作该商品");
        }
        return product;
    }

    /**
     * 读缓存：Redis 连接失败 / 超时 / 反序列化异常一律降级为直接查 DB（记录 warn）。
     */
    private ProductDetailVO readDetailCache(Long id) {
        try {
            String json = stringRedisTemplate.opsForValue().get(RedisKeys.productDetail(id));
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, ProductDetailVO.class);
        } catch (Exception e) {
            log.warn("商品详情缓存读取降级, 直接查库: id={}, err={}", id, e.getMessage());
            return null;
        }
    }

    private void writeDetailCache(Long id, ProductDetailVO vo) {
        try {
            stringRedisTemplate.opsForValue()
                    .set(RedisKeys.productDetail(id), objectMapper.writeValueAsString(vo), DETAIL_CACHE_TTL);
        } catch (Exception e) {
            log.warn("商品详情缓存写入降级: id={}, err={}", id, e.getMessage());
        }
    }

    /**
     * 删除商品详情缓存（必须在 DB 写入之后调用）。
     */
    private void evictDetailCache(Long id) {
        if (id == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(RedisKeys.productDetail(id));
        } catch (Exception e) {
            log.warn("商品详情缓存删除失败（依赖短 TTL 兜底，必要时延迟双删）: id={}, err={}", id, e.getMessage());
        }
    }
}
