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
}
