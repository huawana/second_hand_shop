package shop.shop.controller;

import lombok.extern.slf4j.Slf4j;
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
import shop.common.BizException;
import shop.common.ErrorCode;
import shop.common.Result;
import shop.shop.Bean.CartItem;
import shop.shop.tools.SessionCheck;
import shop.shop.tools.StringToList;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Iterator;
import java.util.List;

@Slf4j
@Controller
public class ShopCartController {

    @Autowired
    ProductMapper productMapper;
    @Autowired
    CartMapper cartMapper;
    @Autowired
    UserMapper userMapper;

    /**
     * 从购物车移除商品。
     *
     * <p>注意本项目购物车不是关联表，而是把商品 id 拼成逗号串存在 lxy_cart.products 里，
     * 所以这里是「读出来 → 字符串替换 → 整串写回」。
     * 这种设计在高并发下会丢失更新（两个请求同时读写同一行），Phase 2 会重构为 cart_item 表。
     */
    @PostMapping("/shop/deleteProduct")
    public String deleteProduct(int productId, Model m, HttpServletRequest request){
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            return "redirect:/shop/login";
        }
        String username = (String)session.getAttribute("shopusername");
        int id = userMapper.getIdByUserName(username);
        Cart cart = cartMapper.getCartById(id);
        if (cart != null) {
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
        }
        // 【Bug 修复】原为转发到视图名，但本方法没有往 Model 里放 cartProduct，
        // 模板渲染时拿不到数据。改为重定向，由 GET /shop/cart 重新装配数据。
        return "redirect:/shop/cart";
    }



    /**
     * 加入购物车。
     *
     * <p>【改造】返回统一响应体；补登录校验（原来未登录会 NPE）。
     */
    @PostMapping("/shop/addToCart")
    @ResponseBody
    public Result<Boolean> addToCart(@Valid @RequestBody CartItem cartItem, HttpServletRequest request){
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String username = (String)session.getAttribute("shopusername");
        int id = userMapper.getIdByUserName(username);
        Integer productId = productMapper.getIdByImgPath(cartItem.getImgPath());
        if (productId == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        String newProductList = StringToList.cartAddProduct(id,productId);
        cartMapper.updateCartProducts(id,newProductList);
        log.info("用户[{}]加入购物车 productId={}", username, productId);
        return Result.success(true);
    }

}
