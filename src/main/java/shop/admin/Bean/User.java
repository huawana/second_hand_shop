package shop.admin.Bean;

import lombok.Data;

import java.sql.Date;

@Data
public class User {
    private int id;
    private String username;
    private String password;
    private String email;
    private String phone;
    private Date createdAt;
    private String province;
    private String city;
    private String area;
    private String school;
    private String search;
}
