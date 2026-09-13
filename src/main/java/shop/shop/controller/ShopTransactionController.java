package shop.shop.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import shop.admin.Bean.Order;
import shop.admin.mapper.OrderMapper;
import shop.admin.mapper.ProductMapper;
import shop.common.BizException;
import shop.common.ErrorCode;
import shop.common.OrderStatus;
import shop.common.Result;
import shop.common.service.OrderService;
import shop.shop.Bean.CartItemRequest;
import shop.shop.tools.SessionCheck;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * 订单状态推进（卖家发货 / 买家收货）。
 *
 * <p>【Phase 2.5 重构】原来这里是 if/else 硬编码：
 * <pre>
 * if ("等待发货".equals(status))      updateOrderStatusById(id, "已发货");
 * else if ("已发货".equals(status))   updateOrderStatusById(id, "订单已完成");
 * else throw ...
 * </pre>
 * 三个问题：流转规则散在 Controller 里、没有非法流转校验（「已取消 → 已发货」拦不住）、
 * 没有并发保护（重复点击会推进两次）。现在统一交给
 * {@link OrderService#advanceToNext}：规则由 {@link OrderStatus} 状态机定义，
 * 校验通过「带旧状态条件的 UPDATE」落到数据库层，并发下只有一个能成功。
 */
@Slf4j
@Controller
public class ShopTransactionController {

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;

    public ShopTransactionController(ProductMapper productMapper, OrderMapper orderMapper,
                                     OrderService orderService) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.orderService = orderService;
    }

    @PostMapping("/shop/changeStatus")
    @ResponseBody
    public Result<Void> changeStatus(@Valid @RequestBody CartItemRequest cartItem, HttpServletRequest request) {
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        Integer productId = productMapper.getIdByImgPath(cartItem.getImgPath());
        if (productId == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        Order order = orderMapper.getOrderByProductId(productId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        String username = (String) session.getAttribute("shopusername");
        boolean isSeller = username.equals(order.getSellerName());
        boolean isBuyer = username.equals(order.getBuyerName());
        if (!isSeller && !isBuyer) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        OrderStatus from = order.getStatus() != null
                ? OrderStatus.of(order.getStatus())
                : OrderStatus.fromLegacyLabel(order.getCondition());

        OrderStatus to = orderService.advanceToNext(order.getId());
        log.info("用户[{}]推进订单状态 orderId={} productId={} {} -> {}",
                username, order.getId(), productId, from.name(), to.name());
        return Result.success();
    }
}
