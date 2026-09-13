package shop.shop.tools;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import shop.admin.Bean.Cart;
import shop.admin.Bean.Product;
import shop.admin.Bean.User;
import shop.admin.mapper.CartMapper;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;
import shop.shop.mapper.SearchMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Component
public class UserProcess {

    static CartMapper cartMapper;
    static ProductMapper productMapper;
    static SearchMapper searchMapper;
    static UserMapper userMapper;
    @Autowired
    public UserProcess(CartMapper cartMapper,ProductMapper productMapper,SearchMapper searchMapper,UserMapper userMapper) {
        this.cartMapper = cartMapper;
        this.productMapper = productMapper;
        this.searchMapper = searchMapper;
        this.userMapper = userMapper;
    }


    public static void cleanCart(int id) {
        List<Cart> cartList = cartMapper.getCarts();
        for (Cart cart : cartList) {
            String stringCart = cart.getProducts();
            List<Integer> listCart = StringToList.stringToList(stringCart);
            if (listCart.contains(id)) {
                listCart.remove(Integer.valueOf(id)); // 使用 Integer.valueOf() 将 id 转换为 Integer 类型
                System.out.println(listCart);
            }
            String newCart = StringToList.listToString(listCart);
            cartMapper.updateCartProducts(cart.getId(), newCart);
        }
    }

    public static List<Product> getProductList(int id){
        List<Integer> cartList = getCartList(id);
        String search = searchMapper.getSearchById(id);
        User user = userMapper.getUserById(id);
        if(search==null||search.length()==0){

            List<Product> productList = productMapper.getProducts(user.getUsername());
            removeCartElementFromProducts(productList, cartList);

            return productList;
        }else {
            return SearchProcess.getProductListBySearchHistory(user.getUsername(),search);
        }
    }

    public static void removeCartElementFromProducts(List<Product> productList, List<Integer> cartList){
        // 如果 idList 包含当前用户的 id，则从 userList 中删除该用户
        productList.removeIf(product -> cartList.contains(product.getId()));
    }

    public static List<Integer> getCartList(int id){
        Cart cart = cartMapper.getCartById(id);
        String cartString = cart.getProducts();
        return StringToList.stringToList(cartString);
    }
}
