package shop.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import shop.admin.Bean.OrderItem;

/**
 * 订单明细 Mapper。单表 CRUD 全由 {@link BaseMapper} 提供，无需写一行 SQL。
 *
 * <p>这是一个「新表 + MyBatis-Plus」的典型形态：建表脚本里的字段与实体一一对应，
 * Mapper 里只写 {@code extends BaseMapper<OrderItem>} 就够用 ——
 * 这也是 2.2 引入 MP 的目的：把单表 CRUD 的重复劳动去掉，
 * 把精力留给真正需要手写 SQL 的多表查询。
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
