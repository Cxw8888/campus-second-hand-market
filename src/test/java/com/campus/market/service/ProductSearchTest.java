package com.campus.market.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.market.common.result.PageResult;
import com.campus.market.config.properties.SearchProperties;
import com.campus.market.dto.product.ProductQuery;
import com.campus.market.entity.Category;
import com.campus.market.entity.Product;
import com.campus.market.entity.User;
import com.campus.market.mapper.CategoryMapper;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.mapper.UserMapper;
import com.campus.market.service.impl.ProductServiceImpl;
import com.campus.market.service.support.SearchCircuitBreaker;
import com.campus.market.vo.ProductListVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品关键词检索单测（批次 5.4.4）：FULLTEXT / LIKE 降级 / 缓存 / 熔断 / 参数边界。
 *
 * <h3>为什么这样搭测试环境</h3>
 * <ul>
 *   <li><b>假 Redis</b>：用一个 {@code Map} 顶替 {@code opsForValue().get/set}，
 *       让"缓存命中"真的走一遍"序列化 → 存 → 读 → 反序列化"，
 *       比对着 mock 数调用次数更容易发现"存进去读不出来"这类问题；</li>
 *   <li><b>真 ObjectMapper</b>（带 JavaTimeModule）：VO 里有 {@code LocalDateTime}，
 *       用 mock 的 ObjectMapper 会把序列化问题整个掩盖掉；</li>
 *   <li><b>假时钟</b>：熔断窗口 30 秒，靠 sleep 验证会把测试拖成半分钟；</li>
 *   <li><b>CountDownLatch 制造慢查询</b>：把 {@code timeout-ms} 调到 50ms 并让 mapper 卡住，
 *       既真实触发 {@code TimeoutException}，又能让"超时后有没有再打库"这个断言保持确定性
 *       （用 {@code Thread.sleep} 的话，排队中的任务会在断言之后才执行，计数会飘）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductSearchTest {

    private static final String KEYWORD = "四级";
    private static final Long CATEGORY_ID = 2L;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    /** 假 Redis 存储 */
    private final Map<String, String> redisStore = new HashMap<>();

    /** 假时钟（熔断窗口验证用） */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private SearchProperties searchProperties;

    private SearchCircuitBreaker breaker;

    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        redisStore.clear();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0)));
        lenient().doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // VO 组装依赖（分类名 / 卖家）：默认返回空，避免 NPE
        lenient().when(categoryMapper.selectBatchIds(any())).thenReturn(List.of());
        lenient().when(userMapper.selectList(any())).thenReturn(List.of());

        searchProperties = new SearchProperties();
        rebuildService();
    }

    /**
     * 纯单测里没有 MyBatis-Plus 运行时，需要手工初始化实体元信息。
     *
     * <p>原因：{@code loadSellers()} 用的是 {@code lambdaQuery().select(User::getId, ...)}，
     * 而 {@code select(...)} 会<b>立即</b>把方法引用翻译成列名，翻译依赖 TableInfo 缓存，
     * 缓存缺失就报 {@code can not find lambda cache for this entity}。
     * （{@code eq(...)} 是懒翻译，所以只按条件构造的 wrapper 不受影响。）</p>
     */
    @BeforeAll
    static void initMybatisPlusEntityMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Product.class);
        TableInfoHelper.initTableInfo(assistant, User.class);
        TableInfoHelper.initTableInfo(assistant, Category.class);
    }

    /**
     * 用当前的 {@link SearchProperties} 重建熔断器与 Service。
     *
     * <p><b>为什么需要重建</b>：{@code SearchCircuitBreaker} 在构造时就把阈值与窗口时长
     * 快照成不可变字段（避免运行中被改到一半），所以测试里改了配置之后必须重新构造
     * 才能生效。</p>
     */
    private void rebuildService() {
        breaker = new SearchCircuitBreaker(searchProperties);
        breaker.setClock(now::get);
        // 构造参数顺序 = ProductServiceImpl 里 final 字段的声明顺序
        productService = new ProductServiceImpl(productMapper, categoryMapper, orderMapper,
                userMapper, stringRedisTemplate, new ObjectMapper().registerModule(new JavaTimeModule()),
                searchProperties, breaker);
    }

    // ================================================================ FULLTEXT 与降级

    @Test
    @DisplayName("① FULLTEXT 命中：返回记录与总数，且不调用 LIKE 路径")
    void fulltextHitShouldReturnRecords() {
        when(productMapper.countFulltext(any(), eq(KEYWORD))).thenReturn(1L);
        when(productMapper.searchFulltext(any(), eq(KEYWORD), anyInt(), anyInt()))
                .thenReturn(List.of(product(7L, "考研英语四级真题", new BigDecimal("25.00"))));

        PageResult<ProductListVO> result = productService.list(query(KEYWORD, 1, 10));

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getTitle()).isEqualTo("考研英语四级真题");
        assertThat(result.getRecords().get(0).getPrice()).isEqualByComparingTo("25.00");
        verify(productMapper).searchFulltext(any(), eq(KEYWORD), eq(0), eq(10));
        verify(productMapper, never()).searchLike(any(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("② LIKE 降级：FULLTEXT 抛数据访问异常 → 自动改走 LIKE，对调用方完全透明")
    void fulltextFailureShouldFallbackToLike() {
        when(productMapper.countFulltext(any(), anyString()))
                .thenThrow(new QueryTimeoutException("FULLTEXT 索引不可用"));
        when(productMapper.countLike(any(), eq(KEYWORD))).thenReturn(1L);
        when(productMapper.searchLike(any(), eq(KEYWORD), anyInt(), anyInt()))
                .thenReturn(List.of(product(9L, "四级词汇书", new BigDecimal("18.50"))));

        PageResult<ProductListVO> result = productService.list(query(KEYWORD, 1, 10));

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getTitle()).isEqualTo("四级词汇书");
        verify(productMapper).searchLike(any(), eq(KEYWORD), eq(0), eq(10));
    }

    @Test
    @DisplayName("② 补充：LIKE 也失败时不再吞异常（不能把真故障伪装成空结果）")
    void whenBothPathsFailShouldPropagate() {
        when(productMapper.countFulltext(any(), anyString())).thenThrow(new QueryTimeoutException("FULLTEXT 挂了"));
        when(productMapper.countLike(any(), anyString())).thenThrow(new QueryTimeoutException("LIKE 也挂了"));

        assertThatThrownBy(() -> productService.list(query(KEYWORD, 1, 10)))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    @DisplayName("③ ngram 保护：单字关键字直接走 LIKE（切不出 2 元组，FULLTEXT 必然搜不到）")
    void singleCharKeywordShouldBypassFulltext() {
        when(productMapper.countLike(any(), eq("书"))).thenReturn(2L);
        when(productMapper.searchLike(any(), eq("书"), anyInt(), anyInt()))
                .thenReturn(List.of(product(1L, "高数书", new BigDecimal("10.00")),
                        product(2L, "英语书", new BigDecimal("12.00"))));

        PageResult<ProductListVO> result = productService.list(query("书", 1, 10));

        assertThat(result.getTotal()).isEqualTo(2L);
        verify(productMapper, never()).countFulltext(any(), anyString());
        verify(productMapper, never()).searchFulltext(any(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("⑭ 路由判据（5.4.5）：纯 ASCII 关键字走 LIKE，含 CJK 才走 FULLTEXT")
    void asciiKeywordShouldBypassFulltext() {
        // 两条路径都给出"有结果"的桩，确保断言的是**路由**而不是"有没有数据"
        when(productMapper.countLike(any(), anyString())).thenReturn(1L);
        when(productMapper.searchLike(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(product(11L, "命中商品", new BigDecimal("9.90"))));
        when(productMapper.countFulltext(any(), anyString())).thenReturn(1L);
        when(productMapper.searchFulltext(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(product(11L, "命中商品", new BigDecimal("9.90"))));

        // ---------- 场景 1：多字纯 ASCII → LIKE ----------
        // ngram 会把 keyboard 切成 ke/ey/yb/bo/oa/ar/rd，而「Nike」也含 ke，
        // 于是 5.4.4 实测出现「搜 keyboard 命中 Nike 运动鞋」的假阳性
        productService.list(query("keyboard", 1, 10));
        verify(productMapper).searchLike(any(), eq("keyboard"), anyInt(), anyInt());
        verify(productMapper, never()).searchFulltext(any(), eq("keyboard"), anyInt(), anyInt());

        // ---------- 场景 2：单字 ASCII → LIKE（5.4.4 已实现） ----------
        productService.list(query("a", 1, 10));
        verify(productMapper).searchLike(any(), eq("a"), anyInt(), anyInt());
        verify(productMapper, never()).searchFulltext(any(), eq("a"), anyInt(), anyInt());

        // ---------- 场景 3：含 CJK 多字 → FULLTEXT ----------
        productService.list(query("机械键盘", 1, 10));
        verify(productMapper).searchFulltext(any(), eq("机械键盘"), anyInt(), anyInt());
        verify(productMapper, never()).searchLike(any(), eq("机械键盘"), anyInt(), anyInt());

        // ---------- 场景 4：含 CJK 单字 → LIKE（5.4.4 已实现） ----------
        productService.list(query("书", 1, 10));
        verify(productMapper).searchLike(any(), eq("书"), anyInt(), anyInt());
        verify(productMapper, never()).searchFulltext(any(), eq("书"), anyInt(), anyInt());
    }

    // ================================================================ 缓存

    @Test
    @DisplayName("④ 缓存命中：第二次相同查询不再访问 mapper，且取回的结果与首次一致")
    void secondIdenticalQueryShouldHitCache() {
        when(productMapper.countFulltext(any(), eq(KEYWORD))).thenReturn(1L);
        when(productMapper.searchFulltext(any(), eq(KEYWORD), anyInt(), anyInt()))
                .thenReturn(List.of(product(7L, "四级真题", new BigDecimal("25.00"))));

        PageResult<ProductListVO> first = productService.list(query(KEYWORD, 1, 10));
        PageResult<ProductListVO> second = productService.list(query(KEYWORD, 1, 10));

        assertThat(second.getTotal()).isEqualTo(first.getTotal());
        assertThat(second.getRecords()).hasSize(1);
        assertThat(second.getRecords().get(0).getTitle()).isEqualTo("四级真题");
        // 关键断言：DB 只被查了一次（第二次是缓存命中）
        verify(productMapper, times(1)).countFulltext(any(), eq(KEYWORD));
        verify(productMapper, times(1)).searchFulltext(any(), eq(KEYWORD), anyInt(), anyInt());
        assertThat(redisStore).hasSize(1);
    }

    @Test
    @DisplayName("⑤ 缓存穿透防护：空结果同样进缓存，第二次不再查库")
    void emptyResultShouldAlsoBeCached() {
        when(productMapper.countFulltext(any(), eq("不存在的词"))).thenReturn(0L);

        PageResult<ProductListVO> first = productService.list(query("不存在的词", 1, 10));
        PageResult<ProductListVO> second = productService.list(query("不存在的词", 1, 10));

        assertThat(first.getTotal()).isZero();
        assertThat(first.getRecords()).isEmpty();
        assertThat(second.getRecords()).isEmpty();
        assertThat(second.getTotal()).isZero();
        verify(productMapper, times(1)).countFulltext(any(), eq("不存在的词"));
        verify(productMapper, never()).searchFulltext(any(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("⑥ 缓存 Key 覆盖全部维度：换排序 / 换页 / 换分类都不能命中同一份缓存")
    void cacheKeyShouldCoverAllDimensions() {
        when(productMapper.countFulltext(any(), eq(KEYWORD))).thenReturn(1L);
        when(productMapper.searchFulltext(any(), eq(KEYWORD), anyInt(), anyInt()))
                .thenReturn(List.of(product(7L, "四级真题", new BigDecimal("25.00"))));

        productService.list(query(KEYWORD, 1, 10));
        productService.list(queryWith(q -> q.setSortBy("price"), KEYWORD, 1, 10));
        productService.list(queryWith(q -> q.setPage(2), KEYWORD, 2, 10));
        productService.list(queryWith(q -> q.setCategoryId(CATEGORY_ID), KEYWORD, 1, 10));

        verify(productMapper, times(4)).countFulltext(any(), eq(KEYWORD));
        assertThat(redisStore).as("四个维度各占一个 Key，说明没有互相串用").hasSize(4);
        assertThat(redisStore.keySet()).allSatisfy(key -> assertThat(key).startsWith("search:"));
    }

    // ================================================================ 熔断

    @Test
    @DisplayName("⑦ 熔断触发：连续 5 次超时后开闸，第 6 次直接返回空列表且不再打 DB")
    void consecutiveTimeoutsShouldOpenBreakerAndSkipDatabase() {
        searchProperties.getCircuitBreaker().setTimeoutMs(50L);
        searchProperties.getCircuitBreaker().setFailureThreshold(5);
        rebuildService();
        blockFulltext();

        for (int i = 0; i < 5; i++) {
            PageResult<ProductListVO> result = productService.list(query(KEYWORD, 1, 10));
            assertThat(result.getRecords()).as("超时应返回空列表（不把异常抛给用户）").isEmpty();
            assertThat(result.getTotal()).isZero();
        }
        assertThat(breaker.isOpen()).as("连续 5 次超时应开闸").isTrue();

        // 第 6 次：熔断已开闸 → 不应再产生任何新的 mapper 调用
        int callsBefore = org.mockito.Mockito.mockingDetails(productMapper).getInvocations().size();
        PageResult<ProductListVO> afterOpen = productService.list(query(KEYWORD, 1, 10));
        int callsAfter = org.mockito.Mockito.mockingDetails(productMapper).getInvocations().size();

        assertThat(afterOpen.getRecords()).isEmpty();
        assertThat(afterOpen.getTotal()).isZero();
        assertThat(callsAfter).as("熔断期间不得访问数据库").isEqualTo(callsBefore);
    }

    @Test
    @DisplayName("⑦ 补充：熔断/超时的兜底空结果不进缓存（否则一次熔断会被 60 秒缓存续命）")
    void fallbackResultShouldNotBeCached() {
        searchProperties.getCircuitBreaker().setTimeoutMs(50L);
        searchProperties.getCircuitBreaker().setFailureThreshold(1);
        rebuildService();
        blockFulltext();

        productService.list(query(KEYWORD, 1, 10)); // 超时 → 开闸
        productService.list(query(KEYWORD, 1, 10)); // 熔断兜底

        assertThat(redisStore).as("兜底空列表不得写缓存").isEmpty();
    }

    @Test
    @DisplayName("⑧ 熔断恢复：30 秒窗口过后自动放行，并恢复正常查询")
    void breakerShouldRecoverAfterWindow() {
        searchProperties.getCircuitBreaker().setTimeoutMs(50L);
        searchProperties.getCircuitBreaker().setFailureThreshold(1);
        rebuildService();
        blockFulltext();

        productService.list(query(KEYWORD, 1, 10));
        assertThat(breaker.isOpen()).isTrue();

        // 窗口内：仍熔断，不碰 DB
        int callsDuringOpen = org.mockito.Mockito.mockingDetails(productMapper).getInvocations().size();
        productService.list(query(KEYWORD, 1, 10));
        assertThat(org.mockito.Mockito.mockingDetails(productMapper).getInvocations().size())
                .isEqualTo(callsDuringOpen);

        // 推进假时钟越过 30 秒，并把 mapper 换成"秒回"
        now.addAndGet(30_100L);
        when(productMapper.countFulltext(any(), eq(KEYWORD))).thenReturn(1L);
        when(productMapper.searchFulltext(any(), eq(KEYWORD), anyInt(), anyInt()))
                .thenReturn(List.of(product(7L, "四级真题", new BigDecimal("25.00"))));

        PageResult<ProductListVO> recovered = productService.list(query(KEYWORD, 1, 10));

        assertThat(recovered.getTotal()).isEqualTo(1L);
        assertThat(recovered.getRecords()).hasSize(1);
    }

    // ================================================================ 参数边界

    @Test
    @DisplayName("⑨ 边界：无关键字走原 MyBatis-Plus 分页路径（不缓存、不走 FULLTEXT / LIKE）")
    void blankKeywordShouldKeepMybatisPlusPath() {
        when(productMapper.selectPage(any(), any())).thenReturn(new Page<>());

        PageResult<ProductListVO> result = productService.list(query("   ", 1, 10));

        assertThat(result.getRecords()).isEmpty();
        verify(productMapper).selectPage(any(), any());
        verify(productMapper, never()).countFulltext(any(), anyString());
        verify(productMapper, never()).countLike(any(), anyString());
        assertThat(redisStore).as("浏览路径不写搜索缓存").isEmpty();
    }

    @Test
    @DisplayName("⑩ 边界：特殊字符关键字既不报错，也不会污染缓存 Key（URL 编码）")
    void specialCharactersShouldBeEncodedInCacheKey() {
        String tricky = "50% 折扣 (特价) + 包邮*";
        when(productMapper.countFulltext(any(), eq(tricky))).thenReturn(0L);

        PageResult<ProductListVO> result = productService.list(query(tricky, 1, 10));

        assertThat(result.getRecords()).isEmpty();
        // 关键字原样（参数化）传给 mapper，SQL 里是 #{}，不存在拼接
        verify(productMapper).countFulltext(any(), eq(tricky));
        // Key 里不得出现裸空格与裸百分号（% 会被编码成 %25）
        // 注意：URLEncoder 并不会编码 '*'（它对 Redis Key 无害），所以这里不断言它
        String key = redisStore.keySet().iterator().next();
        assertThat(key).startsWith("search:").doesNotContain(" ");
        assertThat(key).contains("%25");
    }

    @Test
    @DisplayName("⑩ 补充：Key 编码必须是单射 —— 关键字自带分隔符也不能和别的关键字串味")
    void cacheKeyEncodingMustNotCollide() {
        // 这三个关键字如果编码不当会互相撞车：
        //   "a:b"（冒号是本 Key 的分隔符）/ "a b"（空格被 URLEncoder 编成 +）/ "a+b"（加号被编成 %2B）
        when(productMapper.countFulltext(any(), anyString())).thenReturn(0L);

        productService.list(query("a:b", 1, 10));
        productService.list(query("a b", 1, 10));
        productService.list(query("a+b", 1, 10));

        assertThat(redisStore).as("三个关键字必须落在三个不同的 Key 上").hasSize(3);
        assertThat(redisStore.keySet()).anySatisfy(k -> assertThat(k).contains("%3A"));
        assertThat(redisStore.keySet()).anySatisfy(k -> assertThat(k).contains("%2B"));
    }

    @Test
    @DisplayName("⑪ 边界：offset 由 page/size 正确推导（page=2、size=100 → offset=100）")
    void offsetShouldFollowPageAndSize() {
        when(productMapper.countFulltext(any(), eq(KEYWORD))).thenReturn(150L);
        when(productMapper.searchFulltext(any(), eq(KEYWORD), anyInt(), anyInt()))
                .thenReturn(List.of(product(7L, "四级真题", new BigDecimal("25.00"))));

        productService.list(query(KEYWORD, 2, 100));

        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        verify(productMapper).searchFulltext(any(), eq(KEYWORD), offset.capture(), size.capture());
        assertThat(offset.getValue()).isEqualTo(100);
        assertThat(size.getValue()).isEqualTo(100);
    }

    @Test
    @DisplayName("⑫ 过滤条件下推：分类 / 价格 / 成色 / 交易方式原样交给 mapper（不在内存过滤）")
    void filtersShouldBePushedDownToSql() {
        when(productMapper.countFulltext(any(), eq(KEYWORD))).thenReturn(1L);
        when(productMapper.searchFulltext(any(), eq(KEYWORD), anyInt(), anyInt()))
                .thenReturn(List.of(product(7L, "四级真题", new BigDecimal("25.00"))));

        productService.list(queryWith(q -> {
            q.setCategoryId(CATEGORY_ID);
            q.setMinPrice(new BigDecimal("10.00"));
            q.setMaxPrice(new BigDecimal("50.00"));
            q.setConditionLevel(2);
            q.setTradeType(1);
        }, KEYWORD, 1, 10));

        ArgumentCaptor<ProductQuery> captured = ArgumentCaptor.forClass(ProductQuery.class);
        verify(productMapper).countFulltext(captured.capture(), eq(KEYWORD));
        ProductQuery passed = captured.getValue();
        assertThat(passed.getCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(passed.getMinPrice()).isEqualByComparingTo("10.00");
        assertThat(passed.getMaxPrice()).isEqualByComparingTo("50.00");
        assertThat(passed.getConditionLevel()).isEqualTo(2);
        assertThat(passed.getTradeType()).isEqualTo(1);
    }

    @Test
    @DisplayName("⑬ 开关：熔断关闭走同步直查；缓存关闭则每次都查库")
    void disabledSwitchesShouldDegradeToDirectQuery() {
        searchProperties.getCircuitBreaker().setEnabled(false);
        searchProperties.getCache().setEnabled(false);
        when(productMapper.countFulltext(any(), eq(KEYWORD))).thenReturn(1L);
        when(productMapper.searchFulltext(any(), eq(KEYWORD), anyInt(), anyInt()))
                .thenReturn(List.of(product(7L, "四级真题", new BigDecimal("25.00"))));

        productService.list(query(KEYWORD, 1, 10));
        productService.list(query(KEYWORD, 1, 10));

        verify(productMapper, times(2)).countFulltext(any(), eq(KEYWORD));
        assertThat(redisStore).isEmpty();
    }

    // ================================================================ 工具

    private ProductQuery query(String keyword, int page, int size) {
        return queryWith(q -> {
        }, keyword, page, size);
    }

    private ProductQuery queryWith(Consumer<ProductQuery> customizer, String keyword, int page, int size) {
        ProductQuery query = new ProductQuery();
        query.setKeyword(keyword);
        query.setPage(page);
        query.setSize(size);
        customizer.accept(query);
        return query;
    }

    /**
     * 让 FULLTEXT 路径"卡住"以制造超时。
     *
     * <p>用 latch 而不是 {@code Thread.sleep}：sleep 的话任务会陆续从队列里被取出来执行，
     * "超时后有没有再打库"的计数就会在断言前后漂移；latch 卡住工作线程后，
     * 队列里的任务不会开始，断言是确定性的。2 秒的等待上限保证线程最终会自己释放。</p>
     */
    private void blockFulltext() {
        CountDownLatch blocked = new CountDownLatch(1);
        when(productMapper.countFulltext(any(), anyString())).thenAnswer(inv -> {
            blocked.await(2, TimeUnit.SECONDS);
            return 0L;
        });
        lenient().when(productMapper.searchFulltext(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
    }

    private static Product product(Long id, String title, BigDecimal price) {
        Product product = new Product();
        product.setId(id);
        product.setTitle(title);
        product.setDescription(title + " 描述");
        product.setPrice(price);
        product.setStock(1);
        product.setConditionLevel(2);
        product.setTradeType(1);
        product.setCategoryId(2L);
        product.setUserId(3L);
        product.setStatus(1);
        product.setCreateTime(LocalDateTime.of(2026, 9, 18, 10, 0));
        return product;
    }
}
