package com.campus.market.service;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.dto.admin.DailyCount;
import com.campus.market.mapper.AdminStatsMapper;
import com.campus.market.service.impl.AdminStatsServiceImpl;
import com.campus.market.vo.AdminHotProductVO;
import com.campus.market.vo.AdminOrderStatusVO;
import com.campus.market.vo.AdminOverviewVO;
import com.campus.market.vo.AdminProductCategoryVO;
import com.campus.market.vo.AdminTrendVO;
import com.campus.market.vo.HotProductItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理端统计单测（批次 5.5.1）。
 *
 * <h3>守的是什么</h3>
 * <ol>
 *   <li><b>8 个数字逐个断言</b>：需求里"每个数字都要有断言"，一个不漏；</li>
 *   <li><b>日期切点</b>：4 个"今日"查询必须收到<b>上海时区今天 00:00:00</b>，
 *       且注入的时钟时区不对时也不能算错（本批绝对禁止无时区 {@code LocalDate.now()}）；</li>
 *   <li><b>GMV 口径</b>：只统计 {@code status=3}（已完成），且"今日"按 {@code finish_time} 而不是
 *       {@code create_time} —— 这条锁在 SQL 注解上（见 ⑤），因为它是口径而非算法；</li>
 *   <li><b>无数据返回 0 而不是 null</b>：mock 全部返回 null 时 8 个字段仍必须是 0；</li>
 *   <li><b>恒定 8 条状态分布</b> + 空分类保留 + 孤儿商品兜底；</li>
 *   <li><b>缓存</b>：命中不查库、前缀独立、TTL 在 60~70 秒抖动区间、读写异常降级不炸；</li>
 *   <li><b>统计不分状态</b>：商品总数 SQL 里不得出现 status 过滤
 *       —— 防止把 {@code AdminProductQuery.status} 默认 3 那个坑带进统计。</li>
 * </ol>
 *
 * <h3>测试环境</h3>
 * <p>用假 Redis（{@code Map}）+ 真 {@link ObjectMapper}，让"序列化 → 存 → 读 → 反序列化"
 * 真的走一遍（与 5.4.4 的 ProductSearchTest 同一套路）；时钟固定为
 * {@code 2026-10-02T03:30:00Z}（= 北京时间 11:30），因此"今天"是一个确定的日子，
 * 断言不会因为跑测试的时刻不同而漂移。</p>
 *
 * <p>本测试<b>不需要</b> {@code TableInfoHelper.initTableInfo}：统计实现里全部是自定义
 * {@code @Select} 聚合查询，没有任何 {@code lambdaQuery().select(...)} 式的列名翻译。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminStatsServiceTest {

    /** 固定时刻：北京时间 2026-10-02 11:30。 */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-02T03:30:00Z");

    /** 与之对应的"今天 00:00:00"。 */
    private static final LocalDateTime TODAY_START = LocalDateTime.of(2026, 10, 2, 0, 0);

    @Mock
    private AdminStatsMapper adminStatsMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    /** 假 Redis 存储 */
    private final Map<String, String> redisStore = new HashMap<>();

    /** 记录每次写缓存用的 TTL（验证抖动区间） */
    private final List<Duration> writtenTtls = new ArrayList<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AdminStatsServiceImpl adminStatsService;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        redisStore.clear();
        writtenTtls.clear();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0)));
        lenient().doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            writtenTtls.add(inv.getArgument(2));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        buildService(Clock.fixed(FIXED_INSTANT, AdminStatsServiceImpl.ZONE_SHANGHAI));
    }

    private void buildService(Clock clock) {
        // 构造参数顺序 = AdminStatsServiceImpl 里 final 字段的声明顺序
        adminStatsService = new AdminStatsServiceImpl(adminStatsMapper, stringRedisTemplate, objectMapper);
        adminStatsService.setClock(clock);
    }

    /** 打桩：8 个数字分别是 100/5/50/3/30/2/5000.00/300.00。 */
    private void stubOverview(Long userTotal, Long userTodayNew, Long productTotal, Long productTodayNew,
                              Long orderTotal, Long orderTodayNew, BigDecimal gmvTotal, BigDecimal gmvToday) {
        when(adminStatsMapper.countUsers()).thenReturn(userTotal);
        when(adminStatsMapper.countUsersCreatedSince(any())).thenReturn(userTodayNew);
        when(adminStatsMapper.countProducts()).thenReturn(productTotal);
        when(adminStatsMapper.countProductsCreatedSince(any())).thenReturn(productTodayNew);
        when(adminStatsMapper.countOrders()).thenReturn(orderTotal);
        when(adminStatsMapper.countOrdersCreatedSince(any())).thenReturn(orderTodayNew);
        when(adminStatsMapper.sumFinishedAmount()).thenReturn(gmvTotal);
        when(adminStatsMapper.sumFinishedAmountSince(any())).thenReturn(gmvToday);
    }

    // ================================================================ ① 概览 8 个数字

    @Test
    @DisplayName("① 概览：8 个数字逐个映射（用户/商品/订单 总数与今日新增 + GMV 累计与今日）")
    void overviewShouldMapAllEightNumbers() {
        stubOverview(100L, 5L, 50L, 3L, 30L, 2L, new BigDecimal("5000.00"), new BigDecimal("300.00"));

        AdminOverviewVO vo = adminStatsService.overview();

        assertThat(vo.getUserTotal()).isEqualTo(100L);          // ①
        assertThat(vo.getUserTodayNew()).isEqualTo(5L);         // ②
        assertThat(vo.getProductTotal()).isEqualTo(50L);        // ③
        assertThat(vo.getProductTodayNew()).isEqualTo(3L);      // ④
        assertThat(vo.getOrderTotal()).isEqualTo(30L);          // ⑤
        assertThat(vo.getOrderTodayNew()).isEqualTo(2L);        // ⑥
        assertThat(vo.getGmvTotal()).isEqualByComparingTo("5000.00"); // ⑦
        assertThat(vo.getGmvToday()).isEqualByComparingTo("300.00");  // ⑧
    }

    @Test
    @DisplayName("④ 边界：库里一条数据都没有（mapper 全返回 null）→ 8 个数字都是 0，绝不是 null")
    void emptyDatabaseShouldReturnZerosNotNull() {
        stubOverview(null, null, null, null, null, null, null, null);

        AdminOverviewVO vo = adminStatsService.overview();

        assertThat(vo.getUserTotal()).isZero();
        assertThat(vo.getUserTodayNew()).isZero();
        assertThat(vo.getProductTotal()).isZero();
        assertThat(vo.getProductTodayNew()).isZero();
        assertThat(vo.getOrderTotal()).isZero();
        assertThat(vo.getOrderTodayNew()).isZero();
        // 金额用 compareTo：BigDecimal.ZERO 与 0.00 的 scale 不同，equals 会判不等
        assertThat(vo.getGmvTotal()).isNotNull().isEqualByComparingTo("0");
        assertThat(vo.getGmvToday()).isNotNull().isEqualByComparingTo("0");
    }

    // ================================================================ ② 日期切点与时区

    @Test
    @DisplayName("② 日期切点：4 个「今日」查询收到的都必须是上海时区今天 00:00:00")
    void todayQueriesShouldReceiveShanghaiMidnight() {
        stubOverview(1L, 1L, 1L, 1L, 1L, 1L, BigDecimal.ONE, BigDecimal.ONE);

        adminStatsService.overview();

        ArgumentCaptor<LocalDateTime> users = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> products = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> orders = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> gmv = ArgumentCaptor.forClass(LocalDateTime.class);

        verify(adminStatsMapper).countUsersCreatedSince(users.capture());
        verify(adminStatsMapper).countProductsCreatedSince(products.capture());
        verify(adminStatsMapper).countOrdersCreatedSince(orders.capture());
        verify(adminStatsMapper).sumFinishedAmountSince(gmv.capture());

        assertThat(users.getValue()).isEqualTo(TODAY_START);
        assertThat(products.getValue()).isEqualTo(TODAY_START);
        assertThat(orders.getValue()).isEqualTo(TODAY_START);
        assertThat(gmv.getValue()).isEqualTo(TODAY_START);

        // "总数"类查询不接受时间参数：一旦有人给它加参数，说明口径被改成"按时间段统计"了
        verify(adminStatsMapper).countUsers();
        verify(adminStatsMapper).countProducts();
        verify(adminStatsMapper).countOrders();
    }

    @Test
    @DisplayName("③ 时区：即使 JVM/注入时钟不是 GMT+8，也必须按上海算「今天」（不得用无时区 LocalDate.now()）")
    void todayShouldAlwaysFollowShanghaiZone() {
        // 2026-10-01T17:00Z = 北京时间 2026-10-02 01:00 —— UTC 视角还停在 10-01，上海已是 10-02
        buildService(Clock.fixed(Instant.parse("2026-10-01T17:00:00Z"), ZoneId.of("UTC")));
        stubOverview(0L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO);

        adminStatsService.overview();

        ArgumentCaptor<LocalDateTime> captured = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(adminStatsMapper).countOrdersCreatedSince(captured.capture());
        // 若实现改成无时区 LocalDate.now()，这里会拿到 2026-10-01 → 用例必须红
        assertThat(captured.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 2, 0, 0));
    }

    // ================================================================ ⑤ GMV 口径（SQL 级锁定）

    @Test
    @DisplayName("⑤ GMV 口径：SQL 只统计 status=3（已完成）；今日 GMV 按 finish_time 而不是 create_time")
    void gmvSqlShouldOnlyCountFinishedOrders() {
        String total = mapperSql("sumFinishedAmount");
        assertThat(total).contains("status = 3").contains("is_deleted = 0");
        assertThat(total).contains("SUM(amount)");
        // 未完成/已取消的订单绝不能被算进 GMV：SQL 里不得出现 IN (...) 之类的放宽写法
        assertThat(total).doesNotContain("status IN");

        String today = mapperSql("sumFinishedAmountSince");
        assertThat(today).contains("status = 3");
        assertThat(today).contains("finish_time >= #{start}");
        assertThat(today).doesNotContain("create_time");
    }

    @Test
    @DisplayName("⑯ 反坑：商品总数 SQL 不带 status 过滤（严禁把 AdminProductQuery 默认 status=3 的坑带进统计）")
    void productTotalSqlMustNotFilterStatus() {
        assertThat(mapperSql("countProducts")).doesNotContain("status");
        assertThat(mapperSql("countProductsCreatedSince")).doesNotContain("status");
        // 订单总数同样不分状态（已取消/已冻结也要算）
        assertThat(mapperSql("countOrders")).doesNotContain("status");
    }

    @Test
    @DisplayName("⑮ SQL 全局约定：每条统计 SQL 都必须显式带 is_deleted = 0（自定义 SQL 不会被 MP 自动补）")
    void everyStatsSqlShouldFilterLogicDelete() {
        for (Method method : AdminStatsMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            assertThat(select).as("%s 必须标注 @Select", method.getName()).isNotNull();
            assertThat(String.join(" ", select.value()))
                    .as("%s 的 SQL 必须显式过滤逻辑删除", method.getName())
                    .contains("is_deleted = 0");
        }
    }

    // ================================================================ 状态分布

    @Test
    @DisplayName("⑥ 订单状态分布：恒定 8 条（0~7 顺序），库里没有的状态补 0")
    void orderStatusDistributionShouldAlwaysReturnEightRows() {
        when(adminStatsMapper.countOrdersByStatus()).thenReturn(List.of(
                new AdminOrderStatusVO(0, 10L),
                new AdminOrderStatusVO(1, 5L),
                new AdminOrderStatusVO(3, 7L)));

        List<AdminOrderStatusVO> list = adminStatsService.orderStatusDistribution();

        assertThat(list).hasSize(8);
        assertThat(list).extracting(AdminOrderStatusVO::getStatus)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(list).extracting(AdminOrderStatusVO::getCount)
                .containsExactly(10L, 5L, 0L, 7L, 0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("⑦ 边界：一条订单都没有 → 仍是 8 条，且每条的 count 都是 0（不是 null）")
    void emptyOrdersShouldStillReturnEightZeroRows() {
        when(adminStatsMapper.countOrdersByStatus()).thenReturn(List.of());

        List<AdminOrderStatusVO> list = adminStatsService.orderStatusDistribution();

        assertThat(list).hasSize(8);
        assertThat(list).allSatisfy(row -> assertThat(row.getCount()).isZero());
    }

    @Test
    @DisplayName("⑥ 补充：脏数据不致命 —— status 为 null 的行被忽略，count 为 null 的行按 0 计")
    void dirtyStatusRowsShouldNotBreakDistribution() {
        List<AdminOrderStatusVO> rows = new ArrayList<>();
        rows.add(null);
        rows.add(new AdminOrderStatusVO(null, 99L));
        rows.add(new AdminOrderStatusVO(2, null));
        when(adminStatsMapper.countOrdersByStatus()).thenReturn(rows);

        List<AdminOrderStatusVO> list = adminStatsService.orderStatusDistribution();

        assertThat(list).hasSize(8);
        assertThat(list.get(2).getCount()).isZero();
        assertThat(list).extracting(AdminOrderStatusVO::getCount).containsOnly(0L);
    }

    // ================================================================ 分类分布

    @Test
    @DisplayName("⑧ 分类分布：空分类（count=0）保留，孤儿商品补在最后一条")
    void categoryDistributionShouldKeepEmptyCategoriesAndAppendOrphans() {
        when(adminStatsMapper.countProductsByCategory()).thenReturn(List.of(
                new AdminProductCategoryVO(1L, "教材书籍", 20L),
                new AdminProductCategoryVO(2L, "数码电子", 15L),
                new AdminProductCategoryVO(6L, "其他闲置", 0L)));
        when(adminStatsMapper.countProductsWithoutCategory()).thenReturn(2L);

        List<AdminProductCategoryVO> list = adminStatsService.productCategoryDistribution();

        assertThat(list).hasSize(4);
        assertThat(list.get(0).getCategoryId()).isEqualTo(1L);
        assertThat(list.get(0).getCategoryName()).isEqualTo("教材书籍");
        assertThat(list.get(0).getCount()).isEqualTo(20L);
        assertThat(list.get(2).getCount()).as("空分类必须保留，管理员要能看出哪个分类是空的").isZero();
        // 孤儿行：categoryId/categoryName 为 null，由前端字典出「未分类」文案
        AdminProductCategoryVO orphan = list.get(3);
        assertThat(orphan.getCategoryId()).isNull();
        assertThat(orphan.getCategoryName()).isNull();
        assertThat(orphan.getCount()).isEqualTo(2L);
        // 分布之和必须能对上商品总数：20 + 15 + 0 + 2
        assertThat(list.stream().mapToLong(AdminProductCategoryVO::getCount).sum()).isEqualTo(37L);
    }

    @Test
    @DisplayName("⑨ 边界：没有孤儿商品时不追加 null 行；分类表为空时返回空列表（不是 null）")
    void categoryDistributionWithoutOrphans() {
        when(adminStatsMapper.countProductsByCategory()).thenReturn(List.of(
                new AdminProductCategoryVO(1L, "教材书籍", 3L)));
        when(adminStatsMapper.countProductsWithoutCategory()).thenReturn(0L);

        assertThat(adminStatsService.productCategoryDistribution()).hasSize(1);

        // 清掉上一个断言留下的缓存，否则第二次调用会命中缓存而看不出 mapper 的返回值
        redisStore.clear();
        when(adminStatsMapper.countProductsByCategory()).thenReturn(List.of());
        when(adminStatsMapper.countProductsWithoutCategory()).thenReturn(null);
        assertThat(adminStatsService.productCategoryDistribution()).isEmpty();
        // 分类分布只碰商品与分类表，不得顺带查用户表
        verify(adminStatsMapper, never()).countUsers();
    }

    // ================================================================ 缓存

    @Test
    @DisplayName("⑩ 缓存命中：第二次相同请求不再查库，且取回的数字与首次完全一致")
    void secondRequestShouldHitCache() {
        stubOverview(100L, 5L, 50L, 3L, 30L, 2L, new BigDecimal("5000.00"), new BigDecimal("300.00"));

        AdminOverviewVO first = adminStatsService.overview();
        AdminOverviewVO second = adminStatsService.overview();

        assertThat(second.getUserTotal()).isEqualTo(first.getUserTotal());
        assertThat(second.getGmvTotal()).isEqualByComparingTo(first.getGmvTotal());
        assertThat(second.getGmvToday()).isEqualByComparingTo("300.00");
        // 关键断言：DB 只被查了一次（第二次命中缓存）
        verify(adminStatsMapper, times(1)).countUsers();
        verify(adminStatsMapper, times(1)).sumFinishedAmount();
        // 缓存前缀必须独立于搜索缓存 search:
        assertThat(redisStore.keySet()).hasSize(1);
        assertThat(redisStore.keySet()).allSatisfy(key -> assertThat(key).startsWith("admin:stats:"));
    }

    @Test
    @DisplayName("⑪ 三个接口三个独立 Key：概览 / 状态分布 / 分类分布不互相串用")
    void threeEndpointsShouldUseThreeDistinctKeys() {
        stubOverview(1L, 1L, 1L, 1L, 1L, 1L, BigDecimal.ONE, BigDecimal.ONE);
        when(adminStatsMapper.countOrdersByStatus()).thenReturn(List.of());
        when(adminStatsMapper.countProductsByCategory()).thenReturn(List.of());
        when(adminStatsMapper.countProductsWithoutCategory()).thenReturn(0L);

        adminStatsService.overview();
        adminStatsService.orderStatusDistribution();
        adminStatsService.productCategoryDistribution();

        assertThat(redisStore.keySet()).containsExactlyInAnyOrder(
                "admin:stats:overview",
                "admin:stats:order-status",
                "admin:stats:product-category");
    }

    @Test
    @DisplayName("⑫ TTL 抖动：写入 TTL 落在 60~70 秒（60 + 0~10 抖动），防缓存雪崩")
    void cacheTtlShouldBeJittered() {
        stubOverview(1L, 1L, 1L, 1L, 1L, 1L, BigDecimal.ONE, BigDecimal.ONE);

        for (int i = 0; i < 30; i++) {
            redisStore.clear();
            adminStatsService.overview();
        }

        assertThat(writtenTtls).hasSize(30);
        assertThat(writtenTtls).allSatisfy(ttl -> assertThat(ttl.getSeconds()).isBetween(60L, 70L));
    }

    @Test
    @DisplayName("⑬ 缓存降级：Redis 读/写抛异常时接口照常返回数据（不把缓存故障变成页面故障）")
    void redisFailureShouldDegradeToDatabase() {
        stubOverview(100L, 5L, 50L, 3L, 30L, 2L, new BigDecimal("5000.00"), new BigDecimal("300.00"));

        // 读失败 → 直接查库
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> brokenOps = mock(ValueOperations.class);
        when(brokenOps.get(anyString())).thenThrow(new org.springframework.dao.QueryTimeoutException("Redis 超时"));
        when(stringRedisTemplate.opsForValue()).thenReturn(brokenOps);

        AdminOverviewVO vo = adminStatsService.overview();
        assertThat(vo.getUserTotal()).isEqualTo(100L);

        // 写失败 → 数据照样返回，不抛异常
        lenient().doThrow(new org.springframework.dao.QueryTimeoutException("Redis 写超时"))
                .when(brokenOps).set(anyString(), anyString(), any(Duration.class));
        AdminOverviewVO again = adminStatsService.overview();
        assertThat(again.getGmvTotal()).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("⑭ 缓存往返：存进 Redis 的是 JSON（不是 Java 序列化），且金额精度不丢")
    void cachedJsonShouldRoundTrip() {
        stubOverview(100L, 5L, 50L, 3L, 30L, 2L, new BigDecimal("5000.00"), new BigDecimal("300.00"));
        adminStatsService.overview();

        String json = redisStore.get("admin:stats:overview");
        assertThat(json).isNotNull().contains("userTotal").contains("gmvTotal");
        assertThat(json).contains("5000.00");

        // 把 mapper 换成"新值"，再从缓存读：读到旧值说明缓存真的生效（而不是每次直查 DB）
        when(adminStatsMapper.countUsers()).thenReturn(999L);
        AdminOverviewVO fromCache = adminStatsService.overview();
        assertThat(fromCache.getUserTotal()).as("命中缓存则不应看到新值 999").isEqualTo(100L);
        assertThat(fromCache.getGmvToday()).isEqualByComparingTo("300.00");
        verify(adminStatsMapper, times(1)).countUsers();
        verify(adminStatsMapper, times(1)).countUsersCreatedSince(any());
    }

    // ================================================================ 5.5.2 趋势

    @Test
    @DisplayName("⑰ 趋势：三组数据都返回，日期升序含今天，缺数据的日期补 0（三个数组与 dates 等长）")
    void trendShouldFillMissingDaysWithZeros() {
        when(adminStatsMapper.countOrdersByDay(any(), any())).thenReturn(List.of(
                new DailyCount(LocalDate.of(2026, 9, 30), 4L),
                new DailyCount(LocalDate.of(2026, 10, 2), 7L)));
        when(adminStatsMapper.countProductsByDay(any(), any())).thenReturn(List.of(
                new DailyCount(LocalDate.of(2026, 10, 1), 3L)));
        when(adminStatsMapper.countUsersByDay(any(), any())).thenReturn(List.of());

        AdminTrendVO vo = adminStatsService.trend(7);

        assertThat(vo.getDays()).isEqualTo(7);
        assertThat(vo.getDates()).containsExactly(
                "2026-09-26", "2026-09-27", "2026-09-28", "2026-09-29",
                "2026-09-30", "2026-10-01", "2026-10-02");
        assertThat(vo.getOrderCounts()).containsExactly(0L, 0L, 0L, 0L, 4L, 0L, 7L);
        assertThat(vo.getProductCounts()).containsExactly(0L, 0L, 0L, 0L, 0L, 3L, 0L);
        assertThat(vo.getUserCounts()).containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 0L);
        // 三个数组与日期轴严格等长：前端直接把它们当序列用（折线不会错位）
        assertThat(vo.getOrderCounts()).hasSameSizeAs(vo.getDates());
        assertThat(vo.getProductCounts()).hasSameSizeAs(vo.getDates());
        assertThat(vo.getUserCounts()).hasSameSizeAs(vo.getDates());
    }

    @Test
    @DisplayName("⑱ 趋势 days=30：返回 30 天（首日 = 今天-29 天），不是 7 天")
    void trendShouldReturnThirtyDaysWhenDaysIs30() {
        when(adminStatsMapper.countOrdersByDay(any(), any())).thenReturn(List.of());

        AdminTrendVO vo = adminStatsService.trend(30);

        assertThat(vo.getDays()).isEqualTo(30);
        assertThat(vo.getDates()).hasSize(30);
        assertThat(vo.getDates().get(0)).isEqualTo("2026-09-03");
        assertThat(vo.getDates().get(29)).isEqualTo("2026-10-02");
        assertThat(vo.getOrderCounts()).hasSize(30).containsOnly(0L);
    }

    @Test
    @DisplayName("⑲ 趋势窗口：左闭右开 —— start = 今天-(days-1) 00:00，end = 明天 00:00（不用 23:59:59）")
    void trendWindowShouldBeLeftClosedRightOpen() {
        when(adminStatsMapper.countOrdersByDay(any(), any())).thenReturn(List.of());

        adminStatsService.trend(7);

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(adminStatsMapper).countOrdersByDay(start.capture(), end.capture());
        assertThat(start.getValue()).isEqualTo(LocalDateTime.of(2026, 9, 26, 0, 0));
        // 右边界是"明天 00:00"而不是"今天 23:59:59"：毫秒级数据不会被漏掉
        assertThat(end.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 3, 0, 0));

        // 三条 SQL 必须拿到**同一个**窗口（不同表不同窗口 = 三条线对不齐）
        ArgumentCaptor<LocalDateTime> start30 = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end30 = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(adminStatsMapper).countProductsByDay(start30.capture(), end30.capture());
        verify(adminStatsMapper).countUsersByDay(start30.capture(), end30.capture());
        assertThat(start30.getAllValues()).containsOnly(LocalDateTime.of(2026, 9, 26, 0, 0));
        assertThat(end30.getAllValues()).containsOnly(LocalDateTime.of(2026, 10, 3, 0, 0));
    }

    @Test
    @DisplayName("⑳ 边界：空库时趋势仍返回 7 个日期 + 21 个 0（不是空数组、不是 null）")
    void trendEmptyDatabaseShouldReturnZeros() {
        when(adminStatsMapper.countOrdersByDay(any(), any())).thenReturn(null);
        when(adminStatsMapper.countProductsByDay(any(), any())).thenReturn(List.of());
        when(adminStatsMapper.countUsersByDay(any(), any())).thenReturn(List.of());

        AdminTrendVO vo = adminStatsService.trend(7);

        assertThat(vo.getDates()).hasSize(7);
        assertThat(vo.getOrderCounts()).hasSize(7).containsOnly(0L);
        assertThat(vo.getProductCounts()).hasSize(7).containsOnly(0L);
        assertThat(vo.getUserCounts()).hasSize(7).containsOnly(0L);
    }

    @Test
    @DisplayName("㉑ 白名单：days 只允许 7 / 30，其它值抛 code=100 且不查库（含 null 走默认 7）")
    void trendDaysOutsideWhitelistShouldBeRejected() {
        for (int illegal : new int[]{8, 15, 29, 31, 0, -7}) {
            assertThatThrownBy(() -> adminStatsService.trend(illegal))
                    .as("days=%s 必须被白名单拦下", illegal)
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ErrorCode.PARAM_ERROR.getCode());
        }
        // 越界请求不应该打库（先校验后查询）
        verifyNoInteractions(adminStatsMapper);

        // null（前端省略参数）走默认 7 天，属于合法调用
        when(adminStatsMapper.countOrdersByDay(any(), any())).thenReturn(List.of());
        assertThat(adminStatsService.trend(null).getDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("㉒ 缓存 Key 隔离：trend:7 与 trend:30 必须是两个 Key（禁止共用）")
    void trendCacheKeysShouldBeSeparatedByDays() {
        when(adminStatsMapper.countOrdersByDay(any(), any())).thenReturn(List.of());

        adminStatsService.trend(7);
        adminStatsService.trend(30);
        adminStatsService.trend(7); // 第 3 次命中 7 天的缓存

        assertThat(redisStore.keySet()).containsExactlyInAnyOrder("admin:stats:trend:7", "admin:stats:trend:30");
        // 两次真实查库（7 天 + 30 天），第 3 次是缓存命中；三条 SQL 各查两次
        verify(adminStatsMapper, times(2)).countOrdersByDay(any(), any());
        verify(adminStatsMapper, times(2)).countProductsByDay(any(), any());
        verify(adminStatsMapper, times(2)).countUsersByDay(any(), any());
    }

    @Test
    @DisplayName("㉓ 趋势缓存：第二次相同 days 不再查库，TTL 落在 60~70 秒（抖动）")
    void trendSecondCallShouldHitCache() {
        when(adminStatsMapper.countOrdersByDay(any(), any())).thenReturn(List.of());

        AdminTrendVO first = adminStatsService.trend(7);
        AdminTrendVO second = adminStatsService.trend(7);

        assertThat(second.getDates()).isEqualTo(first.getDates());
        verify(adminStatsMapper, times(1)).countOrdersByDay(any(), any());
        verify(adminStatsMapper, times(1)).countProductsByDay(any(), any());
        verify(adminStatsMapper, times(1)).countUsersByDay(any(), any());
        assertThat(writtenTtls).hasSize(1);
        assertThat(writtenTtls.get(0).getSeconds()).isBetween(60L, 70L);
    }

    @Test
    @DisplayName("㉔ 趋势 SQL 口径：DATE() 只用于分组、不做时区换算；显式逻辑删除过滤 + 右开区间")
    void trendSqlShouldGroupByDateWithoutTimezoneConversion() {
        for (String method : new String[]{"countOrdersByDay", "countProductsByDay", "countUsersByDay"}) {
            String sql = mapperSql(method);
            assertThat(sql).as("%s 必须按天分组", method).contains("GROUP BY DATE(create_time)");
            assertThat(sql).contains("is_deleted = 0");
            // 时区口径统一由 Java 层算好（GMT+8），SQL 里不做 CONVERT_TZ / DATE_FORMAT 之类的换算
            assertThat(sql).doesNotContain("CONVERT_TZ");
            // 左闭右开：>= start 且 < end
            assertThat(sql).contains("create_time >= #{start}").contains("create_time < #{end}");
        }
    }

    // ================================================================ 5.5.2 热门榜

    @Test
    @DisplayName("㉕ 热门榜：按订单数降序（SQL 给乱序也要排对），同分按 productId 升序；limit 下推给 SQL")
    void hotProductsShouldSortByOrderCountDesc() {
        // 故意乱序返回：排序保证不能依赖"派生表行序会被外层继承"这种 MySQL 行为
        when(adminStatsMapper.selectHotProducts(any(), any(), anyInt())).thenReturn(List.of(
                new HotProductItem(5L, "四级真题", "教材书籍", 2L),
                new HotProductItem(9L, "机械键盘", "数码电子", 5L),
                new HotProductItem(2L, "台灯", "生活用品", 5L)));

        AdminHotProductVO vo = adminStatsService.hotProducts(7, 10);

        assertThat(vo.getDays()).isEqualTo(7);
        assertThat(vo.getItems()).extracting(HotProductItem::getProductId).containsExactly(2L, 9L, 5L);
        assertThat(vo.getItems()).extracting(HotProductItem::getOrderCount).containsExactly(5L, 5L, 2L);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(adminStatsMapper).selectHotProducts(any(), any(), limit.capture());
        assertThat(limit.getValue()).isEqualTo(10);
    }

    @Test
    @DisplayName("㉖ 热门榜：商品被逻辑删除时仍上榜，标题用订单快照、分类名为 null（前端显示「—」）")
    void hotProductsShouldKeepOrderSnapshotTitleWhenProductDeleted() {
        when(adminStatsMapper.selectHotProducts(any(), any(), anyInt())).thenReturn(List.of(
                // 商品已删除：LEFT JOIN tb_product 拿不到分类 → categoryName 为 null，
                // 但标题来自 tb_order.product_title（快照），必须原样保留
                new HotProductItem(14L, "二手高等数学教材（第三版）", null, 5L)));

        AdminHotProductVO vo = adminStatsService.hotProducts(7, 10);

        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getItems().get(0).getProductTitle()).isEqualTo("二手高等数学教材（第三版）");
        assertThat(vo.getItems().get(0).getCategoryName()).isNull();
        assertThat(vo.getItems().get(0).getOrderCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("㉗ 边界：近 7 天无订单 → items 是空数组而不是 null（days 照常回显）")
    void hotProductsEmptyShouldReturnEmptyList() {
        when(adminStatsMapper.selectHotProducts(any(), any(), anyInt())).thenReturn(List.of());
        assertThat(adminStatsService.hotProducts(7, 10).getItems()).isEmpty();

        redisStore.clear();
        when(adminStatsMapper.selectHotProducts(any(), any(), anyInt())).thenReturn(null);
        assertThat(adminStatsService.hotProducts(7, 10).getItems()).isEmpty();
    }

    @Test
    @DisplayName("㉘ 白名单：limit 只允许 1~20、days 只允许 7/30，越界抛 code=100 且不查库")
    void hotProductsWhitelistShouldBeEnforced() {
        for (Integer illegal : new Integer[]{0, 21, 100, -1}) {
            assertThatThrownBy(() -> adminStatsService.hotProducts(7, illegal))
                    .as("limit=%s 必须被拦下", illegal)
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ErrorCode.PARAM_ERROR.getCode());
        }
        assertThatThrownBy(() -> adminStatsService.hotProducts(15, 10))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PARAM_ERROR.getCode());
        verifyNoInteractions(adminStatsMapper);

        // 边界值 1 与 20 合法
        when(adminStatsMapper.selectHotProducts(any(), any(), anyInt())).thenReturn(List.of());
        assertThat(adminStatsService.hotProducts(7, 1).getItems()).isEmpty();
        assertThat(adminStatsService.hotProducts(7, 20).getItems()).isEmpty();
        // null 走默认值（days=7 / limit=10）
        assertThat(adminStatsService.hotProducts(null, null).getDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("㉙ 缓存 Key 隔离：hot-products:{days}:{limit} —— 换 days 或 limit 都不能命中同一份缓存")
    void hotProductsCacheKeysShouldCoverBothDimensions() {
        when(adminStatsMapper.selectHotProducts(any(), any(), anyInt())).thenReturn(List.of());

        adminStatsService.hotProducts(7, 10);
        adminStatsService.hotProducts(7, 20);
        adminStatsService.hotProducts(30, 10);
        adminStatsService.hotProducts(7, 10); // 命中缓存

        assertThat(redisStore.keySet()).containsExactlyInAnyOrder(
                "admin:stats:hot-products:7:10",
                "admin:stats:hot-products:7:20",
                "admin:stats:hot-products:30:10");
        verify(adminStatsMapper, times(3)).selectHotProducts(any(), any(), anyInt());
    }

    @Test
    @DisplayName("㉚ 热门榜缓存：第二次相同参数不再查库，TTL 落在 60~70 秒")
    void hotProductsSecondCallShouldHitCache() {
        when(adminStatsMapper.selectHotProducts(any(), any(), anyInt())).thenReturn(List.of(
                new HotProductItem(1L, "教材", "教材书籍", 3L)));

        adminStatsService.hotProducts(7, 10);
        AdminHotProductVO second = adminStatsService.hotProducts(7, 10);

        assertThat(second.getItems()).hasSize(1);
        assertThat(second.getItems().get(0).getProductTitle()).isEqualTo("教材");
        verify(adminStatsMapper, times(1)).selectHotProducts(any(), any(), anyInt());
        assertThat(writtenTtls).hasSize(1);
        assertThat(writtenTtls.get(0).getSeconds()).isBetween(60L, 70L);
    }

    @Test
    @DisplayName("㉛ 热门榜 SQL 口径：取最新标题用 GROUP_CONCAT+SUBSTRING_INDEX（不是 MAX），LEFT JOIN 不过滤已删除商品")
    void hotProductsSqlShouldUseLatestTitleAndLeftJoin() {
        String sql = mapperSql("selectHotProducts");

        // 同一商品可能改过名：必须取"最新一单"的标题
        assertThat(sql).contains("GROUP_CONCAT(o.product_title ORDER BY o.create_time DESC");
        assertThat(sql).contains("SUBSTRING_INDEX");
        // 严禁 MAX(product_title)：那是字典序最大，不是最新
        assertThat(sql).doesNotContain("MAX(");
        // 商品可能已被逻辑删除，但仍要能上榜 + 拿到分类 → 必须是 LEFT JOIN 且不加 p.is_deleted
        assertThat(sql).contains("LEFT JOIN tb_product p ON p.id = t.product_id");
        assertThat(sql).doesNotContain("p.is_deleted");
        // 分类名靠 LEFT JOIN tb_category（选项 A：拿不到就是 null，由前端显示「—」）
        assertThat(sql).contains("LEFT JOIN tb_category c");
        // 内层取前 N、外层再排一次；右开区间
        assertThat(sql).contains("LIMIT #{limit}");
        assertThat(sql).contains("ORDER BY t.order_count DESC");
        assertThat(sql).contains("o.create_time >= #{start}").contains("o.create_time < #{end}");
        assertThat(sql).contains("o.is_deleted = 0");
    }

    // ================================================================ 工具

    /** 读取 Mapper 方法上 {@code @Select} 注解里的 SQL（用于锁定口径，而不只是锁定算法）。 */
    private static String mapperSql(String methodName) {        for (Method method : AdminStatsMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                Select select = method.getAnnotation(Select.class);
                assertThat(select).as("%s 必须标注 @Select", methodName).isNotNull();
                return String.join(" ", select.value());
            }
        }
        throw new IllegalStateException("AdminStatsMapper 上不存在方法: " + methodName);
    }
}
