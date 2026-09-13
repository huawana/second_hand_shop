package shop.admin.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import shop.admin.Bean.Order;
import shop.admin.mapper.OrderMapper;
import shop.admin.mapper.ProductMapper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.List;

@Controller
public class OrderController {
    @Autowired
    OrderMapper orderMapper;
    @Autowired
    ProductMapper productMapper;
    @GetMapping("/admin/order")
    public String order(Model m, HttpServletRequest request){
        HttpSession session = request.getSession();
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        List<Order> orderList = orderMapper.getOrders();
        m.addAttribute("orders",orderList);
        return "/admin/order";
    }

    @GetMapping("/admin/order_delete/{id}")
    public String orderDelete(@PathVariable("id") int id, Model m, HttpSession session){
        if(session.getAttribute("adminuser")==null){
            return "redirect:/admin/login";
        }
        try{
            Order order = orderMapper.getOrderById(id);
//            productMapper.undoSellTimeById(order.getid);
            orderMapper.deleteOrderById(id);

            m.addAttribute("result","删除订单成功");
            return "redirect:/admin/order";
        }catch (Exception e){
            e.printStackTrace();
            m.addAttribute("result","删除订单失败");
            return "redirect:/admin/order";
        }
    }
}
