package shop.common.cache;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 缓存失效器（Phase 3.7 —— 缓存一致性）。
 *
 * <p>【先讲清顺序：为什么是「先更新数据库、再删除缓存」，而且必须删缓存而不是更新缓存】
 * <ol>
 *   <li><b>删缓存而不是更新缓存</b>：更新缓存要自己算出「新值是什么」（商品改了价格，
 *       缓存里那个对象的其它字段是不是也要跟着变？），并且每次写都要写两处；
 *       更致命的是并发写会乱序 —— A 先算好新值、B 后算好新值，B 先写库、A 后写缓存，
 *       缓存里就留下了 A 的旧值。删除则是幂等且无状态的：删错了最多让下一个读请求查一次库。</li>
 *   <li><b>先更库、再删缓存</b>：反过来（先删缓存再更库）会有一段「缓存已空 + 库还是旧值」的窗口，
 *       这期间进来的读请求会把<b>旧值</b>回填进缓存 —— 旧数据反而被写进了缓存，且一直留到 TTL 到期。
 *       先更库再删缓存，最坏情况也只是「缓存里短暂是旧值」，且马上会被删掉。</li>
 * </ol>
 *
 * <p>【为什么要删两次（延迟双删）】即使按上面的顺序，还有一个更隐蔽的竞态：
 * <pre>
 *   T1 读请求：查缓存未命中 → 查库（拿到旧值 100）            ← 数据库还没改
 *   T2 写请求：更新数据库（改成 200）→ 删除缓存               ← 缓存被清掉
 *   T1 读请求：把刚从库里读到的旧值 100 写回缓存              ← 旧值回来了！
 * </pre>
 * 结果：数据库 200、缓存 100，且要等 TTL 才自愈。
 * 解法是在「删除缓存」之后<b>再延迟一小段时间删第二次</b>，把 T1 那种「删之前读库、
 * 删之后写缓存」的脏数据再清一遍。延迟要略大于「一次读请求的查库 + 写缓存」耗时，
 * 本项目取 {@value #SECOND_DELETE_DELAY_MILLIS}ms。
 *
 * <p>【延迟双删的边界（要知道它治不好什么）】它只能把不一致窗口从「TTL 那么长」压到
 * 「毫秒级」，并不能消灭并发写导致的乱序。要强一致需要别的方案：
 * 订阅数据库 binlog（Canal）异步失效、或给缓存读加版本号。因此实际项目里通常还会：
 * 给缓存设过期时间兜底（本项目 30–35 分钟，见 {@link CacheTtl}）、
 * 对一致性要求极高的字段就不放缓存。这些取舍要能说清楚，而不是背一句「延迟双删就解决了」。
 */
@Component
public class CacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidator.class);

    /** 第二次删除的延迟（毫秒） */
    static final long SECOND_DELETE_DELAY_MILLIS = 500L;

    private final ProductCacheService productCacheService;

    /**
     * 延迟任务线程池。
     *
     * <p>为什么不用 {@code @Scheduled}：它是「到点触发」的定时器语义（最小粒度 1 秒、
     * 每次调用都要注册一次任务），而这里需要的是「本次请求结束后 500ms 执行一次」——
     * 属于延时投递，用 {@code ScheduledExecutorService} 才自然。
     * 线程工厂设成守护线程：应用关闭时不需要为了一个删缓存任务阻塞停机。
     */
    private final ScheduledExecutorService secondDeleteScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cache-second-delete");
                t.setDaemon(true);
                return t;
            });

    private final boolean enabled;

    public CacheInvalidator(ProductCacheService productCacheService,
                           @Value("${shop.cache.delayed-double-delete:true}") boolean enabled) {
        this.productCacheService = productCacheService;
        this.enabled = enabled;
    }

    /**
     * 商品更新后失效缓存：立即删一次，再延迟删一次。
     *
     * <p><b>调用时机是硬约束</b>：必须在数据库更新<b>提交之后</b>调用。
     * 如果在事务里调用（事务还没提交就删缓存），读请求会读到「旧的库 + 空缓存」并回填旧值 ——
     * 双删都救不回来，因为脏数据是在第一次删除之后写的。
     * 本项目这几个调用点都是自动提交的单条 UPDATE，所以调用位置正确；
     * 若将来给这些方法加上 {@code @Transactional}，这个顺序就会被打乱，需要注意。
     */
    public void evictProduct(int id) {
        productCacheService.evict(id);
        if (!enabled) {
            return;
        }
        try {
            secondDeleteScheduler.schedule(
                    () -> productCacheService.evict(id),
                    SECOND_DELETE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 提交延迟任务失败（例如已 shutdown）不影响第一次删除已经生效
            log.warn("注册延迟双删任务失败 id={}", id, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        secondDeleteScheduler.shutdown();
        log.info("缓存延迟双删线程池已关闭");
    }
}
