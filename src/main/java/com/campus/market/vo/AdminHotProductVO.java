package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端热门商品榜视图对象（批次 5.5.2）。
 *
 * <pre>
 * {
 *   "days": 7,
 *   "items": [
 *     { "productId": "14", "productTitle": "二手高等数学教材（第三版）",
 *       "categoryName": "教材书籍", "orderCount": "5" },
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <p><b>排名口径</b>（决策 1 + 决策 2）：近 {@code days} 天（默认 7）内该商品被下单的
 * <b>订单条数</b>，按订单数降序、同数按 productId 升序兜底；不分订单状态
 * （口径细节见 {@code AdminStatsMapper.selectHotProducts} 的注释）。</p>
 *
 * <p>无数据时 {@code items} 是<b>空数组</b>而不是 null（前端可以直接 .length 判断）。</p>
 */
@Data
@Schema(description = "管理端热门商品榜（近 N 天按订单数）")
public class AdminHotProductVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "统计窗口天数：7 或 30（白名单校验；本批前端固定传 7）")
    private Integer days;

    @Schema(description = "榜单条目（按 orderCount 降序），无数据时为空数组")
    private List<HotProductItem> items = new ArrayList<>();
}
