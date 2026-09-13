package shop.shop.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import shop.admin.Bean.Cart;
import shop.admin.Bean.Order;
import shop.admin.Bean.Product;
import shop.admin.Bean.User;
import shop.admin.mapper.CartMapper;
import shop.admin.mapper.OrderMapper;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;
import shop.shop.Bean.CartItem;
import shop.shop.tools.SessionCheck;
import shop.shop.tools.StringToList;
import shop.shop.tools.UserProcess;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class ShopPersonalController {

    @Autowired
    UserMapper userMapper;
    @Autowired
    CartMapper cartMapper;
    @Autowired
    ProductMapper productMapper;
    @Autowired
    OrderMapper orderMapper;
    @GetMapping("/shop/city")
    public String city(HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        if(session.getAttribute("shopusername").equals("请登录")){
            return "redirect:/shop/login";
        }else {
            return "/shop/city";
        }

    }

    @GetMapping("/shop/school")
    public String school(HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else {
            return "/shop/school";
        }

    }

    @GetMapping("/shop/chooseCity")
    public String chooseCity(String selectedProvinceText, String selectedCityText, String selectedAreaText, HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else{
            String username = session.getAttribute("shopusername").toString();
            User user = userMapper.getUserByUsername(username);
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
        SessionCheck.checkSessionName(session);
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }else{
            m.addAttribute("shopusername", session.getAttribute("shopusername"));
            int id = userMapper.getIdByUserName((String)session.getAttribute("shopusername"));
            Cart cart = cartMapper.getCartById(id);
            String products = cart.getProducts();
            List<Integer> idList = StringToList.stringToList(products);
            System.out.println(idList);
            System.out.println("---------------");
            List<Product> cartProductList = new ArrayList<>();
            for (int productid:idList) {
                Product cartProduct = productMapper.getProductById(productid);
                cartProductList.add(cartProduct);
                System.out.println(cartProduct.getImgPath());
            }

            m.addAttribute("cartProduct",cartProductList);
            SessionCheck.checkSessionPosition(session,m);
            SessionCheck.checkSessionSchool(session,m);
            return "/shop/cart";
        }
    }
    //判断是否登录，如登录则加入购物车，否则返回登录页
    @GetMapping("/shop/checkSession")
    @ResponseBody
    public boolean checkSession(HttpServletRequest request){
        HttpSession session = request.getSession();
        return !SessionCheck.checkSessionName(session);
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
            return "/shop/personRelease";
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
            int uid = userMapper.getIdByUserName(username);
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
            return "/shop/personSale";
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
            return "/shop/personBuy";
        }
    }

    @PostMapping("/shop/deleteMyRelease")
    @ResponseBody
    public boolean deleteMyRelease(@RequestBody CartItem cartItem, HttpServletRequest request){
        HttpSession session = request.getSession();
        String username = (String)session.getAttribute("shopusername");
        int id = userMapper.getIdByUserName(username);
        String imgPath = cartItem.getImgPath();
        int productId = productMapper.getIdByImgPath(imgPath);
        System.out.println("-----------------");
        System.out.println(productId);
        productMapper.deleteProduct(productId);
        UserProcess.cleanCart(productId);
        return true;
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
            return "/shop/personSuccessBuy";
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
            return "/shop/personSuccessSell";
        }
    }

}
