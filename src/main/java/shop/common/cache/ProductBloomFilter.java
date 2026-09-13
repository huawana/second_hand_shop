package shop.common.cache;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import shop.admin.mapper.ProductMapper;

import java.util.List;

/**
 * 商品 id 布隆过滤器（Phase 3.4 —— 缓存穿透的第二道防线）。
 *
 * <p>【它解决的问题】空值缓存能挡住「反复查同一个不存在的 id」，但挡不住
 * 「每次换一个不存在的 id 来查」的恶意遍历（{@code /shop/api/products/1}、{@code /2}、{@code /3}…
 * 每秒几万个）—— 每个新 id 都会写一条空值缓存，Redis 会被垃圾 key 撑爆，请求也照样打库。
 * 布隆过滤器在<b>查缓存之前</b>就回答「这个 id 一定不在库里 / 可能在库里」：
 * <ul>
 *   <li>「一定不在」→ 直接返回不存在，连 Redis 都不查；</li>
 *   <li>「可能在」→ 继续走正常的缓存 → 查库流程。</li>
 * </ul>
 *
 * <p>【为什么它能这么省】空间上它用位数组 + 多个散列函数，本项目的 3659 个商品 id
 * 只占几 KB（哪怕按 1 万预期量算也只要 12KB 左右），却能挡住绝大部分非法 id。
 * 代价是<b>存在误判：说「可能存在」时可能其实不存在（假阳性），但绝不说「不存在」时其实存在</b>
 * （无假阴性）。所以它只能用来做「提前否决」，不能用来做「确认存在」—— 这个单向性正是它能用在
 * 缓存前置判断上的原因。
 *
 * <p>【两个必须讲清楚的工程约束（本项目也受其限制）】
 * <ol>
 *   <li><b>删除无法反映</b>：布隆过滤器不能删元素（把位清零会影响别的元素）。商品被删除后
 *       过滤器里仍认为「可能存在」→ 只是多一次无效的查库，结果被空值缓存兜住，功能正确、代价可接受。</li>
 *   <li><b>新增必须主动告知</b>：任何「不经过本应用」写库的路径（手工 SQL、数据迁移脚本、别的系统）
 *       都会让过滤器产生<b>假阴性</b> —— 数据真的存在，过滤器却说没有，接口就会返回错误的 404。
 *       所以本项目做了两件事：① 所有应用内新增商品的路径都调用 {@link #add(long)}；
 *       ② 启动时全量重建（下面 {@link #run} 方法），把漂移修回来。这也是布隆过滤器只适合
 *       「写入路径可控」场景的原因。</li>
 * </ol>
 */
@Component
public class ProductBloomFilter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductBloomFilter.class);

    /** 预期元素个数：真实商品 3659，留足余量（阈值放大只会让位数组更大，不会影响正确性） */
    private static final long EXPECTED_INSERTIONS = 20_000L;

    /** 期望误判率 1%：在「省空间」与「少一次无效查库」之间的常用平衡点 */
    private static final double FALSE_PROBABILITY = 0.01D;

    private final ObjectProvider<RedissonClient> redissonProvider;
    private final ProductMapper productMapper;

    /** Redisson 不可用时保持 null，全部判定退化为「可能存在」（放行查库） */
    private volatile RBloomFilter<Long> filter;

    private final Object initLock = new Object();

    public ProductBloomFilter(ObjectProvider<RedissonClient> redissonProvider, ProductMapper productMapper) {
        this.redissonProvider = redissonProvider;
        this.productMapper = productMapper;
    }

    /**
     * 启动时全量装载。
     *
     * <p>为什么「每次启动都重建」而不是「只在第一次初始化时装载」：
     * 过滤器本身存在 Redis 里、能跨重启存活，但它的内容可能已经与数据库漂移
     * （迁移脚本导入的商品、被回滚的数据）。重复添加同一个 id 是幂等的（位已经是 1 了），
     * 所以每次启动重扫一遍的代价（一次 {@code select id} + N 次位设置）换来的是「启动后一定是对的」，
     * 这个买卖很划算。3660 个 id 的装载在本地实测是个位数毫秒级。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            RBloomFilter<Long> f = filter();
            if (f == null) {
                log.warn("布隆过滤器不可用（Redisson 未就绪），商品 id 预检降级为「全部放行到查库」");
                return;
            }
            List<Integer> ids = productMapper.getAllProductIds();
            long t0 = System.currentTimeMillis();
            for (Integer id : ids) {
                if (id != null) {
                    f.add(id.longValue());
                }
            }
            log.info("布隆过滤器装载完成：{} 个商品 id，耗时 {}ms，过滤器预估元素数 {}（位数组约 {}KB）",
                    ids.size(), System.currentTimeMillis() - t0, f.count(),
                    (long) (EXPECTED_INSERTIONS * 10 / 8 / 1024));
        } catch (Exception e) {
            // 不能让布隆过滤器把应用启动搞失败：它只是「省一次查库」的优化
            log.error("布隆过滤器装载失败，降级为全部放行到查库", e);
        }
    }

    /**
     * 判断某 id 是否可能存在。
     *
     * @return true = 可能存在（继续查缓存/库）；false = 一定不存在（可直接返回不存在）
     */
    public boolean mightContain(long id) {
        RBloomFilter<Long> f = filterOrNull();
        if (f == null) {
            // 降级：宁可多查一次库，也不能因为缓存组件故障把正常商品判成不存在
            return true;
        }
        try {
            return f.contains(id);
        } catch (Exception e) {
            log.warn("布隆过滤器查询失败，放行到查库 id={}", id, e);
            return true;
        }
    }

    /** 新增商品后必须调用，否则该商品会被判成「一定不存在」 */
    public void add(long id) {
        RBloomFilter<Long> f = filterOrNull();
        if (f == null) {
            return;
        }
        try {
            f.add(id);
        } catch (Exception e) {
            log.warn("布隆过滤器写入失败（下次重启全量重建会补上） id={}", id, e);
        }
    }

    /** 仅用于诊断接口：过滤器是否可用 */
    public boolean isAvailable() {
        return filterOrNull() != null;
    }

    // ------------------------------------------------------------------ 内部

    private RBloomFilter<Long> filterOrNull() {
        try {
            return filter();
        } catch (Exception e) {
            log.warn("布隆过滤器初始化失败（按不可用处理）", e);
            return null;
        }
    }

    /**
     * 惰性初始化（双重检查 + 同步块）。
     *
     * <p>为什么不放在构造器里：构造阶段 Redis 可能还没就绪，且初始化失败会连累 Spring 上下文启动；
     * 放在第一次使用时初始化，失败也只是本条路径降级。
     */
    private RBloomFilter<Long> filter() {
        RBloomFilter<Long> local = filter;
        if (local != null) {
            return local;
        }
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            return null;
        }
        synchronized (initLock) {
            if (filter == null) {
                RBloomFilter<Long> created = client.getBloomFilter(CacheKeys.BLOOM_PRODUCT);
                // tryInit 只在 key 不存在时生效（返回 true）；已存在时返回 false 且保留原参数 ——
                // 这一点很重要：不能让「两次启动的预期元素个数不一致」把已有过滤器重置掉
                created.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
                filter = created;
            }
            return filter;
        }
    }
}
