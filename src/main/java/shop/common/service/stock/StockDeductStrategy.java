package shop.common.service.stock;

/**
 * 库存扣减策略（策略模式，Phase 2.4）。
 *
 * <p>两种实现用配置切换：{@code shop.stock.strategy=optimistic | pessimistic}。
 * 用策略模式而不是 if/else 的理由很实际：两种实现的<b>并发代价模型完全不同</b>
 * （一个靠重试、一个靠等待），将来要加第三种（例如 Redis 预扣减）时
 * 只需要新增一个 Bean，不用改调用方。
 */
public interface StockDeductStrategy {

    /**
     * 单次扣减尝试。<b>不重试</b> —— 重试是调用方的策略（见 {@code PurchaseService}）。
     *
     * <p>【必须在事务内调用】悲观锁实现依赖调用方的事务边界，
     * 否则 {@code SELECT ... FOR UPDATE} 执行完就释放锁，等于没锁。
     * 本接口所有实现都标注 {@code @Transactional(REQUIRED)}：
     * 被事务方法调用时并入同一事务（不产生嵌套事务），独立调用时自建事务。
     */
    DeductResult tryDeduct(Integer productId, int quantity);

    /** 策略名，仅用于日志与验证 */
    String name();

    /** 扣减结果 —— 把「冲突」和「没库存」区分开，调用方才能决定要不要重试 */
    enum DeductResult {
        /** 扣减成功 */
        SUCCESS,
        /** 版本冲突：有人抢先改了同一行，值得重试 */
        CONFLICT,
        /** 库存不足：重试也没用，直接失败 */
        OUT_OF_STOCK
    }
}
