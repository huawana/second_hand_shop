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
            if (num == productId) {
                iterator.remove();
            }
        }
        String newProductList = StringToList.listToString(numList);
        cartMapper.updateCartProducts(id,newProductList);
        return "/shop/cart";
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
