package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.common.result.PageResult;
import com.campus.market.config.properties.OrderProperties;
import com.campus.market.dto.order.OrderCancelRequest;
import com.campus.market.dto.order.OrderCreateRequest;
import com.campus.market.dto.order.OrderQuery;
import com.campus.market.dto.order.PayCallbackRequest;
import com.campus.market.dto.order.RefundApplyRequest;
import com.campus.market.dto.order.RefundRejectRequest;
import com.campus.market.entity.Order;
import com.campus.market.entity.Product;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.security.UserContext;
import com.campus.market.service.NotificationSender;
import com.campus.market.service.OrderService;
import com.campus.market.service.OrderTokenService;
import com.campus.market.service.PayCallbackSignService;
import com.campus.market.service.StockService;
import com.campus.market.util.TransactionHelper;
import com.campus.market.vo.OrderCreateVO;
import com.campus.market.vo.OrderTokenVO;
import com.campus.market.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单服务实现：CAS 扣减/回补 + 状态机流转 + 幂等判定 + 快照落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final int NOTIFICATION_TYPE_ORDER = 1;
    private static final int BIZ_TYPE_ORDER = 1;

    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;
    private final OrderTokenService orderTokenService;
    private final OrderCreateService orderCreateService;
    private final StockService stockService;
    private final NotificationSender notificationSender;
    private final OrderProperties orderProperties;
    private final StringRedisTemplate redisTemplate;
    /** 支付回调验签（批次 6.0.2 · S3）。 */
    private final PayCallbackSignService payCallbackSignService;

    // ================================================================ 下单

    @Override
    public OrderTokenVO generateOrderToken() {
        Long userId = UserContext.requireUserId();
        long ttl = orderProperties.getTokenTtlSeconds();
        String token = orderTokenService.generate(userId, ttl);
        return new OrderTokenVO(token, ttl);
    }

    @Override
    public OrderCreateVO createOrder(OrderCreateRequest request, String orderToken) {
        Long userId = UserContext.requireUserId();

        // ① 幂等前置：Lua 原子校验并删除防重 Token（在事务之外，校验失败不产生任何业务副作用）
        if (!orderTokenService.consume(userId, orderToken)) {
            throw BusinessException.repeatSubmit();
        }
        // ② 事务内完成校验 + CAS 扣减 + 订单快照落库（独立 Bean，保证 @Transactional 生效）
        return orderCreateService.create(request, userId);
    }

    // ================================================================ 查询

    @Override
    public PageResult<OrderVO> listOrders(OrderQuery query) {
        Long userId = UserContext.requireUserId();
        boolean sellerView = "seller".equalsIgnoreCase(query.getRole());
        Page<Order> page = new Page<>(query.current(), query.pageSize());
        IPage<Order> result = orderMapper.selectPage(page, Wrappers.<Order>lambdaQuery()
                .eq(sellerView ? Order::getSellerId : Order::getUserId, userId)
                .eq(query.getStatus() != null, Order::getStatus, query.getStatus())
                .orderByDesc(Order::getCreateTime));
        Map<Long, Product> productMap = loadProducts(result.getRecords());
        return PageResult.of(result, order -> toVO(order, productMap));
    }

    @Override
    public PageResult<OrderVO> listRefunds(OrderQuery query) {
        Long userId = UserContext.requireUserId();
        boolean sellerView = "seller".equalsIgnoreCase(query.getRole());
        Page<Order> page = new Page<>(query.current(), query.pageSize());
        IPage<Order> result = orderMapper.selectPage(page, Wrappers.<Order>lambdaQuery()
                .eq(sellerView ? Order::getSellerId : Order::getUserId, userId)
                .in(Order::getStatus, List.of(OrderStatus.REFUND_APPLYING, OrderStatus.REFUND_REJECTED))
                .orderByDesc(Order::getUpdateTime));
        Map<Long, Product> productMap = loadProducts(result.getRecords());
        return PageResult.of(result, order -> toVO(order, productMap));
    }

    @Override
    public OrderVO getOrderDetail(Long id) {
        Long userId = UserContext.requireUserId();
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "无权操作该订单");
        }
        // 归属校验：买家 / 卖家 / 管理员，否则 203
        if (!userId.equals(order.getUserId()) && !userId.equals(order.getSellerId()) && !UserContext.isAdmin()) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        // 订单回看已删除商品：自定义 SQL 绕过逻辑删除过滤
        Product product = productMapper.selectByIdIgnoreLogicDelete(order.getProductId());
        return toVO(order, product);
    }

    // ================================================================ 状态流转

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO pay(Long id) {
        Long userId = UserContext.requireUserId();
        Order order = requireOrder(id);
        if (!userId.equals(order.getUserId())) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        if (OrderStatus.REFUND_REJECTED == order.getStatus()) {
            // 7-退款被拒：买家应等待申诉，不得支付
            throw new BusinessException(ErrorCode.REFUND_REJECTED_WAIT_APPEAL);
        }
        if (OrderStatus.PAID == order.getStatus()) {
            throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
        }
        int rows = orderMapper.pay(id);
        ensureStateChanged(rows, order.getStatus(), OrderStatus.PAID);

        // 事务提交后通知卖家
        notificationSender.sendAsync(order.getSellerId(), NOTIFICATION_TYPE_ORDER, BIZ_TYPE_ORDER, id,
                "买家已支付订单「" + order.getProductTitle() + "」，请尽快发货或约定面交");
        return reloadVO(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handlePayCallback(PayCallbackRequest request) {
        String orderNo = request.getOrderNo();
        String tradeNo = request.getTradeNo();

        // ① 验签（时间戳窗口 → 签名非空 → HMAC-SHA256 常量时间比对）
        //    ⚠️ 批次 6.0.2 · S3：修前本方法只做去重 + 状态机，任何人知道 orderNo 就能把订单 0→1。
        //    验签放在最前面，不通过即抛 code=100，绝不进入后面的任何写操作。
        payCallbackSignService.verify(orderNo, tradeNo, request.getTimestamp(), request.getSign());

        // ② 订单存在（按 orderNo 查）；找不到同样按"回调签名校验失败"拒绝 ——
        //    对外不暴露"这个 orderNo 存不存在"，避免回调接口变成订单号探测器。
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getOrderNo, orderNo));
        if (order == null) {
            log.warn("支付回调拒绝: 订单不存在, orderNo={}", orderNo);
            throw new BusinessException(ErrorCode.PARAM_ERROR, PayCallbackSignService.VERIFY_FAILED_MSG);
        }

        // ③ 幂等：order_no + 回调流水号 去重。
        //
        //    ⚠️ 两条历史教训叠在这里，缺一条都会出问题：
        //    ①（6.0.2）去重<b>必须在验签之后</b>：否则未通过验签的请求会先占位，
        //      把随后到达的合法回调当成"重复回调"吞掉；
        //    ②（6.0.6 · Minor 9）去重键的<b>写入必须在事务提交之后</b>：
        //      修前在事务内 SETNX，事务一旦回滚（后续状态机抛异常 / DB 抖动），
        //      Key 已经占位而状态没变 —— 支付方按幂等重试同一笔流水，
        //      会被当成"重复回调"直接 return false，<b>这笔支付永久丢失</b>且不报错。
        //      现在：读侧在事务内判定（快路径），写侧走 TransactionHelper.runAfterCommit；
        //      并发穿透由状态机兜底 —— pay 的 `WHERE status = 0` 保证只有一次能生效。
        String dedupKey = RedisKeys.PAY_CALLBACK_PREFIX + orderNo + ":" + tradeNo;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
                log.info("支付回调重复, 已忽略: orderNo={}, tradeNo={}", orderNo, tradeNo);
                return false;
            }
        } catch (Exception e) {
            // Redis 故障降级：无法去重时继续走状态机（状态机本身幂等，重复回调不会造成二次扣减）
            log.warn("支付回调去重校验降级: orderNo={}, err={}", orderNo, e.getMessage());
        }

        if (order.getStatus() != null && order.getStatus() == OrderStatus.PAID) {
            // 状态机幂等：已支付则视为成功
            return false;
        }
        int rows = orderMapper.pay(order.getId());
        if (rows == 0) {
            // 非待支付状态（已取消/已冻结等），支付回调不再生效
            log.warn("支付回调状态冲突, 已忽略: orderNo={}, status={}", orderNo, order.getStatus());
            return false;
        }

        // ③-b 事务提交后才落去重键：回滚不留下"占位但没生效"的键（Minor 9）
        TransactionHelper.runAfterCommit(() -> {
            try {
                redisTemplate.opsForValue().set(dedupKey, "1", Duration.ofDays(7));
            } catch (Exception e) {
                // 写失败只影响"后续重复回调的去重"，状态机仍能兜住重复调用
                log.warn("支付回调去重键写入失败（降级，靠状态机兜底）: orderNo={}, err={}",
                        orderNo, e.getMessage());
            }
        });

        notificationSender.sendAsync(order.getSellerId(), NOTIFICATION_TYPE_ORDER, BIZ_TYPE_ORDER, order.getId(),
                "买家已支付订单「" + order.getProductTitle() + "」，请尽快发货或约定面交");
        log.info("支付回调验签通过并完成支付: orderNo={}, tradeNo={}, orderId={}", orderNo, tradeNo, order.getId());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO cancel(Long id, OrderCancelRequest request) {
        Long userId = UserContext.requireUserId();
        Order order = requireOrder(id);
        if (!userId.equals(order.getUserId())) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        if (OrderStatus.CANCELLED == order.getStatus()) {
            throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
        }
        String reason = request == null || request.getReason() == null ? "买家主动取消" : request.getReason();
        int rows = orderMapper.cancelByBuyer(id, userId, reason);
        ensureStateChanged(rows, order.getStatus(), OrderStatus.CANCELLED);

        // 库存回补必须与订单状态更新同一事务（0→4 场景 ①）；
        // 批次 6.0.3 · B1：走带幂等凭证的 restoreOnce(orderId, ...)，防止同一订单被多条路径回补两次
        stockService.restoreOnce(id, order.getProductId(), order.getQuantity());

        notificationSender.sendAsync(order.getSellerId(), NOTIFICATION_TYPE_ORDER, BIZ_TYPE_ORDER, id,
                "买家已取消订单「" + order.getProductTitle() + "」");
        return reloadVO(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO ship(Long id) {
        Long userId = UserContext.requireUserId();
        Order order = requireOrder(id);
        if (!userId.equals(order.getSellerId())) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        if (OrderStatus.SHIPPED == order.getStatus()) {
            throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
        }
        // 面交订单严禁发货（SQL 层同样限定 trade_type IN (2,3)，双保险）
        int rows = orderMapper.ship(id, userId);
        ensureStateChanged(rows, order.getStatus(), OrderStatus.SHIPPED);

        notificationSender.sendAsync(order.getUserId(), NOTIFICATION_TYPE_ORDER, BIZ_TYPE_ORDER, id,
                "卖家已发货，订单「" + order.getProductTitle() + "」请注意查收");
        return reloadVO(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO receive(Long id) {
        Long userId = UserContext.requireUserId();
        Order order = requireOrder(id);
        if (!userId.equals(order.getUserId())) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        if (OrderStatus.FINISHED == order.getStatus()) {
            throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
        }
        // 邮寄订单 2→3；面交订单 1→3（跳过发货）
        boolean face = order.getTradeType() != null && order.getTradeType() == 1;
        int rows = face ? orderMapper.receiveByFace(id, userId) : orderMapper.receiveByMail(id, userId);
        ensureStateChanged(rows, order.getStatus(), OrderStatus.FINISHED);

        notificationSender.sendAsync(order.getSellerId(), NOTIFICATION_TYPE_ORDER, BIZ_TYPE_ORDER, id,
                "买家已确认收货，订单「" + order.getProductTitle() + "」交易完成");
        return reloadVO(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO finishFaceToFace(Long id) {
        Long sellerId = UserContext.requireUserId();
        Order order = requireOrder(id);
        // 面交直接完成必须用 seller_id 校验（严禁使用 user_id）
        if (!sellerId.equals(order.getSellerId())) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        if (OrderStatus.FINISHED == order.getStatus()) {
            throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
        }
        int rows = orderMapper.finishFaceToFace(id, sellerId);
        ensureStateChanged(rows, order.getStatus(), OrderStatus.FINISHED);

        notificationSender.sendAsync(order.getUserId(), NOTIFICATION_TYPE_ORDER, BIZ_TYPE_ORDER, id,
                "卖家已确认面交完成，订单「" + order.getProductTitle() + "」交易完成");
        return reloadVO(id);
    }

    /**
     * 卖家确认面交完成（已支付面交单 1→3，批次 6.0.5.1 · M2）。
     *
     * <p>修的是自审报告 M2：已支付的面交单（status=1, trade_type=1）此前<b>没有任何终态路径</b> ——
     * {@code finishFaceToFace} 要求 status=0、{@code receiveByFace} 是买家操作、
     * 自动收货只覆盖 status=2。买家付款后失联（校园场景高发：约好面交但临时有事），
     * 卖家只能干等，订单永久停在 1。</p>
     *
     * <p>权限与状态守卫：归属用 <b>seller_id</b>（严禁 user_id）；状态与交易方式交给 SQL 的
     * {@code status = 1 AND trade_type = 1} 判定，影响行数 0 时：</p>
     * <ul>
     *   <li>当前已是 3-已完成 → 200 +「请勿重复操作」（与其它状态变更接口一致）；</li>
     *   <li>其它（status=0/2/4… 或邮寄单 trade_type≠1）→ 209「当前状态不允许此操作」。</li>
     * </ul>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO finishFaceBySeller(Long id) {
        Long sellerId = UserContext.requireUserId();
        Order order = requireOrder(id);
        // 面交完成由卖家确认：权限必须用 seller_id（与 finishFaceToFace / ship 一致）
        if (!sellerId.equals(order.getSellerId())) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        if (OrderStatus.FINISHED == order.getStatus()) {
            throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
        }
        int rows = orderMapper.finishFaceBySeller(id, sellerId);
        ensureStateChanged(rows, order.getStatus(), OrderStatus.FINISHED);

        notificationSender.sendAsync(order.getUserId(), NOTIFICATION_TYPE_ORDER, BIZ_TYPE_ORDER, id,
                "卖家已确认面交完成，订单「" + order.getProductTitle() + "」交易完成");
        return reloadVO(id);
    }

    // ================================================================ 退款

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO applyRefund(Long id, RefundApplyRequest request) {
        Long userId = UserContext.requireUserId();
        Order order = requireOrder(id);
        if (!userId.equals(order.getUserId())) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        if (OrderStatus.REFUND_APPLYING == order.getStatus()) {
            throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
        }
        if (OrderStatus.REFUND_REJECTED == order.getStatus()) {
            throw new BusinessException(ErrorCode.REFUND_REJECTED_WAIT_APPEAL);
        }
        int rows = orderMapper.applyRefund(id, userId, request.getReason());
        ensureStateChanged(rows, order.getStatus(), OrderStatus.REFUND_APPLYING);

        notificationSender.sendAsync(order.getSellerId(), NOTIFICATION_TYPE_ORDER, BIZ_TYPE_ORDER, id,
                "买家对订单「" + order.getProductTitle() + "」申请退款，请及时处理");
        return reloadVO(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO agreeRefund(Long id) {
        Long sellerId = UserContext.requireUserId();
        Order order = requireOrder(id);
        if (!sellerId.equals(order.getSellerId())) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        if (OrderStatus.CANCELLED == order.getStatus()) {
            throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
        }
        int rows = orderMapper.agreeRefund(id, sellerId);
        ensureStateChanged(rows, order.getStatus(), OrderStatus.CANCELLED);

        // 库存回补（场景 ④）；批次 6.0.3 · B1：带幂等凭证
        stockService.restoreOnce(id, order.getProductId(), order.getQuantity());

        notificationSender.sendAsync(order.getUserId(), NOTIFICATION_TYPE_ORDER, BIZ_TYPE_ORDER, id,
                "卖家已同意退款，订单「" + order.getProductTitle() + "」已取消");
        return reloadVO(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO rejectRefund(Long id, RefundRejectRequest request) {
        Long sellerId = UserContext.requireUserId();
        Order order = requireOrder(id);
        if (!sellerId.equals(order.getSellerId())) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        if (OrderStatus.REFUND_REJECTED == order.getStatus()) {
            throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
        }
        int rows = orderMapper.rejectRefund(id, sellerId, request.getRejectReason());
        ensureStateChanged(rows, order.getStatus(), OrderStatus.REFUND_REJECTED);

        // 7 状态保留 3 天申诉期，超时由定时任务自动恢复为原状态（未发货→1，已发货→2）
        notificationSender.sendAsync(order.getUserId(), NOTIFICATION_TYPE_ORDER, BIZ_TYPE_ORDER, id,
                "卖家已拒绝退款申请，订单「" + order.getProductTitle() + "」可在3天内发起申诉");
        return reloadVO(id);
    }

    // ================================================================ 内部工具

    private Order requireOrder(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        return order;
    }

    /**
     * 幂等判定（PROJECT_CONTEXT 3.3「幂等设计」）。
     *
     * <p>所有状态变更接口必须判断影响行数：</p>
     * <ul>
     *   <li>影响行数 &gt; 0：状态已实际变更，正常继续；</li>
     *   <li>影响行数 = 0 且当前状态已是目标状态：抛 {@code code=200 + msg="请勿重复操作"}
     *       （{@code GlobalExceptionHandler} 对 code=200 仍返回 HTTP 200）；</li>
     *   <li>影响行数 = 0 且状态不匹配：抛 code=209「当前状态不允许此操作」。</li>
     * </ul>
     *
     * <p><b>为什么用抛异常而不是 return：</b>本类的状态变更方法统一返回 {@code OrderVO}，
     * 无法承载提示文案。若"已处于目标状态"直接 {@code return vo}，控制层的
     * {@code Result.success(vo)} 会把 msg 写成默认的「操作成功」，
     * 与验收契约 {@code msg="请勿重复操作"} 不符（且不会报错，属静默失真）。
     * 因此各方法在 SQL 之前的快捷预检，也必须与判定失败时走<b>同一条</b>抛异常路径，
     * 保证两条路径对客户端呈现完全一致的行为。</p>
     */
    private void ensureStateChanged(int rows, Integer currentStatus, int targetStatus) {
        if (rows > 0) {
            return;
        }
        if (currentStatus != null && currentStatus == targetStatus) {
            throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
        }
        throw BusinessException.statusNotAllowed();
    }

    private OrderVO reloadVO(Long id) {
        Order latest = orderMapper.selectById(id);
        return toVO(latest, productMapper.selectByIdIgnoreLogicDelete(latest.getProductId()));
    }

    /**
     * 批量取商品（绕过逻辑删除过滤），避免 N+1 查询。
     */
    private Map<Long, Product> loadProducts(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyMap();
        }
        Collection<Long> ids = orders.stream().map(Order::getProductId).collect(Collectors.toSet());
        return productMapper.selectByIdsIgnoreLogicDelete(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
    }

    private OrderVO toVO(Order order, Map<Long, Product> productMap) {
        return toVO(order, productMap == null ? null : productMap.get(order.getProductId()));
    }

    private OrderVO toVO(Order order, Product product) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        vo.setSellerId(order.getSellerId());
        vo.setProductId(order.getProductId());
        vo.setProductTitle(order.getProductTitle());
        vo.setProductPrice(order.getProductPrice());
        vo.setAmount(order.getAmount());
        vo.setQuantity(order.getQuantity());
        vo.setStatus(order.getStatus());
        vo.setAddress(order.getAddress());
        vo.setTradeType(order.getTradeType());
        vo.setPayTime(order.getPayTime());
        vo.setShipTime(order.getShipTime());
        vo.setFinishTime(order.getFinishTime());
        vo.setCancelTime(order.getCancelTime());
        vo.setCancelReason(order.getCancelReason());
        vo.setCancelBy(order.getCancelBy());
        vo.setRefundApplyTime(order.getRefundApplyTime());
        vo.setRefundRejectTime(order.getRefundRejectTime());
        vo.setRefundReason(order.getRefundReason());
        vo.setRefundRejectReason(order.getRefundRejectReason());
        vo.setCreateTime(order.getCreateTime());
        if (product == null) {
            vo.setProductDeleted(Boolean.TRUE);
        } else {
            vo.setProductDeleted(Integer.valueOf(1).equals(product.getIsDeleted()));
            vo.setProductImages(product.getImageUrls());
            if (product.getImageUrls() != null && !product.getImageUrls().isEmpty()) {
                vo.setProductCover(product.getImageUrls().get(0));
            }
        }
        return vo;
    }
}
