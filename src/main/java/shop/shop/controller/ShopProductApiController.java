package shop.shop.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shop.admin.Bean.Product;
import shop.common.BizException;
import shop.common.ErrorCode;
import shop.common.Result;
import shop.common.cache.CacheStats;
import shop.common.cache.ProductBloomFilter;
import shop.common.cache.ProductCacheService;

/**
 * 商品缓存相关接口（Phase 3.2 / 3.9）。
 *
 * <p>【为什么要新增这组 JSON 接口】详情页（{@code /shop/productDetail/{id}}）返回的是渲染好的 HTML，
 * 没法用来做「命中缓存 vs 直查库」的耗时对比 —— HTML 渲染、模板解析、静态资源引用的开销
 * 会把缓存节省的那几毫秒完全淹没，测出来的数字毫无意义。
 * 所以这里补一组只返回数据的接口，让对照实验只测量「取商品」这一步：
 * <ul>
 *   <li>{@code GET /shop/api/products/{id}} —— 走缓存（Cache-Aside）；</li>
 *   <li>{@code GET /shop/api/products/{id}?bypassCache=true} —— 直查数据库（对照基线）；</li>
 *   <li>{@code GET /shop/api/cache/stats} —— 应用侧命中率与平均耗时快照。</li>
 * </ul>
 *
 * <p>{@code bypassCache} 参数只用于诊断/对照实验，正常业务不应该传它；
 * 它同时也可作为线上「缓存出问题时的应急开关」——出故障时先让某个接口绕过缓存止血，
 * 比改配置重启更快。
 *
 * <p>这组接口是只读的（不改变任何状态），所以放在公开路径里（见 {@code SecurityConfig}）。
 */
@RestController
public class ShopProductApiController {

    private final ProductCacheService productCacheService;
    private final ProductBloomFilter bloomFilter;

    public ShopProductApiController(ProductCacheService productCacheService,
                                    ProductBloomFilter bloomFilter) {
        this.productCacheService = productCacheService;
        this.bloomFilter = bloomFilter;
    }

    /**
     * 商品详情（JSON）。
     *
     * @param bypassCache true = 跳过缓存直查库（对照实验 / 应急）
     */
    @GetMapping("/shop/api/products/{id}")
    public Result<Product> detail(@PathVariable int id,
                                  @RequestParam(name = "bypassCache", defaultValue = "false") boolean bypassCache) {
        Product product = bypassCache ? productCacheService.loadFromDb(id) : productCacheService.getById(id);
        if (product == null) {
            // 404 而不是 200+空对象：不存在的资源要让调用方（与缓存层）能明确区分
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return Result.success(product);
    }

    /** 缓存运行统计（命中率 / 平均耗时 / 真正查库次数 / 布隆过滤器可用性） */
    @GetMapping("/shop/api/cache/stats")
    public Result<CacheStatsView> stats() {
        CacheStats.Snapshot snapshot = productCacheService.stats();
        return Result.success(new CacheStatsView(snapshot, bloomFilter.isAvailable()));
    }

    /**
     * 统计视图。
     *
     * <p>把「布隆过滤器是否可用」和统计一起返回，是为了让降级状态<b>可观测</b>：
     * 如果哪天 Redis 连不上，接口返回的 available=false 就是最直接的证据，
     * 而不是靠人去看日志猜「是不是降级了」。
     */
    public record CacheStatsView(CacheStats.Snapshot productCache, boolean bloomFilterAvailable) {
    }
}
