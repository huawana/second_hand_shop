package shop.common.cache;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import shop.common.BizException;
import shop.common.ErrorCode;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁（Phase 3.8）—— 用 Redisson 的 {@link RLock} 封装「同一用户重复下单」。
 *
 * <p>【要解决的场景】用户在「确认购买」上连点两下 / 浏览器重发请求 / 网络重试，
 * 同一个用户对同一件商品会同时进来两个写请求。库存只有 1，
 * 数据库的<b>条件更新</b>（Phase 2.4）能保证不会超卖 —— 那这里还需要锁吗？需要，因为两者管的事不同：
 * <ul>
 *   <li>条件更新保证的是「不会卖出两件」（数据正确性）；</li>
 *   <li>分布式锁保证的是「同一个用户的重复请求不会都走到写链路里」——
 *       否则第二个请求会一路跑到扣减失败，返回一个「商品已被买走」的错误，
 *       而这个错误其实是<b>同一个人自己</b>造成的，用户看到的是莫名其妙的失败；
 *       更糟的是如果这条链路上还有别的副作用（发消息、扣优惠券），就会出现重复执行。</li>
 * </ul>
 * 所以锁的粒度是「用户 + 商品」而不是「商品」：不同用户之间不需要排队（那是数据库该干的活），
 * 只有同一个人的重复提交才需要被挡回去。
 *
 * <p>【为什么用 Redisson 而不是自己写 SETNX】手写锁有四个经典坑，Redisson 都替你处理了：
 * <ol>
 *   <li><b>加锁与设过期不是原子的</b> → 用 Lua 脚本一次完成；</li>
 *   <li><b>业务没跑完锁就过期</b> → <b>看门狗（watchdog）自动续期</b>：不指定 leaseTime 时，
 *       Redisson 后台线程每隔 {@code lockWatchdogTimeout/3} 把锁续到 30 秒，
 *       业务跑多久锁就活多久（本项目可通过 {@code shop.redis.lock-watchdog-millis} 调）；</li>
 *   <li><b>误删别人的锁</b> → 解锁时比对「锁的持有者标识」（UUID + 线程 id），不是自己加的就不删；</li>
 *   <li><b>不可重入</b> → 用 Hash 记录重入次数，同一个线程可以重复加锁。</li>
 * </ol>
 *
 * <p>【降级】Redis 不可用时 {@code RedissonClient} 为 null（见 {@code RedissonConfig}），
 * 此时<b>直接执行业务逻辑</b>并记 WARN：重复提交的窗口重新打开，但数据库的条件更新仍在兜底，
 * 不会产生脏数据。这个取舍比「Redis 一挂就谁都下不了单」合理得多。
 *
 * <p>【使用约束】被保护的 {@code action} 必须能容忍「抛异常」，锁一定会在 finally 里释放；
 * 另外<b>不要把数据库事务开在锁外面又跨到锁里面</b>，正确顺序是：
 * 先拿锁 → 开事务 → 提交 → 释放锁（本项目由 {@code PurchaseService} 在事务之外调用本类保证这一点）。
 */
@Component
public class DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

    private final ObjectProvider<RedissonClient> redissonProvider;

    public DistributedLock(ObjectProvider<RedissonClient> redissonProvider) {
        this.redissonProvider = redissonProvider;
    }

    /**
     * 在分布式锁保护下执行。
     *
     * @param key        锁 key（见 {@link CacheKeys}）
     * @param waitMillis 最长等待多久去抢锁；<b>0 表示不排队、抢不到立刻失败</b>（重复提交场景要的就是这个语义）
     * @param leaseMillis 锁持有时间；<b>≤0 表示交给看门狗自动续期</b>（推荐，避免业务没跑完锁先过期）
     * @throws BizException 锁被占用且不排队 / 等待超时 —— 映射为 409（冲突），不是 500
     */
    public <T> T runWithLock(String key, long waitMillis, long leaseMillis, Supplier<T> action) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            log.warn("Redisson 不可用，跳过分布式锁直接执行（数据库条件更新仍在兜底） key={}", key);
            return action.get();
        }

        RLock lock = client.getLock(key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 恢复中断标记：吞掉 InterruptedException 会让线程池无法正常停机
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.BIZ_ERROR, "请求被中断，请重试");
        } catch (Exception e) {
            // Redis 抖动导致加锁本身失败：与「没抢到锁」区分开，但仍选择放行（见类注释的降级说明）
            log.warn("加锁异常，降级为直接执行 key={}", key, e);
            return action.get();
        }

        if (!acquired) {
            log.info("重复提交被锁挡住 key={} 等待上限={}ms", key, waitMillis);
            throw new BizException(ErrorCode.CONFLICT, "操作正在处理中，请勿重复提交");
        }

        try {
            return action.get();
        } finally {
            // isHeldByCurrentThread 必须先判断：锁已因 TTL 过期而释放时，unlock 会抛 IllegalMonitorStateException
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
