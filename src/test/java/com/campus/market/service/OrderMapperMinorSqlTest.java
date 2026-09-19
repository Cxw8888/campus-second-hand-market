package com.campus.market.service;

import com.campus.market.config.properties.TaskProperties;
import com.campus.market.entity.Order;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.service.support.OrderTaskProcessor;
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

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.6 · 自审 Minor 2 + Minor 3 单测（都改在 {@link OrderMapper} 的 SQL 上）。
 *
 * <ul>
 *   <li><b>Minor 2</b>：封禁冻结的订单状态集合补 {@code 7}（退款被拒/申诉期）。
 *       修前 7 不在集合里 → 被封禁用户"退款被拒"的单不会被冻结，
 *       3 天后 {@code recoverFromRefundRejected} 仍把它恢复成 1/2 继续流转，封禁形同虚设；</li>
 *   <li><b>Minor 3</b>：超时取消的阈值<b>按交易方式分开</b>（邮寄 15 分钟 / 面交 120 分钟）。
 *       修前只有一条 15 分钟规则，校园面交是"约时间见面"，买家还在路上就被系统取消了。</li>
 * </ul>
 *
 * <p>断言分两层：<b>SQL 文本</b>（Annotation 读取，守住"状态集合与阈值条件真的改了"）+
 * <b>编排层传参</b>（守住 {@code ScheduledTasks} 把两个配置值都传下去了）。
 * 真实 SQL 语义由 6.0.6 的真机场景另行验证（MySQL 里跑出"面交 20 分钟不取消、邮寄 20 分钟取消"）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderMapperMinorSqlTest {

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

    /** 读 Mapper 方法上 {@code @Update/@Select} 的 SQL 文本（自定义 SQL 改动只能这样守）。 */
    private static String sqlOf(String methodName, Class<?>... params) throws Exception {
        Method method = OrderMapper.class.getMethod(methodName, params);
        for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
            if (annotation instanceof org.apache.ibatis.annotations.Update update) {
                return String.join(" ", update.value());
            }
            if (annotation instanceof org.apache.ibatis.annotations.Select select) {
                return String.join(" ", select.value());
            }
        }
        throw new AssertionError("方法上没有 @Update/@Select: " + methodName);
    }

    // ================================================================ Minor 2

    @Test
    @DisplayName("Minor 2 冻结 SQL 必须覆盖 7-退款被拒（否则申诉期满会自动恢复继续流转）")
    void freezeSqlMustCoverRefundRejected() throws Exception {
        String sql = sqlOf("freezeByUser", Long.class);

        assertThat(sql)
                .as("封禁冻结的状态集合：%s", sql)
                .contains("status IN (0,1,2,6,7)");
        assertThat(sql)
                .as("归属条件必须带括号，否则 OR 会越过其它条件（历史踩坑）")
                .contains("(seller_id = #{userId} OR user_id = #{userId})");
    }

    // ================================================================ Minor 3

    @Test
    @DisplayName("Minor 3 超时取数 SQL 必须按 trade_type 分两个阈值（邮寄 mailMinutes / 面交 faceMinutes）")
    void timeoutSqlMustSplitByTradeType() throws Exception {
        String sql = sqlOf("selectTimeoutPendingOrders", Integer.class, Integer.class, Integer.class);

        assertThat(sql)
                .as("超时取数 SQL：%s", sql)
                .contains("trade_type IN (2,3) AND create_time < NOW() - INTERVAL #{mailMinutes} MINUTE")
                .contains("trade_type = 1 AND create_time < NOW() - INTERVAL #{faceMinutes} MINUTE")
                .contains("LIMIT #{limit}");
        assertThat(sql)
                .as("两个条件必须整体加括号，否则 LIMIT/ORDER BY 会与 OR 结合出错")
                .contains("AND (");
    }

    @Test
    @DisplayName("Minor 3 默认阈值：邮寄 15 分钟、面交 120 分钟（面交不再被 15 分钟误取消）")
    void defaultThresholdsAreMail15AndFace120() {
        TaskProperties.TimeoutCancel config = new TaskProperties().getTimeoutCancel();

        assertThat(config.getMinutes()).isEqualTo(15);
        assertThat(config.getFaceMinutes()).isEqualTo(120);
    }

    @Test
    @DisplayName("Minor 3 编排层：两个阈值都从配置读取并传下去（改配置即生效）")
    void scheduledTaskPassesBothThresholdsFromConfig() {
        taskProperties.getTimeoutCancel().setMinutes(20);
        taskProperties.getTimeoutCancel().setFaceMinutes(180);
        taskProperties.getTimeoutCancel().setBatchLimit(50);
        when(orderMapper.selectTimeoutPendingOrders(anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        scheduledTasks.cancelTimeoutOrders();

        verify(orderMapper).selectTimeoutPendingOrders(20, 180, 50);
        verify(orderTaskProcessor, never()).cancelTimeoutOne(any(Order.class));
    }
}
