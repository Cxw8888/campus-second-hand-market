package com.campus.market.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统一分页响应结构。
 *
 * <p>分页参数规范：page 默认 1（@Min(1)）、size 默认 10（@Max(100)），失败返回 code=100。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分页结果")
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "总记录数")
    private long total;

    @Schema(description = "总页数")
    private long pages;

    @Schema(description = "当前页码")
    private long current;

    @Schema(description = "每页条数")
    private long size;

    @Schema(description = "数据列表")
    private List<T> records;

    public static <T> PageResult<T> empty(long current, long size) {
        return new PageResult<>(0L, 0L, current, size, Collections.emptyList());
    }

    /**
     * 直接由 MyBatis-Plus 分页对象构建（Entity 与 VO 同类型时使用）。
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    /**
     * Entity 分页 + Stream API 转 VO（商品列表必须剔除卖家 phone / email 等敏感字段）。
     */
    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        List<T> records = page.getRecords().stream().map(mapper).collect(Collectors.toList());
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), records);
    }

    public static <T> PageResult<T> of(long total, long current, long size, List<T> records) {
        long pages = size <= 0 ? 0 : (total + size - 1) / size;
        return new PageResult<>(total, pages, current, size, records);
    }
}
