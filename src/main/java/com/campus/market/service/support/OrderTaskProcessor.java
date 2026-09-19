package com.campus.market.service.support;

import com.campus.market.entity.Order;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.service.NotificationSender;
import com.campus.market.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定时任务的"单条订单处理"（批次 6.0.5.2 · 定时任务④）。
 *
 * <h3>为什么要单独抽出来</h3>
 * <p>修前 {@code ScheduledTasks.cancelTimeoutOrders} 上有 {@code @Transactional}，
 * <b>整批 200 单共用一个事务</b>：批内任何一单抛异常 → 整批回滚（而异步通知可能已经发出，与 M3 同源），
 * 且失败的那批要等下个周期从头再来。现在批处理方法<b>不再带事务</b>，
 * 逐单调用本类的方法 —— 每次调用各自开一个事务、各自提交，一单失败只影响那一单。</p>
 *
 * <h3>为什么用 {@code REQUIRED} 而不是 {@code REQUIRES_NEW}</h3>
 * <p>生产路径上批处理方法已经没有事务，{@code REQUIRED} 的效果就是"每次调用一个新事务"，与
 * {@code REQUIRES_NEW} 等价；而在 {@code @SpringBootTest + @Transactional} 的用例里，
 * {@code REQUIRED} 会<b>加入测试事务</b>，从而保证任务测试"跑完整体回滚、不留测试数据"这一既有约定
 * （见 {@code AbstractScheduledTaskTest}）。若用 {@code REQUIRES_NEW}，内层事务会挂起测试事务并
 * <b>独立提交</b>，测试数据就再也回滚不掉了。</p>
 * <p>⚠️ 因此调用方 {@code ScheduledTasks} 的批处理方法<b>必须保持无事务</b> ——
 * 一旦有人给它加上 {@code @Transactional}，本类的"逐单独立事务"会静默退化成"整批一个大事务"。</p>
 *
 * <p>通知统一走 {@code sendAsync}（6.0.4 起 = 事务提交后发送），因此"状态已提交"与"通知已发出"一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTaskProcessor {

    private static final int TYPE_ORDER = 1;
    private static final int BIZ_TYPE_ORDER = 1;

    private final OrderMapper orderMapper;
    private final StockService stockService;
    private final NotificationSender notificationSender;

    /**
     * 超时未支付自动取消（0→4）+ 库存回补（库存回补带订单维度幂等凭证）。
     *
     * @return true 表示本单确实被取消（影响行数 &gt; 0）
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public boolean cancelTimeoutOne(Order order) {
        // 影响行数判断：并发下可能已被买家支付/取消，为 0 直接跳过
        int rows = orderMapper.cancelByTimeout(order.getId());
        if (rows == 0) {
            return false;
        }
        stockService.restoreOnce(order.getId(), order.getProductId(), order.getQuantity());
        notificationSender.sendAsync(order.getUserId(), TYPE_ORDER, BIZ_TYPE_ORDER, order.getId(),
                "订单「" + order.getProductTitle() + "」超时未支付，已自动取消");
        return true;
    }

    /**
     * 自动确认收货 / 面交兜底完成（2→3 或 1→3）+ 通知买卖双方。
     *
     * @return true 表示本单确实被自动完成
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public boolean autoConfirmOne(Order order) {
        int rows = orderMapper.autoConfirmOne(order.getId());
        if (rows == 0) {
            // 取数与更新之间状态被改（买家手动确认收货 / 申请退款 / 被冻结）→ 跳过
            log.debug("自动确认跳过（状态已变化）: orderId={}", order.getId());
            return false;
        }
        boolean face = order.getTradeType() != null && order.getTradeType() == 1;
        // 通知双方：买家被告知"已自动完成"，卖家被告知"交易已收尾"（修前该任务不发任何通知）
        notificationSender.sendAsync(order.getUserId(), TYPE_ORDER, BIZ_TYPE_ORDER, order.getId(),
                face
                        ? "面交订单「" + order.getProductTitle() + "」已由系统自动确认完成（双方均未确认）"
                        : "订单「" + order.getProductTitle() + "」已自动确认收货，交易完成");
        notificationSender.sendAsync(order.getSellerId(), TYPE_ORDER, BIZ_TYPE_ORDER, order.getId(),
                "订单「" + order.getProductTitle() + "」已自动完成（买家未确认，系统按超时规则收尾）");
        return true;
    }
}
