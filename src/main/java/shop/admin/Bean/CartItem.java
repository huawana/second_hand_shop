package shop.admin.Bean;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购物车明细（{@code cart_item} 表实体）。
 *
 * <p>它的前身是 {@code lxy_cart.products} 里的一个逗号串片段，例如 {@code "2,1"} 会被拆成
 * 两行 {@code (user_id=4, product_id=1)} 与 {@code (user_id=4, product_id=2)}。
 *
 * <p>【命名说明】这里把表实体叫 {@code CartItem}，而把原来的请求体
 * {@code shop.shop.Bean.CartItem} 改名成 {@code CartItemRequest} ——
 * 否则「CartItem 到底是表实体还是接口入参」会在读代码时反复造成误判。
 * 请求体以 {@code ...Request} 结尾也是更常见的约定。
 */
@Data
@TableName("cart_item")
public class CartItem {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户 id */
    private Integer userId;

    /** 商品 id */
    private Integer productId;

    /**
     * 数量。
     *
     * <p>二手商品是「一物一件」，正常情况下恒为 1。
     * 保留该字段是为了让表结构能表达标准电商语义（将来若接入多件商品的卖家，无需改表）。
     */
    private Integer quantity;

    /** 是否勾选结算：1 是 / 0 否 */
    private Integer picked;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
