package shop.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import shop.admin.Bean.Order;
import shop.admin.Bean.OrderPO;

import java.util.List;

@Mapper
public interface OrderMapper {
    List<Order> getOrders();

    /**
     * 创建订单（Phase 2.5 新增）。
     *
     * <p>{@code useGeneratedKeys=true} 会把数据库生成的自增主键回填到
     * {@code order.getId()}，供后续写订单明细使用 —— 这是选择 POJO 而非 record 的原因。
     */
    int insertOrder(OrderPO order);

    Order getOrderByProductId(@Param("id") int id);

    /** 读取订单的当前状态编码（状态机用） */
    String selectStatusById(@Param("id") Integer id);

    /**
     * 带「旧状态」条件的更新 —— 状态机与并发保护的落点。
     *
     * <p>{@code where id = ? and status = ?} 同时干了两件事：
     * <ul>
     *   <li><b>校验流转</b>：如果当前状态与预期不符（比如已被别人改成已完成），
     *       影响行数为 0，调用方就知道「基于旧状态的这次流转不成立」；</li>
     *   <li><b>并发保护</b>：两个请求都想推进同一单时，只有一个能匹配到旧状态。</li>
     * </ul>
     * 把校验放在 SQL 的 WHERE 里而不是「先查再改」，是这套写法能在并发下正确的前提。
     */
    int updateStatusConditionally(@Param("id") Integer id,
                                  @Param("from") String from,
                                  @Param("to") String to,
                                  @Param("legacy") String legacyCondition);

    void deleteOrderById(@Param("id") int id);

    Order getOrderById(@Param("id") int id);
}
