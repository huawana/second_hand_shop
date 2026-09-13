package shop.admin.Bean;

import lombok.Data;

import java.sql.Date;

@Data
public class Order {
    private int id;
    private String productName;
    private String sellerName;
    private String buyerName;
    private Date createdAt;
    private String condition;

    /** 状态机编码（PAID/SHIPPED/…）。与 {@link #condition} 双写过渡，新代码一律用它 */
    private String status;
}
