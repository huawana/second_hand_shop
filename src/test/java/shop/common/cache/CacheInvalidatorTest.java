package shop.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 延迟双删测试（Phase 3.7）。
 *
 * <p>「延迟双删」最难验证的一点是<b>第二次删除确实发生了</b>：它在一个 500ms 之后的后台任务里，
 * 端到端测试只能看到「缓存被删了」，看不出删了几次。所以这里用 mock 把调用次数钉住：
 * <ul>
 *   <li>开启时：立即 1 次 + 延迟 1 次（共 2 次）—— 这才是双删；</li>
 *   <li>关闭时：只有立即那 1 次 —— 证明开关真的在控制行为，而不是「写了但没生效」。</li>
 * </ul>
 * 顺带覆盖「异步任务不能被测试遗忘」：测试结束前必须 shutdown，否则线程池会一直挂着。
 */
class CacheInvalidatorTest {

    @Test
    @DisplayName("开启延迟双删：立即删一次 + 约 500ms 后再删一次")
    void doubleDeleteHappens() {
        ProductCacheService cacheService = mock(ProductCacheService.class);
        CacheInvalidator invalidator = new CacheInvalidator(cacheService, true);
        try {
            invalidator.evictProduct(42);

            // 第一次删除是同步进行的（不等延迟）
            verify(cacheService, after(100).times(1)).evict(42);
            // 第二次发生在 500ms 之后；给足余量（CI 上定时任务可能被调度延迟）
            verify(cacheService, timeout(3_000).times(2)).evict(42);
        } finally {
            invalidator.shutdown();
        }
    }

    @Test
    @DisplayName("关闭延迟双删：只删一次（开关生效，不是写了没用的配置）")
    void singleDeleteWhenDisabled() {
        ProductCacheService cacheService = mock(ProductCacheService.class);
        CacheInvalidator invalidator = new CacheInvalidator(cacheService, false);
        try {
            invalidator.evictProduct(7);

            verify(cacheService, after(100).times(1)).evict(7);
            // 等足够久，确认不会有第二次
            verify(cacheService, after(1_200).times(1)).evict(7);
            verifyNoMoreInteractions(cacheService);
        } finally {
            invalidator.shutdown();
        }
    }

    @Test
    @DisplayName("shutdown 之后不再接受新的延迟任务（应用停机不报错）")
    void shutdownIsSafe() {
        ProductCacheService cacheService = mock(ProductCacheService.class);
        CacheInvalidator invalidator = new CacheInvalidator(cacheService, true);
        invalidator.shutdown();

        // 已关闭的线程池会抛 RejectedExecutionException，实现里必须吞掉它 ——
        // 否则「停机时正在改商品」这一个请求会 500。这里只断言不抛异常。
        invalidator.evictProduct(1);
    }

    @Test
    @DisplayName("延迟双删的延迟远小于商品缓存 TTL（否则等于没兜住）")
    void delayIsMuchSmallerThanTtl() {
        org.assertj.core.api.Assertions
                .assertThat(CacheInvalidator.SECOND_DELETE_DELAY_MILLIS)
                .isLessThan(CacheTtl.PRODUCT_BASE.toMillis())
                .isLessThan(TimeUnit.SECONDS.toMillis(2));
    }
}
