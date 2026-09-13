package shop.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import shop.admin.Bean.Cart;

import java.util.List;

@Mapper
public interface CartMapper {
    Cart getCartById(@Param("id") int id);

    void updateCartProducts(@Param("id") int id,String products);

    void addUserCartById(@Param("id") int id);

    List<Cart> getCarts();
}
