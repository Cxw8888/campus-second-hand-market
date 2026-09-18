package com.campus.market.service;

import com.campus.market.mapper.AdminStatsMapper;
import com.campus.market.service.impl.AdminStatsServiceImpl;
import com.campus.market.vo.AdminOrderStatusVO;
import com.campus.market.vo.AdminOverviewVO;
import com.campus.market.vo.AdminProductCategoryVO;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    // ================================================================ 工具

    /** 读取 Mapper 方法上 {@code @Select} 注解里的 SQL（用于锁定口径，而不只是锁定算法）。 */
    private static String mapperSql(String methodName) {
        for (Method method : AdminStatsMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                Select select = method.getAnnotation(Select.class);
                assertThat(select).as("%s 必须标注 @Select", methodName).isNotNull();
                return String.join(" ", select.value());
            }
        }
        throw new IllegalStateException("AdminStatsMapper 上不存在方法: " + methodName);
    }
}
