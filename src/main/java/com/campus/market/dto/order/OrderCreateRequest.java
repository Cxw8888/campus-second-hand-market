package com.campus.market.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 下单请求。
 *
 * <p><b>严禁传 amount 与 tradeType</b>：amount = product_price × quantity 由后端计算；
 * tradeType 由后端从商品读取并写入订单快照（严禁信任前端传参）。</p>
 */
@Data
@Schema(description = "下单请求")
public class OrderCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "商品ID不能为空")
    @Schema(description = "商品ID")
    private Long productId;

    /**
     * 购买数量。
     *
     * <p><b>上限兜底（批次 6.0.6 · Minor 4）</b>：修前只有 {@code @Min(1)}，
     * {@code quantity=999999} 这类恶意/误输入会一路走到"读商品 → 校验库存 → CAS 扣减"。
     * CAS 的 {@code stock >= quantity} 能挡住超卖（无资损），但白耗一次库存行锁，
     * 而且报错语义是 201「库存不足」而不是参数错误 —— 参数层能拒绝的输入不该下沉到业务层。</p>
     *
     * <p>为什么是 100：校园二手单笔最多买几十件，100 已远超真实需求，
     * 又远低于任何"数据异常"量级。</p>
     */
    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "购买数量必须大于0")
    @Max(value = 100, message = "购买数量不能超过100")
    @Schema(description = "购买数量")
    private Integer quantity;

    /** 邮寄地址：商品 trade_type = 2 或 3 时必填，业务层校验。 */
    @Size(max = 255, message = "收货地址长度不能超过255")
    @Schema(description = "收货地址（邮寄订单必填）")
    private String address;

    /**
     * 下单防重 Token（由 GET /api/v1/order/token 获取）。
     *
     * <p>支持放在请求体或请求头 {@code X-Order-Token}，二者取其一，请求头优先。</p>
     */
    @Schema(description = "下单防重Token（也可通过请求头 X-Order-Token 传递）")
    private String orderToken;
}
