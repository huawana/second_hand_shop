package shop.common.service.stock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shop.admin.Bean.ProductStock;
import shop.admin.mapper.ProductStockMapper;

/**
 * 乐观锁扣减（默认策略）。
 *
 * <p>流程就是经典的「读版本 → 带版本条件更新 → 看影响行数」：
 * <pre>
 * select stock, version from lxy_product where id = ?
 * update lxy_product set stock = stock - 1, version = version + 1
 *        where id = ? and version = ?      ← 版本条件由 MyBatis-Plus 乐观锁插件自动追加
 * </pre>
 * 影响行数为 0 说明这一行在「读」与「写」之间被别人改过 —— 本次尝试作废，交由上层重试。
 *
 * <p>【为什么不用「先查库存再判断」】那是两次独立读，中间有窗口：
 * 两个人同时读到「还有 1 件」，然后各自认为自己能买 —— 这就是超卖。
 * 乐观锁把「检查」和「扣减」压进同一条 UPDATE 的 WHERE 里，由数据库保证原子性。
 *
 * <p>【乐观锁的适用前提】冲突概率低。它不阻塞任何请求，冲突时用重试换一致性；
 * 如果冲突概率高（比如秒杀），重试会放大数据库压力，那时悲观锁或 Redis 预扣减更合适。
 */
@Component
@ConditionalOnProperty(name = "shop.stock.strategy", havingValue = "optimistic", matchIfMissing = true)
public class OptimisticStockDeductStrategy implements StockDeductStrategy {

    private static final Logger log = LoggerFactory.getLogger(OptimisticStockDeductStrategy.class);

    private final ProductStockMapper stockMapper;

    public OptimisticStockDeductStrategy(ProductStockMapper stockMapper) {
        this.stockMapper = stockMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public DeductResult tryDeduct(Integer productId, int quantity) {
        ProductStock current = stockMapper.selectOne(new LambdaQueryWrapper<ProductStock>()
                .select(ProductStock::getId, ProductStock::getStock, ProductStock::getVersion)
                .eq(ProductStock::getId, productId)
                .last("limit 1"));
        if (current == null) {
            return DeductResult.OUT_OF_STOCK;
        }
        if (current.getStock() == null || current.getStock() < quantity) {
            return DeductResult.OUT_OF_STOCK;
        }

        ProductStock update = new ProductStock();
        update.setId(productId);
        update.setStock(current.getStock() - quantity);
        update.setVersion(current.getVersion());
        // 库存扣到 0 时顺手把状态改为已售出，避免出现「stock=0 但 status 还是 ON_SALE」的脏数据
        update.setStatus(current.getStock() - quantity == 0 ? "SOLD" : "ON_SALE");

        int rows = stockMapper.updateById(update);
        if (rows == 1) {
            return DeductResult.SUCCESS;
        }
        log.debug("乐观锁冲突 productId={} 读到的版本={}", productId, current.getVersion());
        return DeductResult.CONFLICT;
    }

    @Override
    public String name() {
        return "optimistic";
    }
}
