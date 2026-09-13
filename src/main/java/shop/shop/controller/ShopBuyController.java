package shop.shop.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import shop.admin.Bean.Order;
import shop.admin.Bean.Product;
import shop.admin.mapper.CartMapper;
import shop.admin.mapper.OrderMapper;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;
import shop.shop.Bean.CartItem;
import shop.shop.tools.SearchProcess;
import shop.shop.tools.SessionCheck;
import shop.shop.tools.UserProcess;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

@Controller
public class ShopBuyController {
    @Autowired
    ProductMapper productMapper;
    @Autowired
    OrderMapper orderMapper;
    @Autowired
    UserMapper userMapper;

    @PostMapping("/shop/buy")
    @ResponseBody
    public Boolean buy(Model m, HttpServletRequest request,@RequestBody CartItem cartItem){
        HttpSession session = request.getSession();
        String imgPath = cartItem.getImgPath();
        int id = productMapper.getIdByImgPath(imgPath);
        System.out.println(id);
        UserProcess.cleanCart(id);
        return true;
    }

    @PostMapping("/shop/buySuccess")
    @ResponseBody
    public void buySuccess(Model m, HttpServletRequest request, @RequestBody CartItem cartItem){
        HttpSession session = request.getSession();
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        String imgPath = cartItem.getImgPath();
        int id = productMapper.getIdByImgPath(imgPath);
        Product product = productMapper.getProductById(id);
        UserProcess.cleanCart(id);
        int productId = product.getId();
        int sellerId = userMapper.getIdByUserName(product.getSellerName());
        int buyId = userMapper.getIdByUserName((String)session.getAttribute("shopusername"));
        session.setAttribute("productId",productId);
        String condition = "等待发货";
        orderMapper.insertNewOrder(productId,sellerId,buyId,condition);
        productMapper.updateSellTimeById(id);
    }

    @GetMapping("/shop/buySuccess")
    public String getBuySuccess(Model m,HttpServletRequest request) {
        // 使用productId进行后续逻辑处理
        // 可以使用productMapper等进行数据库操作等
        HttpSession session = request.getSession();
        if(SessionCheck.checkSessionName(session)){
            return "redirect:/shop/login";
        }
        m.addAttribute("shopusername",session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        if(session.getAttribute("productId") == null){
            return "redirect:/shop/index";
        }
        int productId = (int)session.getAttribute("productId");
        Product product = productMapper.getProductById(productId);
        session.removeAttribute("productId");
        Order order= orderMapper.getOrderByProductId(productId);
        // 将产品信息添加到模型中
        m.addAttribute("product", product);
        m.addAttribute("order",order);
        // 返回购买成功的页面视图
        return "/shop/buySuccess";
    }
}
