package shop.common.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存过期时间策略（Phase 3.6 —— 缓存雪崩防护）。
 *
 * <p>【雪崩是什么】大量 key 在同一时刻集中过期，这一瞬间所有请求同时穿透到数据库。
 * 最典型的成因恰恰是「按经验统一设 30 分钟」：如果这些 key 是在同一时刻（比如服务启动后
 * 第一波流量、或某次缓存预热）写入的，它们的过期时间就会完全对齐，过期也会完全对齐。
 * 原本被缓存挡住的峰值 QPS 会在那一秒全部砸到 MySQL 上。
 *
 * <p>【本项目的做法】TTL = 基础值 + [0, 抖动秒数] 的随机值。让每个 key 的过期时刻散开，
 * 过期就从「同一瞬间的脉冲」变成「持续的小水流」。
 *
 * <p>为什么不写成「基础值 ± 百分比」：两者都对。这里用<b>只加不减</b>的形式，
 * 是为了让「最短存活时间」可预测（{@code 30min ~ 35min} 的下界固定是 30 分钟），
 * 排查「为什么缓存这么快就失效」时不用再算概率分布。
 *
 * <p>另一个常见做法（本项目没用，但要知道）：热点数据干脆不设 TTL，靠容量淘汰（LRU）来兜底 ——
 * 这要求 Redis 配了 {@code maxmemory-policy}，否则会把内存写满。见 application.properties 的说明。
 */
public final class CacheTtl {

    private CacheTtl() {
    }

    /** 商品详情缓存基础 TTL */
    public static final Duration PRODUCT_BASE = Duration.ofMinutes(30);

    /** 商品详情缓存 TTL 抖动范围（秒）：真实 TTL ∈ [30min, 35min] */
    public static final int PRODUCT_JITTER_SECONDS = 300;

    /** 分类列表（读多写少、几乎不变）基础 TTL */
    public static final Duration CATEGORY_BASE = Duration.ofHours(2);

    /** 分类列表 TTL 抖动范围（秒）：真实 TTL ∈ [2h, 2h10min] */
    public static final int CATEGORY_JITTER_SECONDS = 600;

    /**
     * 空值（不存在的 id）缓存 TTL —— 穿透防护用短 TTL。
     *
     * <p>为什么必须短：空值缓存的作用是「别让同一个不存在的 id 反复打库」，
     * 但如果缓存太久，而该 id 恰好在此后变成了真实存在的记录（例如商品被创建、id 被复用），
     * 就会出现「数据已经存在、接口却说没有」的假 404。60 秒是在这两者之间的平衡点。
     */
    public static final Duration NULL_VALUE = Duration.ofSeconds(60);

    /**
     * 缓存重建互斥锁的 TTL —— 击穿防护用，必须「短」。
     *
     * <p>短是为了<b>自动兜住死锁</b>：拿到锁的线程如果进程崩了 / 被 kill，锁不会被显式释放，
     * 靠 TTL 到期自动淘汰，其它线程才能继续重建缓存。
     * 但也不能太短：比一次数据库重建耗时还短，锁会提前失效，等于没锁。
     * 3 秒对本项目「主键 JOIN 查一次商品」来说足够宽裕。
     */
    public static final Duration REBUILD_LOCK = Duration.ofSeconds(3);

    /** 分类列表真实 TTL（带抖动） */
    public static Duration categoryEnabled() {
        return withJitter(CATEGORY_BASE, CATEGORY_JITTER_SECONDS);
    }

    /** 商品详情真实 TTL（带抖动） */
    public static Duration product() {
        return withJitter(PRODUCT_BASE, PRODUCT_JITTER_SECONDS);
    }

    /**
     * 基础 TTL + [0, jitterSeconds] 的随机秒数。
     *
     * @param jitterSeconds ≤ 0 时不做抖动（用于需要精确 TTL 的场景，例如测试）
     */
    public static Duration withJitter(Duration base, int jitterSeconds) {
        if (jitterSeconds <= 0) {
            return base;
        }
        return base.plusSeconds(ThreadLocalRandom.current().nextInt(jitterSeconds + 1));
    }
}
