package shop.admin.Bean;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细（{@code order_item} 表实体，Phase 2.5）。
 *
 * <p>【为什么要这张表】订单是<b>历史凭证</b>：卖家之后改了商品名或价格，
 * 历史订单必须仍然显示成交当时的信息。之前订单直接引用 {@code product_id}
 * 展示时 JOIN 商品表，商品一改历史订单就跟着变 —— 这是电商建模的经典错误。
 * 明细表里的 {@code productName} / {@code productPrice} 是<b>下单瞬间的快照</b>。
 *
 * <p>【为什么金额用 BigDecimal】`double` 无法精确表示 0.1 这类十进制小数，
 * 累加会出现 0.30000000000000004 这种结果。金额必须用十进制精确类型。
 * （DB 里是 {@code decimal(10,2)}，Java 侧对应 BigDecimal，不要用 double 中转。）
 */
@Data
@TableName("order_item")
public class OrderItem {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Integer orderId;
    private Integer productId;

    /** 下单时的商品名（快照） */
    private String productName;

    /** 下单时的单价（快照） */
    private BigDecimal productPrice;

    private Integer quantity;

    /** 卖家 id（快照，便于卖家侧直接查询） */
    private Integer sellerUid;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
