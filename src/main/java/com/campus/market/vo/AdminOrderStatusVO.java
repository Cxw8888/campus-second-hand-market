package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 订单状态分布（批次 5.5.1）：{@code status} + {@code count}。
 *
 * <p><b>为什么没有 label 字段（与需求给出的 JSON 略有差异，已在报告中说明）</b>：
 * 状态文案的唯一事实来源是前端 {@code utils/constants.js} 的 {@code ORDER_STATUS_MAP}
 * （其中 2 的文案是「已发货待收货」，5 是「已冻结」）。后端 {@code OrderStatus} 只有数字常量、
 * 没有任何中文文案，若在 VO 里补一个 label 就必然要把中文（或一张数字→文案的映射表）
 * 硬编码进后端 —— 那正是本批「禁止硬编码 ORDER_STATUS_MAP 的 label」所禁止的
 * （还会与前端字典慢慢长歪：改一处忘一处，同一个状态在两个页面上叫两个名字）。
 * 因此后端只回传状态码与数量，label 由前端从字典取。</p>
 *
 * <p>接口保证<b>恒定返回 8 条</b>（0~7 全量补齐，无数据的状态 count=0），
 * 这样前端的图表槽位是稳定的，不会因为"某个状态恰好没有订单"而少一格。</p>
 */
@Data
@Schema(description = "订单状态分布（status + count，label 由前端字典提供）")
public class AdminOrderStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单状态：0-待支付,1-已支付待发货,2-已发货待收货,3-已完成,4-已取消,5-已冻结,6-退款申请中,7-退款被拒")
    private Integer status;

    @Schema(description = "该状态订单数（未删除）")
    private Long count;

    public AdminOrderStatusVO() {
    }

    public AdminOrderStatusVO(Integer status, Long count) {
        this.status = status;
        this.count = count;
    }
}
