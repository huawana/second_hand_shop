package shop.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import shop.common.BizException;
import shop.common.ErrorCode;
import shop.common.cache.CacheKeys;
import shop.common.cache.DistributedLock;
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

    /** 【Phase 3.8】同一用户重复下单的互斥（Redisson 锁，见 DistributedLock） */
    private final DistributedLock distributedLock;

    /** 最大重试次数。按「冲突概率」配置：秒杀场景冲突多，盲目调大反而压垮数据库 */
    private final int maxRetry;

    public PurchaseService(PurchaseTxService purchaseTxService,
                           DistributedLock distributedLock,
                           @Value("${shop.stock.max-retry:3}") int maxRetry) {
        this.purchaseTxService = purchaseTxService;
        this.distributedLock = distributedLock;
        this.maxRetry = Math.max(1, maxRetry);
    }

    /**
     * 购买一件商品（库存 1 的二手商品）。
     *
     * <p>【Phase 3.8】先过分布式锁，再进事务。
     * 锁的 key 是「用户 + 商品」，等待时间 0（<b>不排队</b>），租期交给看门狗自动续期：
     * <ul>
     *   <li><b>等待时间 0</b>：用户连点两下时，第二个请求应该立刻被挡回去（409「请勿重复提交」），
     *       而不是排队等一会儿再执行 —— 排队的后果是两个请求都成功，正好制造出重复订单；</li>
     *   <li><b>租期 -1（看门狗）</b>：这次购买里包含「重试 + 建单 + 标记售出」，
     *       耗时不可预测。写死 5 秒的租期，一旦业务超过 5 秒锁就自动过期，
     *       第二个请求会趁虚而入 —— 这就是为什么 Redisson 要提供续期机制。</li>
     *   <li><b>锁必须在事务外面</b>：如果把锁加在 {@code doPurchase} 的事务内部，
     *       锁的释放可能早于事务提交（这正是「锁没锁住」的经典写法）。这里的顺序是
     *       拿锁 → 事务开始 → 提交 → 释放锁，中间隔着完整的重试循环。</li>
     * </ul>
     *
     * <p>注意这层锁<b>不是</b>防超卖的手段：超卖由库存的条件更新（Phase 2.4）保证。
     * 它挡的是「同一个人的重复请求」，所以粒度是用户 + 商品，不同用户之间互不影响。
     *
     * @return 实际参与重试的轮数（1 表示一次成功），供日志与验证使用
     */
    public int purchase(Integer productId, int sellerId, int buyerId) {
        String lockKey = CacheKeys.userOrderLock(buyerId, productId);
        return distributedLock.runWithLock(lockKey, 0L, -1L,
                () -> doPurchaseWithRetry(productId, sellerId, buyerId));
    }

    private int doPurchaseWithRetry(Integer productId, int sellerId, int buyerId) {
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
