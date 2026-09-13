package shop.admin.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import shop.admin.Bean.Order;
import shop.admin.mapper.OrderMapper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.List;

@Slf4j
@Controller
public class OrderController {
    @Autowired
    OrderMapper orderMapper;

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
            // 【清理】原代码查出的 order 从未使用（还留了一行注释掉的 undoSellTimeById），属于死代码。
            // 【已知缺陷】删除订单后没有把对应商品恢复为「在售」（productMapper.undoSellTimeById），
            // 商品会被 sold_time 永久锁死、再也不会出现在列表里。productMapper 虽有该方法但从未被调用。
            // 这属于状态一致性问题，Phase 2 引入订单状态机时统一处理。
            orderMapper.deleteOrderById(id);
            log.info("管理员[{}]删除订单 id={}", session.getAttribute("adminuser"), id);
            m.addAttribute("result","删除订单成功");
            return "redirect:/admin/order";
        }catch (Exception e){
            log.error("删除订单失败 id={}", id, e);
            m.addAttribute("result","删除订单失败");
            return "redirect:/admin/order";
        }
    }
}
