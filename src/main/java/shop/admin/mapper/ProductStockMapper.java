package shop.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import shop.admin.Bean.ProductStock;

/**
 * 商品库存写模型 Mapper。
 *
 * <p>{@link BaseMapper} 提供 {@code selectById} / {@code updateById}（配合 {@code @Version}
 * 即得到乐观锁更新）；悲观锁需要手写一条 {@code SELECT ... FOR UPDATE}。
 */
@Mapper
public interface ProductStockMapper extends BaseMapper<ProductStock> {

    /**
     * 悲观锁读取：{@code SELECT ... FOR UPDATE}。
     *
     * <p>它会在该行上加排他锁，直到当前事务提交/回滚才释放；
     * 期间其他事务的同一行 FOR UPDATE 会被阻塞等待 —— 这就是「悲观」的含义：
     * 先锁住再操作，用等待换确定性。
     *
     * <p>【必须在事务内调用】否则语句执行完锁就释放了，等于没锁。
     */
    ProductStock selectByIdForUpdate(@Param("id") Integer id);
}
