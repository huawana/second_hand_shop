package shop.admin.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.access.prepost.PreAuthorize;
import shop.admin.Bean.Order;
import shop.admin.mapper.OrderMapper;
import shop.security.CurrentUser;

import java.util.List;

@Slf4j
// 【Phase 1】后台订单管理：仅 ADMIN 可访问（方法级鉴权，与 URL 规则形成纵深防御）
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class OrderController {
    @Autowired
    OrderMapper orderMapper;

    // 说明：这里原本还有一段「session 里没有 adminuser 就跳登录」的判断。
    // Phase 1 后它已完全冗余 —— 安全层（/admin/** 的 hasRole("ADMIN")）与类上的
    // @PreAuthorize("hasRole('ADMIN')") 已经拦在前面，且两者都基于 JWT，
    // 不依赖 HttpSession。控制器里再判一次反而引入「两份真相」，一旦 session 失效
    // 就会出现「认证通过却被自己的代码踢出去」的怪现象。故删除。
    @GetMapping("/admin/order")
    public String order(Model m){
        List<Order> orderList = orderMapper.getOrders();
        m.addAttribute("orders",orderList);
        return "admin/order";
    }

    @GetMapping("/admin/order_delete/{id}")
    public String orderDelete(@PathVariable("id") int id, Model m){
        try{
            // 【清理】原代码查出的 order 从未使用（还留了一行注释掉的 undoSellTimeById），属于死代码。
            // 【已知缺陷】删除订单后没有把对应商品恢复为「在售」（productMapper.undoSellTimeById），
            // 商品会被 sold_time 永久锁死、再也不会出现在列表里。productMapper 虽有该方法但从未被调用。
            // 这属于状态一致性问题，Phase 2 引入订单状态机时统一处理。
            orderMapper.deleteOrderById(id);
            log.info("管理员[{}]删除订单 id={}", CurrentUser.username(), id);
            m.addAttribute("result","删除订单成功");
            return "redirect:/admin/order";
        }catch (Exception e){
            log.error("删除订单失败 id={}", id, e);
            m.addAttribute("result","删除订单失败");
            return "redirect:/admin/order";
        }
    }
}
