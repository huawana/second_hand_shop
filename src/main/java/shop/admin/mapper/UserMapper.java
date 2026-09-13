package shop.admin.mapper;


import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import shop.admin.Bean.User;

import java.util.Date;
import java.util.List;

@Mapper
public interface UserMapper {
    User getUserById(@Param("id") int id);

    List<User> getUsers();

    void addUser(@Param("username") String username, @Param("password") String password, @Param("email") String email, @Param("phone") String phone);

    void deleteUser(@Param("id") Long id);

    void updateUserByUserName(@Param("username") String username, @Param("phone") String phone, @Param("email") String email);

    User getUserByUsername(@Param("username") String username);

    void updateUserLocationByUserName(@Param("username") String username, @Param("province") String province,@Param("city") String city,@Param("area") String area);

    void updateUserSchoolByUserName(@Param("username") String username, @Param("school") String school);

    int getIdByUserName(@Param("username") String username);

    void changePassword(@Param("username") String username, @Param("password") String password);
}

