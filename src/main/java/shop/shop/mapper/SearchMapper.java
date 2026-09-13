package shop.shop.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import shop.admin.Bean.User;

import java.util.List;

@Mapper
public interface SearchMapper {
    String getSearchByUserName(@Param("username") String username);

    void updateSearchByUserName(@Param("username") String username, @Param("search") String search);

    String getSearchById(@Param("id") int id);
}
