package shop.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import shop.admin.Bean.Product;

import java.awt.event.PaintEvent;
import java.util.List;

@Mapper
public interface ProductMapper {
    List<Product> getProducts(@Param("username") String username);

    void deleteProduct(@Param("id") int id);

    List<Product> getProductsByLocation(@Param("province") String province, @Param("city") String city, @Param("area") String area,String username);

    List<Product> getProductsBySchool(@Param("school") String school,String username);

    List<Product> getProductBySearch(@Param("username") String username,@Param("query") String query);

    Product getProductById(@Param("id") int id);

    void uploadProduct(@Param("name") String name, @Param("uid") int uid, @Param("description") String description, @Param("price") double price, @Param("img_store_path") String img_store_path);

    void updateProduct(@Param("name") String name, @Param("description") String description, @Param("price") double price, @Param("img_store_path") String img_store_path);

    int getNowId();

    int getIdByImgPath(@Param("imgPath") String imgPath);

    void updateProductViewCount(@Param("id") int id, @Param("viewCount") int viewCount);

    List<Product> getProductsByUid(@Param("uid") int uid);

    List<Product> getSoldProductByUsername(@Param("username") String username);

    List<Product> getBoughtProductByUsername(@Param("username") String username);

    void updateSellTimeById(@Param("id") int id);

    List<Product> getFinishBuyProductByUsername(@Param("username") String username);

    List<Product> getFinishSellProductByUsername(@Param("username") String username);

    void undoSellTimeById(@Param("id") int id);
}
