package shop.admin.Bean;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

/**
 * 商品库存的「窄写模型」（Phase 2.4）。
 *
 * <p>【为什么不让 {@link Product} 直接当 MP 实体】Product 是「读/展示模型」：
 * 它的 {@code sellerName}、{@code sellerHandle}、{@code buyerHandle} 都不是
 * {@code lxy_product} 的列（靠 SQL 里的 JOIN 与别名填进来），而且它的
 * {@code status} 字段已经被历史 SQL 复用为<b>订单状态</b>
 * （见 {@code ProductMapper.getSoldProductByUsername}：{@code o.condition AS status}）。
 * 如果把它标成实体，MP 就会按「表列」去写这些字段 —— 语义直接串台。
 *
 * <p>所以库存相关的写操作单独用这个只含真实列的窄模型：
 * 读写分离、职责单一，也避免为了复用一个类而给 Product 打一堆 {@code @TableField(exist=false)}。
 * （这在工程上叫「读模型 / 写模型分离」，CQRS 的简化版思路。）
 *
 * <p>注意金额/库存都用包装类型（{@code Integer}）而不是基本类型：
 * MP 的 {@code updateById} 默认只更新<b>非 null</b> 字段，
 * 用 {@code int} 会让「不打算修改的字段」带着默认值 0 一起写进数据库。
 */
@Data
@TableName("lxy_product")
public class ProductStock {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 库存。二手商品一对一：1 = 在售，0 = 已售出 */
    private Integer stock;

    /**
     * 乐观锁版本号。
     *
     * <p>标注 {@code @Version} 后，{@code MybatisPlusInterceptor} 里的
     * {@code OptimisticLockerInnerInterceptor} 会把 {@code updateById} 的 SQL 改写成
     * <pre>update lxy_product set stock=?, version=version+1 where id=? and version=?</pre>
     * 并把影响行数为 0 的情况交回业务处理 —— 插件本身<b>不重试</b>，
     * 重试策略属于业务决策（见 {@code ProductService#deductStockOptimistic}）。
     */
    @Version
    private Integer version;

    /** ON_SALE / SOLD / OFF_SHELF */
    private String status;
}
