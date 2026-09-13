package shop.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import shop.common.BizException;
import shop.common.ErrorCode;
import shop.common.service.stock.StockConflictException;

/**
 * 下单入口：负责<b>重试策略</b>，事务边界交给 {@link PurchaseTxService}。
 *
 * <p>【为什么重试要放在事务外面（这是本阶段最值得讲的一点）】
 * MySQL 默认隔离级别是 REPEATABLE READ：事务内第一次快照读之后，
 * 后续的普通 SELECT 都复用同一份快照。于是如果「读版本 → 条件更新失败 → 再读版本重试」
 * 全发生在同一个事务里，第二次读到的<b>还是旧版本号</b>，条件更新必然再次失败 ——
 * 重试毫无意义，纯属空转。
 * 所以每次重试都必须是<b>一个新事务</b>（拿到新快照）。
 * 本类不加 {@code @Transactional}，每轮通过代理调用 {@code PurchaseTxService.doPurchase}
 * 各自开启事务，就是为了这个。
 *
 * <p>（顺带解释：为什么 UPDATE 能看见最新数据而 SELECT 不能？因为 UPDATE 是「当前读」，
 * 会读最新已提交版本并加锁；普通 SELECT 是「快照读」。这一点面试常被追问。）
 */
@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    private final PurchaseTxService purchaseTxService;

    /** 最大重试次数。按「冲突概率」配置：秒杀场景冲突多，盲目调大反而压垮数据库 */
    private final int maxRetry;

    public PurchaseService(PurchaseTxService purchaseTxService,
                           @Value("${shop.stock.max-retry:3}") int maxRetry) {
        this.purchaseTxService = purchaseTxService;
        this.maxRetry = Math.max(1, maxRetry);
    }

    /**
     * 购买一件商品（库存 1 的二手商品）。
     *
     * @return 实际参与重试的轮数（1 表示一次成功），供日志与验证使用
     */
    public int purchase(Integer productId, int sellerId, int buyerId) {
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                purchaseTxService.doPurchase(productId, sellerId, buyerId);
                if (attempt > 1) {
                    log.info("乐观锁重试成功 productId={} 第 {} 次尝试", productId, attempt);
                }
                return attempt;
            } catch (StockConflictException e) {
                log.debug("第 {}/{} 次尝试遇到版本冲突 productId={}", attempt, maxRetry, productId);
            }
        }
        // 重试耗尽：说明竞争太激烈，如实告诉用户失败，而不是继续等待
        log.warn("库存扣减重试耗尽 productId={} 次数={}", productId, maxRetry);
        throw new BizException(ErrorCode.CONFLICT, "下单失败，请重试");
    }
}
