package shop.shop.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import shop.admin.Bean.Cart;
import shop.admin.mapper.CartMapper;

import java.util.List;

/**
 * 首页 / 搜索页的「过滤掉已在购物车里的商品」辅助类。
 *
 * <p>【Phase 2.3 清理】原来的 {@code cleanCart(productId)} 与 {@code getProductList(id)}
 * 已无调用者，删除：
 * <ul>
 *   <li>{@code cleanCart} —— 它的职责（把商品从所有购物车移除）已收敛到
 *       {@link shop.common.service.CartService#removeProductFromAllCarts}，
 *       且新实现是一条 DELETE，不再是「遍历所有购物车逐串写回」；</li>
 *   <li>{@code getProductList} —— 死代码，全项目只有它自己引用自己，
 *       真正的调用走的是 {@link SearchProcess#getProductListBySearchHistory}。</li>
 * </ul>
 *
 * <p>【仍读旧表，是有意的】{@link #getCartList} 读的是 {@code lxy_cart.products} 逗号串，
 * 而写路径已经切到 {@code cart_item}。之所以现在仍然正确，是因为
 * CartService 每次写操作都会把新表内容投影回旧串（兼容层，单向、全量重写、天然幂等）。
 * 也就是说这处读的是「旧表这个物化视图」，内容与 cart_item 一致。
 * 等读路径也全部迁到新表后，本类即可整体删除。
 *
 * <p>（注：用静态字段接收注入的 Bean 是原项目的写法，属于待清理的技术债 ——
 * 它让单元测试无法替换依赖、也让依赖关系隐式化。Phase 5 会把它改成正常的 Spring Bean。
 * 本次只做「删死代码」，不做无关重构。）
 */
@Slf4j
@Component
public class UserProcess {

    private static CartMapper cartMapper;

    @Autowired
    public UserProcess(CartMapper cartMapper) {
        UserProcess.cartMapper = cartMapper;
    }

    /** 纯列表操作：把「已在购物车里的商品」从待展示列表中剔除 */
    public static void removeCartElementFromProducts(List<shop.admin.Bean.Product> productList, List<Integer> cartList) {
        productList.removeIf(product -> cartList.contains(product.getId()));
    }

    /** 读某用户购物车里的商品 id 列表（兼容层：读旧串，内容与 cart_item 一致） */
    public static List<Integer> getCartList(int id) {
        Cart cart = cartMapper.getCartById(id);
        if (cart == null) {
            return new java.util.ArrayList<>();
        }
        return StringToList.stringToList(cart.getProducts());
    }
}
