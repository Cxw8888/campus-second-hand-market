package com.campus.market.common.enums;

/**
 * 统一错误码 / 业务码（严格遵循 PROJECT_CONTEXT 第 5 章）。
 *
 * <p>HTTP 状态码与业务错误码分离：业务接口一律 HTTP 200，业务结果由 body.code 区分；
 * 仅 401（未登录 / Token 失效）返回真实 HTTP 401。</p>
 */
public enum ErrorCode implements IErrorCode {

    // ---------------- 成功 ----------------
    SUCCESS(200, "操作成功"),

    // ---------------- 参数校验 ----------------
    PARAM_ERROR(100, "参数校验失败"),
    EMAIL_FORMAT_ERROR(102, "邮箱格式错误"),
    EMAIL_CODE_ERROR(103, "验证码错误或已过期"),
    EMAIL_CODE_TOO_FREQUENT(106, "发送过于频繁，请稍后重试"),

    // ---------------- 认证 / 安全 ----------------
    LOGIN_FAILED(101, "用户名或密码错误"),
    ACCOUNT_LOCKED(104, "账号已锁定15分钟"),
    IP_LIMITED(104, "IP已被临时限制，请稍后重试"),
    EMAIL_CODE_LOCKED(107, "验证码服务已锁定30分钟"),
    UNAUTHORIZED(401, "登录已失效，请重新登录"),
    NOT_LOGIN(401, "请先登录"),
    FORBIDDEN(403, "无权限访问"),

    // ---------------- 系统 ----------------
    MAIL_SEND_FAILED(105, "邮件发送失败，请稍后重试"),
    SYSTEM_ERROR(500, "服务器内部错误"),

    // ---------------- 业务逻辑 ----------------
    STOCK_NOT_ENOUGH(201, "库存不足"),
    REPEAT_SUBMIT(202, "请勿重复提交"),
    NO_PERMISSION(203, "无权操作该订单"),
    PRODUCT_NOT_AVAILABLE(204, "商品不存在或已下架"),
    USER_BANNED(205, "用户已被封禁"),
    REFUND_REJECTED_WAIT_APPEAL(206, "退款被拒，请等待申诉结果"),
    PRODUCT_HAS_ORDER(207, "商品有未完成订单，禁止删除"),
    CATEGORY_HAS_PRODUCT(208, "分类下存在商品，请先迁移"),
    STATUS_NOT_ALLOWED(209, "当前状态不允许此操作");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
