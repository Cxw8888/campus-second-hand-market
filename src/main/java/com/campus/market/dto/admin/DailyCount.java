package com.campus.market.dto.admin;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 「按天分组」聚合结果的投影对象（批次 5.5.2）。
 *
 * <p>只服务于 {@link com.campus.market.mapper.AdminStatsMapper} 的三条趋势 SQL
 * （订单 / 商品 / 用户各一条）。<b>它不是对外 VO</b> —— 对外是 {@code AdminTrendVO}
 * （三条 SQL 的结果由 Service 合并成「同一个时间轴上的三个数组」后才出去），
 * 所以放在 dto 包而不是 vo 包，避免被误当成接口契约。</p>
 *
 * <p>字段与 SQL 的对应关系：</p>
 * <pre>
 *   SELECT DATE(create_time) AS statDate, COUNT(*) AS `count` ...
 * </pre>
 *
 * <p>{@code DATE(create_time)} 经 MySQL Connector/J 返回 {@code java.sql.Date}，
 * MyBatis 默认的 {@code LocalDateTypeHandler} 会把它转成 {@link LocalDate}
 * （已在真库上实测：返回 2026-09-16 等正确日期，不是 1970 之类的时区错位值）。</p>
 */
@Data
public class DailyCount implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 统计日期（数据库按 GMT+8 存的时间直接取日期部分，不做任何时区换算）。 */
    private LocalDate statDate;

    /** 该日期的计数。 */
    private Long count;

    public DailyCount() {
    }

    public DailyCount(LocalDate statDate, Long count) {
        this.statDate = statDate;
        this.count = count;
    }
}
