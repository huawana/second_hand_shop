package shop.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import shop.admin.Bean.Category;
import shop.admin.mapper.CategoryMapper;

import java.util.List;

/**
 * 分类业务层（Phase 2 起新增的 Service 层）。
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
 *   <li>单元测试可以直接 {@code new CategoryService(mockMapper)}，不需要启动 Spring 容器。</li>
 * </ul>
 * 项目老代码大量使用字段注入，面试若被问到「两种注入的区别」，
 * 可以直接指着这两处代码对比着讲。
 */
@Service
public class CategoryService {

    private final CategoryMapper categoryMapper;

    public CategoryService(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    /**
     * 查询启用状态的分类，按 sort 升序。
     *
     * <p>用 {@code LambdaQueryWrapper} 而不是字符串列名：
     * {@code Category::getSort} 在重构改字段名时能被编译器发现，
     * 老写法 {@code orderByAsc("sort")} 改名字段后只会在运行时 SQL 报错。
     */
    public List<Category> listEnabled() {
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getStatus, 1)
                .orderByAsc(Category::getSort));
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
