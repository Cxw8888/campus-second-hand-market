package com.campus.market.controller;

import com.campus.market.common.result.PageResult;
import com.campus.market.common.result.Result;
import com.campus.market.dto.order.OrderCancelRequest;
import com.campus.market.dto.order.OrderCreateRequest;
import com.campus.market.dto.order.OrderQuery;
import com.campus.market.dto.order.PayCallbackRequest;
import com.campus.market.dto.order.RefundApplyRequest;
import com.campus.market.dto.order.RefundRejectRequest;
import com.campus.market.service.OrderService;
import com.campus.market.vo.OrderCreateVO;
import com.campus.market.vo.OrderTokenVO;
import com.campus.market.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口（强制认证路径，除支付回调外均需有效 Token）。
 *
 * <p>权限归属：买家操作支付 / 收货 / 取消 / 退款申请（user_id 校验），
 * 卖家操作发货 / 退款处理 / 面交直接完成（seller_id 校验），管理员除外。</p>
 */
@Tag(name = "订单", description = "下单、支付、发货、收货、取消、退款")
@RestController
@RequestMapping("/api/v1/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "获取下单防重Token", description = "Redis order:token:{userId}:{uuid}，TTL 5 分钟")
    @GetMapping("/token")
    public Result<OrderTokenVO> orderToken() {
        return Result.success(orderService.generateOrderToken());
    }

    @Operation(summary = "下单", description = "Lua 原子校验防重Token；CAS 扣减库存；后端计算 amount 与 trade_type 快照")
    @PostMapping
    public Result<OrderCreateVO> create(@Valid @RequestBody OrderCreateRequest request,
                                        @RequestHeader(value = "X-Order-Token", required = false) String orderTokenHeader) {
        // 请求头优先，其次取请求体字段
        String token = (orderTokenHeader != null && !orderTokenHeader.isBlank())
                ? orderTokenHeader : request.getOrderToken();
        return Result.success(orderService.createOrder(request, token));
    }

    @Operation(summary = "订单列表", description = "role=buyer 我买到的（默认）/ seller 我卖出的")
    @GetMapping("/list")
    public Result<PageResult<OrderVO>> list(@Valid OrderQuery query) {
        return Result.success(orderService.listOrders(query));
    }

    @Operation(summary = "订单详情", description = "归属校验失败返回 203；商品已删除时回看快照")
    @GetMapping("/detail/{id}")
    public Result<OrderVO> detail(@PathVariable Long id) {
        return Result.success(orderService.getOrderDetail(id));
    }

    @Operation(summary = "支付订单", description = "0→1，买家操作")
    @PutMapping("/pay/{id}")
    public Result<OrderVO> pay(@PathVariable Long id) {
        return Result.success(orderService.pay(id));
    }

    @Operation(summary = "模拟支付回调", description = "公开路径（签名校验）；按 order_no + tradeNo 幂等去重，重复回调直接返回成功")
    @PostMapping("/pay/callback")
    public Result<Boolean> payCallback(@Valid @RequestBody PayCallbackRequest request) {
        boolean processed = orderService.handlePayCallback(request.getOrderNo(), request.getTradeNo());
        return Result.success(processed ? "支付成功" : "请勿重复回调", processed);
    }

    @Operation(summary = "买家取消订单", description = "0→4，cancel_by=买家ID，同步库存回补")
    @PutMapping("/cancel/{id}")
    public Result<OrderVO> cancel(@PathVariable Long id, @Valid @RequestBody(required = false) OrderCancelRequest request) {
        return Result.success(orderService.cancel(id, request));
    }

    @Operation(summary = "卖家发货", description = "1→2，仅 trade_type IN (2,3)，面交订单严禁发货")
    @PutMapping("/ship/{id}")
    public Result<OrderVO> ship(@PathVariable Long id) {
        return Result.success(orderService.ship(id));
    }

    @Operation(summary = "买家确认收货", description = "邮寄 2→3；面交 1→3")
    @PutMapping("/receive/{id}")
    public Result<OrderVO> receive(@PathVariable Long id) {
        return Result.success(orderService.receive(id));
    }

    @Operation(summary = "面交直接完成", description = "0→3，必须用 seller_id 校验（严禁使用 user_id）")
    @PutMapping("/finish-face/{id}")
    public Result<OrderVO> finishFaceToFace(@PathVariable Long id) {
        return Result.success(orderService.finishFaceToFace(id));
    }

    @Operation(summary = "买家申请退款", description = "1/2→6")
    @PostMapping("/refund/apply/{id}")
    public Result<OrderVO> applyRefund(@PathVariable Long id, @Valid @RequestBody RefundApplyRequest request) {
        return Result.success(orderService.applyRefund(id, request));
    }

    @Operation(summary = "卖家同意退款", description = "6→4，同步库存回补")
    @PutMapping("/refund/agree/{id}")
    public Result<OrderVO> agreeRefund(@PathVariable Long id) {
        return Result.success(orderService.agreeRefund(id));
    }

    @Operation(summary = "卖家拒绝退款", description = "6→7，保留 3 天申诉期，超时自动恢复 1/2")
    @PutMapping("/refund/reject/{id}")
    public Result<OrderVO> rejectRefund(@PathVariable Long id, @Valid @RequestBody RefundRejectRequest request) {
        return Result.success(orderService.rejectRefund(id, request));
    }

    @Operation(summary = "退款申请列表", description = "status IN (6,7)")
    @GetMapping("/refund/list")
    public Result<PageResult<OrderVO>> refundList(@Valid OrderQuery query) {
        return Result.success(orderService.listRefunds(query));
    }
}
