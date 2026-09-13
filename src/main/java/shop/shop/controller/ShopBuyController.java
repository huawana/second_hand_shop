package shop.shop.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import shop.shop.Bean.CartItem;
import shop.shop.tools.SessionCheck;
import shop.shop.tools.UserProcess;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;

@Slf4j
@Controller
public class ShopBuyController {
    @Autowired
    ProductMapper productMapper;
    @Autowired
    OrderMapper orderMapper;
    @Autowired
    UserMapper userMapper;

    /**
     * 结算前把商品从购物车中移出（仅清理，不创建订单）。
     *
     * <p>【Bug 修复】原代码未校验登录，session 里没有 shopusername 时后续取值为 null → NPE。
     */
    @PostMapping("/shop/buy")
    @ResponseBody
    public Result<Boolean> buy(Model m, HttpServletRequest request, @Valid @RequestBody CartItem cartItem){
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        Integer id = productMapper.getIdByImgPath(cartItem.getImgPath());
        if (id == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        UserProcess.cleanCart(id);
        return Result.success(true);
    }

    /**
     * 下单：创建订单 + 标记商品售出。
     *
     * <p>【已知缺陷（Phase 4 修复）】本方法是典型的并发隐患现场：
     * <ol>
     *   <li>没有事务：insertNewOrder 与 updateSellTimeById 之间失败会导致半完成状态</li>
     *   <li>没有幂等：客户端重复提交会产生多个订单</li>
     *   <li>「先查是否售出、再标记售出」不是原子操作 → 两人同时买同一件商品都能成功（超卖）</li>
     * </ol>
     * 这三条正是秒杀模块要解决的问题，也是面试时非常好的「主动暴露问题」素材。
     */
    @PostMapping("/shop/buySuccess")
    @ResponseBody
    public Result<Void> buySuccess(Model m, HttpServletRequest request, @Valid @RequestBody CartItem cartItem){
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
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
        UserProcess.cleanCart(id);
        int productId = product.getId();
        int sellerId = userMapper.getIdByUserName(product.getSellerName());
        int buyId = userMapper.getIdByUserName(buyerName);
        session.setAttribute("productId",productId);
        orderMapper.insertNewOrder(productId, sellerId, buyId, "等待发货");
        productMapper.updateSellTimeById(id);
        log.info("用户[{}]下单商品 id={} name={} 卖家[{}]", buyerName, productId, product.getName(), product.getSellerName());
        return Result.success();
    }

    @GetMapping("/shop/buySuccess")
    public String getBuySuccess(Model m,HttpServletRequest request) {
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
        return "shop/buySuccess";
    }
}
