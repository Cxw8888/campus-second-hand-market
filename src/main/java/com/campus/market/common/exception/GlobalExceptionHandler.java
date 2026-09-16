package com.campus.market.common.exception;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.filter.RequestIdFilter;
import com.campus.market.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器（第 5 章错误码规范）。
 *
 * <ul>
 *   <li>业务接口一律 HTTP 200，业务结果由 body.code 区分；</li>
 *   <li><b>仅未登录 / Token 失效返回 HTTP 401</b>（code=401）；</li>
 *   <li>Spring Validation 注解校验失败统一 code=100，msg 携带具体字段错误；</li>
 *   <li>102/103/106/107 仅由业务层手动校验返回（本类不映射）；</li>
 *   <li>其他异常 log.error("完整堆栈", e) 记录含 requestId，响应层脱敏 code=500。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ------------------------------------------------ 业务异常

    /**
     * 业务异常：HTTP 200 + body.code 区分。
     * 特例：401 未登录 / Token 失效必须返回真实 HTTP 401（见验收标准）。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e, HttpServletRequest request) {
        String requestId = requestId(request);
        log.warn("[requestId={}] 业务异常: code={}, msg={}, uri={} {}",
                requestId, e.getCode(), e.getMessage(), request.getMethod(), request.getRequestURI());
        Result<Void> body = Result.error(e.getCode(), e.getMessage());
        if (e.getCode() == ErrorCode.UNAUTHORIZED.getCode()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------ 参数校验（统一 code=100）

    /** @RequestBody + @Valid 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse(ErrorCode.PARAM_ERROR.getMsg());
        log.warn("[requestId={}] 参数校验失败: {}", requestId(request), msg);
        return Result.error(ErrorCode.PARAM_ERROR, msg);
    }

    /** 表单 / Query 对象绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse(ErrorCode.PARAM_ERROR.getMsg());
        log.warn("[requestId={}] 参数绑定失败: {}", requestId(request), msg);
        return Result.error(ErrorCode.PARAM_ERROR, msg);
    }

    /** 方法参数级 @Validated（@Min / @Max 等）校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e, HttpServletRequest request) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse(ErrorCode.PARAM_ERROR.getMsg());
        log.warn("[requestId={}] 参数约束失败: {}", requestId(request), msg);
        return Result.error(ErrorCode.PARAM_ERROR, msg);
    }

    /** 缺少必填请求参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e, HttpServletRequest request) {
        String msg = "缺少必填参数: " + e.getParameterName();
        log.warn("[requestId={}] {}", requestId(request), msg);
        return Result.error(ErrorCode.PARAM_ERROR, msg);
    }

    /** 参数类型不匹配。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        String msg = "参数类型不正确: " + e.getName();
        log.warn("[requestId={}] {}", requestId(request), msg);
        return Result.error(ErrorCode.PARAM_ERROR, msg);
    }

    /** 请求体不可读（JSON 格式错误等）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.warn("[requestId={}] 请求体解析失败: {}", requestId(request), e.getMessage());
        return Result.error(ErrorCode.PARAM_ERROR, "请求参数格式不正确");
    }

    /**
     * 上传文件超过 multipart 限制（spring.servlet.multipart.max-file-size=5MB）。
     *
     * <p>该异常在进入 Controller 之前由 MultipartResolver 抛出，必须单独映射为 code=100，
     * 否则会落到兜底分支返回 code=500，与"参数校验统一 100"的规范不符。</p>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e, HttpServletRequest request) {
        log.warn("[requestId={}] 上传文件超限: {}", requestId(request), e.getMessage());
        return Result.error(ErrorCode.PARAM_ERROR, "上传文件大小不能超过5MB");
    }

    /** 其他 multipart 异常（如请求不是 multipart 格式）。 */
    @ExceptionHandler(MultipartException.class)
    public Result<Void> handleMultipart(MultipartException e, HttpServletRequest request) {
        log.warn("[requestId={}] multipart 请求异常: {}", requestId(request), e.getMessage());
        return Result.error(ErrorCode.PARAM_ERROR, "文件上传请求格式不正确");
    }

    // ------------------------------------------------ 其他

    /** 请求方法不支持。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("[requestId={}] 请求方法不支持: {}", requestId(request), e.getMessage());
        return Result.error(ErrorCode.PARAM_ERROR, "请求方法不支持");
    }

    /**
     * 兜底异常：记录完整堆栈 + requestId，响应层脱敏。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e, HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("[requestId={}] 服务器内部错误, uri={} {}", requestId, request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.ok(Result.error(ErrorCode.SYSTEM_ERROR));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? "-" : value.toString();
    }
}
