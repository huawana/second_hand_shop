package shop.common;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机（Phase 2.5）。
 *
 * <p>【为什么需要它】原来的状态流转是 Controller 里的 if/else 硬编码：
 * <pre>
 * if ("等待发货".equals(status))      updateOrderStatusById(id, "已发货");
 * else if ("已发货".equals(status))   updateOrderStatusById(id, "订单已完成");
 * else throw ...;
 * </pre>
 * 有三个具体问题：
 * <ol>
 *   <li><b>没有「允许的流转」定义</b>：判断散落在流程代码里，新增状态要改多处，
 *       很容易漏；「已取消 → 已发货」这类非法跳转没有任何机制拦截；</li>
 *   <li><b>用中文字符串当状态码</b>：既不可枚举也不可校验，写错一个字就落库成脏数据；</li>
 *   <li><b>没有并发保护</b>：两个请求同时推进，可能都基于同一个旧状态计算出目标状态。</li>
 * </ol>
 * 改成枚举 + 显式流转表，并用「带旧状态条件的 UPDATE」把校验落到数据库层
 * （见 {@code OrderMapper.updateStatusConditionally}），并发下只有一个能成功。
 *
 * <p>【与旧中文文案的关系】{@link #legacyLabel} 保留旧的中文值：
 * 数据库里 {@code lxy_order.condition} 仍被既有页面/查询使用，短期内需要双写。
 * 状态机用枚举作为唯一事实来源，中文只是它的一个展示/兼容投影。
 */
public enum OrderStatus {

    /** 待支付（Phase 5 接入模拟支付后会用到；当前下单流程不经过此状态） */
    PENDING_PAY("待支付"),

    /** 已支付/待发货 —— 旧文案「等待发货」 */
    PAID("等待发货"),

    /** 卖家已发货 */
    SHIPPED("已发货"),

    /** 交易完成 —— 旧文案「订单已完成」 */
    COMPLETED("订单已完成"),

    /** 已取消（Phase 4 会由「超时未支付」自动触发） */
    CANCELLED("已取消");

    /**
     * 允许的流转：key 能到达 value 集合中的状态。
     *
     * <p>写成静态表而不是一串 if：这张表本身就是「业务规则」的可读文档，
     * 评审时能一眼看出有没有漏掉或多余的边。
     */
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED;

    static {
        Map<OrderStatus, Set<OrderStatus>> m = new EnumMap<>(OrderStatus.class);
        m.put(PENDING_PAY, EnumSet.of(PAID, CANCELLED));
        m.put(PAID, EnumSet.of(SHIPPED, CANCELLED));
        m.put(SHIPPED, EnumSet.of(COMPLETED));
        // 终态：已完成不可再流转；已取消不可复活（要重新交易就下新单）
        m.put(COMPLETED, EnumSet.noneOf(OrderStatus.class));
        m.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED = Collections.unmodifiableMap(m);
    }

    private final String legacyLabel;

    OrderStatus(String legacyLabel) {
        this.legacyLabel = legacyLabel;
    }

    /** 兼容旧数据/旧页面用的中文文案 */
    public String legacyLabel() {
        return legacyLabel;
    }

    public boolean canTransferTo(OrderStatus target) {
        return target != null && ALLOWED.getOrDefault(this, EnumSet.noneOf(OrderStatus.class)).contains(target);
    }

    /** 该状态可流转到的全部目标，用于错误提示（告诉调用方「你现在只能去这些状态」） */
    public Set<OrderStatus> nextStatuses() {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(OrderStatus.class));
    }

    /**
     * 主流程的下一个状态（不含「已取消」这类旁支），用于界面上的「一键推进」交互。
     *
     * <p>为什么单独一个方法：{@link #nextStatuses()} 是<b>所有合法</b>去向
     * （例如待发货既能去已发货、也能去已取消），界面上的按钮只有一个，
     * 需要的是「主线下一条边」。把这条规则放在状态机里，
     * 调用方就不必再写 if/else 判断「当前是什么状态、下一个是什么」——
     * 那正是重构前散落在 Controller 里的东西。
     *
     * @return 下一步状态；已是终态时返回 {@code null}
     */
    public OrderStatus nextOnMainFlow() {
        switch (this) {
            case PENDING_PAY:
                return PAID;
            case PAID:
                return SHIPPED;
            case SHIPPED:
                return COMPLETED;
            default:
                return null;
        }
    }

    /** 按枚举名解析，非法值抛业务异常而不是静默返回 null */
    public static OrderStatus of(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "订单状态不能为空");
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未知的订单状态：" + code);
        }
    }

    /** 按旧中文文案反查（仅用于兼容历史数据） */
    public static OrderStatus fromLegacyLabel(String label) {
        for (OrderStatus s : values()) {
            if (s.legacyLabel.equals(label)) {
                return s;
            }
        }
        throw new BizException(ErrorCode.PARAM_ERROR, "未知的订单状态文案：" + label);
    }
}
