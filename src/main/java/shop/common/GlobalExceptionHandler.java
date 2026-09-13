package shop.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>改造前：每个 Controller 自己 try-catch + printStackTrace + 返回各自形状的结果，
 * 同一个错误在不同接口表现不一，堆栈直接打到控制台（生产环境会淹没日志、且无告警）。
 *
 * <p>改造后：所有异常在这里收敛成统一的 {@link Result}，并遵循两条原则：
 * <ol>
 *   <li><b>HTTP 状态码与业务错误码并存</b>：HTTP 状态码给网关/监控/浏览器看
 *       （401 触发登录跳转、500 触发告警、404 不计入错误率），
 *       业务 code 给前端做精确提示。只返回 200 + 业务码会让所有监控失真。</li>
 *   <li><b>预期内 vs 预期外区别对待</b>：业务异常用 WARN 记录（不需要告警），
 *       把 message 原样返回；系统异常用 ERROR 记录完整堆栈（需要告警），
 *       对外只给兜底文案 —— 绝不把表名、SQL、绝对路径等内部信息泄露给客户端。</li>
 * </ol>
 *
 * <p>注意：本类用 {@code @RestControllerAdvice}，页面控制器抛出的异常也会返回 JSON。
 * Phase 1 引入前后端分离后，会在这里按 Accept / URI 分流：页面请求渲染 error 页，
 * 接口请求返回 JSON。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：预期内的失败，返回原始 message 便于前端直接提示 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("业务异常 uri={} code={} message={}", request.getRequestURI(), e.getCode(), e.getMessage());
        return ResponseEntity.status(toHttpStatus(e.getCode()))
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** {@code @RequestBody} + {@code @Valid} 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                     HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 uri={} message={}", request.getRequestURI(), message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ErrorCode.PARAM_ERROR, message));
    }

    /** 表单对象绑定 + {@code @Valid} 校验失败 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e, HttpServletRequest request) {
        String message = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败 uri={} message={}", request.getRequestURI(), message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ErrorCode.PARAM_ERROR, message));
    }

    /** 必填请求参数缺失 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e,
                                                           HttpServletRequest request) {
        log.warn("缺少必填参数 uri={} param={}", request.getRequestURI(), e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ErrorCode.PARAM_ERROR, "缺少必填参数：" + e.getParameterName()));
    }

    /** 兜底：预期外的系统异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e, HttpServletRequest request) {
        // 打印完整堆栈（含根因），但不把细节返回给客户端
        log.error("系统异常 uri={}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.SYSTEM_ERROR));
    }

    /**
     * 业务错误码 → HTTP 状态码。
     *
     * <p>只对「有明确 HTTP 语义」的码做映射，其余统一落 500：
     * 让浏览器和网关能对未登录(401)、无权限(403)、不存在(404)、参数错(400) 做出正确反应。
     */
    private static HttpStatus toHttpStatus(int code) {
        switch (code) {
            case 400:
                return HttpStatus.BAD_REQUEST;
            case 401:
                return HttpStatus.UNAUTHORIZED;
            case 403:
                return HttpStatus.FORBIDDEN;
            case 404:
                return HttpStatus.NOT_FOUND;
            default:
                return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }
}
