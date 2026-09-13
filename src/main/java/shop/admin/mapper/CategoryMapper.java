package shop.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import shop.admin.Bean.Category;

/**
 * 分类 Mapper。
 *
 * <p>【MyBatis-Plus 的价值点】继承 {@code BaseMapper<Category>} 之后，
 * 下面这行什么都不用写，就已经拥有：
 * {@code insert} / {@code deleteById} / {@code updateById} / {@code selectById} /
 * {@code selectList(Wrapper)} / {@code selectPage(...)} 等一整套单表 CRUD。
 *
 * <p>对比项目里老的 {@code ProductMapper}：为了「按 id 查商品」这种最简单的事，
 * 也要在接口声明方法 + 在 XML 里手写 SQL + 在 XML 里维护 resultType 映射 ——
 * 单表 CRUD 占了大量重复劳动，这才是引入 MP 的真实理由
 * （而不是「用了新框架显得厉害」）。
 *
 * <p>【边界】复杂的多表 JOIN 查询仍然用 XML 手写（例如订单列表那几条 SQL），
 * MP 只解决单表 CRUD 与分页。混用是完全正常的做法，
 * 不必为了统一而把 JOIN 硬写成 MP 的 Wrapper。
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
