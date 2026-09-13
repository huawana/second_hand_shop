package shop.shop.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import shop.admin.Bean.Order;
import shop.admin.mapper.OrderMapper;
import shop.admin.mapper.ProductMapper;
import shop.shop.Bean.CartItem;
import shop.shop.tools.SessionCheck;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

@Controller
public class ShopTransactionController {

    @Autowired
    ProductMapper productMapper;
    @Autowired
    OrderMapper orderMapper;
    @PostMapping("/shop/changeStatus")
    @ResponseBody
    public void changeStatus(@RequestBody CartItem cartItem, HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        if(!SessionCheck.checkSessionName(session)){
            String imgPath = cartItem.getImgPath();
            int id = productMapper.getIdByImgPath(imgPath);
            Order order = orderMapper.getOrderByProductId(id);
            String status = order.getCondition();
            if(status.equals("等待发货")){
                String newStatus = "已发货";
                orderMapper.updateOrderStatusById(id,newStatus);
            } else if (status.equals("已发货")) {
                String newStatus = "订单已完成";
                orderMapper.updateOrderStatusById(id,newStatus);
            }


        }
    }
}
