package shop.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import shop.admin.Bean.Category;
import shop.admin.mapper.CategoryMapper;
import shop.common.cache.CacheKeys;
import shop.common.cache.CacheTtl;

import java.util.List;

/**
 * 分类业务层（Phase 2 起新增的 Service 层；Phase 3.3 起带缓存）。
 *
 * <p>【为什么新代码走三层】项目原有代码是 Controller 直接调 Mapper，
 * 逻辑一复杂就会出现「事务边界落在 Controller、SQL 拼在 Controller」的问题。
 * 新增功能统一按 Controller → Service → Mapper 分层：
 * 事务注解（{@code @Transactional}）加在 Service 上、业务规则集中在 Service 里，
 * Controller 只做参数与响应。老代码在后续 Phase 逐步迁移，不强行一次性重写。
 *
 * <p>【依赖注入方式】这里用构造器注入而不是字段注入（{@code @Autowired}）：
 * <ul>
 *   <li>依赖是 {@code final} 的，对象一旦创建就不可变，天然线程安全；</li>
 *   <li>缺依赖时<b>启动即失败</b>，而不是运行时 NPE；</li>
 *   <li>单元测试可以直接 {@code new CategoryService(mockMapper, ...)}，不需要启动 Spring 容器。</li>
 * </ul>
 * 项目老代码大量使用字段注入，面试若被问到「两种注入的区别」，
 * 可以直接指着这两处代码对比着讲。
 *
 * <p>【Phase 3.3：为什么这一层最适合做缓存】分类列表是「读多写少」的教科书场景：
 * 首页、搜索页侧栏、后台下拉框都要用它，写入几乎没有（后台还没做分类管理）。
 * 缓存直接加在 Service 的查询方法上，Controller 一行不用改 —— 这正是 Cache-Aside
 * 放在服务层的好处：<b>所有调用方自动受益</b>，不用挨个改。
 *
 * <p>【与商品缓存的差别】分类列表用整表结果做 value（几百字节的 JSON 数组），
 * 而不是按 id 逐个缓存。判断依据是「数据量 + 读取方式」：
 * 数据量小、每次都要全量、且没有按 id 单独查的热点，整体缓存比逐个缓存少一层拼接逻辑。
 */
@Service
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    private final CategoryMapper categoryMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final boolean cacheEnabled;

    public CategoryService(CategoryMapper categoryMapper,
                           RedisTemplate<String, Object> redisTemplate,
                           @Value("${shop.cache.enabled:true}") boolean cacheEnabled) {
        this.categoryMapper = categoryMapper;
        this.redisTemplate = redisTemplate;
        this.cacheEnabled = cacheEnabled;
    }

    /**
     * 查询启用状态的分类，按 sort 升序（Phase 3.3 起带缓存）。
     *
     * <p>用 {@code LambdaQueryWrapper} 而不是字符串列名：
     * {@code Category::getSort} 在重构改字段名时能被编译器发现，
     * 老写法 {@code orderByAsc("sort")} 改名字段后只会在运行时 SQL 报错。
     *
     * <p>缓存细节：
     * <ul>
     *   <li>value 是整个 List（序列化成 JSON 数组），key 见 {@link CacheKeys#CATEGORY_ENABLED}；</li>
     *   <li>TTL 带随机抖动（雪崩防护），见 {@link CacheTtl#categoryEnabled()}；</li>
     *   <li>读/写缓存都吞异常：Redis 挂了就退化成「每次都查库」，接口照常可用。
     *       缓存永远不该成为业务可用性的单点。</li>
     * </ul>
     *
     * <p><b>还没有写入口需要处理</b>：后台目前没有分类管理功能（无新增/修改/删除分类的接口），
     * 所以这里只有 TTL 兜底、没有主动失效。一旦将来加了分类写接口，
     * <b>必须在写完库之后删除这个 key</b>（先更库再删缓存，理由见 {@code CacheInvalidator}），
     * 否则会出现「后台改了分类、前台十分钟还是旧的」。
     */
    @SuppressWarnings("unchecked")
    public List<Category> listEnabled() {
        if (cacheEnabled) {
            Object cached = cacheGet();
            if (cached instanceof List<?> list) {
                return (List<Category>) list;
            }
        }

        List<Category> categories = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getStatus, 1)
                .orderByAsc(Category::getSort));

        if (cacheEnabled) {
            cacheSet(categories);
        }
        return categories;
    }

    private Object cacheGet() {
        try {
            return redisTemplate.opsForValue().get(CacheKeys.CATEGORY_ENABLED);
        } catch (Exception e) {
            log.warn("读分类缓存失败（按未命中处理）", e);
            return null;
        }
    }

    private void cacheSet(List<Category> categories) {
        try {
            redisTemplate.opsForValue().set(CacheKeys.CATEGORY_ENABLED, categories, CacheTtl.categoryEnabled());
        } catch (Exception e) {
            log.warn("回写分类缓存失败", e);
        }
    }

    /** 分类写入后必须调用（当前无调用方，见 {@link #listEnabled()} 的说明） */
    public void evictCache() {
        try {
            redisTemplate.delete(CacheKeys.CATEGORY_ENABLED);
        } catch (Exception e) {
            log.warn("删除分类缓存失败（等 TTL 自愈）", e);
        }
    }

    /**
     * 分页查询（演示 MyBatis-Plus 分页插件）。
     *
     * <p>关键在于：<b>这里完全不写 LIMIT</b>。分页插件会拦截 SQL，
     * 自动追加 {@code LIMIT ?,?}，并额外执行一条 {@code COUNT} 得到总数，
     * 一起封装进返回的 {@link IPage}（含 records / total / pages / current / size）。
     *
     * <p>同时做了参数兜底：页码最小 1、每页条数 1~100。
     * 即使有人把 size 传成 0 或 -1 也不会构造出非法 SQL。
     */
    public IPage<Category> page(long current, long size) {
        long safeCurrent = Math.max(current, 1L);
        long safeSize = Math.min(Math.max(size, 1L), 100L);
        Page<Category> page = new Page<>(safeCurrent, safeSize);
        return categoryMapper.selectPage(page, new LambdaQueryWrapper<Category>()
                .orderByAsc(Category::getSort));
    }

    /** 按 id 查单个分类（BaseMapper 自带方法，无需写 SQL） */
    public Category getById(Integer id) {
        return id == null ? null : categoryMapper.selectById(id);
    }
}
