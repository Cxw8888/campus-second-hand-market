package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 管理端概览卡片视图对象（批次 5.5.1）：一次请求返回 8 个数字。
 *
 * <p>字段口径见 {@code AdminStatsServiceImpl} 的类注释，这里只强调两处实现约定：</p>
 * <ul>
 *   <li><b>计数一律 Long</b>：JacksonConfig 把 Long 序列化成<b>字符串</b>（防 JS 精度丢失），
 *       所以响应里 {@code "userTotal":"100"} 而不是 {@code 100}，前端需 Number() 后再展示；</li>
 *   <li><b>GMV 一律 BigDecimal</b>：金额严禁 Double（SUM(amount) 用 Double 接会引入
 *       二进制浮点误差，累加金额后出现 4999.999999999 这类脏数字）。</li>
 * </ul>
 */
@Data
@Schema(description = "管理端概览统计（8 个数字）")
public class AdminOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户总数（tb_user 未删除）")
    private Long userTotal;

    @Schema(description = "今日新增用户（create_time >= 今天 00:00:00 GMT+8）")
    private Long userTodayNew;

    @Schema(description = "商品总数（tb_product 未删除，不分状态）")
    private Long productTotal;

    @Schema(description = "今日新增商品")
    private Long productTodayNew;

    @Schema(description = "订单总数（tb_order 未删除，不分状态）")
    private Long orderTotal;

    @Schema(description = "今日新增订单")
    private Long orderTodayNew;

    @Schema(description = "累计 GMV（status=3 已完成订单的 SUM(amount)）")
    private BigDecimal gmvTotal;

    @Schema(description = "今日 GMV（今天完成的订单 SUM(amount)）")
    private BigDecimal gmvToday;
}
