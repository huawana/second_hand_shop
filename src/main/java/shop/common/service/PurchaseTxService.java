package shop.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shop.admin.mapper.ProductMapper;
import shop.common.BizException;
import shop.common.ErrorCode;
import shop.common.service.stock.StockConflictException;
import shop.common.service.stock.StockDeductStrategy;

/**
 * 下单事务的「内层」：库存扣减 + 建订单 + 标记售出，三件事在一个事务里。
 *
 * <p>【为什么单独一个类】Spring 的 {@code @Transactional} 靠代理生效，
 * <b>同类内部方法互相调用不会走代理</b>（self-invocation），事务注解等于没写。
 * 而这里的「每次重试必须是独立事务」又要求调用必须穿过代理想 ——
 * 所以必须把它拆成独立 Bean，由 {@link PurchaseService} 通过代理调用。
 *
 * <p>如果把这三点留在 Controller 里，就会出现原项目那种情况：
 * 先 insert 订单、再标记售出，中间失败（或抛异常被吞）→ 订单存在但商品还在售，
 * 或者反过来。没有事务边界，这类「半完成状态」无法避免。
 */
@Service
public class PurchaseTxService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseTxService.class);

    private final StockDeductStrategy stockDeductStrategy;
    private final OrderService orderService;
    private final ProductMapper productMapper;

    public PurchaseTxService(StockDeductStrategy stockDeductStrategy,
                             OrderService orderService,
                             ProductMapper productMapper) {
        this.stockDeductStrategy = stockDeductStrategy;
        this.orderService = orderService;
        this.productMapper = productMapper;
    }

    /**
     * 在一个事务里完成购买。
     *
     * @throws StockConflictException 版本冲突，调用方可重试（会回滚本事务）
     * @throws BizException           库存不足，重试无用，直接失败
     */
    @Transactional(rollbackFor = Exception.class)
    public void doPurchase(Integer productId, int sellerId, int buyerId) {
        StockDeductStrategy.DeductResult result = stockDeductStrategy.tryDeduct(productId, 1);
        if (result == StockDeductStrategy.DeductResult.OUT_OF_STOCK) {
            // 用 409 而不是 BIZ_ERROR：这是「资源状态已变更」的预期内失败，不是服务端故障
            throw new BizException(ErrorCode.CONFLICT, "商品已被买走或库存不足");
        }
        if (result == StockDeductStrategy.DeductResult.CONFLICT) {
            // 抛出以回滚事务（同时释放可能持有的行锁），由外层换一个新事务重试
            throw new StockConflictException("库存版本冲突 productId=" + productId);
        }
        // 【Phase 2.5】建单交给 OrderService：写全字段（order_no / status / total_amount / pay_time）
        // 并同步写入订单明细快照（商品名与价格），不再只写 4 个字段 + 一个中文状态串
        orderService.createOrder(productId, sellerId, buyerId);
        productMapper.updateSellTimeById(productId);
        log.info("购买成功 productId={} buyerId={} 策略={}", productId, buyerId, stockDeductStrategy.name());
    }
}
