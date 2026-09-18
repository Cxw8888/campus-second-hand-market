package com.campus.market.service.impl;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.AdminStatsMapper;
import com.campus.market.service.AdminStatsService;
import com.campus.market.vo.AdminOrderStatusVO;
import com.campus.market.vo.AdminOverviewVO;
import com.campus.market.vo.AdminProductCategoryVO;
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

    // ================================================================ 缓存与工具

    /**
     * 「今天」的起点（00:00:00），按 {@link #ZONE_SHANGHAI} 计算。
     *
     * <p>这里用 {@code clock.withZone(ZONE_SHANGHAI)} 而<b>不是</b> {@code LocalDate.now()}：
     * 前者把时区钉死在代码里（就算注入的时钟时区不对也照样按 GMT+8 取"今天"），
     * 后者取的是 JVM 默认时区。这是本批的硬性约定 —— 服务器时区不可控，
     * 一旦 JVM 跑在 UTC，"今日新增"就会在北京时间 00:00~08:00 之间算错一整天。</p>
     */
    private LocalDateTime todayStart() {
        return LocalDate.now(clock.withZone(ZONE_SHANGHAI)).atStartOfDay();
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
