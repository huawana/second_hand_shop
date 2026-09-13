package shop.shop.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import shop.admin.Bean.Cart;
import shop.admin.Bean.Product;
import shop.admin.Bean.User;
import shop.admin.mapper.OrderMapper;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;
import shop.common.BizException;
import shop.common.ErrorCode;
import shop.common.Result;
import shop.common.service.CartService;
import shop.shop.Bean.CartItemRequest;
import shop.shop.tools.SessionCheck;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;

@Slf4j
@Controller
public class ShopPersonalController {

    @Autowired
    UserMapper userMapper;
    /** 【Phase 2.3】购物车读写统一走 CartService（切到 cart_item 关联表） */
    @Autowired
    CartService cartService;
    @Autowired
    ProductMapper productMapper;
    @Autowired
    OrderMapper orderMapper;

    @GetMapping("/shop/city")
    public String city(HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        // 【Bug 修复】原代码直接 session.getAttribute("shopusername").equals(...)，
        // 首次访问（属性为 null）时抛 NPE。统一走 SessionCheck。
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else {
            return "shop/city";
        }

    }

    @GetMapping("/shop/school")
    public String school(HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else {
            return "shop/school";
        }

    }

    @GetMapping("/shop/chooseCity")
    public String chooseCity(String selectedProvinceText, String selectedCityText, String selectedAreaText, HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else{
            String username = session.getAttribute("shopusername").toString();
            userMapper.updateUserLocationByUserName(username,selectedProvinceText,selectedCityText,selectedAreaText);
            session.setAttribute("province",selectedProvinceText);
            session.setAttribute("city",selectedCityText);
            session.setAttribute("area",selectedAreaText);
            SessionCheck.checkSessionPosition(session,m);
            SessionCheck.checkSessionSchool(session,m);
            return "redirect:/shop/index";
        }
    }

    @GetMapping("/shop/chooseSchool")
    public String chooseSchool(String schoolText, HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else{
            String username = session.getAttribute("shopusername").toString();
            userMapper.updateUserSchoolByUserName(username,schoolText);
            session.setAttribute("school",schoolText);
            SessionCheck.checkSessionSchool(session,m);
            return "redirect:/shop/index";
        }
    }

    @GetMapping("/shop/cart")
    public String getCart(HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else{
            m.addAttribute("shopusername", session.getAttribute("shopusername"));
            int userId = userMapper.getIdByUserName((String)session.getAttribute("shopusername"));
            // 【Phase 2.3 重构】原来是 N+1：把逗号串拆成 id 列表后逐个 getProductById。
            // 现在一条 JOIN 取回全部展示字段（CartItemMapper.selectCartProducts）。
            // 顺带简化了一处防御代码：已下架/已删除的商品因为 INNER JOIN 不到 lxy_product
            // 天然不会出现在结果里，不再需要「查出来是 null 就跳过」。
            List<Product> cartProductList = cartService.listCartProducts(userId);
            m.addAttribute("cartProduct",cartProductList);
            SessionCheck.checkSessionPosition(session,m);
            SessionCheck.checkSessionSchool(session,m);
            return "shop/cart";
        }
    }

    /**
     * 判断是否已登录。前端"加入购物车"按钮会先调这个接口探测登录态。
     *
     * <p>【改造】返回值由裸 Boolean 改为统一响应体 Result&lt;Boolean&gt;。
     */
    @GetMapping("/shop/checkSession")
    @ResponseBody
    public Result<Boolean> checkSession(HttpServletRequest request){
        HttpSession session = request.getSession();
        return Result.success(!SessionCheck.checkSessionName(session));
    }

    @GetMapping("/shop/personRelease")
    public String getPersonRelease(HttpServletRequest request,Model m){
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else{
            m.addAttribute("shopusername",session.getAttribute("shopusername"));
            SessionCheck.checkSessionPosition(session,m);
            SessionCheck.checkSessionSchool(session,m);
            String username = (String) session.getAttribute("shopusername");
            int uid = userMapper.getIdByUserName(username);
            List<Product> productList = productMapper.getProductsByUid(uid);
            m.addAttribute("products",productList);
            return "shop/personRelease";
        }
    }

    @GetMapping("/shop/personSale")
    public String getPersonSale(HttpServletRequest request,Model m){
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else{
            m.addAttribute("shopusername",session.getAttribute("shopusername"));
            SessionCheck.checkSessionPosition(session,m);
            SessionCheck.checkSessionSchool(session,m);
            String username = (String) session.getAttribute("shopusername");
            List<Product> productList = productMapper.getSoldProductByUsername(username);
            for (Product product:productList) {
                String status = product.getStatus();
                if(status.equals("等待发货")){
                    product.setSellerHandle("发货");
                } else if (status.equals("已发货")) {
                    product.setSellerHandle("无");
                } else if (status.equals("已签收")) {
                    product.setSellerHandle("无");
                }
            }
            m.addAttribute("products",productList);
            return "shop/personSale";
        }
    }

    @GetMapping("/shop/personBuy")
    public String getPersonBuy(HttpServletRequest request,Model m){
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else{
            m.addAttribute("shopusername",session.getAttribute("shopusername"));
            SessionCheck.checkSessionPosition(session,m);
            SessionCheck.checkSessionSchool(session,m);
            String username = (String) session.getAttribute("shopusername");
            int uid = userMapper.getIdByUserName(username);
            List<Product> productList = productMapper.getBoughtProductByUsername(username);
            for (Product product:productList) {
                String status = product.getStatus();
                if(status.equals("等待发货")){
                    product.setBuyerHandle("无");
                } else if (status.equals("已发货")) {
                    product.setBuyerHandle("签收");
                } else if (status.equals("已签收")) {
                    product.setBuyerHandle("无");
                }
            }
            m.addAttribute("products",productList);
            return "shop/personBuy";
        }
    }

    /**
     * 下架（删除）自己发布的商品。
     *
     * <p>【安全修复】原代码只按 imgPath 找到商品就直接删除，完全没有校验商品归属 ——
     * 任何人只要构造一个 imgPath 就能删掉别人的商品（水平越权 / IDOR）。
     * 现在必须校验当前登录用户 == 商品卖家。
     */
    @PostMapping("/shop/deleteMyRelease")
    @ResponseBody
    public Result<Boolean> deleteMyRelease(@Valid @RequestBody CartItemRequest cartItem, HttpServletRequest request){
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String username = (String) session.getAttribute("shopusername");
        Integer productId = productMapper.getIdByImgPath(cartItem.getImgPath());
        if (productId == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        Product product = productMapper.getProductById(productId);
        if (product == null || !username.equals(product.getSellerName())) {
            // 注意：这里刻意不区分「商品不存在」和「不是你的商品」的对外文案，
            // 避免通过错误信息探测他人商品是否存在
            throw new BizException(ErrorCode.FORBIDDEN, "只能下架自己发布的商品");
        }
        log.info("用户[{}]下架商品 id={} name={}", username, productId, product.getName());
        productMapper.deleteProduct(productId);
        // 【Phase 2.3】原来是 UserProcess.cleanCart(productId)（遍历所有购物车逐个整串写回），
        // 现在是一条 DELETE ... WHERE product_id = ?（见 CartService.removeProductFromAllCarts）
        cartService.removeProductFromAllCarts(productId);
        return Result.success(true);
    }


    @GetMapping("/shop/personSuccessBuy")
    public String getPersonBuyFinishOrder(HttpServletRequest request,Model m){
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else{
            m.addAttribute("shopusername",session.getAttribute("shopusername"));
            SessionCheck.checkSessionPosition(session,m);
            SessionCheck.checkSessionSchool(session,m);
            String username = (String) session.getAttribute("shopusername");
            int uid = userMapper.getIdByUserName(username);
            List<Product> productList = productMapper.getFinishBuyProductByUsername(username);
            m.addAttribute("products",productList);
            return "shop/personSuccessBuy";
        }
    }

    @GetMapping("/shop/personSuccessSell")
    public String getPersonSellFinishOrder(HttpServletRequest request,Model m){
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else{
            m.addAttribute("shopusername",session.getAttribute("shopusername"));
            SessionCheck.checkSessionPosition(session,m);
            SessionCheck.checkSessionSchool(session,m);
            String username = (String) session.getAttribute("shopusername");
            int uid = userMapper.getIdByUserName(username);
            List<Product> productList = productMapper.getFinishSellProductByUsername(username);
            m.addAttribute("products",productList);
            return "shop/personSuccessSell";
        }
    }

}
