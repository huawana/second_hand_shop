package shop.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import shop.admin.Bean.Product;
import shop.admin.mapper.ProductMapper;

import java.time.Duration;

/**
 * 商品详情缓存（Phase 3.2 / 3.4 / 3.5 / 3.6 —— Cache-Aside 模式）。
 *
 * <p>【Cache-Aside 的读写顺序】本类只实现「读」这一侧，且顺序固定为：
 * <pre>
 *   查缓存 → 命中直接返回
 *          → 未命中：加互斥锁重建（3.5）→ 再查一次缓存（双重检查）→ 查库 → 回写缓存
 * </pre>
 * 为什么不缓存「库里没有」这件事的答案是：要缓存，但用短 TTL 的哨兵值（3.4 穿透防护，见下）。
 *
 * <p>【为什么选 Cache-Aside 而不是 Read/Write-Through】Cache-Aside 是「应用自己管缓存」：
 * 读时按需回填、写时主动删除。优点是缓存故障不影响写链路，缓存里也不会出现「写成功但缓存写失败」
 * 这种需要补偿的状态；缺点是每个读路径都要写一遍这段样板逻辑。本项目采用它，
 * 也是业界最常见的做法（写侧的删除策略见 {@link CacheInvalidator}）。
 *
 * <p>【本类承载的三个考点】
 * <ol>
 *   <li><b>穿透（3.4）</b>：不存在的 id 每次都会「未命中 → 查库 → 什么都不写」，
 *       缓存形同虚设。两道防线：先过布隆过滤器（{@link ProductBloomFilter}，
 *       一定不存在的 id 连 Redis 都不查）；过滤器的假阳性漏过来的，写空值哨兵占位（短 TTL，见 {@link CacheTtl#NULL_VALUE}）。</li>
 *   <li><b>击穿（3.5）</b>：某个热点 key 恰好过期，N 个并发请求同时未命中、同时查库。
 *       用 {@code SET NX} 抢一把短 TTL 的重建锁，抢到的查库回写，没抢到的等一会儿再读缓存。</li>
 *   <li><b>雪崩（3.6）</b>：TTL 加随机抖动，避免大批 key 同时过期，见 {@link CacheTtl#product()}。</li>
 * </ol>
 *
 * <p>【为什么不缓存「浏览量」这个字段（一个容易被忽略的设计取舍）】浏览量每次访问详情页都 +1，
 * 是典型的高频变更字段。如果把它纳入缓存并在每次浏览时删除缓存，缓存等于自毁：
 * 每个请求都会变成「删缓存 → 未命中 → 查库 → 回写」，收益归零。
 * 因此本类的取舍是：<b>缓存低频变更的商品信息，浏览量走数据库原子自增</b>
 * （见 {@code ProductMapper.increaseViewCount}），展示时用「缓存值 + 1」做近似。
 * 库里的是精确值，页面上允许偏小。要绝对精确的做法是把浏览量改成 Redis 计数器（{@code INCR}）
 * + 定时回写数据库 —— Phase 4 的定时任务正好是它的落点，本项目先不做，但这是能讲清楚的一条路。
 */
@Service
public class ProductCacheService {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheService.class);

    private final ProductMapper productMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheStats stats;
    private final ProductBloomFilter bloomFilter;

    /** 缓存总开关：置 false 时全部直查库（用于对照实验与线上紧急降级） */
    private final boolean cacheEnabled;

    /** 未抢到重建锁时，等待并重试读缓存的最长时间（毫秒） */
    private final long rebuildWaitMillis;

    public ProductCacheService(ProductMapper productMapper,
                               RedisTemplate<String, Object> redisTemplate,
                               CacheStats stats,
                               ProductBloomFilter bloomFilter,
                               @Value("${shop.cache.enabled:true}") boolean cacheEnabled,
                               @Value("${shop.cache.rebuild-wait-millis:400}") long rebuildWaitMillis) {
        this.productMapper = productMapper;
        this.redisTemplate = redisTemplate;
        this.stats = stats;
        this.bloomFilter = bloomFilter;
        this.cacheEnabled = cacheEnabled;
        this.rebuildWaitMillis = Math.max(0, rebuildWaitMillis);
    }

    /**
     * 按 id 读取商品（带缓存）。
     *
     * @return 商品；不存在时返回 {@code null}（调用方据此返回 404，而不是抛 NPE）
     */
    public Product getById(int id) {
        if (!cacheEnabled) {
            return loadFromDb(id);
        }

        // ---- 第一道防线：布隆过滤器。一定不存在 → 不碰 Redis、不碰数据库 ----
        if (!bloomFilter.mightContain(id)) {
            stats.recordNegativeHit(0L);
            log.debug("布隆过滤器判定商品不存在 id={}", id);
            return null;
        }

        String key = CacheKeys.product(id);

        // ---- 第二步：查缓存 ----
        // 【统计口径】一次请求只记一种结果（命中 / 空值命中 / 未命中）。
        // 不能「先记一次未命中、等重建完再记一次命中」——那样一个请求会同时贡献分子和分母，
        // 把命中率算成一半（20 个请求 19 个从缓存拿到数据，报表上却显示 48%）。
        // 所以这里先不记账，等最终结果是「拿到值」还是「真去查了库」再记。
        long t0 = System.nanoTime();
        Object cached = getQuietly(key);
        long readNanos = System.nanoTime() - t0;
        if (cached != null) {
            if (CacheKeys.NULL_VALUE.equals(cached)) {
                stats.recordNegativeHit(readNanos);
                return null;
            }
            stats.recordHit(readNanos);
            return unwrap(cached);
        }

        // ---- 第三步：未命中 → 互斥锁重建（击穿防护） ----
        return rebuild(id, key, readNanos);
    }

    /** 直查数据库（不做缓存）。供对照实验 / 缓存降级复用 */
    public Product loadFromDb(int id) {
        long t0 = System.nanoTime();
        try {
            return productMapper.getProductById(id);
        } finally {
            stats.recordDbLoad(System.nanoTime() - t0);
        }
    }

    /** 统计快照（诊断接口用，见 {@code ShopProductApiController}） */
    public CacheStats.Snapshot stats() {
        return stats.snapshot();
    }

    /**
     * 删除商品缓存（写链路的「删缓存」动作，Phase 3.7）。
     *
     * <p>返回值刻意是 void 且吞掉异常：删缓存失败绝不能影响「商品已经改好了」这个事实，
     * 最坏的结果是缓存里多留了一会儿旧值，等 TTL 到期自愈。
     */
    public void evict(int id) {
        try {
            redisTemplate.delete(CacheKeys.product(id));
        } catch (Exception e) {
            log.warn("删除商品缓存失败（等 TTL 自愈） id={}", id, e);
        }
    }

    /** 回写缓存。商品不存在时写空值哨兵（防穿透）；存在时写专用 VO（见 {@link ProductCacheVO}） */
    public void put(int id, Product product) {
        try {
            String key = CacheKeys.product(id);
            if (product == null) {
                redisTemplate.opsForValue().set(key, CacheKeys.NULL_VALUE, CacheTtl.NULL_VALUE);
                return;
            }
            redisTemplate.opsForValue().set(key, ProductCacheVO.from(product), CacheTtl.product());
        } catch (Exception e) {
            // 回写失败只影响命中率，不影响正确性（下次请求继续查库）
            log.warn("回写商品缓存失败 id={}", id, e);
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 缓存重建：抢到锁的查库回写，没抢到的等一等再读缓存。
     *
     * <p>【为什么还需要双重检查】抢锁之前已经查过一次缓存（未命中）。等锁 / 拿锁的这段时间里，
     * 别的线程可能已经把值写进去了。如果不再查一次，就会出现「排队进来的 10 个线程
     * 依次拿到锁、依次查一遍库」—— 锁退化成了串行排队，等于没解决问题。
     * 拿到锁后再查一次缓存，才能让第 2 个及之后的线程直接命中并立刻返回。
     *
     * <p>【为什么抢不到锁的线程最终还是会查库】等待是有上限的（{@code shop.cache.rebuild-wait-millis}）：
     * 击穿防护的目标是「把 N 次并发查库压成 1 次」，不是「保证任何一个请求都不查库」。
     * 让最后一个线程在超时后降级查库，好过让用户为了一个缓存而阻塞几秒 ——
     * 可用性优先于「绝对不查库」这个漂亮的指标。
     */
    private Product rebuild(int id, String key, long firstReadNanos) {
        String lockKey = CacheKeys.productRebuildLock(id);
        boolean locked = acquireLock(lockKey);

        if (locked) {
            try {
                stats.recordMiss(firstReadNanos);
                return loadAndCache(id, key);
            } finally {
                releaseLock(lockKey);
            }
        }

        // 没抢到锁：说明有人正在重建，等它写回缓存
        long deadline = System.currentTimeMillis() + rebuildWaitMillis;
        long sleepMillis = 20L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long t0 = System.nanoTime();
            Object cached = getQuietly(key);
            if (cached != null) {
                stats.recordHit(System.nanoTime() - t0);
                log.debug("等待重建锁期间命中缓存（击穿防护生效） id={}", id);
                return unwrap(cached);
            }
            sleepMillis = Math.min(sleepMillis * 2, 80L);
        }

        log.warn("等待缓存重建超时，降级为直接查库 id={} 等待上限={}ms", id, rebuildWaitMillis);
        stats.recordMiss(firstReadNanos);
        Product product = loadFromDb(id);
        put(id, product);
        return product;
    }

    /** 真正让数据库说话的那一步 */
    private Product loadAndCache(int id, String key) {
        Object again = getQuietly(key);
        if (again != null) {
            // 等锁期间已被别的线程写好了（双重检查）
            return unwrap(again);
        }
        Product product = loadFromDb(id);
        put(id, product);
        if (log.isDebugEnabled()) {
            log.debug("缓存重建完成 id={} 是否存在={}", id, product != null);
        }
        return product;
    }

    /**
     * 缓存值 → 实体。
     *
     * <p>三种输入都要处理，缺一不可：
     * <ul>
     *   <li>{@link ProductCacheVO}：正常命中；</li>
     *   <li>空值哨兵：已知不存在 → null（调用方据此走 404 / 回首页）；</li>
     *   <li>其它（历史遗留的旧格式缓存、人工改过的脏数据）：记 WARN 后按未命中处理。
     *       <b>不要在这里抛异常</b> —— 一条脏缓存不该把接口打成 500，删掉它下次就自愈了。</li>
     * </ul>
     */
    private Product unwrap(Object cached) {
        if (cached instanceof ProductCacheVO vo) {
            return vo.toProduct();
        }
        if (!CacheKeys.NULL_VALUE.equals(cached)) {
            log.warn("缓存值类型不认识（按未命中处理，下次请求会覆盖） type={}",
                    cached == null ? "null" : cached.getClass().getName());
        }
        return null;
    }

    /**
     * 抢重建锁：{@code SET key value NX PX ttl}。
     *
     * <p>必须「SET NX + 过期时间」一次完成（Redis 的 {@code setIfAbsent(key, value, ttl)} 就是
     * 这一条命令的封装）。如果拆成 {@code SETNX} 再 {@code EXPIRE}，两条命令之间进程挂掉，
     * 锁就永远不会过期 —— 这是手写分布式锁最经典的坑之一。
     *
     * <p>抢锁失败 / Redis 异常时一律返回「没抢到」，让调用方走等待降级，不会把请求卡死。
     */
    private boolean acquireLock(String lockKey) {
        try {
            Boolean ok = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", CacheTtl.REBUILD_LOCK);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("缓存重建锁获取异常（按未抢到处理） key={}", lockKey, e);
            return false;
        }
    }

    /**
     * 释放重建锁。
     *
     * <p>【诚实的说明】这里没有校验「锁是不是自己加的」就删了 —— 严格意义上存在一个极小的窗口：
     * 若本次重建耗时超过锁的 3 秒 TTL，锁已自动过期并被别的线程重新抢到，此时这个 delete
     * 会把别人的锁删掉（连锁失效）。
     * 正确做法是用 Lua 脚本做「值比对 + 删除」的原子操作，或用 Redisson 的锁
     * （{@link DistributedLock} 走的就是后者）。
     * 本项目在这里选择简化，理由：这个锁只保护「一次数据库查询」的重建，
     * 最坏后果是多个线程各查一次库（性能问题，不是正确性问题）；
     * 而真正的业务互斥（下单）用的是 Redisson 锁，不允许这种妥协。
     * 能说清「这里为什么可以妥协、哪里不能妥协」，比到处套同一个锁更有价值。
     */
    private void releaseLock(String lockKey) {
        try {
            redisTemplate.delete(lockKey);
        } catch (Exception e) {
            log.warn("释放缓存重建锁失败（等 TTL 自动过期） key={}", lockKey, e);
        }
    }

    /** 读缓存并吞掉 Redis 异常（缓存读失败就当作未命中，绝不让缓存故障打断业务） */
    private Object getQuietly(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("读缓存失败（按未命中处理） key={}", key, e);
            return null;
        }
    }
}
