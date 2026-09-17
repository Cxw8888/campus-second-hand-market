package com.campus.market.common.exception;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.filter.RequestIdFilter;
import com.campus.market.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /**
     * 唯一索引名 → 面向用户的文案。
     *
     * <p>键取自 {@code V1__init.sql} 里**实际存在**的索引名（uk_user_username / uk_user_email /
     * uk_category_name / uk_order_no / uk_user_product），不臆造。命中不了就退化为通用文案，
     * 保证"msg 携带具体字段错误"的同时不泄露 SQL 与库表结构。</p>
     */
    private static final Map<String, String> UNIQUE_KEY_MESSAGES = Map.of(
            "uk_category_name", "分类名称已存在",
            "uk_user_username", "该账号已被注册",
            "uk_user_email", "该邮箱已被注册",
            "uk_order_no", "订单号重复，请重试",
            "uk_user_product", "该商品已在收藏列表中"
    );

    /** MySQL 唯一键冲突消息形如：{@code Duplicate entry '乐器' for key 'tb_category.uk_category_name'} */
    private static final Pattern UNIQUE_KEY_PATTERN = Pattern.compile("for key '([^']+)'");

    /**
     * 从异常链里解析唯一索引名（去掉 {@code 表名.} 前缀）；解析不到返回空串。
     *
     * <p>沿 cause 链查找：MyBatis 的 Spring 异常转换可能把原始 SQL 异常包在里层。</p>
     */
    private static String extractUniqueKeyName(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                Matcher matcher = UNIQUE_KEY_PATTERN.matcher(message);
                if (matcher.find()) {
                    String key = matcher.group(1);
                    int dot = key.lastIndexOf('.');
                    return dot >= 0 ? key.substring(dot + 1) : key;
                }
            }
            current = current.getCause();
        }
        return "";
    }

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

    /**
     * 唯一索引冲突（批次 5.4.2 新增）。
     *
     * <p>为什么必须单独映射：唯一索引是<b>数据库层</b>的约束，业务层的前置查重**看不全**它。
     * 典型场景就是分类重名 —— {@code existsByName} 走 MyBatis-Plus，会自动追加
     * {@code is_deleted = 0}，对"已逻辑删除但仍占用唯一索引"的名字返回 count=0，
     * 于是预检放行、INSERT 才抛 {@link DuplicateKeyException}。若不单独处理，它会落到兜底分支
     * 变成 code=500「服务器内部错误」，用户看到的是一个无法理解的错误。</p>
     *
     * <p>按 PROJECT_CONTEXT 第 5 章「100 = 参数校验（通用），msg 必须携带具体字段错误」，
     * 这里统一映射为 code=100，并尽量从异常信息里解析出**具体是哪个唯一键**冲突，
     * 给出可读文案（解析失败时退化为通用文案，绝不把 SQL / 约束名直接抛给用户）。</p>
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e, HttpServletRequest request) {
        String keyName = extractUniqueKeyName(e);
        String msg = UNIQUE_KEY_MESSAGES.getOrDefault(keyName, "数据已存在，请检查唯一字段后重试");
        log.warn("[requestId={}] 唯一索引冲突: key={}, msg={}", requestId(request), keyName, msg);
        return Result.error(ErrorCode.PARAM_ERROR, msg);
    }

    /**
     * 请求的路径不存在（批次 5.4.3 新增）。
     *
     * <p>触发场景：URL 打错、路径变量漏写、前端调了后端没有的接口。Spring Boot 3.2 默认开启
     * 静态资源映射，未匹配到任何 {@code @RequestMapping} 的请求会落到 ResourceHttpRequestHandler，
     * 由它抛 {@link NoResourceFoundException}。它继承自 {@code ServletException}，
     * 若不在本类单独映射，就会落到最后的兜底分支 → <b>code=500「服务器内部错误」</b>，
     * 把"调用方地址写错了"误报成"服务端炸了"（5.4.2 实测中就是这样被误导过一次）。</p>
     *
     * <p><b>为什么用 code=100 而不是 404：</b>PROJECT_CONTEXT 第 5 章的错误码表（V26 封版）
     * <b>没有 404</b>，且明确规定「业务接口一律 HTTP 200，业务结果由 body.code 区分，
     * 仅未登录 / Token 失效返回 HTTP 401」。新增一个 404 码会让代码与封版规范产生漂移
     * （还要同步前端 {@code constants.js} 的 CODE），收益不抵成本。路径写错本质是
     * "请求参数/地址不对"，归入 100「参数校验」自洽，且 msg 会带上真实路径，排查足够快。
     * 若将来要引入 404 语义，属于跨端契约变更，需先更新 PROJECT_CONTEXT 再改代码。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        // 优先用 request 的原始 URI：异常自带的 resourcePath 在"带前缀映射"的场景下是**相对路径**
        // （实测 /static/uploads/missing.png 只会报 "/missing.png"），对排查没有帮助；
        // 调用方真正请求的那个 URL 才是他想看到的。
        String uri = request.getRequestURI();
        String path = (uri == null || uri.isBlank()) ? "/" + e.getResourcePath() : uri;
        String msg = "请求的接口不存在: " + path;
        log.warn("[requestId={}] 路径未匹配到任何接口: {} {}", requestId(request), request.getMethod(), msg);
        return Result.error(ErrorCode.PARAM_ERROR, msg);
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
