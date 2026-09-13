package shop.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shop.admin.Bean.OrderItem;
import shop.admin.Bean.OrderPO;
import shop.admin.Bean.Product;
import shop.admin.mapper.OrderItemMapper;
import shop.admin.mapper.OrderMapper;
import shop.admin.mapper.ProductMapper;
import shop.common.BizException;
import shop.common.ErrorCode;
import shop.common.OrderStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单业务层（Phase 2.5）：建单（含明细快照）与状态机推进。
 *
 * <p>这个类把原来散在两处的订单逻辑收拢起来：
 * <ul>
 *   <li>{@code ShopBuyController} 里「建订单」只写了 4 个字段（缺订单号/状态/金额/支付时间）；</li>
 *   <li>{@code ShopTransactionController} 里用 if/else 硬编码状态流转，且没有非法流转校验。</li>
 * </ul>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 订单号里的时间部分：精确到毫秒，配合随机后缀降低并发碰撞概率 */
    private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;

    public OrderService(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                        ProductMapper productMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
    }

    /**
     * 创建订单 + 写订单明细快照。
     *
     * <p>【为什么明细要存快照】商品名与价格在成交后仍可能被卖家修改（改名、改价），
     * 订单是历史凭证，必须显示成交当时的样子。所以这里把 name/price 复制进
     * {@code order_item}，而不是展示时再去 JOIN 商品表。
     *
     * <p>【初始状态】当前没有支付环节（Phase 5 接入模拟支付），下单即视为已支付，
     * 因此初始状态是 {@link OrderStatus#PAID}；预留的
     * {@link OrderStatus#PENDING_PAY} 等接入支付后启用。
     *
     * @return 新建订单的主键 id（由 {@code useGeneratedKeys} 回填）
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer createOrder(Integer productId, int sellerId, int buyerId) {
        Product product = productMapper.getProductById(productId);
        if (product == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        // 金额一律走 BigDecimal：Product.price 是 double（历史遗留），
        // 先用 valueOf 转成十进制再定标 2 位，避免 double 的二进制误差被带进金额字段
        BigDecimal amount = BigDecimal.valueOf(product.getPrice()).setScale(2, RoundingMode.HALF_UP);

        OrderPO order = new OrderPO();
        order.setOrderNo(generateOrderNo());
        order.setProductId(productId);
        order.setSellUid(sellerId);
        order.setBuyUid(buyerId);
        order.setCreatedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.PAID.name());
        order.setCondition(OrderStatus.PAID.legacyLabel());   // 兼容层：中文旧文案双写
        order.setTotalAmount(amount);
        order.setPayTime(LocalDateTime.now());

        orderMapper.insertOrder(order);
        if (order.getId() == null) {
            // 拿不到自增 id 说明映射配置有问题，这里直接失败，避免写出一条没有明细的订单
            throw new IllegalStateException("订单主键未回填，无法写订单明细");
        }

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setProductId(productId);
        item.setProductName(product.getName());
        item.setProductPrice(amount);
        item.setQuantity(1);
        item.setSellerUid(sellerId);
        orderItemMapper.insert(item);

        log.info("创建订单 orderNo={} id={} productId={} 金额={} 明细快照=[{}]",
                order.getOrderNo(), order.getId(), productId, amount, product.getName());
        return order.getId();
    }

    /**
     * 推进订单状态（状态机校验 + 并发保护）。
     *
     * <p>三步：读当前状态 → 用状态机判断是否允许流转 → 带旧状态条件的 UPDATE。
     * 第三步的影响行数为 0 表示「这单已经不在我刚才读到的状态了」——
     * 可能是别人先推进了，也可能是并发的重复点击。两种情况都拒绝并提示刷新。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderStatus advance(Integer orderId, OrderStatus target) {
        String currentCode = orderMapper.selectStatusById(orderId);
        if (currentCode == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        OrderStatus current = OrderStatus.of(currentCode);
        if (!current.canTransferTo(target)) {
            throw new BizException(ErrorCode.CONFLICT,
                    String.format("订单状态不允许从「%s」变更为「%s」，当前只允许：%s",
                            current.legacyLabel(), target.legacyLabel(),
                            current.nextStatuses().isEmpty() ? "无（终态）"
                                    : current.nextStatuses().stream().map(OrderStatus::legacyLabel).toList()));
        }
        int rows = orderMapper.updateStatusConditionally(orderId, current.name(), target.name(), target.legacyLabel());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "订单状态已被其他操作变更，请刷新后重试");
        }
        log.info("订单状态推进 id={} {} -> {}", orderId, current.name(), target.name());
        return target;
    }

    /** 按主流程推进一格（界面上的「发货」/「签收」按钮走这里） */
    public OrderStatus advanceToNext(Integer orderId) {
        String currentCode = orderMapper.selectStatusById(orderId);
        if (currentCode == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        OrderStatus next = OrderStatus.of(currentCode).nextOnMainFlow();
        if (next == null) {
            OrderStatus current = OrderStatus.of(currentCode);
            throw new BizException(ErrorCode.CONFLICT, "订单已是终态：「" + current.legacyLabel() + "」");
        }
        return advance(orderId, next);
    }

    /**
     * 生成业务订单号。
     *
     * <p>格式：{@code SO + yyyyMMddHHmmssSSS + 4 位随机}。
     * <ul>
     *   <li>前缀 {@code SO}（Sale Order）便于人工识别与检索；</li>
     *   <li>时间前缀让订单号<b>大致有序</b>，便于按时间范围排查；</li>
     *   <li>不用自增 id 直接对外：自增 id 会暴露业务量，也方便被遍历探测；</li>
     *   <li>随机后缀降低同毫秒并发的碰撞概率，<b>唯一性最终由 {@code uk_order_no} 兜底</b>。</li>
     * </ul>
     * 真实高并发系统应换成雪花算法（全局唯一、趋势递增、不依赖随机碰撞概率），
     * 本项目量级用时间戳+随机足够，且已在表上加了唯一约束保证不出现两笔同号订单。
     */
    private String generateOrderNo() {
        return "SO" + ORDER_NO_TIME.format(LocalDateTime.now())
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
