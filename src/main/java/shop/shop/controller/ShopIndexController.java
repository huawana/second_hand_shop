package shop.shop.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import shop.admin.Bean.Cart;
import shop.admin.Bean.Product;
import shop.admin.mapper.CartMapper;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;
import shop.shop.Bean.CartItem;
import shop.shop.mapper.SearchMapper;
import shop.shop.tools.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;

@Controller
public class ShopIndexController {
    @Autowired
    ProductMapper productMapper;
    @Autowired
    SearchMapper searchMapper;
    @Autowired
    CartMapper cartMapper;
    @Autowired
    UserMapper userMapper;
    @GetMapping("/shop/index")
    public String shopIndex(Model m, HttpServletRequest request) {
//        DailyUpdateTask.performUpdate();
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            List<Product> productList = productMapper.getProducts("");
            // 【Bug 修复】原为 subList(0,100)，商品不足 100 条时抛 IndexOutOfBoundsException。
            // 已登录分支（下方 :49）本来就用了 Math.min，这里保持一致。
            m.addAttribute("products", productList.subList(0, Math.min(productList.size(), 100)));
        }else{
            String username = (String) session.getAttribute("shopusername");
            int id = userMapper.getIdByUserName(username);
            String search = searchMapper.getSearchByUserName(username);
            if(search==null||search.length()==0){
                List<Product> productList = productMapper.getProducts(username);
                List<Integer> cartList = UserProcess.getCartList(id);
                UserProcess.removeCartElementFromProducts(productList,cartList);
                m.addAttribute("products", productList.subList(0,Math.min(productList.size(),100)));
            }else {
                List<Product> productList = SearchProcess.getProductListBySearchHistory(username,search);
                m.addAttribute("products", productList);
            }
        }
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        return "/shop/index";
    }

    @GetMapping("/shop/index/sortedByTime")
    public String shopIndexSortedByTime(Model m, HttpServletRequest request) {
        HttpSession session = request.getSession();
        String username = (String) session.getAttribute("shopusername");
        List<Product> productList = productMapper.getProducts(username);
        productList.sort(new ProductsSortedByTime());
        m.asMap().remove("products");
        m.addAttribute("products", productList);
        if (SessionCheck.checkSessionName(session)) {
            session.setAttribute("shopusername", "请登录");
        }
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        return "/shop/index";
    }


    @GetMapping("/shop/person")
    public String personalHome(HttpServletRequest request,Model m) {
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else{
            m.addAttribute("shopusername",session.getAttribute("shopusername"));
            SessionCheck.checkSessionPosition(session,m);
            SessionCheck.checkSessionSchool(session,m);
            List<Product> productList = new ArrayList<>();
            m.addAttribute("products",productList);
            return "/shop/person";
        }
    }


    @GetMapping("/shop/index/chosenByLocation")
    public String shopIndexChosenByLocation(Model m, HttpServletRequest request) {
        HttpSession session = request.getSession();
        SessionCheck.checkSessionName(session);
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        if (session.getAttribute("shopusername").equals("请登录")) {
            return "redirect:/shop/login";
        }
        if (session.getAttribute("province") == null) {
//            m.addAttribute("province", "空");
//            m.addAttribute("city", "空");
//            m.addAttribute("area", "空");
            return "redirect:/shop/city";
        } else {
            if (session.getAttribute("province").toString().isEmpty()) {
//                m.addAttribute("province", "空");
//                m.addAttribute("city", "空");
//                m.addAttribute("area", "空");
                return "redirect:/shop/city";
            } else {
                String province = (String) session.getAttribute("province");
                String city = (String) session.getAttribute("city");
                String area = (String) session.getAttribute("area");
                String username = (String) session.getAttribute("shopusername");
                List<Product> productList = productMapper.getProductsByLocation(province, city, area,username);
                m.asMap().remove("products");
                m.addAttribute("products", productList);
                SessionCheck.checkSessionPosition(session,m);
                SessionCheck.checkSessionSchool(session,m);
                return "/shop/index";
            }
        }
    }

    @GetMapping("/shop/index/chosenBySchool")
    public String shopIndexChooseBySchool(Model m, HttpServletRequest request) {
        HttpSession session = request.getSession();
        SessionCheck.checkSessionName(session);
        if (session.getAttribute("shopusername").equals("请登录")) {
            return "redirect:/shop/login";
        }
        m.addAttribute("shopusername", session.getAttribute("shopusername"));

        if (session.getAttribute("school") == null) {
            m.addAttribute("school", "空");
            return "redirect:/shop/school";
        } else {
            String school = (String) session.getAttribute("school");
            String username = (String) session.getAttribute("shopusername");
            List<Product> productList = productMapper.getProductsBySchool(school,username);
            m.asMap().remove("school");
            m.addAttribute("products", productList);
            SessionCheck.checkSessionPosition(session,m);
            SessionCheck.checkSessionSchool(session,m);
            return "/shop/index";
        }
    }



    @GetMapping("/shop/sale")
    public String Sale(HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        SessionCheck.checkSessionName(session);
        if (SessionCheck.checkSessionName(session)) {
            return "redirect:/shop/login";
        }
        if(session.getAttribute("saleError")=="商品价格必须大于0"){
            m.addAttribute("saleError","商品价格必须大于0");
            session.removeAttribute("saleError");
        }
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        return "/shop/sale";
    }

//    @PostMapping("/shop/productDetail")
//    public String getDetail(@RequestBody CartItem cartItem,HttpServletRequest request, Model m){
//        HttpSession session = request.getSession();
//        SessionCheck.checkSessionName(session);
//        if (session.getAttribute("shopusername").equals("请登录")) {
//            return "redirect:/shop/login";
//        }
//        m.addAttribute("shopusername", session.getAttribute("shopusername"));
//        if (session.getAttribute("province") != "null") {
//            m.addAttribute("province", session.getAttribute("province"));
//            m.addAttribute("city", session.getAttribute("city"));
//            m.addAttribute("area", session.getAttribute("area"));
//        } else {
//            m.addAttribute("province", "空");
//            m.addAttribute("city", "空");
//            m.addAttribute("area", "空");
//        }
//        if (session.getAttribute("school") != "null") {
//            m.addAttribute("school", session.getAttribute("school"));
//        } else {
//            m.addAttribute("school", "空");
//        }
//        String imgPath = cartItem.getImgPath();
//        int id = productMapper.getIdByImgPath(imgPath);
//        int newViewCount = productMapper.getProductById(id).getViewCount()+1;
//        productMapper.updateProductViewCount(id,newViewCount);
//        Product product = productMapper.getProductById(id);
//        System.out.println(product);
//        m.addAttribute("product",product);
//        return "/shop/productDetail";
//    }

    @GetMapping("/shop/productDetail/{id}")
    public String productDetail(HttpServletRequest request, Model m,@PathVariable int id){
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            return "redirect:/shop/login";
        }
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        Product product = productMapper.getProductById(id);

        productMapper.updateProductViewCount(id,product.getViewCount()+1);
        m.addAttribute("product",product);
        return "/shop/productDetail";
    }
}
