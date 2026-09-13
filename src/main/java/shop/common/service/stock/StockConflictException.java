package shop.common.service.stock;

/**
 * 库存版本冲突 —— 内部信号异常，不对用户暴露。
 *
 * <p>它存在的原因是把「值得重试的失败」和「不值得重试的失败」区分开：
 * <ul>
 *   <li>{@code StockConflictException} → 有人抢先改了同一行，重试有可能成功；</li>
 *   <li>库存不足（{@code OUT_OF_STOCK}）→ 重试多少次都一样，必须立刻失败。</li>
 * </ul>
 * 两者如果都抛同一个异常，调用方就只能无脑重试，白白放大数据库压力。
 *
 * <p>它是 {@code RuntimeException}，所以会触发事务回滚 —— 这正是我们要的：
 * 一次失败的扣减必须把事务整体回滚掉，否则 FOR UPDATE 的行锁不会及时释放。
 */
public class StockConflictException extends RuntimeException {

    public StockConflictException(String message) {
        super(message);
    }
}
