package shop.shop.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import shop.admin.Bean.Order;
import shop.admin.mapper.OrderMapper;
import shop.admin.mapper.ProductMapper;
import shop.common.BizException;
import shop.common.ErrorCode;
import shop.common.Result;
import shop.shop.Bean.CartItemRequest;
import shop.shop.tools.SessionCheck;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@Slf4j
@Controller
public class ShopTransactionController {

    @Autowired
    ProductMapper productMapper;
    @Autowired
    OrderMapper orderMapper;

    /**
     * 推进订单状态：等待发货 → 已发货 → 订单已完成。
     *
     * <p>【安全修复】原代码只要拿到 imgPath 就能随意推进任何订单的状态，
     * 没有任何身份与归属校验。现在要求操作者必须是该订单的买家或卖家。
     *
     * <p>【设计缺陷】状态流转写成了 if/else 硬编码，且没有校验非法流转路径。
     * Phase 2 会改为状态机（枚举 + 允许的流转表），杜绝「已取消 → 已发货」这类非法跳转。
     */
    @PostMapping("/shop/changeStatus")
    @ResponseBody
    public Result<Void> changeStatus(@Valid @RequestBody CartItemRequest cartItem, HttpServletRequest request){
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        Integer id = productMapper.getIdByImgPath(cartItem.getImgPath());
        if (id == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        Order order = orderMapper.getOrderByProductId(id);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        String username = (String) session.getAttribute("shopusername");
        boolean isSeller = username.equals(order.getSellerName());
        boolean isBuyer = username.equals(order.getBuyerName());
        if (!isSeller && !isBuyer) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        String status = order.getCondition();
        if ("等待发货".equals(status)) {
            orderMapper.updateOrderStatusById(id, "已发货");
        } else if ("已发货".equals(status)) {
            orderMapper.updateOrderStatusById(id, "订单已完成");
        } else {
            throw new BizException(ErrorCode.BIZ_ERROR, "当前订单状态不可流转：" + status);
        }
        log.info("用户[{}]推进订单状态 productId={} {} -> {}", username, id, status, "等待发货".equals(status) ? "已发货" : "订单已完成");
        return Result.success();
    }
}
