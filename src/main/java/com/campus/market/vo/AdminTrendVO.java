package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端趋势数据视图对象（批次 5.5.2）。
 *
 * <p>一次请求返回<b>同一个时间轴上的三组数据</b>（决策 3：只做一个趋势接口），
 * 前端不必发 3 个请求，也就不会出现「三条线的日期轴对不齐」这种画面。</p>
 *
 * <h3>字段与 JSON 形态</h3>
 * <pre>
 * {
 *   "days": 7,                                  // Integer → JSON number
 *   "dates": ["2026-09-12", ..., "2026-09-18"], // 已序列化好的 YYYY-MM-DD，前端不再格式化
 *   "orderCounts":   ["0","0","0","0","36","2","0"],   // List&lt;Long&gt; → **字符串数组**
 *   "productCounts": ["3","3","2","3","2","0","0"],
 *   "userCounts":    ["0","0","0","5","26","0","0"]
 * }
 * </pre>
 *
 * <p><b>⚠️ 为什么 counts 是字符串数组</b>：{@code JacksonConfig} 给 {@code Long/long}
 * 注册了 {@code ToStringSerializer}（防 JS 精度丢失），所以 {@code List<Long>} 的每个元素
 * 都会被序列化成字符串。而 {@code days} 是 {@code Integer}，<b>不受该配置影响</b>，
 * 仍是 JSON number。前端在 {@code api/admin.js} 里统一 Number() 归一化
 * （api 层职责，组件层不再做转换）。</p>
 *
 * <h3>三个数组与 dates 严格等长</h3>
 * <p>SQL 只返回「有数据的日期」，缺口由 Service 按 {@code today - (days-1)} ~ {@code today}
 * <b>连续补齐 0</b>。这样前端可以直接把三个数组当作与 dates 一一对应的序列喂给 ECharts
 * （折线不会因为缺日期而断点或错位）。</p>
 */
@Data
@Schema(description = "管理端趋势数据（同一时间轴上的订单/商品/用户三组计数）")
public class AdminTrendVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "统计天数：7 或 30（白名单校验）")
    private Integer days;

    @Schema(description = "日期轴（含今天，升序），格式 yyyy-MM-dd，长度恒等于 days")
    private List<String> dates = new ArrayList<>();

    @Schema(description = "每日新增订单数（字符串数组，长度恒等于 days，缺数据的位置为 \"0\"）")
    private List<Long> orderCounts = new ArrayList<>();

    @Schema(description = "每日新增商品数（同上）")
    private List<Long> productCounts = new ArrayList<>();

    @Schema(description = "每日新增用户数（同上）")
    private List<Long> userCounts = new ArrayList<>();
}
