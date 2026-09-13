package shop.shop.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
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

/**
 * 购物车写操作（加入 / 移除）。
 *
 * <p>【Phase 2.3 重构】本类不再自己拼装逗号串。原来「加入购物车」的流程是
 * 「查出整串 → 在内存里判断是否已存在 → 追加 → 整串写回」，两处并发的后果是
 * 后写覆盖先写（丢失更新）；现在是 {@code cartService.addToCart(userId, productId)} 一行，
 * 去重与并发安全由 {@code cart_item} 的唯一键 {@code uk_user_product} 保证。
 *
 * <p>同时把两个 Mapper 的字段注入改成了构造器注入（依赖 final、缺依赖启动即失败、
 * 单测可直接 new）。
 */
@Slf4j
@Controller
public class ShopCartController {

    private final ProductMapper productMapper;
    private final UserMapper userMapper;
    private final CartService cartService;

    public ShopCartController(ProductMapper productMapper, UserMapper userMapper, CartService cartService) {
        this.productMapper = productMapper;
        this.userMapper = userMapper;
        this.cartService = cartService;
    }

    /**
     * 从自己的购物车移除商品。
     *
     * <p>对应关系：{@code cart_item} 里删除一行（{@code where user_id=? and product_id=?}）。
     */
    @PostMapping("/shop/deleteProduct")
    public String deleteProduct(int productId, Model m, HttpServletRequest request) {
        HttpSession session = request.getSession();
        if (SessionCheck.checkSessionName(session)) {
            return "redirect:/shop/login";
        }
        String username = (String) session.getAttribute("shopusername");
        int userId = userMapper.getIdByUserName(username);
        boolean removed = cartService.removeFromCart(userId, productId);
        log.info("用户[{}]从购物车移除商品 productId={} 是否真的删掉了={}", username, productId, removed);
        // 【Bug 修复保持】原为转发到视图名，但本方法没有往 Model 里放 cartProduct，
        // 模板渲染时拿不到数据。改为重定向，由 GET /shop/cart 重新装配数据。
        return "redirect:/shop/cart";
    }

    /**
     * 加入购物车。
     *
     * <p>【响应契约保持不变】仍然返回 {@code Result.success(true)}。
     * 「重复加入」在业务上也是成功的（该商品确实在你的购物车里），
     * 所以没必要把「本次是否新增」暴露给前端 —— 那会让前端多一个分支，
     * 而它对这个分支并没有不同的处理方式。是否真的新增只记在日志里。
     */
    @PostMapping("/shop/addToCart")
    @ResponseBody
    public Result<Boolean> addToCart(@Valid @RequestBody CartItemRequest cartItem, HttpServletRequest request) {
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
        boolean newlyAdded = cartService.addToCart(userId, productId);
        log.info("用户[{}]加入购物车 productId={} 本次新增={}", username, productId, newlyAdded);
        return Result.success(true);
    }
}
