package shop.admin.Bean;

import lombok.Data;

import java.sql.Date;

@Data
public class Admin {
    private int id;
    private String adminuser;
    private String adminpass;
    private Date createdAt;
    private Date loginAt;
}
