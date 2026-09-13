package shop.shop.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import shop.admin.Bean.Cart;
import shop.admin.mapper.CartMapper;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;
import shop.shop.Bean.CartItem;
import shop.shop.tools.SessionCheck;
import shop.shop.tools.StringToList;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Iterator;
import java.util.List;

@Controller
public class ShopCartController {

    @Autowired
    ProductMapper productMapper;
    @Autowired
    CartMapper cartMapper;
    @Autowired
    UserMapper userMapper;

    @PostMapping("/shop/deleteProduct")
    public String deleteProduct(int productId, Model m, HttpServletRequest request){
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            return "redirect:/shop/login";
        }
        String username = (String)session.getAttribute("shopusername");
        int id = userMapper.getIdByUserName(username);
        Cart cart = cartMapper.getCartById(id);
        String products = cart.getProducts();
        List<Integer> numList = StringToList.stringToList(products);
        Iterator<Integer> iterator = numList.iterator();
        while (iterator.hasNext()) {
            Integer num = iterator.next();
            // 【Bug 修复】原为 num == productId（Integer 与 int 比较，依赖自动拆箱才勉强正确），
            // 语义上应显式按值比较。注意 Integer 的 -128~127 缓存：== 只在缓存区间内"碰巧"成立。
            if (num.equals(productId)) {
                iterator.remove();
            }
        }
        String newProductList = StringToList.listToString(numList);
        cartMapper.updateCartProducts(id,newProductList);
        // 【Bug 修复】原为 return "/shop/cart"（转发），但本方法没有往 Model 里放 cartProduct，
        // 模板渲染时拿不到数据。改为重定向，由 GET /shop/cart 重新装配数据。
        return "redirect:/shop/cart";
    }



    @PostMapping("/shop/addToCart")
    @ResponseBody
    public boolean addToCart(@RequestBody CartItem cartItem, HttpServletRequest request){
        HttpSession session = request.getSession();
        String username = (String)session.getAttribute("shopusername");
        int id = userMapper.getIdByUserName(username);
        String imgPath = cartItem.getImgPath();
        System.out.println(imgPath);
        int productId = productMapper.getIdByImgPath(imgPath);
        String newProductList = StringToList.cartAddProduct(id,productId);
        cartMapper.updateCartProducts(id,newProductList);
        return true;
    }

}
