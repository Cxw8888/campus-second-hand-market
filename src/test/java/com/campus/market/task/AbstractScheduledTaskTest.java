package com.campus.market.task;

import com.campus.market.entity.Order;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.service.NotificationSender;
import com.campus.market.service.NotificationService;
import com.campus.market.service.ScheduledTasks;
import com.campus.market.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 定时任务测试公共基类。
 *
 * <p>三个每日 cron 任务（自动确认收货 / 收货前 24h 提醒 / 退款被拒超时恢复）无法等真实调度，
 * 这里直接调用 {@link ScheduledTasks} 的对应方法，只验证「SQL 条件 + 状态流转」本身。</p>
 *
 * <p><b>为什么 @Transactional 能保证不污染库：</b>测试方法本身开事务，被调用的三个任务方法
 * 内部只是 mapper 调用，会加入当前线程事务；断言在同一事务内可见，方法结束整体回滚。
 * 因此不需要清理脚本，也不会留下测试订单。</p>
 *
 * <p><b>约束：同一个任务类的锁名只能被调用一次。</b>任务上有 {@code @SchedulerLock}，
 * 走代理调用会真的去 {@code shedlock} 表抢锁；锁在同一事务内未提交，若在同一测试类里
 * 第二次调用同一任务，会读到自己那把「未过期」的锁而被静默跳过。
 * 所以本包下每个测试类只放一个 {@code @Test} 方法，只调用一次对应任务。</p>
 *
 * <p><b>已知无关紧要的干扰：</b>@SpringBootTest 会启动真实调度器，超时取消任务（每分钟）
 * 只扫 status=0，与本包用例使用的 status=2/7 不相交，故不互相影响。</p>
 */
@SpringBootTest
@Transactional
@Import(SyncNotificationTestConfig.class)
abstract class AbstractScheduledTaskTest {

    /**
     * 测试用的买家/卖家/商品 ID。
     * tb_order 在 V1__init.sql 里没有任何外键约束，所以直接写合成 ID 即可，
     * 不必为了造订单再去 insert 用户和商品；况且整个测试结束会回滚。
     */
    protected static final long BUYER_ID = 9_000_001L;
    protected static final long SELLER_ID = 9_000_002L;
    protected static final long PRODUCT_ID = 9_000_003L;

    /** 1-仅面交。定时任务的 SQL 一律用 trade_type IN (2,3) 排除面交单。 */
    protected static final int TRADE_TYPE_FACE = 1;

    /** 2-仅邮寄。 */
    protected static final int TRADE_TYPE_MAIL = 2;

    @Autowired
    protected ScheduledTasks scheduledTasks;

    @Autowired
    protected OrderMapper orderMapper;

    @Autowired
    protected SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 造一条订单并落库。
     *
     * <p>ship_time / refund_reject_time 不在 {@code BaseEntity} 的自动填充字段里，
     * 可以直接指定成过去的任意时间，用来命中各任务的窗口条件。</p>
     */
    protected Order insertOrder(int status, int tradeType, LocalDateTime shipTime, LocalDateTime refundRejectTime) {
        return insertOrder(status, tradeType, shipTime, refundRejectTime, null);
    }

    /**
     * 造一条订单并落库（带 pay_time）。
     *
     * <p>批次 6.0.5.2 · C 起，自动确认收货也覆盖"已支付面交单"，判龄字段是 {@code pay_time}
     * （面交不发货，没有 ship_time），因此测试需要能指定它。</p>
     */
    protected Order insertOrder(int status, int tradeType, LocalDateTime shipTime,
                                LocalDateTime refundRejectTime, LocalDateTime payTime) {
        Order order = new Order();
        order.setOrderNo(snowflakeIdGenerator.nextOrderNo());
        order.setUserId(BUYER_ID);
        order.setSellerId(SELLER_ID);
        order.setProductId(PRODUCT_ID);
        order.setProductTitle("定时任务测试商品");
        order.setProductPrice(new BigDecimal("10.00"));
        order.setAmount(new BigDecimal("10.00"));
        order.setQuantity(1);
        order.setStatus(status);
        order.setTradeType(tradeType);
        order.setShipTime(shipTime);
        order.setRefundRejectTime(refundRejectTime);
        order.setPayTime(payTime);
        orderMapper.insert(order);
        return order;
    }

    /** 重新查库，拿到定时任务改动后的最新状态。 */
    protected Order reload(Long orderId) {
        return orderMapper.selectById(orderId);
    }
}

/**
 * 把站内信的「异步边界」换成同步调用，只为让测试可断言、可回滚。
 *
 * <p>{@code NotificationServiceImpl.sendAsync} 带 {@code @Async}，会在
 * {@code notificationExecutor} 的另一个线程里用自己的连接 insert 并<b>独立提交</b>——
 * 那样既不受测试事务管辖（回滚不掉，属于污染），又需要轮询等待，天然不稳定。</p>
 *
 * <p>所以这里用 {@code @Primary} 覆盖 {@link NotificationSender}：{@code sendAsync} 直接转调
 * 同步的 {@code NotificationService.send}（本身 {@code @Transactional}），于是站内信
 * <b>真实写进 tb_notification</b>、能被断言读到，又随测试事务一起回滚。</p>
 *
 * <p>代价：本包用例不覆盖「@Async 线程池派发」这一层基础设施（它不属于这三个任务的状态机逻辑）。
 * 若要连异步一起验，应改为真实 {@code sendAsync} + 轮询等待 + @AfterEach 手工删通知行。</p>
 */
@TestConfiguration
class SyncNotificationTestConfig {

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
