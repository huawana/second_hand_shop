package shop.common.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存运行时统计（Phase 3.9）。
 *
 * <p>【为什么要有这个类】简历上写「命中率 93%」这类数字，必须来自真实采集，
 * 而不是自己估的。{@code INFO stats} 里的 keyspace_hits/keyspace_misses 是 Redis 全实例视角的
 * （本机 Redis 还会被别的项目共用，见 db 3 的隔离说明），在验证脚本里用它算整机命中率没问题，
 * 但要回答「商品详情这个接口的命中率与耗时」就需要应用自己的视角 —— 这就是本类。
 *
 * <p>三个状态分开计数，别混成一个「命中/未命中」：
 * <ul>
 *   <li><b>hit</b>（命中）：缓存里有真实商品，直接返回；</li>
 *   <li><b>negativeHit</b>（空值命中）：缓存里是空值哨兵，说明这个 id 已知不存在 ——
 *       它<b>也算命中</b>（成功挡住了数据库），如果把它算成未命中，加了穿透防护之后命中率数字
 *       反而会变得更难看，与事实相反；</li>
 *   <li><b>miss</b>（未命中）：缓存里什么都没有，必须查库并回写。</li>
 * </ul>
 *
 * <p>耗时用 {@link LongAdder} 累计纳秒（比 AtomicLong 在高并发下更省：分段累加，减少 CAS 冲突），
 * 快照时再算平均。统计只在诊断接口里读，不影响主链路。
 */
@Component
public class CacheStats {

    private final LongAdder hits = new LongAdder();
    private final LongAdder negativeHits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder dbLoads = new LongAdder();

    /** 缓存读（Redis GET）累计耗时 */
    private final LongAdder cacheNanos = new LongAdder();

    /** 数据库读（含互斥锁重建）累计耗时 */
    private final LongAdder dbNanos = new LongAdder();

    /** 命中缓存（真实值） */
    public void recordHit(long nanos) {
        hits.increment();
        cacheNanos.add(nanos);
    }

    /** 命中空值哨兵（穿透防护生效） */
    public void recordNegativeHit(long nanos) {
        negativeHits.increment();
        cacheNanos.add(nanos);
    }

    /** 缓存未命中 */
    public void recordMiss(long nanos) {
        misses.increment();
        cacheNanos.add(nanos);
    }

    /** 记录一次真正的数据库读取（用于验证「击穿防护把 N 次并发降成 1 次查库」） */
    public void recordDbLoad(long nanos) {
        dbLoads.increment();
        dbNanos.add(nanos);
    }

    /** 清空统计（验证脚本里让每段测量从 0 开始） */
    public void reset() {
        hits.reset();
        negativeHits.reset();
        misses.reset();
        dbLoads.reset();
        cacheNanos.reset();
        dbNanos.reset();
    }

    public Snapshot snapshot() {
        long h = hits.sum();
        long n = negativeHits.sum();
        long m = misses.sum();
        long total = h + n + m;
        long cacheCalls = total;
        long db = dbLoads.sum();
        long cacheNanosSum = cacheNanos.sum();
        long dbNanosSum = dbNanos.sum();

        return new Snapshot(
                h, n, m, total, db,
                cacheNanosSum, dbNanosSum,
                // 命中率按「缓存挡住的请求 / 总请求」算：空值命中同样挡住了库
                total == 0 ? 0.0 : round1((h + n) * 100.0 / total),
                avgMicros(cacheNanosSum, cacheCalls),
                avgMicros(dbNanosSum, db));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double avgMicros(long nanos, long count) {
        return count == 0 ? 0.0 : round1(nanos / 1000.0 / count);
    }

    /**
     * 统计快照（不可变）。
     *
     * <p>除了平均数，还额外给出<b>累计纳秒</b>：验证脚本要算「某一段操作」的平均耗时，
     * 做法是前后各取一次快照再作差 —— 只给平均数的话没法做差（两个平均数相减没有意义），
     * 而专门加一个「重置统计」的接口又会引入一个会被误用的写接口。
     *
     * @param hits              命中真实值次数
     * @param negativeHits      命中空值哨兵次数
     * @param misses            未命中次数
     * @param total             缓存查询总次数
     * @param dbLoads           真正落到数据库的读取次数
     * @param cacheNanosTotal   缓存读累计耗时（纳秒）
     * @param dbNanosTotal      查库累计耗时（纳秒）
     * @param hitRatePercent    命中率（%），保留 1 位小数
     * @param avgCacheMicros    缓存读平均耗时（微秒）
     * @param avgDbLoadMicros   查库平均耗时（微秒）
     */
    public record Snapshot(long hits, long negativeHits, long misses, long total, long dbLoads,
                           long cacheNanosTotal, long dbNanosTotal,
                           double hitRatePercent, double avgCacheMicros, double avgDbLoadMicros) {

        /** 平均耗时（微秒），供验证脚本对「快照差值」计算 */
        public static double avgMicrosOfDelta(long nanosDelta, long countDelta) {
            return countDelta <= 0 ? 0.0 : round1(nanosDelta / 1000.0 / countDelta);
        }
    }
}
