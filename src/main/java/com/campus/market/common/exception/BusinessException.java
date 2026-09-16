package com.campus.market.common.exception;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.enums.IErrorCode;
import lombok.Getter;

/**
 * 业务异常：由 Service / 拦截器抛出，交由 {@code GlobalExceptionHandler} 统一转换为 Result。
 *
 * <p>约定：业务异常不回滚以外的情况一律 {@code @Transactional(rollbackFor = Exception.class)} 回滚。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务码（非 HTTP 状态码）。 */
    private final int code;

    public BusinessException(IErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
    }

    /**
     * 覆盖默认 msg（如 100 参数校验携带具体字段错误、203 携带具体资源名称）。
     */
    public BusinessException(IErrorCode errorCode, String msg) {
        super(msg);
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    // ------------------------------------------------------------ 语义化快捷方法

    /** 库存不足（201）。 */
    public static BusinessException stockNotEnough() {
        return new BusinessException(ErrorCode.STOCK_NOT_ENOUGH);
    }

    /** 重复提交（202）。 */
    public static BusinessException repeatSubmit() {
        return new BusinessException(ErrorCode.REPEAT_SUBMIT);
    }

    /** 无权操作该订单/商品/通知（203）。 */
    public static BusinessException noPermission(String msg) {
        return new BusinessException(ErrorCode.NO_PERMISSION, msg);
    }

    /** 商品不存在或已下架（204）。 */
    public static BusinessException productNotAvailable() {
        return new BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE);
    }

    /** 当前状态不允许此操作（209，状态机冲突）。 */
    public static BusinessException statusNotAllowed() {
        return new BusinessException(ErrorCode.STATUS_NOT_ALLOWED);
    }

    /** 未登录 / Token 失效（401，HTTP 401）。 */
    public static BusinessException unauthorized() {
        return new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    /** 无管理员权限（403）。 */
    public static BusinessException forbidden() {
        return new BusinessException(ErrorCode.FORBIDDEN);
    }
}
