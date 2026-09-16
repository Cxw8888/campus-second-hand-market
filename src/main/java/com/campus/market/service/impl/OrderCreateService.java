package com.campus.market.service.impl;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.dto.order.OrderCreateRequest;
import com.campus.market.entity.Order;
import com.campus.market.entity.Product;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.service.NotificationSender;
import com.campus.market.service.StockService;
import com.campus.market.util.SnowflakeIdGenerator;
import com.campus.market.vo.OrderCreateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 下单事务体。
 *
 * <p>单独抽成 Bean 是为了让 {@code @Transactional} 生效：{@link OrderServiceImpl} 的
 * 防重 Token 消费必须发生在事务<b>之外</b>（否则校验失败会连带回滚已删除的 Token），
 * 因此不能通过同类内部调用触发事务（Spring AOP 自调用失效）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateService {

    private static final int NOTIFICATION_TYPE_ORDER = 1;
    private static final int BIZ_TYPE_ORDER = 1;

    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;
    private final StockService stockService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final NotificationSender notificationSender;

    /**
     * 事务内下单：读商品最新价 → 校验可见性/地址 → CAS 扣减 → 写订单快照。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderCreateVO create(OrderCreateRequest request, Long userId) {
        // ① 事务内直接读 MySQL 最新商品（绕过 Redis 缓存，规避"下单与改价并发窗口"）
        Product product = productMapper.selectById(request.getProductId());
        if (product == null || product.getStatus() == null || product.getStatus() != 1) {
            throw BusinessException.productNotAvailable();
        }
        if (product.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能购买自己发布的商品");
        }

        // ② 交易方式快照由后端从商品读取，严禁信任前端传参
        Integer tradeType = product.getTradeType();
        if (tradeType != null && (tradeType == 2 || tradeType == 3)
                && (request.getAddress() == null || request.getAddress().isBlank())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "邮寄订单收货地址不能为空");
        }

        // ③ CAS 扣减库存：影响行数 0 → 库存不足，抛异常触发事务回滚
        int affected = stockService.deduct(product.getId(), request.getQuantity());
        if (affected == 0) {
            throw BusinessException.stockNotEnough();
        }

        // ④ 金额必须 BigDecimal 计算：amount = product_price × quantity
        BigDecimal priceSnapshot = product.getPrice();
        BigDecimal amount = priceSnapshot.multiply(BigDecimal.valueOf(request.getQuantity()));

        Order order = new Order();
        order.setOrderNo(snowflakeIdGenerator.nextOrderNo());
        order.setUserId(userId);
        order.setProductId(product.getId());
        order.setSellerId(product.getUserId());
        order.setProductTitle(product.getTitle());
        order.setProductPrice(priceSnapshot);
        order.setAmount(amount);
        order.setQuantity(request.getQuantity());
        order.setStatus(OrderStatus.PENDING_PAY);
        order.setTradeType(tradeType);
        order.setAddress(request.getAddress());
        order.setCancelBy(0L);
        orderMapper.insert(order);

        // ⑤ 事务提交后异步通知卖家，避免阻塞主交易流程
        notificationSender.sendAsync(product.getUserId(), NOTIFICATION_TYPE_ORDER, BIZ_TYPE_ORDER, order.getId(),
                "您出售的商品「" + product.getTitle() + "」有新订单，请及时处理");

        OrderCreateVO vo = new OrderCreateVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setAmount(amount);
        vo.setStatus(order.getStatus());
        return vo;
    }
}
