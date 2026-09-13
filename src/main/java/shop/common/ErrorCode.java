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

    /**
     * 【Phase 2.4】资源状态已变更 —— 典型场景是并发抢购同一件商品时「你慢了一步」。
     *
     * <p>为什么单独给一个 409 而不是复用 {@code BIZ_ERROR}：
     * <ul>
     *   <li>{@code BIZ_ERROR} 的 code 就是 500，会被映射成 HTTP 500 ——
     *       于是「商品已被别人买走」这种<b>预期内的失败</b>在监控里和真正的服务端故障混在一起，
     *       错误率、告警全部失真（这一点是在并发测试里暴露出来的）；</li>
     *   <li>409 Conflict 的语义正是「请求与资源的当前状态冲突」，对应乐观锁/条件更新失败这类场景。</li>
     * </ul>
     */
    CONFLICT(409, "资源状态已变更，请刷新后重试"),
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
