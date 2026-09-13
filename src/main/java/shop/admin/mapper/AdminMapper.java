package shop.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import shop.admin.Bean.Admin;

import java.util.Date;
import java.util.List;

@Mapper
public interface AdminMapper {
    List<Admin> getAdmins();

    Admin getAdmin(@Param("adminuser") String adminuser);

    void UpdateAdminPassword(@Param("adminuser") String adminuser, @Param("adminpass") String adminpass);

    void UpdateAdminLoginTime(@Param("adminuser") String adminuser, @Param("loginTime") Date loginTime);
}
