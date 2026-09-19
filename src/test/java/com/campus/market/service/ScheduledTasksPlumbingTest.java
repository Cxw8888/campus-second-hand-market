package com.campus.market.service;

import com.campus.market.common.constant.RedisKeys;
import com.campus.market.config.properties.TaskProperties;
import com.campus.market.entity.Order;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.service.support.OrderTaskProcessor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.5.2 安全加固 · 定时任务 4 条单测（B1~B4 + C 的编排层）。
 *
 * <ul>
 *   <li><b>B1</b>：自动确认收货的阈值来自 {@code app.task.auto-confirm.days}（修前 SQL 写死 7 天）；</li>
 *   <li><b>B2</b>：{@code @SchedulerLock.lockAtMostFor} 改为配置占位符（修前配置项是死配置）——
 *       这里用反射守住"注解里不能再出现写死的 PT5M/PT10M"；真实解析由
 *       {@code ScheduledLockConfigurationWiringTest} 用 ShedLock 自己的 extractor 断言；</li>
 *   <li><b>B3</b>：提醒窗口按配置放宽 + 单批上限 + Redis 去重（第二次不发）；</li>
 *   <li><b>B4</b>：超时取消逐单独立事务（批处理方法上<b>不能</b>有 {@code @Transactional}），一单失败不影响其它单；</li>
 *   <li><b>C</b>：自动确认收货同时处理面交候选单（状态机 1→3）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduledTasksPlumbingTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderTaskProcessor orderTaskProcessor;

    @Mock
    private NotificationSender notificationSender;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TaskProperties taskProperties;

    private ScheduledTasks scheduledTasks;

    @BeforeEach
    void setUp() {
        taskProperties = new TaskProperties();
        scheduledTasks = new ScheduledTasks(orderMapper, orderTaskProcessor, notificationSender,
                taskProperties, redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);
    }

    private static Order order(long id, int status, int tradeType) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(status);
        order.setTradeType(tradeType);
        order.setUserId(100L + id);
        order.setSellerId(200L + id);
        order.setProductId(300L + id);
        order.setQuantity(1);
        order.setProductTitle("测试商品" + id);
        return order;
    }

    // ---------------------------------------------------------------- B1 / C

    @Test
    @DisplayName("B1 自动确认收货的阈值来自配置（days=3 → 取数 SQL 收到 3，而不是写死的 7）")
    void autoConfirmShouldPassConfiguredDays() {
        taskProperties.getAutoConfirm().setDays(3);
        when(orderMapper.selectAutoConfirmCandidates(anyInt(), anyInt())).thenReturn(List.of());

        scheduledTasks.autoConfirmReceive();

        verify(orderMapper).selectAutoConfirmCandidates(eq(3), eq(taskProperties.getAutoConfirm().getBatchLimit()));
    }

    @Test
    @DisplayName("C 面交候选单同样交给逐单处理器（1→3 的兜底路径），且单批上限生效")
    void autoConfirmShouldProcessFaceCandidatesOneByOne() {
        taskProperties.getAutoConfirm().setDays(7);
        taskProperties.getAutoConfirm().setBatchLimit(2);
        // 一条邮寄（2→3）+ 一条面交（1→3）：取数 SQL 已按两条规则筛出候选
        when(orderMapper.selectAutoConfirmCandidates(7, 2)).thenReturn(List.of(
                order(1L, OrderStatus.SHIPPED, 2),
                order(2L, OrderStatus.PAID, 1)));
        when(orderTaskProcessor.autoConfirmOne(any())).thenReturn(true);

        scheduledTasks.autoConfirmReceive();

        verify(orderTaskProcessor, times(2)).autoConfirmOne(any());
    }

    @Test
    @DisplayName("B4 自动确认：某单抛异常不影响其它单（逐单独立事务）")
    void autoConfirmShouldIsolateFailures() {
        when(orderMapper.selectAutoConfirmCandidates(anyInt(), anyInt())).thenReturn(List.of(
                order(1L, OrderStatus.SHIPPED, 2),
                order(2L, OrderStatus.SHIPPED, 2),
                order(3L, OrderStatus.SHIPPED, 2)));
        when(orderTaskProcessor.autoConfirmOne(any()))
                .thenReturn(true)
                .thenThrow(new RuntimeException("单条处理失败"))
                .thenReturn(true);

        assertThatCode(() -> scheduledTasks.autoConfirmReceive()).doesNotThrowAnyException();

        verify(orderTaskProcessor, times(3)).autoConfirmOne(any());
    }

    // ---------------------------------------------------------------- B4

    @Test
    @DisplayName("B4 超时取消：逐单调用处理器，一单失败其它单照常处理，异常不外抛")
    void cancelTimeoutShouldIsolateFailures() {
        taskProperties.getTimeoutCancel().setBatchLimit(100);
        when(orderMapper.selectTimeoutPendingOrders(15, 100)).thenReturn(List.of(
                order(1L, OrderStatus.PENDING_PAY, 1),
                order(2L, OrderStatus.PENDING_PAY, 1),
                order(3L, OrderStatus.PENDING_PAY, 1)));
        when(orderTaskProcessor.cancelTimeoutOne(any()))
                .thenReturn(true)
                .thenThrow(new RuntimeException("该单失败"))
                .thenReturn(true);

        assertThatCode(() -> scheduledTasks.cancelTimeoutOrders()).doesNotThrowAnyException();

        verify(orderTaskProcessor, times(3)).cancelTimeoutOne(any());
    }

    @Test
    @DisplayName("B4 回归保护：批处理方法上不能再出现 @Transactional（否则逐单事务会退化成整批一个大事务）")
    void batchMethodsMustNotBeTransactional() throws Exception {
        assertThat(annotationOf("cancelTimeoutOrders", Transactional.class))
                .as("超时取消必须逐单独立事务，批方法不得带 @Transactional")
                .isNull();
        assertThat(annotationOf("autoConfirmReceive", Transactional.class))
                .as("自动确认同理")
                .isNull();
    }

    // ---------------------------------------------------------------- B3

    @Test
    @DisplayName("B3 提醒窗口按配置放宽：days=7 + 提前 1 天 + 窗口 2 天 → 取数区间 (6 天, 8 天]")
    void remindShouldUseConfiguredWindow() {
        when(orderMapper.selectAutoConfirmRemindOrders(anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        scheduledTasks.remindBeforeAutoConfirm();

        verify(orderMapper).selectAutoConfirmRemindOrders(
                eq(6), eq(8), eq(taskProperties.getAutoConfirm().getRemindBatchLimit()));
    }

    @Test
    @DisplayName("B3 提醒去重：Redis 里已有标记（SETNX 返回 false）→ 不再发通知")
    void remindShouldSkipAlreadySent() {
        when(orderMapper.selectAutoConfirmRemindOrders(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(order(7L, OrderStatus.SHIPPED, 2)));
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.FALSE);

        scheduledTasks.remindBeforeAutoConfirm();

        verify(notificationSender, never()).sendAsync(anyLong(), anyInt(), anyInt(), anyLong(), anyString());
        verify(valueOperations).setIfAbsent(eq(RedisKeys.taskRemindSent(7L)), eq("1"), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("B3 首次提醒：写入 7 天去重标记并通知买家")
    void remindShouldSendOnceAndMarkSent() {
        when(orderMapper.selectAutoConfirmRemindOrders(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(order(7L, OrderStatus.SHIPPED, 2)));

        scheduledTasks.remindBeforeAutoConfirm();

        verify(valueOperations).setIfAbsent(RedisKeys.taskRemindSent(7L), "1", Duration.ofDays(7));
        verify(notificationSender).sendAsync(eq(107L), anyInt(), anyInt(), eq(7L), anyString());
    }

    @Test
    @DisplayName("B3 Redis 故障 → 降级为「照发」，不漏提醒")
    void remindShouldDegradeWhenRedisDown() {
        when(orderMapper.selectAutoConfirmRemindOrders(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(order(7L, OrderStatus.SHIPPED, 2)));
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        scheduledTasks.remindBeforeAutoConfirm();

        verify(notificationSender).sendAsync(eq(107L), anyInt(), anyInt(), eq(7L), anyString());
    }

    // ---------------------------------------------------------------- B2

    @Test
    @DisplayName("B2 四个任务的 @SchedulerLock.lockAtMostFor 必须是配置占位符（修前写死 PT5M/PT10M）")
    void lockAtMostForMustBeConfigurablePlaceholder() throws Exception {
        assertThat(lockAtMostForOf("cancelTimeoutOrders"))
                .isEqualTo("${app.task.timeout-cancel.lock-at-most-for:PT5M}");
        assertThat(lockAtMostForOf("autoConfirmReceive"))
                .isEqualTo("${app.task.auto-confirm.lock-at-most-for:PT10M}");
        assertThat(lockAtMostForOf("remindBeforeAutoConfirm"))
                .isEqualTo("${app.task.auto-confirm.remind-lock-at-most-for:PT5M}");
        assertThat(lockAtMostForOf("recoverFromRefundRejected"))
                .isEqualTo("${app.task.refund-reject-recover.lock-at-most-for:PT10M}");
    }

    private static <T extends java.lang.annotation.Annotation> T annotationOf(String methodName, Class<T> type)
            throws Exception {
        Method method = ScheduledTasks.class.getMethod(methodName);
        return method.getAnnotation(type);
    }

    private static String lockAtMostForOf(String methodName) throws Exception {
        return annotationOf(methodName, SchedulerLock.class).lockAtMostFor();
    }
}
