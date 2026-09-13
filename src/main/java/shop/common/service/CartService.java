package shop.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shop.admin.Bean.Cart;
import shop.admin.Bean.CartItem;
import shop.admin.Bean.Product;
import shop.admin.mapper.CartItemMapper;
import shop.admin.mapper.CartMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 购物车业务层（Phase 2.3）。
 *
 * <p>【这次重构要解决什么】原来的购物车是 {@code lxy_cart.products} 里的一个逗号串，
 * 增删改都是「读整串 → 在内存里改 → 整串写回」，带来四个具体问题：
 * <ol>
 *   <li><b>并发丢失更新</b>：两个请求同时读同一串再写回，后写的覆盖先写的（用两个浏览器
 *       同时加购就能复现）；</li>
 *   <li><b>N+1 查询</b>：展示购物车要把 id 串拆开逐个查商品；</li>
 *   <li><b>无法 JOIN / 无法统计</b>：想查「有多少人把某商品加了购物车」只能全表捞出来在内存里数；</li>
 *   <li><b>无唯一约束</b>：靠代码里的 {@code contains} 去重，绕过代码就能塞重复项。</li>
 * </ol>
 * 改成 {@code cart_item} 关联表后：改的是「不同的行」而不是「同一行里的同一个字符串」，
 * 一条 JOIN 取完数据，去重交给数据库唯一键 {@code uk_user_product}。
 *
 * <p>【非破坏性迁移：兼容层】数据库里旧的 {@code lxy_cart} 表与页面/旧代码都还在用，
 * 所以每次写操作之后，都会把 {@code cart_item} 的内容<b>投影回</b>
 * {@code lxy_cart.products} 逗号串（见 {@link #syncLegacyCart}）。
 * 方向是单向的：<b>以 cart_item 为唯一事实来源，逗号串只是它的一个物化视图</b>。
 * 这样任何时刻两边都是一致的，出问题可以随时回退到旧实现而不丢数据 ——
 * 等所有读路径都切到新表（并稳定一段时间）后，再删掉兼容层即可。
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartItemMapper cartItemMapper;
    private final CartMapper cartMapper;

    public CartService(CartItemMapper cartItemMapper, CartMapper cartMapper) {
        this.cartItemMapper = cartItemMapper;
        this.cartMapper = cartMapper;
    }

    /**
     * 加入购物车。
     *
     * <p>重复加入是<b>幂等</b>的：已经存在就什么都不做（返回 false）。
     * 二手商品一物一件，数量没有意义，所以不累加 quantity ——
     * 累加反而会让「同一件商品的购物车里有 3 件」这种失真数据出现。
     *
     * <p>并发下两个请求可能同时通过「不存在」的判断，此时数据库唯一键
     * {@code uk_user_product} 会让后一条 insert 报 DuplicateKeyException，
     * 这里捕获并当作「已在购物车」处理 —— 这正是<b>用数据库约束兜住并发</b>的写法，
     * 而不是只靠应用层的先查后插（那只在高并发下不可靠）。
     */
    @Transactional
    public boolean addToCart(Integer userId, Integer productId) {
        CartItem existing = findItem(userId, productId);
        if (existing != null) {
            log.debug("用户[{}]的商品 {} 已在购物车，跳过", userId, productId);
            return false;
        }
        CartItem item = new CartItem();
        item.setUserId(userId);
        item.setProductId(productId);
        item.setQuantity(1);
        item.setPicked(1);
        try {
            cartItemMapper.insert(item);
        } catch (DuplicateKeyException e) {
            log.debug("并发加入购物车，唯一键已兜住 userId={} productId={}", userId, productId);
            return false;
        }
        syncLegacyCart(userId);
        log.info("用户[{}]加入购物车 productId={}", userId, productId);
        return true;
    }

    /** 从自己的购物车移除某商品 */
    @Transactional
    public boolean removeFromCart(Integer userId, Integer productId) {
        int rows = cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getProductId, productId));
        if (rows > 0) {
            syncLegacyCart(userId);
        }
        return rows > 0;
    }

    /** 清空某用户的购物车 */
    @Transactional
    public int clearCart(Integer userId) {
        int rows = cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId));
        if (rows > 0) {
            syncLegacyCart(userId);
        }
        return rows;
    }

    /**
     * 把某商品从<b>所有人</b>的购物车里移除（商品被买走或下架时调用）。
     *
     * <p>【对比旧实现】原来叫 {@code UserProcess.cleanCart}，做法是
     * 「查出所有用户的购物车 → 逐个把逗号串拆开、删掉该 id、再整串写回」，
     * 有 N 个购物车就执行 N 次 UPDATE，且期间任何并发加购都会被覆盖。
     * 现在只是一条 {@code DELETE ... WHERE product_id = ?}，
     * 顺便用 {@code GROUP_CONCAT} 一次拿到受影响的用户，只为兼容层补写。
     */
    @Transactional
    public int removeProductFromAllCarts(Integer productId) {
        // 先取受影响用户（删除之后就查不到了），用于同步兼容层
        List<Integer> affectedUsers = cartItemMapper
                .selectList(new LambdaQueryWrapper<CartItem>()
                        .select(CartItem::getUserId)
                        .eq(CartItem::getProductId, productId))
                .stream()
                .map(CartItem::getUserId)
                .distinct()
                .collect(Collectors.toList());

        int rows = cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getProductId, productId));

        for (Integer uid : affectedUsers) {
            syncLegacyCart(uid);
        }
        if (rows > 0) {
            log.info("商品 {} 已从 {} 个购物车中移除", productId, affectedUsers.size());
        }
        return rows;
    }

    /** 购物车商品列表（一条 JOIN 取回全部展示字段） */
    public List<Product> listCartProducts(Integer userId) {
        return cartItemMapper.selectCartProducts(userId);
    }

    /** 购物车里的商品 id 列表（首页/搜索页用来自动隐藏「已在购物车」的商品） */
    public List<Integer> listCartProductIds(Integer userId) {
        return cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                        .select(CartItem::getProductId)
                        .eq(CartItem::getUserId, userId))
                .stream()
                .map(CartItem::getProductId)
                .collect(Collectors.toList());
    }

    /** 购物车里某商品是否已存在 */
    public boolean contains(Integer userId, Integer productId) {
        return findItem(userId, productId) != null;
    }

    private CartItem findItem(Integer userId, Integer productId) {
        return cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getProductId, productId)
                .last("limit 1"));
    }

    /**
     * 兼容层：把 {@code cart_item} 投影回 {@code lxy_cart.products} 逗号串。
     *
     * <p>方向是单向的（新表 → 旧串）。注意这里刻意「全量重写」而不是增量维护：
     * 全量重写天然幂等，不存在「两边算不一致」的可能；
     * 而增量维护需要在两个地方各自处理新增/删除，任何一处漏掉就会长期漂移。
     *
     * <p>先确保行存在：老用户注册时会建 {@code lxy_cart} 行，但新注册的用户
     * 如果没有这条记录，UPDATE 会静默影响 0 行，兼容层就写不进去了。
     */
    private void syncLegacyCart(Integer userId) {
        String joined = listCartProductIds(userId).stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        Cart legacy = cartMapper.getCartById(userId);
        if (legacy == null) {
            try {
                cartMapper.addUserCartById(userId);
            } catch (DuplicateKeyException e) {
                // 并发下另一个请求刚建好了这一行，忽略即可
                log.debug("lxy_cart 行已由并发请求创建 userId={}", userId);
            }
        }
        cartMapper.updateCartProducts(userId, joined);
    }
}
