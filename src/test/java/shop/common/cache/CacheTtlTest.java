package shop.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TTL 策略测试（Phase 3.6 雪崩防护）。
 *
 * <p>要锁住的两件事：
 * <ol>
 *   <li><b>抖动必须在区间内</b>：不能抖动成「比基础值还短」——那会让缓存寿命不可预测；</li>
 *   <li><b>抖动必须真的散开</b>：如果实现里写错了（比如把随机数写成了常量），
 *       这个测试会失败。这是「防雪崩」这个说法的唯一证据。</li>
 * </ol>
 */
class CacheTtlTest {

    @Test
    @DisplayName("抖动后的 TTL 落在 [base, base+jitter] 区间内")
    void jitterStaysInRange() {
        Duration base = Duration.ofMinutes(30);
        int jitterSeconds = 300;

        for (int i = 0; i < 2_000; i++) {
            Duration actual = CacheTtl.withJitter(base, jitterSeconds);
            assertThat(actual).isGreaterThanOrEqualTo(base);
            assertThat(actual).isLessThanOrEqualTo(base.plusSeconds(jitterSeconds));
        }
    }

    @Test
    @DisplayName("抖动真的在散开：2000 次采样出现大量不同的 TTL（否则防雪崩就是空话）")
    void jitterReallySpreads() {
        Set<Long> distinct = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            distinct.add(CacheTtl.product().getSeconds());
        }
        // 理论上界是 300（300~600 秒之间的整数），采样 2000 次基本能覆盖满
        assertThat(distinct).hasSizeGreaterThan(100);
    }

    @Test
    @DisplayName("jitter<=0 时不做抖动（需要精确 TTL 的场景）")
    void zeroJitterIsExact() {
        assertThat(CacheTtl.withJitter(Duration.ofSeconds(60), 0)).isEqualTo(Duration.ofSeconds(60));
        assertThat(CacheTtl.withJitter(Duration.ofSeconds(60), -5)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("商品缓存 30~35 分钟、分类缓存 2~2h10min、空值缓存 60 秒")
    void documentedRanges() {
        assertThat(CacheTtl.product()).isBetween(Duration.ofMinutes(30), Duration.ofMinutes(35));
        assertThat(CacheTtl.categoryEnabled()).isBetween(Duration.ofHours(2), Duration.ofMinutes(130));
        // 空值缓存的 TTL 不抖动：它本来就短，且「多久后重试一次不存在的 id」需要可预期
        assertThat(CacheTtl.NULL_VALUE).isEqualTo(Duration.ofSeconds(60));
        // 重建锁必须远小于一次查库的容忍时间，否则锁会先于业务失效
        assertThat(CacheTtl.REBUILD_LOCK).isLessThanOrEqualTo(Duration.ofSeconds(5));
    }
}
