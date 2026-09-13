package shop.shop.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import shop.admin.Bean.Category;
import shop.common.BizException;
import shop.common.ErrorCode;
import shop.common.Result;
import shop.common.service.CategoryService;

import java.util.List;

/**
 * 分类查询接口（Phase 2 新增，只读、可匿名访问）。
 *
 * <p>这一组接口的作用有三个：
 * <ol>
 *   <li><b>业务</b>：给首页 / 搜索页提供分类列表，替代原来「无分类」的商品组织方式；</li>
 *   <li><b>验证 MyBatis-Plus 真正接入了</b>：{@code /shop/api/categories/page} 走的就是
 *       分页插件拦截改写的 SQL，不写 LIMIT 也能正确翻页；</li>
 *   <li><b>演示统一响应体</b>：所有接口都返回 {@code Result<T>}，前端处理方式一致。</li>
 * </ol>
 *
 * <p>这里用 {@code @RestController}（= {@code @Controller} + {@code @ResponseBody}）：
 * 本类所有方法都返回 JSON，没有视图名，不需要在方法上逐个加 {@code @ResponseBody}。
 * 项目里老的 Controller 混合返回「视图名」与 JSON，所以用的是 {@code @Controller} + 方法级注解。
 */
@Slf4j
@RestController
public class ShopCategoryController {

    private final CategoryService categoryService;

    public ShopCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** 全部分类（首页 / 搜索页侧栏用） */
    @GetMapping("/shop/api/categories")
    public Result<List<Category>> list() {
        return Result.success(categoryService.listEnabled());
    }

    /** 分类分页（演示分页插件，不写 LIMIT 也能翻页） */
    @GetMapping("/shop/api/categories/page")
    public Result<IPage<Category>> page(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "3") long size) {
        return Result.success(categoryService.page(current, size));
    }

    /** 单个分类详情 */
    @GetMapping("/shop/api/categories/{id}")
    public Result<Category> detail(@PathVariable Integer id) {
        Category category = categoryService.getById(id);
        if (category == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "分类不存在");
        }
        return Result.success(category);
    }
}
