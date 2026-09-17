package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.common.result.PageResult;
import com.campus.market.config.properties.SearchProperties;
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
import com.campus.market.service.support.SearchCircuitBreaker;
import com.campus.market.vo.ProductDetailVO;
import com.campus.market.vo.ProductListVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 商品服务实现。
 *
 * <h3>列表检索</h3>
 * 多条件过滤 + 排序全部下推 SQL（{@code ORDER BY}），关键字检索见下节，<b>严禁字符串拼接</b>；
 * 逻辑删除由全局配置自动追加 {@code is_deleted=0}。
 *
 * <h3>关键词检索（批次 5.4.4）</h3>
 * 关键字非空时走 {@link #keywordSearch}：<b>缓存 → 熔断判断 → FULLTEXT 查询 → LIKE 降级</b>。
 * <ul>
 *   <li>FULLTEXT：{@code MATCH(title, description) AGAINST(? IN NATURAL LANGUAGE MODE)}，
 *       依赖 V2 迁移建的 {@code ft_product_title_desc}（ngram 解析器，中文按 2 字切分）；</li>
 *   <li>LIKE 降级：捕获 {@link DataAccessException}（索引缺失 / 语法不支持等）后自动改走
 *       {@code title LIKE %kw% OR description LIKE %kw%}，对调用方完全透明；</li>
 *   <li><b>走 LIKE 而非 FULLTEXT 的两种关键字</b>（见 {@link #supportsFulltext}）：
 *       ① 长度短于 ngram_token_size（2）的单字（「书」「a」）——切不出 ngram，FULLTEXT 必然为空；
 *       ② 纯 ASCII 关键字（如 {@code keyboard}）——ngram 对拉丁文会因共享二元组而误召回
 *       （实测命中过「Nike 运动鞋」）；</li>
 *   <li>无关键字（纯浏览）不经过本路径，保持 5.4.4 之前的 MyBatis-Plus 分页行为。</li>
 * </ul>
 *
 * <h3>详情可见性分级</h3>
 * 未登录 → 仅 status=1；登录非卖家 / 非管理员 → status IN (0,1,2)（禁止查看待审核 3）；
 * 卖家本人 / 管理员 → 全部状态；不满足 → code=204。
 *
 * <h3>缓存一致性</h3>
 * 读路径 Cache-Aside（{@code product:detail:{id}}、{@code search:*}），Redis 故障降级直接查库；
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

    /**
     * 搜索结果缓存 Key 前缀。
     *
     * <p>与 {@code RedisKeys} 里既有 Key 的命名风格保持一致，但<b>刻意不往 common 下加常量</b> ——
     * 沿用 5.3/5.4 批次里 {@code CategoryServiceImpl} 的做法：新增缓存 Key 只服务本类，
     * 就收口在本类，避免为了一个字符串去动公共文件。</p>
     */
    private static final String SEARCH_CACHE_PREFIX = "search:";

    /**
     * MySQL {@code ngram_token_size} 的镜像值（默认 2，本机实测为 2）。
     *
     * <p>ngram 解析器按 N 元组切词，因此长度 &lt; N 的关键字（典型是单字「书」「鞋」）
     * 切不出任何 token，FULLTEXT 结果<b>必然为空</b>。这类关键字必须直接走 LIKE，
     * 否则用户会从"能搜到"变成"永远搜不到"，属于功能性回归。</p>
     *
     * <p>写死为常量而不做配置，是因为它必须与<b>数据库服务端变量</b>保持一致，
     * 前端配置改了反而容易两边不一致；真要调整，应与 DBA 一起改
     * {@code ngram_token_size} 并同步这里。</p>
     */
    private static final int NGRAM_TOKEN_SIZE = 2;

    /**
     * CJK（汉字）字符判定：命中任意一个 U+4E00–U+9FFF 即为"含中文"。
     *
     * <p>用来决定关键字走 FULLTEXT 还是 LIKE，见 {@link #supportsFulltext}。</p>
     */
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");

    /**
     * 搜索超时隔离线程池。
     *
     * <p>几个刻意的取舍：</p>
     * <ul>
     *   <li><b>daemon 线程</b>：超时后正被 {@code Future.get} 抛弃的 JDBC 查询仍会在池里跑完
     *       （JDBC 不保证响应 {@code cancel(true)}），非 daemon 线程会让 JVM 无法退出；</li>
     *   <li><b>有界队列 + AbortPolicy</b>：超时查询会短暂堆积，队列满时直接拒绝、
     *       由调用方按"过载"处理（见 {@code executeKeywordSearch} 的 RejectedExecutionException 分支），
     *       绝不能让搜索把线程堆到 OOM；</li>
     *   <li>池子很小（2~4）是故意的：正常搜索是毫秒级，池子只用来承担"少数慢查询"的隔离，
     *       真出现大面积超时会由熔断先一步挡住。</li>
     * </ul>
     */
    private final ExecutorService searchExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),
            runnable -> {
                Thread thread = new Thread(runnable, "cm-search");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

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
    private final SearchProperties searchProperties;
    private final SearchCircuitBreaker searchCircuitBreaker;

    /** 容器关闭时收掉搜索线程池（daemon 线程虽不阻塞退出，显式关闭更干净）。 */
    @PreDestroy
    void shutdownSearchExecutor() {
        searchExecutor.shutdownNow();
    }

    // ================================================================ 查询

    @Override
    public PageResult<ProductListVO> list(ProductQuery query) {
        String keyword = query.getKeyword() == null ? null : query.getKeyword().trim();
        if (!StringUtils.hasText(keyword)) {
            // 无关键字（纯浏览 / 只按分类价格筛）：保持 5.4.4 之前的 MyBatis-Plus 分页路径，
            // 不加缓存也不加超时熔断。理由是"新商品上架后应立刻可见"比"省一次查询"更重要，
            // 60 秒的浏览列表缓存会让刚审核通过的商品延迟出现。
            LambdaQueryWrapper<Product> wrapper = buildListWrapper(query);
            // 游客 / 买家视角：只返回上架中商品
            wrapper.eq(Product::getStatus, ProductStatus.ON_SALE);
            return pageQuery(query, wrapper);
        }
        return keywordSearch(query, keyword);
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
        // 注意：不能直接返回 PageResult.empty，否则翻到超出末页时会丢失 total
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                toVoList(result.getRecords()));
    }

    /**
     * Entity 列表 → VO 列表（批量补分类名与卖家展示字段，避免 N+1）。
     *
     * <p>被两条列表路径共用：MyBatis-Plus 分页（{@link #pageQuery}）与关键词检索
     * （{@link #runKeywordQuery}）。抽出来是为了保证两条路径产出的 VO <b>逐字段一致</b>。</p>
     */
    private List<ProductListVO> toVoList(List<Product> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, String> categoryNames = loadCategoryNames(records);
        Map<Long, User> sellers = loadSellers(records);
        return records.stream()
                .map(product -> fill(new ProductListVO(), product,
                        categoryNames.get(product.getCategoryId()),
                        sellers.get(product.getUserId())))
                .collect(Collectors.toList());
    }

    // ================================================================ 关键词检索（批次 5.4.4）

    /**
     * 关键词检索主流程：<b>缓存 → 熔断 → FULLTEXT → LIKE 降级</b>。
     *
     * <p>读缓存放在熔断之前：熔断期间若命中缓存就直接给真实结果，比返回空列表更好。</p>
     */
    private PageResult<ProductListVO> keywordSearch(ProductQuery query, String keyword) {
        SearchProperties.Cache cacheConfig = searchProperties.getCache();
        String cacheKey = cacheConfig.isEnabled() ? buildSearchCacheKey(query, keyword) : null;

        if (cacheKey != null) {
            PageResult<ProductListVO> cached = readSearchCache(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        SearchProperties.CircuitBreaker breakerConfig = searchProperties.getCircuitBreaker();
        if (breakerConfig.isEnabled() && searchCircuitBreaker.isOpen()) {
            // 熔断期间直接兜底，不打 DB。注意：这条空结果**不写缓存**，
            // 否则一次 30 秒的熔断会被 60 秒的缓存"续命"，比熔断本身活得更久。
            log.warn("搜索已熔断，直接返回空列表（搜索繁忙，请稍后重试）: keyword={}, timeoutMs={}, openMillis={}",
                    keyword, breakerConfig.getTimeoutMs(), breakerConfig.getOpenMillis());
            return PageResult.empty(query.current(), query.pageSize());
        }

        PageResult<ProductListVO> result = executeKeywordSearch(query, keyword);
        if (result == null) {
            // 超时 / 线程池过载：返回空列表且不写缓存（理由同上）
            return PageResult.empty(query.current(), query.pageSize());
        }
        if (cacheKey != null) {
            writeSearchCache(cacheKey, result);
        }
        return result;
    }

    /**
     * 在超时预算内执行检索。
     *
     * @return 正常结果；返回 {@code null} 表示"超时 / 过载"，调用方应返回空列表且不要缓存
     */
    private PageResult<ProductListVO> executeKeywordSearch(ProductQuery query, String keyword) {
        SearchProperties.CircuitBreaker config = searchProperties.getCircuitBreaker();
        if (!config.isEnabled()) {
            // 开关关闭：同步直查，不做 500ms 超时隔离（排障时用）
            return doKeywordSearch(query, keyword);
        }

        Future<PageResult<ProductListVO>> future;
        try {
            future = searchExecutor.submit(() -> doKeywordSearch(query, keyword));
        } catch (RejectedExecutionException e) {
            // 线程池被打满（前面的慢查询还占着线程）→ 按过载处理，等同超时
            recordSearchTimeout(keyword, "搜索线程池已满（过载）");
            return null;
        }

        try {
            PageResult<ProductListVO> result = future.get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
            searchCircuitBreaker.recordSuccess();
            return result;
        } catch (TimeoutException e) {
            // 只取消 Future；JDBC 查询不保证响应中断，会在池里跑完（daemon 线程，不影响退出）
            future.cancel(true);
            recordSearchTimeout(keyword, "查询超过 " + config.getTimeoutMs() + "ms");
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            recordSearchTimeout(keyword, "查询被中断");
            return null;
        } catch (ExecutionException e) {
            // 能走到这里说明 FULLTEXT 与 LIKE **两条路都失败了**（doKeywordSearch 已吞掉前者）
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("搜索执行失败（FULLTEXT 与 LIKE 均未成功）: keyword={}, err={}", keyword, cause.getMessage());
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "搜索失败，请稍后重试");
        }
    }

    /**
     * 实际执行检索：先 FULLTEXT，失败则 LIKE 降级。
     *
     * <p>只捕获 {@link DataAccessException}（Spring 对 SQLException 的统一转换）：
     * 索引不存在、ngram 解析器不可用、SQL 语法不被支持都落在这里。其它异常（如 NPE）
     * 属于代码缺陷，必须让它冒出去，不能被"降级"掩盖。</p>
     */
    private PageResult<ProductListVO> doKeywordSearch(ProductQuery query, String keyword) {
        if (supportsFulltext(keyword)) {
            try {
                return runKeywordQuery(query, keyword, true);
            } catch (DataAccessException e) {
                log.warn("FULLTEXT 检索不可用，降级为 LIKE: keyword={}, err={}", keyword, e.getMessage());
            }
        }
        return runKeywordQuery(query, keyword, false);
    }

    /**
     * 关键字是否适合走 FULLTEXT（批次 5.4.5 收紧了判据）。
     *
     * <p>两个条件<b>必须同时满足</b>：</p>
     * <ol>
     *   <li><b>长度 ≥ ngram_token_size（2）</b>：ngram 按 N 元组切词，长度不足 N 的关键字
     *       （单字「书」「鞋」、单字母「a」）切不出任何 token，FULLTEXT 结果必然为空 ——
     *       必须走 LIKE，否则用户会从"搜得到"变成"永远搜不到"；</li>
     *   <li><b>含 CJK（汉字）字符</b>：ngram 是为中日韩设计的，用在拉丁文上会明显放宽召回。
     *       实测（5.4.4）：{@code keyword=keyboard} 命中「Nike 运动鞋 42 码」——
     *       因为 ngram 把 keyboard 切成 ke/ey/yb/bo/oa/ar/rd，而 Nike 也含 ke。
     *       中文没有这个问题（实测「C语言」「机械键盘」都准确），所以纯 ASCII 关键字
     *       一律交给 LIKE：牺牲一点性能换取"不返回毫不相关的商品"。</li>
     * </ol>
     *
     * <p>代价说明：纯 ASCII 关键字走 LIKE 时不再有相关度排序，且耗时随表大小线性增长。
     * 按第 4 章的商品量上限（5000 条）实测仍在十几毫秒量级，可接受。</p>
     */
    private boolean supportsFulltext(String keyword) {
        if (keyword.codePointCount(0, keyword.length()) < NGRAM_TOKEN_SIZE) {
            return false;
        }
        return CJK_PATTERN.matcher(keyword).find();
    }

    /** 按指定路径查一次（count + select），并组装成与浏览列表同构的 PageResult。 */
    private PageResult<ProductListVO> runKeywordQuery(ProductQuery query, String keyword, boolean fulltext) {
        long total = fulltext
                ? productMapper.countFulltext(query, keyword)
                : productMapper.countLike(query, keyword);

        List<Product> records = Collections.emptyList();
        if (total > 0) {
            int size = (int) query.pageSize();
            int offset = (int) Math.max(0L, (query.current() - 1L) * size);
            records = fulltext
                    ? productMapper.searchFulltext(query, keyword, offset, size)
                    : productMapper.searchLike(query, keyword, offset, size);
        }
        return PageResult.of(total, query.current(), query.pageSize(), toVoList(records));
    }

    /** 记录一次超时并处理熔断开闸日志。 */
    private void recordSearchTimeout(String keyword, String reason) {
        boolean opened = searchCircuitBreaker.recordTimeout();
        if (opened) {
            log.error("搜索连续超时达阈值，熔断开闸 {}ms（期间直接返回空列表）: keyword={}, reason={}",
                    searchProperties.getCircuitBreaker().getOpenMillis(), keyword, reason);
        } else {
            log.warn("搜索超时（第 {} 次连续）: keyword={}, reason={}",
                    searchCircuitBreaker.consecutiveTimeouts(), keyword, reason);
        }
    }

    /**
     * 组装搜索缓存 Key。
     *
     * <p>Key 里必须带上<b>所有会影响结果的维度</b>：关键字、分类、价格区间、成色、交易方式、
     * 排序字段与方向、分页。少带一个维度就会出现"换了排序却返回上一次顺序"这类诡异现象 ——
     * 这是缓存 Key 设计最容易漏的地方。</p>
     *
     * <p>关键字做 URL-safe 编码：中文可直接进 Key，但空格、{@code *}、{@code :} 等字符会破坏可读性
     * 甚至与分隔符混淆，编码后一律变成安全的 ASCII。</p>
     *
     * <p><b>编码语义备忘</b>（实测，和直觉不同）：{@code URLEncoder} 把空格编成 {@code +}，
     * 把 {@code +} 编成 {@code %2B}，把 {@code :} 编成 {@code %3A}，但<b>不编码</b> {@code *}。
     * 对本场景而言这就够了：{@code ':'} 与 {@code '+'} 一定被编码，所以关键字<b>不可能伪造出
     * 分隔符</b>（"a:b" 与 "a b" 不会撞 Key）；剩下的 {@code *} 对 Redis Key 完全无害
     * （只有 KEYS 通配才特殊，我们从不按通配读搜索缓存）。</p>
     *
     * <p><b>可以安全共享</b>：{@code list()} 恒定只返回 {@code status=1}，且不使用
     * {@link UserContext}，因此结果与"谁在查"无关，不存在越权读到他人可见性的问题。</p>
     */
    private String buildSearchCacheKey(ProductQuery query, String keyword) {
        String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        return SEARCH_CACHE_PREFIX
                + encodedKeyword + ':'
                + query.getCategoryId() + ':'
                + query.getMinPrice() + ':'
                + query.getMaxPrice() + ':'
                + query.getConditionLevel() + ':'
                + query.getTradeType() + ':'
                + (query.getSortBy() == null ? "create_time" : query.getSortBy()) + ':'
                + (query.getOrder() == null ? "desc" : query.getOrder()) + ':'
                + query.current() + ':'
                + query.pageSize();
    }

    /** 读搜索缓存：任何异常（连接失败 / 反序列化失败）都降级为直接查库。 */
    private PageResult<ProductListVO> readSearchCache(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, new TypeReference<PageResult<ProductListVO>>() {
            });
        } catch (Exception e) {
            log.warn("搜索缓存读取降级, 直接查库: key={}, err={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 写搜索缓存：TTL = 配置值 + 0~jitter 秒随机抖动（防雪崩）。
     *
     * <p>空结果也写入 —— 这是<b>防缓存穿透</b>的关键：否则"不存在的词"每次都会实打实查一遍库。</p>
     */
    private void writeSearchCache(String key, PageResult<ProductListVO> result) {
        try {
            SearchProperties.Cache config = searchProperties.getCache();
            long jitter = config.getTtlJitterSeconds() <= 0
                    ? 0L
                    : ThreadLocalRandom.current().nextLong(config.getTtlJitterSeconds() + 1);
            Duration ttl = Duration.ofSeconds(config.getTtlSeconds() + jitter);
            stringRedisTemplate.opsForValue()
                    .set(key, objectMapper.writeValueAsString(result), ttl);
        } catch (Exception e) {
            log.warn("搜索缓存写入降级: key={}, err={}", key, e.getMessage());
        }
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
