package shop.common.cache;

import shop.admin.Bean.Product;

import java.sql.Date;

/**
 * 商品缓存的专用值对象（Phase 3.2）。
 *
 * <p>【为什么不直接把 {@link Product} 实体塞进缓存】（这是被测试逼出来的结论，
 * 见 {@code ProductCacheCodecTest.cachingTheEntityIsLossy}）
 * <ol>
 *   <li><b>序列化陷阱</b>：实体的 {@code createdAt} 是 {@code java.sql.Date}。
 *       经 Jackson 往返后实测出两个问题：① 时分秒丢失（"2024-05-06 20:30" 被写成 "2024-05-06"）；
 *       ② <b>时区漂移</b>：写入时按 JVM 本地时区渲染成 "2024-05-06"，
 *       读回时按 mapper 的时区（默认 UTC）解析 → epoch 直接差了 8 小时（UTC+8）。
 *       缓存里存一个「和库里不一样的时间」，属于最难查的那类 bug ——
 *       它不报错，只是页面上的时间慢慢变歪。</li>
 *   <li><b>实体是会变的</b>：实体加字段、改包名，Redis 里带 {@code @class} 的旧 JSON
 *       就反序列化失败（见 {@code RedisConfig} 的说明）。缓存的是 VO 的话，
 *       实体怎么改都不影响「已经写进 Redis 的那批缓存」。</li>
 *   <li><b>缓存体积</b>：实体里有 5 个详情页根本用不到的字段（soldTime/status/sellerHandle…），
 *       缓存全量实体等于把这些字段的网络与内存开销一起付掉。</li>
 * </ol>
 *
 * <p>这里用 {@code String} 存时间（"yyyy-MM-dd"）：与 {@code java.sql.Date.valueOf} 完全对称 ——
 * 写进去什么、读出来就是什么，不经过任何时区换算。缓存里能用字符串表示的就不要用
 * 「承载时区语义的类型」，这是序列化场景的一条通用经验。
 *
 * <p>为什么用 {@code record}：不可变、没有 setter，天然线程安全，也顺手避免了
 * 「读缓存拿到对象后被人改掉」这类问题（本项目详情页确实会改 viewCount，
 * 所以服务层是先转成实体再改，改的是新对象）。
 */
public record ProductCacheVO(
        int id,
        String name,
        String sellerName,
        String description,
        double price,
        String createdAt,
        String imgPath,
        int viewCount) {

    /** 实体 → 缓存值（只取详情页用得上的字段） */
    public static ProductCacheVO from(Product product) {
        return new ProductCacheVO(
                product.getId(),
                product.getName(),
                product.getSellerName(),
                product.getDescription(),
                product.getPrice(),
                formatDate(product.getCreatedAt()),
                product.getImgPath(),
                product.getViewCount());
    }

    /** 缓存值 → 实体（让 Controller / 模板无需感知缓存的存在） */
    public Product toProduct() {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setSellerName(sellerName);
        product.setDescription(description);
        product.setPrice(price);
        product.setCreatedAt(parseDate(createdAt));
        product.setImgPath(imgPath);
        product.setViewCount(viewCount);
        // 注意：soldTime / status / sellerHandle / buyerHandle 不缓存，
        // 保持 null —— 详情页的 SQL 本来也不查这几列（见 ProductMapper.xml 的 getProductById）
        return product;
    }

    private static String formatDate(Date date) {
        return date == null ? null : date.toString();
    }

    private static Date parseDate(String text) {
        // Date.valueOf 要求 "yyyy-MM-dd"；空值与异常格式都退化为 null，不让一条脏缓存把页面打挂
        return (text == null || text.isBlank()) ? null : Date.valueOf(text);
    }
}
