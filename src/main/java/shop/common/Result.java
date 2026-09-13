package shop.common;

import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>项目现在混用了三种「返回结果」：Boolean（addToCart/checkSession）、void（changeStatus）、
 * 视图名（页面）。这对前端不友好 —— 每个接口都要写一套不同的解析逻辑，而且失败时前端
 * 无从判断。这里统一为 {@code {"code":200,"message":"ok","data":...}}，
 * 前端只需看 {@code code}/{@code success} 两个字段。
 *
 * <p>之所以带 {@code code} 而不是单纯 Boolean：HTTP 200 只能表示「请求处理完了」，
 * 不能表示「业务成功」，两者必须分开（否则缓存/网关/监控都会误判）。
 *
 * @param <T> 业务数据类型
 */
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务状态码，见 {@link ErrorCode} */
    private int code;

    /** 提示信息，可直接展示给用户 */
    private String message;

    /** 业务数据，失败时为 null */
    private T data;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ------------------------------------------------------------------ 成功

    public static <T> Result<T> success() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), message, data);
    }

    // ------------------------------------------------------------------ 失败

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 供 Jackson 序列化出 {@code success} 字段，同时避免每个前端都自己写
     * {@code code === 200} 的判断。
     */
    public boolean isSuccess() {
        return this.code == ErrorCode.SUCCESS.getCode();
    }

    // ------------------------------------------------------------- getter/setter

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "Result{code=" + code + ", message='" + message + "', data=" + data + '}';
    }
}
