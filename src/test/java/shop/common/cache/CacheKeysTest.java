package shop.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Key 规范测试。
 *
 * <p>看起来像在测字符串拼接，但它锁住的是<b>一条真实的故障</b>：
 * key 拼错（少个冒号、前缀换了）不会有任何报错，只会表现为「缓存永远不命中」，
 * 而且这类问题在代码 review 时极难被发现。
 * 断言写死期望值，等于把 key 布局变成一份可执行的文档 —— 谁改了布局，测试先红。
 */
class CacheKeysTest {

    @Test
    @DisplayName("商品缓存 key：shop:cache:product:{id}")
    void productKey() {
        assertThat(CacheKeys.product(1)).isEqualTo("shop:cache:product:1");
        assertThat(CacheKeys.product(3659)).isEqualTo("shop:cache:product:3659");
    }

    @Test
    @DisplayName("分类列表 key：shop:cache:category:enabled")
    void categoryKey() {
        assertThat(CacheKeys.CATEGORY_ENABLED).isEqualTo("shop:cache:category:enabled");
    }

    @Test
    @DisplayName("所有缓存 key 都在 shop:cache: 前缀下，便于 redis-cli 按前缀扫")
    void cacheKeysSharePrefix() {
        assertThat(CacheKeys.product(7)).startsWith(CacheKeys.CACHE_PREFIX);
        assertThat(CacheKeys.CATEGORY_ENABLED).startsWith(CacheKeys.CACHE_PREFIX);
    }

    @Test
    @DisplayName("锁 key 与缓存 key 不共用前缀，避免「按 shop:cache:* 清理时把锁一起删了」")
    void lockKeysAreSeparateNamespace() {
        assertThat(CacheKeys.productRebuildLock(7)).isEqualTo("shop:lock:cache:product:7");
        assertThat(CacheKeys.userOrderLock(3, 7)).isEqualTo("shop:lock:order:3:7");
        assertThat(CacheKeys.productRebuildLock(7)).startsWith(CacheKeys.LOCK_PREFIX);
        // 缓存前缀不能是锁 key 的前缀，否则 keys 'shop:cache:*' 会扫到重建锁
        assertThat(CacheKeys.productRebuildLock(7)).doesNotStartWith(CacheKeys.CACHE_PREFIX);
    }

    @Test
    @DisplayName("空值哨兵不能是空串：空串在某些序列化器下与「key 不存在」难以区分")
    void nullSentinelIsNotEmpty() {
        assertThat(CacheKeys.NULL_VALUE).isNotBlank();
    }
}
