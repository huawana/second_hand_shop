package shop.shop.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import shop.admin.Bean.Cart;
import shop.admin.mapper.CartMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link StringToList} 单元测试。
 *
 * <p>这个类是整个购物车功能的基石（把逗号串与 List<Integer> 互转），
 * 而购物车是项目里最容易出边界问题的地方，所以优先补测试。
 */
class StringToListTest {

    @Test
    @DisplayName("null / 空串 应返回空列表，而不是抛异常")
    void stringToList_nullOrEmpty_returnsEmptyList() {
        assertTrue(StringToList.stringToList(null).isEmpty());
        assertTrue(StringToList.stringToList("").isEmpty());
    }

    @Test
    @DisplayName("正常逗号串应正确解析为整数列表")
    void stringToList_normal() {
        assertEquals(Arrays.asList(1, 2, 3), StringToList.stringToList("1,2,3"));
        assertEquals(Collections.singletonList(42), StringToList.stringToList("42"));
    }

    @Test
    @DisplayName("列表转字符串：正常与空列表")
    void listToString() {
        assertEquals("1,2,3", StringToList.listToString(Arrays.asList(1, 2, 3)));
        assertEquals("", StringToList.listToString(Collections.emptyList()));
    }

    @Test
    @DisplayName("字符串往返转换应保持等价")
    void roundTrip() {
        List<Integer> original = Arrays.asList(7, 8, 9);
        List<Integer> parsed = StringToList.stringToList(StringToList.listToString(original));
        assertEquals(original, parsed);
    }

    @Test
    @DisplayName("cartAddProduct：新商品应追加，已存在的商品应保持不变（幂等）")
    void cartAddProduct() {
        CartMapper cartMapper = mock(CartMapper.class);
        Cart cart = new Cart();
        cart.setId(1);
        cart.setProducts("10,20");
        when(cartMapper.getCartById(1)).thenReturn(cart);

        // 注意：StringToList 的 cartMapper 是「静态字段」，
        // 这里通过构造函数赋值（用 mock 替换真实 Mapper），这是静态字段注入的代价 ——
        // Phase 2 会把这套静态工具类重构为 Spring 单例 Bean，彻底消除这个隐患。
        new StringToList(cartMapper);

        // 已存在 → 不重复添加
        assertEquals("10,20", StringToList.cartAddProduct(1, 20));
        // 新商品 → 追加到末尾
        assertEquals("10,20,30", StringToList.cartAddProduct(1, 30));
    }
}
