package shop.common;

/**
 * 业务错误码。
 *
 * <p>为什么不直接用 HTTP 状态码：HTTP 状态码描述的是「传输层/协议层」的结果，
 * 而业务失败（如「商品已下架」「余额不足」）本质是 HTTP 200 的正常响应。
 * 两者混用会导致网关、监控、前端拦截器都误判。
 *
 * <p>编码分段约定：
 * <ul>
 *   <li>2xx   成功</li>
 *   <li>4xxx  客户端错误（参数、认证、权限、资源不存在）—— 请求方改代码能解决</li>
 *   <li>5xxx  服务端错误 —— 请求方重试或联系运维</li>
 * </ul>
 */
public enum ErrorCode {

    SUCCESS(200, "操作成功"),

    PARAM_ERROR(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或登录状态已过期"),
    FORBIDDEN(403, "无权限访问该资源"),
    NOT_FOUND(404, "请求的资源不存在"),

    BIZ_ERROR(500, "业务处理失败"),
    SYSTEM_ERROR(5000, "系统异常，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
