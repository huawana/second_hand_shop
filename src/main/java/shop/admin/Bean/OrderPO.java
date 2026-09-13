package shop.admin.Bean;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单的持久化写模型（PO）—— 与表 {@code lxy_order} 的字段一一对应。
 *
 * <p>【为什么不用 {@link Order}】{@code Order} 是「查询/展示模型」：
 * 它里面的 {@code productName} / {@code sellerName} / {@code buyerName} 都不是本表的列，
 * 而是靠 JOIN + 别名填进来的。把它当作写入对象会出现两个问题：
 * 一是 MP 或 MyBatis 会尝试去写并不存在的列；二是「用来展示的字段」与「要落库的字段」
 * 混在一起，读代码时无法判断哪些字段是真实的表状态。
 *
 * <p>【PO / DTO / VO 的分工】（面试常问）
 * <ul>
 *   <li><b>PO</b>（本类）：Persistent Object，与表结构一一对应，只在持久层使用；</li>
 *   <li><b>DTO</b>：接口入参，例如 {@code CartItemRequest}，带参数校验注解；</li>
 *   <li><b>VO</b>：接口出参/页面展示，例如 {@code Order}，字段按前端需要拼装。</li>
 * </ul>
 * 三者不混用，改表结构时改 PO、改接口契约时改 DTO/VO，互不牵连。
 *
 * <p>用 POJO 而不是 Java 17 的 record 是刻意的：MyBatis 的
 * {@code useGeneratedKeys} 需要把数据库生成的主键<b>写回对象的 setter</b>，
 * record 是不可变的，拿不到自增 id。
 */
@Data
public class OrderPO {

    /** 自增主键 */
    private Integer id;

    /** 对外的业务订单号（不暴露自增 id，见 OrderService 的生成策略） */
    private String orderNo;

    private Integer productId;
    private Integer sellUid;
    private Integer buyUid;

    private LocalDateTime createdAt;

    /** 旧的中文状态文案（兼容层，与 status 双写） */
    private String condition;

    /** 状态机编码（PENDING_PAY / PAID / SHIPPED / COMPLETED / CANCELLED） */
    private String status;

    private BigDecimal totalAmount;

    private LocalDateTime payTime;
}
