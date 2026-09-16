package com.campus.market.common.enums;

/**
 * 错误码抽象，便于 Result / BusinessException 统一承载。
 */
public interface IErrorCode {

    /**
     * 业务码（见 PROJECT_CONTEXT 第 5 章错误码分段规范）。
     */
    int getCode();

    /**
     * 面向用户的提示信息（严禁携带堆栈、SQL 等敏感信息）。
     */
    String getMsg();
}
