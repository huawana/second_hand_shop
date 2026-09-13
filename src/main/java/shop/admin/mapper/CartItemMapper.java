package shop.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import shop.admin.Bean.CartItem;
import shop.admin.Bean.Product;

import java.util.List;

/**
 * 购物车明细 Mapper。
 *
 * <p>单表 CRUD 由 {@link BaseMapper} 提供；只有「购物车页要展示商品完整信息」这一条
 * 需要 JOIN，用 XML 手写（位置：{@code resources/mapper/CartItemMapper.xml}）。
 */
@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {

    /**
     * 查询某用户购物车里的商品（含卖家名等展示字段）。
     *
     * <p>【对比旧实现】原来购物车页是「先取出逗号串 → 拆成 id 列表 → for 循环逐个
     * {@code getProductById}」，一个用户加 10 件商品就是 1 + 10 条 SQL（经典的 N+1）。
     * 改成关联表后，一条 JOIN 就能全部取回 —— 这正是「把字符串换成关系模型」的直接收益，
     * 也是这个重构最值得在面试里讲的一点。
     */
    List<Product> selectCartProducts(@Param("userId") Integer userId);
}
