package shop.common;

/**
 * 业务异常。
 *
 * <p>与 {@link RuntimeException} 的区别：业务异常是「预期内的、可以明确告知用户的」失败
 * （商品不存在、价格非法、库存不足…），必须带错误码；而系统异常是「预期外的 bug」，
 * 对外只给一句兜底提示，细节写日志。
 *
 * <p>继承 {@code RuntimeException} 而非 {@code Exception} 的原因：业务异常几乎总是
 * 需要回滚事务的「非受检」场景，用受检异常会污染每一层方法签名，且容易在 catch 中吞掉。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
    }

    public int getCode() {
        return code;
    }
}
