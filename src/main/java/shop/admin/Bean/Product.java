package shop.admin.Bean;

import lombok.Data;

import java.sql.Date;

@Data
public class Product {
    private int id;
    private String name;
    private String sellerName;
    private String description;
    private double price;
    private Date createdAt;
    private String imgPath;
    private int viewCount;
    private Date soldTime;
    private String status;
    private String sellerHandle;
    private String buyerHandle;
}
