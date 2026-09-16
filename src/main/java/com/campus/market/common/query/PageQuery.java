package com.campus.market.common.query;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 分页查询基类。
 *
 * <p>参数校验细化（第 3.3 节）：page 默认 1、{@code @Min(1)}；size 默认 10、{@code @Max(100)}；
 * 校验失败统一由全局异常处理器映射为 code=100。</p>
 */
@Data
@Schema(description = "分页查询参数")
public class PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    @Min(value = 1, message = "页码必须大于0")
    @Schema(description = "页码，默认 1")
    private Integer page = 1;

    @Min(value = 1, message = "分页大小必须大于0")
    @Max(value = 100, message = "分页大小不能超过100")
    @Schema(description = "每页条数，默认 10，最大 100")
    private Integer size = 10;

    public long current() {
        return page == null ? 1L : page;
    }

    public long pageSize() {
        return size == null ? 10L : size;
    }
}
