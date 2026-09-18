package com.campus.market.service.impl;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.dto.admin.DailyCount;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.AdminStatsMapper;
import com.campus.market.service.AdminStatsService;
import com.campus.market.vo.AdminHotProductVO;
import com.campus.market.vo.AdminOrderStatusVO;
import com.campus.market.vo.AdminOverviewVO;
import com.campus.market.vo.AdminProductCategoryVO;
import com.campus.market.vo.AdminTrendVO;
import com.campus.market.vo.HotProductItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 管理端统计服务实现（批次 5.5.1）。
 *
 * <h3>一、口径（8 个数字，全部已与需求逐条对齐）</h3>
 * <pre>
 *   userTotal         tb_user   is_deleted=0 全部用户（含封禁用户）
 *   userTodayNew      create_time >= 今天 00:00:00 (GMT+8)
 *   productTotal      tb_product is_deleted=0 全部商品，**不分状态**（0/1/2/3 全算）
 *   productTodayNew   create_time >= 今天 00:00:00
 *   orderTotal        tb_order  is_deleted=0 全部订单，**不分状态**（含已取消/已冻结）
 *   orderTodayNew     create_time >= 今天 00:00:00
 *   gmvTotal          status=3（已完成）订单的 SUM(amount)
 *   gmvToday          status=3 且 finish_time >= 今天 00:00:00 的 SUM(amount)
 * </pre>
 *
 * <h3>二、为什么"今日"必须显式带时区</h3>
 * <p>用 {@code LocalDate.now()}（无参）取的是 <b>JVM 默认时区</b>的今天。本项目 JVM 与数据库
 * 的时区并不由代码保证（服务器可能是 UTC），一旦不匹配，"今日新增"会在每天 08:00 整点附近的
 * 统计上出现 8 小时错位（北京时间凌晨下的单被算进前一天）。所以这里固定
 * {@link #ZONE_SHANGHAI}：{@code LocalDate.now(clock).atStartOfDay()}，
 * 并且时钟可被测试替换（见 {@link #setClock}）以锁死边界。</p>
 *
 * <h3>三、缓存（沿用 5.4.4 的搜索缓存范式：短 TTL + 独立前缀 + 抖动）</h3>
 * <ul>
 *   <li>前缀 {@code admin:stats:}，<b>不复用</b> {@code search:}（5.4.5 踩坑：
 *       多实例共享 Redis 时清缓存是按前缀清的，混用前缀会误伤另一半功能）；</li>
 *   <li>TTL 60 秒 + 0~10 秒随机抖动：同一批 Key 若 TTL 完全一致会同时失效，
 *       把并发读一起打到 DB（缓存雪崩）；</li>
 *   <li><b>没有主动失效</b>：统计只读，60 秒自然过期足够；管理端页面上也明确写了
 *       "数据缓存 60 秒"，避免管理员刚操作完发现数字没动而以为坏了；</li>
 *   <li>读写异常（Redis 挂了 / 反序列化失败）一律降级为直接查库，<b>不把异常抛给调用方</b> ——
 *       统计数据不该因为缓存故障而看不了。</li>
 * </ul>
 *
 * <h3>四、无数据时的返回值</h3>
 * <p>所有计数为 null（SQL 未返回 / mock 未打桩）时兜成 <b>0</b>，金额兜成 {@code BigDecimal.ZERO}；
 * 订单状态分布恒定 8 条。前端因此可以放心地对数字做四则运算，不必到处判空。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminStatsServiceImpl implements AdminStatsService {

    /** 业务时区：全站统一 GMT+8（与 application.yml 的 spring.jackson.time-zone 一致）。 */
    public static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 统计缓存 TTL（秒）。 */
    private static final long CACHE_TTL_SECONDS = 60L;

    /** TTL 随机抖动上限（秒），防同一秒写入的 Key 集体失效。0 = 不抖动。 */
    private static final long CACHE_TTL_JITTER_SECONDS = 10L;

    /**
     * 趋势 / 热门榜允许的窗口天数白名单（批次 5.5.2）。
     *
     * <p>只有 7 与 30：前端的范围切换固定在「近 7 天 / 近 30 天」两档，
     * 后端也不再支持任意天数 —— 任意天数会让缓存 Key 无限膨胀
     * （{@code admin:stats:trend:{days}}），且趋势图的横轴密度也没法保证可读。</p>
     */
    private static final int[] ALLOWED_WINDOW_DAYS = {7, 30};

    /** 窗口天数缺省值（省略参数时按近 7 天算，与前端默认档一致）。 */
    private static final int DEFAULT_WINDOW_DAYS = 7;

    /** 热门榜返回条数：默认 10，白名单 1~20。 */
    private static final int DEFAULT_HOT_LIMIT = 10;
    private static final int MIN_HOT_LIMIT = 1;
    private static final int MAX_HOT_LIMIT = 20;

    /**
     * 订单状态全量（0~7），用于把 GROUP BY 的结果补齐成恒定 8 条。
     *
     * <p>这里引用 {@link OrderStatus} 常量而不是字面量 0~7：状态码本身是后端定义的事实，
     * 不随文案变化；而"每个状态叫什么"由前端字典负责（后端 VO 不带 label）。</p>
     */
    private static final int[] ALL_ORDER_STATUS = {
            OrderStatus.PENDING_PAY,
            OrderStatus.PAID,
            OrderStatus.SHIPPED,
            OrderStatus.FINISHED,
            OrderStatus.CANCELLED,
            OrderStatus.FROZEN,
            OrderStatus.REFUND_APPLYING,
            OrderStatus.REFUND_REJECTED
    };

    private final AdminStatsMapper adminStatsMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 业务时钟（带 {@link #ZONE_SHANGHAI}）；非 final 以便测试注入固定时钟。 */
    private Clock clock = Clock.system(ZONE_SHANGHAI);

    /**
     * 注入固定时钟（仅测试使用）。
     *
     * <p>有了它，"今天 00:00:00" 这个切点才能被断言成<b>一个确定的时刻</b>，
     * 而不是"跟当前时间算出来的某个值比一下"（那种断言在跨零点跑测试时会假绿）。</p>
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    // ================================================================ 概览

    @Override
    public AdminOverviewVO overview() {
        String key = RedisKeys.adminStatsOverview();
        AdminOverviewVO cached = readCache(key, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        AdminOverviewVO vo = loadOverview(todayStart());
        writeCache(key, vo);
        return vo;
    }

    /**
     * 查库组装概览。
     *
     * <p>每个数字都单独一条 SQL（8 条 COUNT/SUM）：单表 COUNT 走索引，代价极低，
     * 而拆开的好处是"哪个数字算错了"在 SQL 日志里一眼可见（合成一条大 SQL 就只剩一行输出）。</p>
     */
    private AdminOverviewVO loadOverview(LocalDateTime todayStart) {
        AdminOverviewVO vo = new AdminOverviewVO();
        vo.setUserTotal(zeroIfNull(adminStatsMapper.countUsers()));
        vo.setUserTodayNew(zeroIfNull(adminStatsMapper.countUsersCreatedSince(todayStart)));
        vo.setProductTotal(zeroIfNull(adminStatsMapper.countProducts()));
        vo.setProductTodayNew(zeroIfNull(adminStatsMapper.countProductsCreatedSince(todayStart)));
        vo.setOrderTotal(zeroIfNull(adminStatsMapper.countOrders()));
        vo.setOrderTodayNew(zeroIfNull(adminStatsMapper.countOrdersCreatedSince(todayStart)));
        vo.setGmvTotal(zeroIfNull(adminStatsMapper.sumFinishedAmount()));
        vo.setGmvToday(zeroIfNull(adminStatsMapper.sumFinishedAmountSince(todayStart)));
        return vo;
    }

    // ================================================================ 订单状态分布

    @Override
    public List<AdminOrderStatusVO> orderStatusDistribution() {
        String key = RedisKeys.adminStatsOrderStatus();
        List<AdminOrderStatusVO> cached = readCache(key, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        List<AdminOrderStatusVO> list = loadOrderStatusDistribution();
        writeCache(key, list);
        return list;
    }

    private List<AdminOrderStatusVO> loadOrderStatusDistribution() {
        Map<Integer, Long> counts = new HashMap<>();
        for (AdminOrderStatusVO row : nullSafe(adminStatsMapper.countOrdersByStatus())) {
            if (row == null || row.getStatus() == null) {
                continue;
            }
            // 防御性 merge：GROUP BY status 正常不会给出重复行，但脏数据（status 为 null）不参与
            counts.merge(row.getStatus(), row.getCount() == null ? 0L : row.getCount(), Long::sum);
        }
        List<AdminOrderStatusVO> list = new ArrayList<>(ALL_ORDER_STATUS.length);
        for (int status : ALL_ORDER_STATUS) {
            list.add(new AdminOrderStatusVO(status, counts.getOrDefault(status, 0L)));
        }
        return list;
    }

    // ================================================================ 商品分类分布

    @Override
    public List<AdminProductCategoryVO> productCategoryDistribution() {
        String key = RedisKeys.adminStatsProductCategory();
        List<AdminProductCategoryVO> cached = readCache(key, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        List<AdminProductCategoryVO> list = loadProductCategoryDistribution();
        writeCache(key, list);
        return list;
    }

    private List<AdminProductCategoryVO> loadProductCategoryDistribution() {
        List<AdminProductCategoryVO> list = new ArrayList<>();
        for (AdminProductCategoryVO row : nullSafe(adminStatsMapper.countProductsByCategory())) {
            if (row == null) {
                continue;
            }
            list.add(new AdminProductCategoryVO(row.getCategoryId(), row.getCategoryName(),
                    row.getCount() == null ? 0L : row.getCount()));
        }
        // 孤儿商品（分类被逻辑删除）单独补一条，保证「各分类之和 + 孤儿 = 商品总数」
        Long orphan = adminStatsMapper.countProductsWithoutCategory();
        if (orphan != null && orphan > 0L) {
            list.add(new AdminProductCategoryVO(null, null, orphan));
        }
        return list;
    }

    // ================================================================ 趋势（5.5.2）

    @Override
    public AdminTrendVO trend(Integer days) {
        int windowDays = requireAllowedDays(days);
        // Key 必须带 days：7 天与 30 天的数组长度与日期区间都不同（见 RedisKeys 注释）
        String key = RedisKeys.adminStatsTrend(windowDays);
        AdminTrendVO cached = readCache(key, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        AdminTrendVO vo = loadTrend(windowDays);
        writeCache(key, vo);
        return vo;
    }

    /**
     * 查库组装趋势：3 条按天分组的 SQL + 在 Java 里补齐缺口。
     *
     * <p>窗口是<b>左闭右开</b> {@code [start, end)}：{@code start = 今天-(days-1) 00:00}，
     * {@code end = 明天 00:00}。用右开区间而不是"今天 23:59:59"，是为了避免
     * 毫秒级边界（23:59:59.500 这种数据会被漏掉）—— 这是时间窗口最常见的写法错误。</p>
     *
     * <p>补齐规则：SQL 只返回"有数据的日期"，这里按 {@code today-(days-1)} ~ {@code today}
     * 逐日取值，取不到就是 0。三个数组长度恒等于 days，且与 dates 一一对应，
     * 前端可以直接当序列用（折线不会错位）。</p>
     */
    private AdminTrendVO loadTrend(int days) {
        LocalDate today = today();
        LocalDateTime start = dayStart(today.minusDays(days - 1L));
        LocalDateTime end = dayStart(today.plusDays(1));

        Map<LocalDate, Long> orders = indexDailyCounts(adminStatsMapper.countOrdersByDay(start, end));
        Map<LocalDate, Long> products = indexDailyCounts(adminStatsMapper.countProductsByDay(start, end));
        Map<LocalDate, Long> users = indexDailyCounts(adminStatsMapper.countUsersByDay(start, end));

        List<String> dates = new ArrayList<>(days);
        List<Long> orderCounts = new ArrayList<>(days);
        List<Long> productCounts = new ArrayList<>(days);
        List<Long> userCounts = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            // 从最早的一天到"今天"，升序（ECharts 的 x 轴按此顺序）
            LocalDate date = today.minusDays(days - 1L - i);
            // LocalDate.toString() 就是 ISO-8601 的 yyyy-MM-dd，与前端约定的格式一致，
            // 不额外用 DateTimeFormatter（多此一举且容易写错成 yyyy-MM-DD）
            dates.add(date.toString());
            orderCounts.add(orders.getOrDefault(date, 0L));
            productCounts.add(products.getOrDefault(date, 0L));
            userCounts.add(users.getOrDefault(date, 0L));
        }

        AdminTrendVO vo = new AdminTrendVO();
        vo.setDays(days);
        vo.setDates(dates);
        vo.setOrderCounts(orderCounts);
        vo.setProductCounts(productCounts);
        vo.setUserCounts(userCounts);
        return vo;
    }

    /** 把「按天分组」的结果整理成 {@code 日期 → 计数} 的 Map（顺带兜住脏数据）。 */
    private static Map<LocalDate, Long> indexDailyCounts(List<DailyCount> rows) {
        Map<LocalDate, Long> counts = new HashMap<>();
        for (DailyCount row : nullSafe(rows)) {
            if (row == null || row.getStatDate() == null) {
                continue;
            }
            counts.merge(row.getStatDate(), row.getCount() == null ? 0L : row.getCount(), Long::sum);
        }
        return counts;
    }

    // ================================================================ 热门榜（5.5.2）

    @Override
    public AdminHotProductVO hotProducts(Integer days, Integer limit) {
        int windowDays = requireAllowedDays(days);
        int size = requireAllowedLimit(limit);
        // Key 同时带 days 与 limit：不同 limit 是"前 10"与"前 20"，不是同一份数据
        String key = RedisKeys.adminStatsHotProducts(windowDays, size);
        AdminHotProductVO cached = readCache(key, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        AdminHotProductVO vo = loadHotProducts(windowDays, size);
        writeCache(key, vo);
        return vo;
    }

    private AdminHotProductVO loadHotProducts(int days, int limit) {
        LocalDate today = today();
        LocalDateTime start = dayStart(today.minusDays(days - 1L));
        LocalDateTime end = dayStart(today.plusDays(1));

        List<HotProductItem> items = new ArrayList<>();
        for (HotProductItem row : nullSafe(adminStatsMapper.selectHotProducts(start, end, limit))) {
            if (row == null || row.getProductId() == null) {
                continue;
            }
            // 只做兜底（orderCount 为 null → 0），不改标题与分类名：
            // 标题是订单快照（商品改名/删除后依然可取），分类名可能为 null（前端显示「—」）
            items.add(new HotProductItem(row.getProductId(), row.getProductTitle(), row.getCategoryName(),
                    row.getOrderCount() == null ? 0L : row.getOrderCount()));
        }

        // SQL 里已经 ORDER BY order_count DESC；这里再排一次是**防御**：
        // 排序保证不依赖"派生表的行序会被外层继承"这种 MySQL 行为，将来换实现也不会让
        // 前端契约（列表按订单数降序）失效。排序键与 SQL 完全一致
        // （订单数降序 + productId 升序兜底），同分顺序稳定、不会每次刷新跳来跳去。
        items.sort(Comparator.comparingLong((HotProductItem item) -> item.getOrderCount())
                .reversed()
                .thenComparing(HotProductItem::getProductId));

        AdminHotProductVO vo = new AdminHotProductVO();
        vo.setDays(days);
        vo.setItems(items);
        return vo;
    }

    // ================================================================ 缓存与工具

    /**
     * 「今天」的起点（00:00:00），按 {@link #ZONE_SHANGHAI} 计算。
     *
     * <p>这里用 {@code clock.withZone(ZONE_SHANGHAI)} 而<b>不是</b> {@code LocalDate.now()}：
     * 前者把时区钉死在代码里（就算注入的时钟时区不对也照样按 GMT+8 取"今天"），
     * 后者取的是 JVM 默认时区。这是本批的硬性约定 —— 服务器时区不可控，
     * 一旦 JVM 跑在 UTC，"今日新增"就会在北京时间 00:00~08:00 之间算错一整天。</p>
     *
     * <p>（5.5.2 起改为委托 {@link #today()}：趋势与热门榜也要按同一天切窗口，
     * 时区口径只能有一处实现，否则迟早出现"概览按 GMT+8、趋势按 UTC"的分裂。
     * 行为与 5.5.1 完全一致，5.5.1 的单测未做任何修改。）</p>
     */
    private LocalDateTime todayStart() {
        return today().atStartOfDay();
    }

    /**
     * GMT+8 的「今天」。
     *
     * <p>趋势窗口、热门榜窗口、概览的"今日"全部由它派生，
     * 时区口径<b>只有这一处</b>（见 {@link #ZONE_SHANGHAI} 与 {@link #setClock}）。</p>
     */
    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE_SHANGHAI));
    }

    /** 某天的 00:00:00（窗口左边界统一从这里取，避免各处手写 atStartOfDay）。 */
    private static LocalDateTime dayStart(LocalDate date) {
        return date.atStartOfDay();
    }

    /**
     * days 白名单校验：只允许 7 或 30；null 视为默认 7。
     *
     * <p><b>为什么放在 Service 而不是 Controller</b>（5.5.1 没有带参数的接口，故无先例可抄，
     * 这里给出本批的选择与理由）：</p>
     * <ol>
     *   <li>项目既有的"参数白名单"语义就是 <b>code=100</b>（{@code @Valid} 失败、
     *       {@code @Pattern} 失败都走 {@code GlobalExceptionHandler} → PARAM_ERROR），
     *       抛 {@link BusinessException} 得到完全相同的语义（HTTP 200 + code=100）；</li>
     *   <li>放在 Service：单测能<b>直接</b>断言 code=100，不必启动 MVC 上下文；</li>
     *   <li>趋势与热门榜共用同一个校验方法，不会出现"某个接口漏校验"。</li>
     * </ol>
     * <p>Controller 侧只给 {@code @RequestParam(defaultValue=...)} 提供默认值，
     * 不做第二份校验（两处校验迟早会不一致）。</p>
     */
    private static int requireAllowedDays(Integer days) {
        if (days == null) {
            return DEFAULT_WINDOW_DAYS;
        }
        for (int allowed : ALLOWED_WINDOW_DAYS) {
            if (allowed == days) {
                return days;
            }
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "days 仅支持 7 或 30");
    }

    /** limit 白名单校验：1~20；null 视为默认 10。 */
    private static int requireAllowedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_HOT_LIMIT;
        }
        if (limit < MIN_HOT_LIMIT || limit > MAX_HOT_LIMIT) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "limit 仅支持 1~20");
        }
        return limit;
    }

    private static Long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** 读缓存：任何异常（连接失败 / 反序列化失败）都降级为直接查库。 */
    private <T> T readCache(String key, TypeReference<T> type) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("统计缓存读取降级, 直接查库: key={}, err={}", key, e.getMessage());
            return null;
        }
    }

    /** 写缓存：TTL = 60 秒 + 0~10 秒随机抖动（防雪崩）；写入失败只记日志。 */
    private void writeCache(String key, Object value) {
        try {
            long jitter = CACHE_TTL_JITTER_SECONDS <= 0L
                    ? 0L
                    : ThreadLocalRandom.current().nextLong(CACHE_TTL_JITTER_SECONDS + 1);
            Duration ttl = Duration.ofSeconds(CACHE_TTL_SECONDS + jitter);
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("统计缓存写入降级: key={}, err={}", key, e.getMessage());
        }
    }
}
