package com.campus.market.service.support;

import com.campus.market.entity.Order;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.service.NotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付回调的"状态机"部分（S3 遗留批）。
 *
 * <h3>为什么要单独抽出来</h3>
 * <p>回调的完整校验链是：<b>参数校验 → 验签 → 查订单 → 金额比对</b>（全部只读、纯计算/单次查询）
 * → 最后才是<b>状态机写库</b>。前三步放在事务里做没有任何好处，只会让"验签期间"也持有一个
 * 数据库连接与事务上下文（HMAC 是 CPU 操作，没必要占着连接）。
 * 因此 {@code OrderServiceImpl#handlePayCallback} <b>不再带 {@code @Transactional}</b>，
 * 只负责校验与编排；真正写库的那一步交给本类 —— 每次调用一个新事务。</p>
 *
 * <p>⚠️ 之所以要"另一个 Bean"而不是本类内部方法：Spring 的 {@code @Transactional} 依赖代理，
 * <b>同类自调用不走代理</b>，把事务方法留在 {@code OrderServiceImpl} 里自己调自己会静默退化成
 * "没有事务"（6.0.4 的 {@code NotificationDispatcher} 踩过同一个坑）。</p>
 *
 * <p>去重键（{@code pay:callback:{orderNo}:{tradeNo}}）刻意<b>不</b>在本类里写：
 * 调用方在本方法<b>返回之后</b>写，天然满足"提交后才落键"（6.0.6 · Minor 9 的约定）——
 * 本方法抛异常（事务回滚）时键一定不会被写，同一笔流水可以安全重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayCallbackProcessor {

    private static final int TYPE_ORDER = 1;

    private static final int BIZ_TYPE_ORDER = 1;

    private final OrderMapper orderMapper;

    private final NotificationSender notificationSender;

    /**
     * 支付回调状态机：{@code 0→1} + 通知卖家。
     *
     * <p>SQL 自带 {@code AND status = 0} 守卫：并发下若已被别的回调/买家自己付掉，影响行数为 0，
     * 本方法返回 false（调用方按"重复回调"处理，返回 200 而不是报错）。</p>
     *
     * <p>通知走 {@code sendAsync}（6.0.4 起 = 事务提交后发送），因此"状态已提交"与"通知已发出"一致。</p>
     *
     * @param order 已通过验签且金额匹配的订单（至少含 id / sellerId / productTitle）
     * @return true 表示本次调用确实把订单推进到已支付
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public boolean payOne(Order order) {
        int rows = orderMapper.pay(order.getId());
        if (rows == 0) {
            // 取数与更新之间状态被改（并发回调 / 买家自己付了 / 已取消）→ 不报错，按重复回调处理
            log.warn("支付回调状态冲突（影响行数 0），已忽略: orderId={}, orderNo={}",
                    order.getId(), order.getOrderNo());
            return false;
        }
        notificationSender.sendAsync(order.getSellerId(), TYPE_ORDER, BIZ_TYPE_ORDER, order.getId(),
                "买家已支付订单「" + order.getProductTitle() + "」，请尽快发货或约定面交");
        return true;
    }
}
