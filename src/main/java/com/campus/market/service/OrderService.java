package com.campus.market.service;

import com.campus.market.common.result.PageResult;
import com.campus.market.dto.order.OrderCancelRequest;
import com.campus.market.dto.order.OrderCreateRequest;
import com.campus.market.dto.order.OrderQuery;
import com.campus.market.dto.order.RefundApplyRequest;
import com.campus.market.dto.order.RefundRejectRequest;
import com.campus.market.vo.OrderCreateVO;
import com.campus.market.vo.OrderTokenVO;
import com.campus.market.vo.OrderVO;

/**
 * 订单服务：下单、支付、发货、收货、取消、退款、冻结等全状态机流转。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li><b>CAS 扣减 / 回补统一走 {@code ProductMapper} 的两条 CAS SQL</b>，MySQL 行锁保证并发安全，无需分布式锁；</li>
 *   <li>库存回补必须与订单状态更新<b>同一事务</b>，且仅在状态实际发生变更（影响行数 > 0）时执行；</li>
 *   <li>所有状态变更接口必须判断影响行数：0 行时若当前状态已是目标状态返回 200 + "请勿重复操作"，
 *       否则返回 code=209「当前状态不允许此操作」；</li>
 *   <li>金额一律 BigDecimal，比较用 compareTo()；下单不传 amount / tradeType。</li>
 * </ul>
 */
public interface OrderService {

    /** 生成下单防重 Token（order:token:{userId}:{uuid}，TTL 5 分钟）。 */
    OrderTokenVO generateOrderToken();

    /** 下单：Lua 原子校验防重 Token → 事务内读商品 → CAS 扣减 → 写订单快照。 */
    OrderCreateVO createOrder(OrderCreateRequest request, String orderToken);

    /** 订单列表（买家 / 卖家视角）。 */
    PageResult<OrderVO> listOrders(OrderQuery query);

    /** 订单详情：归属校验（买家 / 卖家 / 管理员），失败 code=203。 */
    OrderVO getOrderDetail(Long id);

    /** 支付（0→1，买家）。 */
    OrderVO pay(Long id);

    /**
     * 模拟支付回调（按 {@code order_no + tradeNo} 幂等去重，重复回调直接返回成功）。
     *
     * @return true 表示本次回调完成了状态流转，false 表示重复回调（已处理过）
     */
    boolean handlePayCallback(String orderNo, String tradeNo);

    /** 买家主动取消（0→4，cancel_by=买家ID）+ 库存回补。 */
    OrderVO cancel(Long id, OrderCancelRequest request);

    /** 卖家发货（1→2，仅 trade_type IN (2,3)；面交订单严禁发货）。 */
    OrderVO ship(Long id);

    /** 买家确认收货（邮寄 2→3；面交 1→3）。 */
    OrderVO receive(Long id);

    /** 面交直接完成（0→3，<b>必须用 seller_id 校验</b>）。 */
    OrderVO finishFaceToFace(Long id);

    /** 买家申请退款（1/2→6）。 */
    OrderVO applyRefund(Long id, RefundApplyRequest request);

    /** 卖家同意退款（6→4）+ 库存回补。 */
    OrderVO agreeRefund(Long id);

    /** 卖家拒绝退款（6→7，进入 3 天申诉期）。 */
    OrderVO rejectRefund(Long id, RefundRejectRequest request);

    /** 退款申请列表（status IN (6,7)）。 */
    PageResult<OrderVO> listRefunds(OrderQuery query);
}
