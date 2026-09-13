package shop.common.service.stock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shop.admin.Bean.ProductStock;
import shop.admin.mapper.ProductStockMapper;

/**
 * 悲观锁扣减（{@code shop.stock.strategy=pessimistic} 时启用）。
 *
 * <p>用 {@code SELECT ... FOR UPDATE} 先锁住这一行，拿到锁之后再判断库存、再扣减：
 * 同一时刻只有一个事务能持有这把行锁，所以「读—判断—写」之间不会有人插进来，
 * <b>一次成功，不存在重试</b>。
 *
 * <p>代价是<b>排队等待</b>：持有锁的时间 = 从 FOR UPDATE 到事务提交之间的全部耗时
 * （包括后面写订单的时间）。高并发下这里会成为瓶颈，并且等待超时（默认 50s）会抛
 * {@code LockWaitTimeoutException}。所以悲观锁适合冲突概率高、且临界区极短的场景。
 *
 * <p>【两个必须注意的点】
 * <ol>
 *   <li>必须在事务内 —— 否则语句一执行完锁就释放了；</li>
 *   <li>where 条件必须能走索引（这里是主键 id）—— 否则 InnoDB 会退化成锁更多行甚至锁表。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "shop.stock.strategy", havingValue = "pessimistic")
public class PessimisticStockDeductStrategy implements StockDeductStrategy {

    private final ProductStockMapper stockMapper;

    public PessimisticStockDeductStrategy(ProductStockMapper stockMapper) {
        this.stockMapper = stockMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public DeductResult tryDeduct(Integer productId, int quantity) {
        // 加行锁；其他事务对本行的 for update / update 都会在此阻塞等待
        ProductStock locked = stockMapper.selectByIdForUpdate(productId);
        if (locked == null || locked.getStock() == null || locked.getStock() < quantity) {
            return DeductResult.OUT_OF_STOCK;
        }

        ProductStock update = new ProductStock();
        update.setId(productId);
        update.setStock(locked.getStock() - quantity);
        update.setVersion(locked.getVersion());
        update.setStatus(locked.getStock() - quantity == 0 ? "SOLD" : "ON_SALE");

        // 行已被锁住，理论上不会冲突；仍按影响行数判断，出错就交给上层当作可重试处理
        return stockMapper.updateById(update) == 1 ? DeductResult.SUCCESS : DeductResult.CONFLICT;
    }

    @Override
    public String name() {
        return "pessimistic";
    }
}
