package shop.common.cache;

/**
 * 缓存 / 锁的 key 规范（Phase 3）。
 *
 * <p>【为什么把 key 集中到一个类】散落在各个 Service 里手拼字符串，典型后果是三处写
 * {@code "shop:product:"}、两处写 {@code "shop:cache:product:"} —— 读的时候命中不了缓存，
 * 但不会有任何报错，只会表现为「缓存看起来没生效，命中率 0%」。集中定义后：
 * <ul>
 *   <li>key 的形态成为可审查的一处事实，改前缀只改这里；</li>
 *   <li>能一眼看出 key 的<b>命名空间分层</b>（见下），排查时用
 *       {@code redis-cli -n 3 keys 'shop:cache:*'} 就能按类扫。</li>
 * </ul>
 *
 * <p>命名分层（全部挂在 {@code shop:} 前缀下，配合 {@code spring.data.redis.database=3} 隔离本机其他项目）：
 * <pre>
 *   shop:cache:product:{id}      商品详情缓存（Cache-Aside，JSON）
 *   shop:cache:category:enabled  启用的分类列表（读多写少）
 *   shop:bloom:product           商品 id 布隆过滤器（穿透防护）
 *   shop:lock:cache:product:{id} 缓存重建互斥锁（击穿防护，短 TTL）
 *   shop:lock:order:{uid}:{pid}  下单分布式锁（Redisson，同一用户重复提交）
 * </pre>
 *
 * <p>另外两类 key 在 Phase 1 就存在，见 {@code shop.security.RedisTokenStore}：
 * {@code shop:killed:*}（access 黑名单）、{@code shop:rt:*}（refresh token）。
 * 它们与缓存共用 db 3，但前缀不同，互不干扰。
 */
public final class CacheKeys {

    private CacheKeys() {
        // 常量类不允许实例化
    }

    /** 全局前缀：本项目所有 Redis key 的起点 */
    public static final String PREFIX = "shop:";

    /** 缓存类 key 的公共前缀 */
    public static final String CACHE_PREFIX = PREFIX + "cache:";

    /** 商品详情缓存：{@code shop:cache:product:{id}} */
    public static final String PRODUCT_PREFIX = CACHE_PREFIX + "product:";

    /** 启用状态的分类列表（全量列表，一个 key 装得下，不需要按 id 拆） */
    public static final String CATEGORY_ENABLED = CACHE_PREFIX + "category:enabled";

    /**
     * 空值哨兵（穿透防护用）。
     *
     * <p>「查不到的 id」也要占用一个缓存位（短 TTL），否则每次请求都会穿过缓存打库 —— 这就是缓存穿透。
     * 但 Redis 里存不了 null，所以存一个<b>约定的哨兵字符串</b>表示「这个 id 确实不存在」。
     *
     * <p>为什么用字符串而不是自定义的哨兵对象：value 序列化器（见 {@code RedisConfig}）带类型信息，
     * 一个自定义类改名/换包后旧值就反序列化失败；而 {@code String} 是 JDK 自带类型，
     * 不受本项目重构影响，且 {@code redis-cli} 里一眼可读。
     */
    public static final String NULL_VALUE = "__NULL__";

    /** 布隆过滤器名（Redisson 里 filter 名就是 Redis key，所以这里带完整前缀） */
    public static final String BLOOM_PRODUCT = PREFIX + "bloom:product";

    /** 锁类 key 的公共前缀 */
    public static final String LOCK_PREFIX = PREFIX + "lock:";

    private static final String PRODUCT_REBUILD_LOCK_PREFIX = LOCK_PREFIX + "cache:product:";
    private static final String ORDER_LOCK_PREFIX = LOCK_PREFIX + "order:";

    /** 某商品的详情缓存 key */
    public static String product(long id) {
        return PRODUCT_PREFIX + id;
    }

    /** 某商品的缓存重建互斥锁 key（击穿防护） */
    public static String productRebuildLock(long id) {
        return PRODUCT_REBUILD_LOCK_PREFIX + id;
    }

    /**
     * 下单分布式锁 key：{@code shop:lock:order:{uid}:{pid}}。
     *
     * <p>粒度选「用户 + 商品」而不是「商品」：锁商品会让所有买家互相排队（二手商品库存 1，
     * 真正的并发保护已由数据库条件更新完成）；这里的锁要解决的是
     * <b>同一个用户连点两次</b>产生两笔订单的重复提交问题。
     */
    public static String userOrderLock(int userId, int productId) {
        return ORDER_LOCK_PREFIX + userId + ":" + productId;
    }
}
