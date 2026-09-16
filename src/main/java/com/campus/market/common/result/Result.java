package com.campus.market.common.result;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.enums.IErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应体 {@code Result<T>}（code, msg, data）。
 *
 * <p>约定：业务接口一律 HTTP 200，业务结果由 code 区分；仅未登录 / Token 失效返回 HTTP 401。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一响应体")
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "业务码：200 成功，其余见错误码规范")
    private int code;

    @Schema(description = "提示信息")
    private String msg;

    @Schema(description = "业务数据")
    private T data;

    // ---------------------------------------------------------------- 成功

    public static <T> Result<T> success() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), data);
    }

    /**
     * 幂等场景：已处于目标状态时返回 200 并提示"请勿重复操作"。
     */
    public static <T> Result<T> success(String msg, T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), msg, data);
    }

    // ---------------------------------------------------------------- 失败

    public static <T> Result<T> error(IErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMsg(), null);
    }

    /**
     * 覆盖默认 msg 的失败响应（如 100 参数校验需携带具体字段错误）。
     */
    public static <T> Result<T> error(IErrorCode errorCode, String msg) {
        return new Result<>(errorCode.getCode(), msg, null);
    }

    public static <T> Result<T> error(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    public boolean isSuccess() {
        return this.code == ErrorCode.SUCCESS.getCode();
    }
}
