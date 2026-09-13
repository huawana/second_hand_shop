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

    /**
     * 【调整】返回类型由 int 改为 Integer。
     * 原返回类型在「查询无结果」时需要把 null 拆箱成 int，会抛 NullPointerException，
     * 把「表里还没有商品」这种正常状态伪装成了系统崩溃。
     */
    Integer getNowId();

    Integer getIdByImgPath(@Param("imgPath") String imgPath);

    void updateProductViewCount(@Param("id") int id, @Param("viewCount") int viewCount);

    /**
     * 【Phase 3.2】浏览数原子自增。
     *
     * <p>原来的写法是「查出来 view_count → 在 Java 里 +1 → 条件更新写回」，
     * 这是典型的 read-modify-write：两个用户同时打开详情页，都读到 100，
     * 都写回 101，实际两次浏览只记了一次（丢失更新）。
     * 现在压成一条 {@code set view_count = view_count + 1}，由数据库保证原子性。
     *
     * <p>顺带说明它为什么<b>不</b>参与缓存：浏览数每次访问都变，放进缓存就必须每次删除缓存，
     * 缓存等于自毁。详见 {@code ProductCacheService} 的类注释。
     */
    void increaseViewCount(@Param("id") int id);

    /**
     * 【Phase 3.4】取全部商品 id —— 供布隆过滤器启动时全量装载。
     *
     * <p>只取 id 不取整行：装载只需要 id，{@code select id} 可以走覆盖索引，
     * 3660 行的结果集只有几十 KB，而 {@code select *} 会把描述等文本列一起拉出来。
     */
    List<Integer> getAllProductIds();

    List<Product> getProductsByUid(@Param("uid") int uid);

    List<Product> getSoldProductByUsername(@Param("username") String username);

    List<Product> getBoughtProductByUsername(@Param("username") String username);

    void updateSellTimeById(@Param("id") int id);

    List<Product> getFinishBuyProductByUsername(@Param("username") String username);

    List<Product> getFinishSellProductByUsername(@Param("username") String username);

    void undoSellTimeById(@Param("id") int id);
}
