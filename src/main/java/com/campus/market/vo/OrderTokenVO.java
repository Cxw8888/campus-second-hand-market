package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 下单防重 Token 响应：前端获取后在提交订单时回传，后端通过 Lua 原子校验并删除实现幂等。
 */
@Data
@Schema(description = "下单防重Token")
public class OrderTokenVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "防重Token（UUID）")
    private String token;

    @Schema(description = "有效期（秒）")
    private long expireSeconds;

    public OrderTokenVO() {
    }

    public OrderTokenVO(String token, long expireSeconds) {
        this.token = token;
        this.expireSeconds = expireSeconds;
    }
}
