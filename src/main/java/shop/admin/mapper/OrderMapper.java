package shop.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import shop.admin.Bean.Order;

import java.util.Date;
import java.util.List;

@Mapper
public interface OrderMapper {
    List<Order> getOrders();

    void insertNewOrder(@Param("productId") int productId, @Param("sellUid") int sellUid, @Param("buyUid") int buyUid, @Param("condition") String condition);

    Order getOrderByProductId(@Param("id") int id);
    //更新订单信息
    void updateOrderStatusById(@Param("id") int id, @Param("status") String status);

    void deleteOrderById(@Param("id") int id);

    Order getOrderById(@Param("id") int id);
}
