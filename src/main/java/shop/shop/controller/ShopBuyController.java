package shop.shop.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import shop.admin.Bean.Order;
import shop.admin.Bean.Product;
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

@Slf4j
@Controller
public class ShopBuyController {

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final UserMapper userMapper;
    private final CartService cartService;

    public ShopBuyController(ProductMapper productMapper, OrderMapper orderMapper,
                             UserMapper userMapper, CartService cartService) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.userMapper = userMapper;
        this.cartService = cartService;
    }

    /**
     * 结算前把商品从<b>自己</b>的购物车中移出（仅清理，不创建订单）。
     *
     * <p>【Phase 2.3 修正了一个副作用】原实现调用 {@code UserProcess.cleanCart(productId)}，
     * 而那个方法的做法是「遍历所有人的购物车，把该商品删掉」——
     * 于是「我结算我的购物车」会把商品从<b>其他用户</b>的购物车里也删掉。
     * 它的语义应该是「清理当前用户的购物车」，现在改为
     * {@code cartService.removeFromCart(userId, productId)}。
     *
     * <p>（真正的「所有人」场景是商品被买走/下架，见 {@link #buySuccess}。）
     */
    @PostMapping("/shop/buy")
    @ResponseBody
    public Result<Boolean> buy(Model m, HttpServletRequest request, @Valid @RequestBody CartItemRequest cartItem) {
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String username = (String) session.getAttribute("shopusername");
        int userId = userMapper.getIdByUserName(username);
        Integer productId = productMapper.getIdByImgPath(cartItem.getImgPath());
        if (productId == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        boolean removed = cartService.removeFromCart(userId, productId);
        log.info("用户[{}]结算清理购物车 productId={} 是否删掉了={}", username, productId, removed);
        return Result.success(true);
    }

    /**
     * 下单：创建订单 + 标记商品售出。
     *
     * <p>【已知缺陷（Phase 2.4 / 2.5 处理）】本方法是典型的并发隐患现场，保留原样以便后续对比：
     * <ol>
     *   <li>没有事务：insertNewOrder 与 updateSellTimeById 之间失败会导致半完成状态</li>
     *   <li>没有幂等：客户端重复提交会产生多个订单</li>
     *   <li>「先查是否售出、再标记售出」不是原子操作 → 两人同时买同一件商品都能成功（超卖）</li>
     * </ol>
     * Phase 2.4 会用「条件更新 + 影响行数判断」把第 3 条修掉，2.5 用订单状态机补上前两条。
     */
    @PostMapping("/shop/buySuccess")
    @ResponseBody
    public Result<Void> buySuccess(Model m, HttpServletRequest request, @Valid @RequestBody CartItemRequest cartItem) {
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        SessionCheck.checkSessionPosition(session, m);
        SessionCheck.checkSessionSchool(session, m);
        Integer id = productMapper.getIdByImgPath(cartItem.getImgPath());
        if (id == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        Product product = productMapper.getProductById(id);
        if (product == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        String buyerName = (String) session.getAttribute("shopusername");
        if (buyerName.equals(product.getSellerName())) {
            throw new BizException(ErrorCode.BIZ_ERROR, "不能购买自己发布的商品");
        }
        // 商品即将售出 → 从【所有人】的购物车里移除。这才是「跨用户清理」的正确场景：
        // 一件二手商品只有一个买家，其他人购物车里的这条已经不可能买到了。
        cartService.removeProductFromAllCarts(id);

        int productId = product.getId();
        int sellerId = userMapper.getIdByUserName(product.getSellerName());
        int buyId = userMapper.getIdByUserName(buyerName);
        session.setAttribute("productId", productId);
        orderMapper.insertNewOrder(productId, sellerId, buyId, "等待发货");
        productMapper.updateSellTimeById(id);
        log.info("用户[{}]下单商品 id={} name={} 卖家[{}]", buyerName, productId, product.getName(), product.getSellerName());
        return Result.success();
    }

    @GetMapping("/shop/buySuccess")
    public String getBuySuccess(Model m, HttpServletRequest request) {
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            return "redirect:/shop/login";
        }
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session, m);
        SessionCheck.checkSessionSchool(session, m);
        if (session.getAttribute("productId") == null) {
            return "redirect:/shop/index";
        }
        int productId = (int) session.getAttribute("productId");
        Product product = productMapper.getProductById(productId);
        session.removeAttribute("productId");
        Order order = orderMapper.getOrderByProductId(productId);
        m.addAttribute("product", product);
        m.addAttribute("order", order);
        return "shop/buySuccess";
    }
}
