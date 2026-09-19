package com.campus.market.concurrent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.dto.order.OrderCreateRequest;
import com.campus.market.entity.Order;
import com.campus.market.entity.Product;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.entity.enums.ProductStatus;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.security.LoginUser;
import com.campus.market.security.UserContext;
import com.campus.market.service.NotificationSender;
import com.campus.market.service.NotificationService;
import com.campus.market.service.OrderService;
import com.campus.market.service.OrderTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §9 并发抢购测试：1000 线程抢购 1 件商品，验证 CAS 防超卖。
 *
 * <p><b>刻意不加 {@code @Transactional}：</b>测试事务是绑在发起线程上的，1000 个工作线程
 * 各自开自己的事务、看不到测试事务，回滚也管不到它们；加了只会给人"能回滚"的错觉，
 * 反而更难发现脏数据。所以本类改为 {@link BeforeEach}/{@link AfterEach} 手工建数据、手工清理。</p>
 *
 * <p><b>调用的是真实下单入口</b> {@link OrderService#createOrder}（而不是内层的事务体
 * {@code OrderCreateService.create}）：只有走这一层，防重 Token 的 Redis Lua 校验才会真正参与，
 * 才能真正验证"每个线程独立 Token ⇒ 不出现 202"。线程内自行注入 {@link UserContext}
 * （它是 ThreadLocal，每个工作线程各写各的，不串号）。</p>
 *
 * <p><b>关于响应码分布的预期（重要）：</b>这 999 个失败请求<b>不会</b>只返回 201。
 * 抢购成功后 {@code deductStock} 会把商品从 1-上架联动改为 2-售罄，所以
 * 「读商品时已经看到 status=2」的线程会在第一步的可见性校验就被拦下，返回
 * <b>204 商品不存在或已下架</b>；只有「读商品时恰好还是 status=1、随后 CAS 落空」的线程才返回
 * <b>201 库存不足</b>。因此断言写成 {@code 201 + 204 == 999}，同时单独断言 202 必须为 0。</p>
 */
@SpringBootTest
@Import(OrderConcurrencyTest.SyncNotificationConfig.class)
class OrderConcurrencyTest {

    /** 并发线程数。 */
    private static final int THREADS = 1000;

    /**
     * 合成 ID：tb_product / tb_order 在 V1__init.sql 里没有外键约束，直接写合成 ID 即可，
     * 不会碰到你原有的验收数据（真实数据里 id 才到两位数）。
     *
     * <p><b>批次 6.0.3 · B2 起，卖家必须是一条真实存在的用户行</b>：
     * 下单时新增了"卖家 status=0（正常）"的校验（封禁卖家不得被下单）。
     * 因此本用例在 {@code setUp} 里用同一个合成 ID 造一条 tb_user 卖家行，
     * 并在 {@code tearDown} 里物理删除 —— 否则 1000 个线程会全部被 204 拒绝，
     * 压测直接失去意义。买家不需要用户行：它只出现在 UserContext 与 Redis 防重 Token 里。</p>
     */
    private static final long SELLER_ID = 9_100_001L;
    private static final long BUYER_ID = 9_100_002L;

    private static final int CODE_SUCCESS = 200;
    private static final int CODE_STOCK_NOT_ENOUGH = 201;
    private static final int CODE_REPEAT_SUBMIT = 202;
    private static final int CODE_PRODUCT_NOT_AVAILABLE = 204;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderTokenService orderTokenService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次用例的商品 ID。 */
    private Long productId;

    /** 1000 个互不相同的防重 Token（同一个买家的 1000 次"获取 Token"）。 */
    private List<String> tokens;

    @BeforeEach
    void setUp() {
        // ⓪ 造卖家用户（合成 ID，status=0 正常）：批次 6.0.3 · B2 起下单会校验卖家状态
        jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", SELLER_ID);
        jdbcTemplate.update("INSERT INTO tb_user (id, username, password, nickname, role, status) "
                + "VALUES (?, ?, ?, ?, 0, 0)", SELLER_ID, "concurrency_seller_9100001", "{noop}test-only", "并发测试卖家");

        // ① 造商品：stock=1、status=1（上架）、trade_type=1（面交，省掉邮寄地址校验）
        Product product = new Product();
        product.setUserId(SELLER_ID);
        product.setCategoryId(1L);
        product.setTitle("§9 并发压测商品");
        product.setDescription("1000 线程抢购 1 件，验证 CAS 防超卖");
        product.setPrice(new BigDecimal("9.90"));
        product.setStock(1);
        product.setConditionLevel(1);
        product.setTradeType(1);
        product.setTradeLocation("并发测试地点");
        product.setImageUrls(Collections.singletonList("/static/uploads/demo1.jpg"));
        product.setStatus(ProductStatus.ON_SALE);
        productMapper.insert(product);
        productId = product.getId();

        // ② 预生成 1000 个独立防重 Token。
        //    必须一人一个：若 1000 个线程共用同一个 Token，Lua 的"校验并删除"只会有 1 个成功，
        //    其余 999 个全部在 Token 这一步就被判 202，根本压不到库存 CAS 那一层。
        tokens = new ArrayList<>(THREADS);
        for (int i = 0; i < THREADS; i++) {
            tokens.add(orderTokenService.generate(BUYER_ID, 300L));
        }
    }

    @AfterEach
    void tearDown() {
        // 手工清理：本类没有 @Transactional，所有写入都是真提交，必须自己收尾。
        // 这里刻意走【物理删除】而不是 mapper 的逻辑删除：逻辑删除只是把行标成 is_deleted=1，
        // 反复跑压测会不断堆积墓碑行，反而干扰你后续对 tb_order / tb_product 的统计。
        // 删除条件严格限定在本用例的合成 ID 上，碰不到你的验收数据。
        if (productId != null) {
            jdbcTemplate.update("DELETE FROM tb_order WHERE product_id = ?", productId);
            jdbcTemplate.update("DELETE FROM tb_product WHERE id = ?", productId);
        }
        // 站内信（买家下单后给卖家的"有新订单"）同步落库了，一并物理清掉
        jdbcTemplate.update("DELETE FROM tb_notification WHERE user_id = ?", SELLER_ID);
        // ⓪ 的合成卖家用户同样清理（B2 起它必须存在，故必须由本用例负责收尾）
        jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", SELLER_ID);

        // 防重 Token：成功的那个已被 Lua 消费，其余 999 个仍是有效 Key，必须显式删除
        if (tokens != null) {
            List<String> keys = new ArrayList<>(tokens.size());
            for (String token : tokens) {
                keys.add(RedisKeys.orderToken(BUYER_ID, token));
            }
            redisTemplate.delete(keys);
            tokens = null;
        }
        productId = null;
    }

    @Test
    @DisplayName("§9 1000 线程抢购 1 件商品 → 恰好 1 单成功，不超卖")
    void concurrentPurchaseShouldNeverOversell() throws Exception {
        Map<Integer, AtomicInteger> codeCounter = new ConcurrentHashMap<>();
        AtomicInteger unexpectedErrors = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                THREADS, THREADS, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

        long startMillis = System.currentTimeMillis();

        for (int i = 0; i < THREADS; i++) {
            final String token = tokens.get(i);
            pool.execute(() -> {
                OrderCreateRequest request = new OrderCreateRequest();
                request.setProductId(productId);
                request.setQuantity(1);
                try {
                    // UserContext 是 ThreadLocal：每个工作线程注入自己的登录态
                    UserContext.set(new LoginUser(BUYER_ID, 0, 0L));
                    // 所有线程在这里等同一个发令枪，尽量同时起跑
                    startGate.await();
                    orderService.createOrder(request, token);
                    bump(codeCounter, CODE_SUCCESS);
                } catch (BusinessException e) {
                    // 业务失败（201/202/204…）就是本用例要统计的"响应码"
                    bump(codeCounter, e.getCode());
                } catch (Throwable t) {
                    // 非业务异常（连接超时、死锁…）单独统计，避免污染响应码分布
                    unexpectedErrors.incrementAndGet();
                } finally {
                    UserContext.clear();
                    finished.countDown();
                }
            });
        }

        startGate.countDown();
        boolean allDone = finished.await(5, TimeUnit.MINUTES);
        long elapsedMillis = System.currentTimeMillis() - startMillis;
        pool.shutdown();

        assertThat(allDone)
                .as("%d 个线程应在 5 分钟内全部结束", THREADS)
                .isTrue();

        // ---------------- 断言 ----------------
        int success = count(codeCounter, CODE_SUCCESS);
        int stockNotEnough = count(codeCounter, CODE_STOCK_NOT_ENOUGH);
        int repeatSubmit = count(codeCounter, CODE_REPEAT_SUBMIT);
        int notAvailable = count(codeCounter, CODE_PRODUCT_NOT_AVAILABLE);

        assertThat(unexpectedErrors.get())
                .as("不应出现非业务异常（连接超时/死锁等），否则压测数据不干净")
                .isZero();

        // ① 恰好 1 个请求下单成功
        assertThat(success)
                .as("1000 线程抢 1 件商品，成功数必须恰好为 1")
                .isEqualTo(1);

        // ② 其余 999 个全部被拒绝：要么 CAS 落空(201)，要么读到已售罄状态(204)
        assertThat(stockNotEnough + notAvailable)
                .as("剩余线程必须全部被拒绝：201(库存不足)=%d + 204(已下架/售罄)=%d 应等于 %d",
                        stockNotEnough, notAvailable, THREADS - 1)
                .isEqualTo(THREADS - 1);

        // ②-补充：独立 Token 的意义就在这条——一旦 202 出现，说明 Token 被共用或没生效
        assertThat(repeatSubmit)
                .as("每个线程都用独立 Token，不应出现 202 请勿重复提交；出现即说明 Token 被共用")
                .isZero();

        assertThat(codeCounter.keySet())
                .as("响应码只应出现 200/201/204，实际分布=%s", new TreeMap<>(codeCounter))
                .containsOnly(CODE_SUCCESS, CODE_STOCK_NOT_ENOUGH, CODE_PRODUCT_NOT_AVAILABLE);

        // ③ 最终库存与状态
        Product after = productMapper.selectById(productId);
        assertThat(after.getStock())
                .as("1 件商品被买走 1 件，库存必须归零（超卖则会变成负数）")
                .isZero();
        assertThat(after.getStatus())
                .as("库存归零必须联动为 2-售罄")
                .isEqualTo(ProductStatus.SOLD_OUT);

        // ④ 订单数
        Long orderCount = orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().eq(Order::getProductId, productId));
        assertThat(orderCount)
                .as("抢购成功 1 次，该商品只应产生 1 条订单")
                .isEqualTo(1L);

        Order winner = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getProductId, productId).last("LIMIT 1"));
        assertThat(winner.getStatus())
                .as("抢到的订单应处于 0-待支付")
                .isEqualTo(OrderStatus.PENDING_PAY);

        printReport(codeCounter, success, after.getStock(), after.getStatus(), elapsedMillis);
    }

    private static void bump(Map<Integer, AtomicInteger> counter, int code) {
        counter.computeIfAbsent(code, k -> new AtomicInteger()).incrementAndGet();
    }

    private static int count(Map<Integer, AtomicInteger> counter, int code) {
        AtomicInteger value = counter.get(code);
        return value == null ? 0 : value.get();
    }

    private static String describe(int code) {
        switch (code) {
            case CODE_SUCCESS:
                return "下单成功";
            case CODE_STOCK_NOT_ENOUGH:
                return "库存不足";
            case CODE_REPEAT_SUBMIT:
                return "请勿重复提交";
            case CODE_PRODUCT_NOT_AVAILABLE:
                return "商品不存在或已下架";
            default:
                return "其它";
        }
    }

    /** 打印压测报告（论文用的数据就取自这里）。 */
    private static void printReport(Map<Integer, AtomicInteger> counter, int success,
                                    int stock, int status, long elapsedMillis) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("==================== §9 1000 线程并发抢购结果 ====================").append(System.lineSeparator());
        sb.append(String.format("%-8s %-20s %-10s %-10s%n", "code", "含义", "次数", "占比"));
        sb.append("---------------------------------------------------------------").append(System.lineSeparator());
        int total = 0;
        for (AtomicInteger v : counter.values()) {
            total += v.get();
        }
        for (Map.Entry<Integer, AtomicInteger> e : new TreeMap<>(counter).entrySet()) {
            int c = e.getValue().get();
            double pct = total == 0 ? 0 : (c * 100.0 / total);
            sb.append(String.format("%-8d %-20s %-10d %-10s%n",
                    e.getKey(), describe(e.getKey()), c, String.format("%.2f%%", pct)));
        }
        sb.append("---------------------------------------------------------------").append(System.lineSeparator());
        sb.append(String.format("%-14s %d%n", "总请求数", total));
        sb.append(String.format("%-14s %d%n", "成功订单数", success));
        sb.append(String.format("%-14s %d%n", "最终库存值", stock));
        sb.append(String.format("%-14s %d%s%n", "商品最终状态", status, status == ProductStatus.SOLD_OUT ? " (售罄)" : ""));
        sb.append(String.format("%-14s %d ms%n", "并发耗时", elapsedMillis));
        sb.append("===============================================================");
        System.out.println(sb);
    }

    /**
     * 把站内信的异步边界换成同步调用。
     *
     * <p>{@code sendAsync} 带 {@code @Async}，会在另一个线程用自己的连接写入，那么这条
     * 通知的清理就会和 {@link AfterEach} 抢时间（可能在我删完之后才落库，变成残留）。
     * 本用例只关心 CAS 防超卖，通知不是被测对象，改成同步后它跟着下单事务一起提交，
     * 收尾时能被 {@code @AfterEach} 确定性地删掉。</p>
     */
    @TestConfiguration
    static class SyncNotificationConfig {

        @Bean
        @Primary
        NotificationSender syncNotificationSender(NotificationService notificationService) {
            return new NotificationSender() {

                @Override
                public void send(Long userId, Integer type, Integer bizType, Long bizId, String content) {
                    notificationService.send(userId, type, bizType, bizId, content);
                }

                @Override
                public void sendAsync(Long userId, Integer type, Integer bizType, Long bizId, String content) {
                    notificationService.send(userId, type, bizType, bizId, content);
                }
            };
        }
    }
}
