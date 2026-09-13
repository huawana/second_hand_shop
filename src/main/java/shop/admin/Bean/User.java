package shop.admin.Bean;

import lombok.Data;

import java.sql.Date;

@Data
public class User {
    private int id;
    private String username;
    private String password;
    /** 角色：USER / ADMIN。对应 lxy_user.role（Phase 1 新增，支撑 RBAC） */
    private String role;
    /** 状态：1 正常 / 0 禁用。对应 lxy_user.status（Phase 1 新增） */
    private Integer status;
    private String email;
    private String phone;
    private Date createdAt;
    private String province;
    private String city;
    private String area;
    private String school;
    private String search;
}
