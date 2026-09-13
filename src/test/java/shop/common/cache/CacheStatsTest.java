package shop.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存统计测试（Phase 3.9）。
 *
 * <p>重点在<b>命中率的定义</b>：空值命中（穿透防护拦住的不存在 id）必须算进命中率分子。
 * 如果算错，会出现很荒谬的现象 —— 加了防护之后因为挡住的请求被记为「未命中」，
 * 报表上的命中率反而下降，于是有人把防护删掉「优化」指标。
 */
class CacheStatsTest {

    @Test
    @DisplayName("空计数时命中率是 0（不抛除零异常）")
    void emptySnapshot() {
        CacheStats.Snapshot s = new CacheStats().snapshot();
        assertThat(s.total()).isZero();
        assertThat(s.hitRatePercent()).isZero();
        assertThat(s.avgCacheMicros()).isZero();
        assertThat(s.avgDbLoadMicros()).isZero();
    }

    @Test
    @DisplayName("命中率 = (真实命中 + 空值命中) / 总次数")
    void hitRateCountsNegativeHits() {
        CacheStats stats = new CacheStats();
        stats.recordHit(1_000);
        stats.recordHit(1_000);
        stats.recordNegativeHit(1_000);
        stats.recordMiss(1_000);

        CacheStats.Snapshot s = stats.snapshot();
        assertThat(s.hits()).isEqualTo(2);
        assertThat(s.negativeHits()).isEqualTo(1);
        assertThat(s.misses()).isEqualTo(1);
        assertThat(s.total()).isEqualTo(4);
        // 3/4 = 75%
        assertThat(s.hitRatePercent()).isEqualTo(75.0);
    }

    @Test
    @DisplayName("平均耗时按各自次数分别算；查库次数单独统计（验证击穿防护用）")
    void averagesArePerBucket() {
        CacheStats stats = new CacheStats();
        stats.recordHit(2_000);        // 缓存 2 微秒
        stats.recordHit(4_000);        // 缓存 4 微秒 → 平均 3 微秒
        stats.recordDbLoad(100_000);   // 查库 100 微秒
        stats.recordDbLoad(200_000);   // 查库 200 微秒 → 平均 150 微秒

        CacheStats.Snapshot s = stats.snapshot();
        assertThat(s.avgCacheMicros()).isEqualTo(3.0);
        assertThat(s.avgDbLoadMicros()).isEqualTo(150.0);
        assertThat(s.dbLoads()).isEqualTo(2);
    }

    @Test
    @DisplayName("reset 后计数归零")
    void reset() {
        CacheStats stats = new CacheStats();
        stats.recordHit(1_000);
        stats.recordDbLoad(1_000);
        stats.reset();

        CacheStats.Snapshot s = stats.snapshot();
        assertThat(s.total()).isZero();
        assertThat(s.dbLoads()).isZero();
        assertThat(s.avgCacheMicros()).isZero();
    }
}
